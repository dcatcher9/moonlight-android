package com.limelight.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.view.Surface;

import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.Game;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.ClientSbsRenderSurface;
import com.limelight.ui.PresentationMode;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.XrStreamPresenter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real presenter failure callback and renderer, with a bounded simulated GPU lock owner. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class Stereo3DRendererEntryFailureTest {
    public static final class TestGame extends Game {
        int surfaceFailures;

        @Override public void handleDecoderSurfaceSwitchFailure() {
            surfaceFailures++;
        }
    }

    @Test public void failedEntryStopsWithoutWaitingForFrameDrain() throws Exception {
        verifyEntryFailure("frameLock", false);
    }

    @Test public void failedEntryStopsWithoutWaitingForTargetAllocation() throws Exception {
        verifyEntryFailure("liveStreamResizeLock", false);
    }

    @Test public void ackedEntryReconnectsWithoutWaitingForFrameDrain() throws Exception {
        verifyEntryFailure("frameLock", true);
    }

    @Test public void ackedEntryReconnectsWithoutWaitingForTargetAllocation() throws Exception {
        verifyEntryFailure("liveStreamResizeLock", true);
    }

    private void verifyEntryFailure(String lockName, boolean ackFirst) throws Exception {
        TestGame game = Robolectric.buildActivity(TestGame.class).get();
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(game);
        prefs.width = 1920;
        prefs.height = 1080;
        ReflectionHelpers.setField(game, "prefConfig", prefs);
        ReflectionHelpers.setField(game, "connected", true);
        Stereo3DRenderer renderer;
        try (MockedStatic<ClientSbsModelAssetCache> cache = mockStatic(ClientSbsModelAssetCache.class)) {
            renderer = new Stereo3DRenderer(mock(ClientSbsRenderSurface.class),
                    mock(Stereo3DRenderer.OnSurfaceReadyListener.class), game, prefs, false);
        }
        ReflectionHelpers.setField(renderer, "clientSbs", true);
        ReflectionHelpers.setField(renderer, "outputSurfaceValidated", true);
        ClientSbsPresentationTransaction proof = ReflectionHelpers.getField(
                renderer, "presentationCompletion");
        assertTrue(proof.arm(ClientSbsPresentationTransaction.Kind.MODE_ENTRY, 1, 1,
                () -> fail("Abandoned entry completed presentation")) > 0);

        StreamContainer container = new StreamContainer(game, null);
        ReflectionHelpers.setField(container, "game", game);
        ReflectionHelpers.setField(container, "mStereoRenderer", renderer);
        ReflectionHelpers.setField(container, "mRequestedEglAttachGeneration", 7);
        ReflectionHelpers.setField(container, "mCreatedEglAttachGeneration", 7);
        ReflectionHelpers.setField(container, "mRequestedEglDetachGeneration", 6);
        ReflectionHelpers.setField(container, "mExpectedEglOutputSurface", mock(Surface.class));
        ReflectionHelpers.setField(game, "streamContainer", container);
        XrStreamPresenter presenter = new XrStreamPresenter(game, prefs,
                surface -> { }, visible -> { });
        ReflectionHelpers.setField(container, "mXrPresenter", presenter);
        ReflectionHelpers.setField(presenter, "surfaceEntity", mock(SurfaceEntity.class));
        ReflectionHelpers.setField(presenter, "modeSwitchInProgress", true);
        AtomicInteger reconnects = new AtomicInteger();
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onLiveStreamQualityResyncRequired(boolean commitStagedSettings) {
                assertTrue(commitStagedSettings);
                assertTrue(ReflectionHelpers.getField(renderer, "terminalSurfaceDestroyRequested"));
                reconnects.incrementAndGet();
            }
        });

        Class<?> itemClass = Class.forName("com.limelight.ui.XrStreamPresenter$BarItem");
        Constructor<?> constructor = itemClass.getDeclaredConstructor(
                XrStreamPresenter.class, String.class, int.class, PresentationMode.class);
        constructor.setAccessible(true);
        Object item = constructor.newInstance(presenter, "Client SBS AI", 0,
                PresentationMode.CLIENT_SBS_AI);
        if (ackFirst) {
            ReflectionHelpers.setField(presenter, "pendingAckFirstModeItem", item);
            ReflectionHelpers.setField(presenter, "pendingAckFirstPreviousMode", PresentationMode.NORMAL);
            ReflectionHelpers.setField(presenter, "liveQualityChangeInProgress", true);
            ReflectionHelpers.setField(presenter, "pendingLiveQualityOrigin", userQualityOrigin());
        }
        Method failureCallback = XrStreamPresenter.class.getDeclaredMethod(
                "finishModeSwitchAfterSurfaceHandoff", itemClass, PresentationMode.class,
                PresentationMode.class, boolean.class, boolean.class, StreamContainer.class,
                boolean.class);
        failureCallback.setAccessible(true);

        Object driverLock = ReflectionHelpers.getField(renderer, lockName);
        CountDownLatch driverEntered = new CountDownLatch(1);
        CountDownLatch releaseDriver = new CountDownLatch(1);
        AtomicBoolean driverTimedOut = new AtomicBoolean();
        Thread driver = new Thread(() -> {
            synchronized (driverLock) {
                driverEntered.countDown();
                try {
                    // Bound a regression: a blocking callback fails instead of hanging the JVM.
                    driverTimedOut.set(!releaseDriver.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    driverTimedOut.set(true);
                    Thread.currentThread().interrupt();
                }
            }
        }, "ClientSbsBlockedEntryDriver");
        driver.start();
        CountDownLatch cleaned = new CountDownLatch(1);
        try {
            assertTrue(driverEntered.await(3, TimeUnit.SECONDS));
            failureCallback.invoke(presenter, item, PresentationMode.NORMAL,
                    PresentationMode.CLIENT_SBS_AI, false, true, container, false);
            assertFalse("Entry failure waited for the GPU owner of " + lockName,
                    driverTimedOut.get());
            assertTrue(driver.isAlive());
            assertTrue(ReflectionHelpers.getField(renderer, "terminalSurfaceDestroyRequested"));
            assertFalse(ReflectionHelpers.getField(renderer, "outputSurfaceValidated"));
            assertFalse(proof.hasPending());
            assertEquals(0, (int) ReflectionHelpers.getField(container, "mRequestedEglAttachGeneration"));
            assertEquals(0, (int) ReflectionHelpers.getField(container, "mCreatedEglAttachGeneration"));
            assertEquals(0, (int) ReflectionHelpers.getField(container, "mRequestedEglDetachGeneration"));
            assertNull(ReflectionHelpers.getField(container, "mExpectedEglOutputSurface"));
            assertEquals(ackFirst ? 0 : 1, game.surfaceFailures);
            assertEquals(ackFirst ? 1 : 0, reconnects.get());
            assertEquals(PresentationMode.NORMAL,
                    ReflectionHelpers.getField(presenter, "currentPresenterMode"));
            assertFalse(ReflectionHelpers.getField(presenter, "modeSwitchInProgress"));
        } finally {
            releaseDriver.countDown();
            driver.join(3000);
            renderer.onSurfaceDestroyedAsync(Runnable::run, cleaned::countDown);
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
            presenter.onDestroy();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object userQualityOrigin() throws ClassNotFoundException {
        return Enum.valueOf((Class) Class.forName(
                "com.limelight.ui.XrStreamPresenter$LiveQualityRequestOrigin"), "USER");
    }
}
