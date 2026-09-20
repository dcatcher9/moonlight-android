package com.limelight.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.xrcontrols.GameStereoSourceState;
import com.limelight.ui.xrcontrols.StreamQualityTuple;
import com.limelight.ui.xrcontrols.XrControlUiState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;

/** Real presenter transactions with only the decoder and SceneCore device boundary mocked. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
public final class XrGameStereoIntegrationTest {
    private Game game;
    private XrStreamPresenter presenter;
    private PreferenceConfiguration prefs;
    private GameStereoSourceState source;
    private int decoderGeneration;
    private int sendCountAtDecoderGate;
    private int decoderCompletions;
    private int persistedQuality;
    private int resyncs;
    private int[] decoderDimensions = {1920, 1080};
    private int[] allocatedDimensions = {1920, 1080};
    private SurfaceEntity.StereoMode stereo = SurfaceEntity.StereoMode.MONO;

    @Before
    public void setUp() throws Exception {
        ShadowMoonBridge.reset();
        game = spy(Robolectric.buildActivity(Game.class).get());
        game.setTheme(R.style.AppTheme);
        ReflectionHelpers.setField(game, "connected", true);
        prefs = PreferenceConfiguration.readPreferences(game);
        prefs.width = 1920;
        prefs.height = 1080;
        prefs.fps = 60;
        prefs.bitrate = 100000;
        prefs.enablePerfOverlay = false;
        createPresenter();
    }

    private void createPresenter() throws Exception {
        presenter = new XrStreamPresenter(game, prefs, ignored -> { }, ignored -> { });
        // All mode models start with this same tuple and no staged reconnect-only changes.
        // Game normally supplies this flag with the live settings snapshot.
        ReflectionHelpers.setField(presenter, "applyRequiresReconnect", false);
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onLiveStreamQualityApplied(PresentationMode mode,
                                                             StreamQualityTuple applied) {
                persistedQuality++;
            }
            @Override public void onLiveStreamQualityResyncRequired(boolean commit) {
                resyncs++;
            }
        });
        source = ReflectionHelpers.getField(presenter, "gameSourceState");
        invoke("restoreViewState", new Class<?>[] {PresentationMode.class}, PresentationMode.GAME_3D);
        ReflectionHelpers.setField(presenter, "fullAspect", 16f / 9f);
        stereo = SurfaceEntity.StereoMode.MONO;
        SurfaceEntity entity = mock(SurfaceEntity.class);
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        when(entity.getSurface()).thenReturn(surface);
        doAnswer(call -> { stereo = call.getArgument(0); return null; })
                .when(entity).setStereoMode(any());
        doAnswer(call -> {
            IntSize2d dimensions = call.getArgument(0);
            allocatedDimensions = new int[] {dimensions.getWidth(), dimensions.getHeight()};
            return null;
        }).when(entity).setSurfacePixelDimensions(any());
        ReflectionHelpers.setField(presenter, "surfaceEntity", entity);
        StreamContainer container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(presenter);
        doReturn(container).when(game).getStreamContainer();
        doAnswer(call -> {
            // Every automatic request closes the decoder before its control packet is queued.
            sendCountAtDecoderGate = ShadowMoonBridge.getSetVideoModeV2CallCount();
            return ++decoderGeneration;
        }).when(game).beginDecoderPresentationModeTransition();
        doAnswer(call -> { decoderCompletions++; return null; })
                .when(game).completeDecoderPresentationModeTransition();
        doNothing().when(game).cancelDecoderPresentationModeTransition();
        doNothing().when(game).updateDecoderStreamGeometry(anyInt(), anyInt(), anyInt());
        doNothing().when(game).updateDecoderSurfaceFrameRateCeiling(anyInt());
        doAnswer(call -> decoderDimensions).when(game).getDecoderOutputDimensions();
        doAnswer(call -> {
            boolean adopted = presenter.setHostSurfaceSize(call.getArgument(0),
                    call.getArgument(1), call.getArgument(2));
            ((StreamContainer.SurfaceSwitchCallback) call.getArgument(3)).onComplete(adopted);
            return true;
        }).when(container).resizeHostSbsSurface(anyBoolean(), anyInt(), anyInt(), any());
        presenter.setAtomicPresentationV2Supported(true);
        presenter.setGameProviderV1Supported(true);
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        game.finish();
    }

    @Test
    public void bootstrapThenReadyWidensOnlyAfterAckAndMatchingFrameAndKeepsFallbackPacked()
            throws Exception {
        ReflectionHelpers.setField(presenter, "modeOptionsHost", new FrameLayout(game));
        XrControlUiState ui = ReflectionHelpers.getField(presenter, "controlUiState");
        ui.toggleModeOptions(PresentationMode.GAME_3D.name());
        invoke("renderModeOptions", new Class<?>[0]);
        assertSourceCard(R.string.xr_game_waiting, R.string.xr_game_source_waiting);
        bootstrap();
        ready(10, 1);
        assertRequest(MoonBridge.SBS_MODE_GAME_SBS, 2);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 3840, 1080);
        assertArrayEquals(new int[] {3840, 1080}, allocatedDimensions);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        assertFalse(source.isReady());
        // The host can publish before the new decoder generation is committed. Its unchanged
        // heartbeat must establish proof once that frame arrives.
        ready(11, 1);
        assertFalse(source.isReady());
        frame(3840, 1080);
        assertEquals(SurfaceEntity.StereoMode.SIDE_BY_SIDE, stereo);
        ready(11, 1);
        assertTrue(source.isReady());
        assertSourceCard(R.string.xr_game_3d_active, R.string.xr_game_source_active);
        assertArrayEquals(new int[] {1920, 1080}, new int[] {prefs.width, prefs.height});
        assertEquals(60f, prefs.fps, 0f);
        assertEquals(100000, prefs.bitrate);
        assertEquals(0, persistedQuality);

        presenter.onGameSourceStatus(0, 0, 11, 2, 1920, 1080, 3840, 1080);
        idle();
        assertFalse(source.isReady());
        assertEquals(GameStereoSourceState.Status.FALLBACK, source.status(true, 3));
        assertSourceCard(R.string.xr_game_showing_2d, R.string.xr_game_source_flat);
        assertEquals(SurfaceEntity.StereoMode.SIDE_BY_SIDE, stereo);
        ready(11, 3);
        assertTrue(source.isReady());
        assertSourceCard(R.string.xr_game_3d_active, R.string.xr_game_source_active);
        assertEquals(2, ShadowMoonBridge.getSetVideoModeV2CallCount());
        presenter.onConnectionStopping();
        ready(11, 4);
        assertEquals(2, ShadowMoonBridge.getSetVideoModeV2CallCount());
    }

    @Test
    public void gameDumpUsesExistingCommandForMissingDepthButNotDuringTransitions() throws Exception {
        View dump = createDumpButton();
        assertFalse(dump.isEnabled());
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(0, ShadowMoonBridge.getSbsDebugDumpCallCount());
        bootstrap();
        assertTrue(dump.isEnabled());
        assertFalse(source.isReady());
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(1, ShadowMoonBridge.getSbsDebugDumpCallCount());

        ready(10, 1);
        assertFalse(dump.isEnabled());
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(1, ShadowMoonBridge.getSbsDebugDumpCallCount());
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 3840, 1080);
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(1, ShadowMoonBridge.getSbsDebugDumpCallCount());
        frame(3840, 1080);
        assertTrue(dump.isEnabled());
        assertFalse(source.isReady());
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(2, ShadowMoonBridge.getSbsDebugDumpCallCount());

        presenter.onConnectionStopping();
        assertFalse(dump.isEnabled());
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(2, ShadowMoonBridge.getSbsDebugDumpCallCount());
    }

    @Test
    public void gameDumpRequiresNegotiatedProviderAndAtomicPresentation() throws Exception {
        View dump = createDumpButton();
        bootstrap();
        presenter.setGameProviderV1Supported(false);
        // A stale Host AI phase can never authorize a Game dump.
        presenter.onDepthStatus(2);
        assertFalse(dump.isEnabled());
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(0, ShadowMoonBridge.getSbsDebugDumpCallCount());
        presenter.setGameProviderV1Supported(true);
        assertTrue(dump.isEnabled());
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(1, ShadowMoonBridge.getSbsDebugDumpCallCount());

        presenter.setAtomicPresentationV2Supported(false);
        presenter.setGameProviderV1Supported(true);
        assertFalse(dump.isEnabled());
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(1, ShadowMoonBridge.getSbsDebugDumpCallCount());
        presenter.setHostControlExtensionsSupported(false);
        invoke("requestHostDebugDump", new Class<?>[0]);
        assertEquals(1, ShadowMoonBridge.getSbsDebugDumpCallCount());
    }

    @Test
    public void independentMonoRebuildReconfirmsOnceBeforeAcceptingFreshReadiness() {
        bootstrap();
        ready(9, 1);
        ready(0x8000000A, 1);
        presenter.onGameSourceStatus(1, 1, 11, 0, 1920, 1080, 3840, 1080);
        presenter.onGameSourceStatus(1, 1, 11, 1, 1920, 1080, 1920, 1080);
        presenter.onGameSourceStatus(1, 1, 11, 1, 3840, 2160, 7680, 2160);
        presenter.onGameSourceStatus(1, 0, 11, 1, 1920, 1080, 3840, 1080);
        idle();
        assertEquals(1, ShadowMoonBridge.getSetVideoModeV2CallCount());

        // Repeated observations before the posted action runs must produce one same-mode request.
        presenter.onGameSourceStatus(1, 1, 11, 1, 1920, 1080, 3840, 1080);
        presenter.onGameSourceStatus(1, 1, 11, 1, 1920, 1080, 3840, 1080);
        presenter.onGameSourceStatus(0, 0, 12, 1, 1920, 1080, 3840, 1080);
        assertEquals(1, ShadowMoonBridge.getSetVideoModeV2CallCount());
        idle();
        assertRequest(MoonBridge.SBS_MODE_GAME_MONO, 2);
        assertEquals(1, sendCountAtDecoderGate);
        assertFalse(source.isReady());
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        ready(12, 1);
        assertEquals(2, ShadowMoonBridge.getSetVideoModeV2CallCount());

        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_MONO, 12, 1920, 1080);
        ready(12, 1);
        assertFalse(source.isReady());
        frame(1920, 1080);
        ready(11, 2);
        assertEquals(2, ShadowMoonBridge.getSetVideoModeV2CallCount());
        ready(12, 1);
        assertRequest(MoonBridge.SBS_MODE_GAME_SBS, 3);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        assertEquals(0, persistedQuality);
        assertEquals(0, resyncs);
    }

    @Test
    public void refusedMonoReconfirmationReconnectsWithoutBlockingFreshSource() throws Exception {
        bootstrap();
        ready(11, 1);
        assertRequest(MoonBridge.SBS_MODE_GAME_MONO, 2);
        ack(MoonBridge.VIDEO_MODE_ACK_FAILED, MoonBridge.SBS_MODE_GAME_MONO, 12, 1920, 1080);
        assertFalse(source.hasWidenFailure());
        assertEquals(1, resyncs);
        ready(13, 1);
        ready(14, 1);
        assertEquals(2, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        reconnectAndRecoverStereo(2);
    }

    @Test
    public void failedPackedReconfirmationAfterFullscreenRecoversStereoOnReconnect()
            throws Exception {
        bootstrap();
        ready(10, 1);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 3840, 1080);
        frame(3840, 1080);
        ready(11, 1);

        ready(12, 1);
        assertRequest(MoonBridge.SBS_MODE_GAME_SBS, 3);
        ack(MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT,
                MoonBridge.SBS_MODE_GAME_MONO, 13, 1920, 1080);
        assertEquals(1, resyncs);
        assertFalse(source.hasWidenFailure());
        ready(14, 1);
        assertEquals(3, ShadowMoonBridge.getSetVideoModeV2CallCount());
        reconnectAndRecoverStereo(3);
    }

    @Test
    public void independentPackedRebuildReconfirmsPackedModeWithoutAnotherWidening() {
        bootstrap();
        ready(10, 1);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 3840, 1080);
        frame(3840, 1080);
        ready(11, 1);
        assertTrue(source.isReady());

        presenter.onGameSourceStatus(0, 0, 12, 1, 1920, 1080, 3840, 1080);
        idle();
        assertRequest(MoonBridge.SBS_MODE_GAME_SBS, 3);
        assertFalse(source.isReady());
        assertEquals(SurfaceEntity.StereoMode.SIDE_BY_SIDE, stereo);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 13, 3840, 1080);
        ready(13, 1);
        assertFalse(source.isReady());
        frame(3840, 1080);
        ready(13, 1);
        ready(13, 1);
        assertTrue(source.isReady());
        assertEquals(3, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertArrayEquals(new int[] {3840, 1080}, allocatedDimensions);
        assertEquals(0, persistedQuality);
        assertEquals(0, resyncs);
    }

    @Test
    public void refusedWideningRecoversProvenMonoAndDoesNotRetryForNewSourceRevisions()
            throws Exception {
        bootstrap();
        ready(10, 1);
        ack(MoonBridge.VIDEO_MODE_ACK_FAILED, MoonBridge.SBS_MODE_GAME_MONO, 12, 1920, 1080);
        assertTrue(source.hasWidenFailure());
        frame(1920, 1080);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        assertEquals(0, resyncs);
        ready(12, 2);
        ready(12, 3);
        ready(50, 1);
        ready(51, 1);
        assertEquals(2, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertTrue(source.hasWidenFailure());

        // A deliberate quality change clears the attempt latch; automatic status changes do not.
        presenter.applyLiveStreamQuality(new StreamQualityTuple("1920x1080", "60", 120000));
        assertFalse(source.hasWidenFailure());
        assertRequest(MoonBridge.SBS_MODE_GAME_MONO, 3);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_MONO, 13, 1920, 1080);
        ready(13, 1);
        assertRequest(MoonBridge.SBS_MODE_GAME_SBS, 4);
    }

    @Test
    public void cappedGameRasterCannotBecomeStereoAndFailureSurvivesSameQualityReconnect()
            throws Exception {
        bootstrap();
        ready(10, 1);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 2560, 720);
        assertEquals(1, resyncs);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        assertTrue(source.hasWidenFailure());
        Intent reconnect = new Intent();
        presenter.captureReconnectViewState(reconnect);
        game.setIntent(reconnect);
        invoke("restoreViewState", new Class<?>[] {PresentationMode.class}, PresentationMode.GAME_3D);
        assertTrue(source.hasWidenFailure());
        assertEquals(MoonBridge.SBS_MODE_GAME_MONO, presenter.getInitialHostSbsWireMode());
        assertFalse(source.isReady());
    }

    @Test
    public void hostRollbackRequiringReconnectCannotBeOverriddenByOldMonoFields() {
        bootstrap();
        ready(10, 1);
        ack(MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT,
                MoonBridge.SBS_MODE_GAME_MONO, 10, 1920, 1080);
        assertEquals(1, resyncs);
        assertTrue(source.hasWidenFailure());
        assertEquals(1, decoderCompletions);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
    }

    @Test
    public void unsupportedSourceCanKeepCodecFittedMonoWithoutReconnect() {
        presenter.onFirstVideoFrameRendered();
        idle();
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_MONO, 10, 1280, 720);
        frame(1280, 720);
        presenter.onGameSourceStatus(2, 0, 10, 1, 1920, 1080, 3840, 1080);
        idle();
        assertEquals(GameStereoSourceState.Status.UNSUPPORTED, source.status(true, 2));
        assertArrayEquals(new int[] {1280, 720}, allocatedDimensions);
        assertArrayEquals(new int[] {1920, 1080}, new int[] {prefs.width, prefs.height});
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        assertEquals(0, resyncs);
        assertEquals(1, ShadowMoonBridge.getSetVideoModeV2CallCount());
    }

    @Test
    public void refusedLocalResizeRetainsProofUntilTheNextRequestQueues() {
        bootstrap();
        doReturn(0).when(game).beginDecoderPresentationModeTransition();
        presenter.applyLiveStreamQuality(new StreamQualityTuple("1280x720", "60", 100000));
        assertEquals(1, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertEquals(0, resyncs);

        doAnswer(call -> {
            sendCountAtDecoderGate = ShadowMoonBridge.getSetVideoModeV2CallCount();
            return ++decoderGeneration;
        }).when(game).beginDecoderPresentationModeTransition();
        ready(10, 1);
        assertRequest(MoonBridge.SBS_MODE_GAME_SBS, 2);
        // A request that did queue must still reject the old source proof.
        assertFalse(source.isReady());
        ready(10, 2);
        assertFalse(source.isReady());
        assertEquals(2, ShadowMoonBridge.getSetVideoModeV2CallCount());
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 3840, 1080);
        frame(3840, 1080);
        ready(11, 1);
        assertTrue(source.isReady());
        assertEquals(SurfaceEntity.StereoMode.SIDE_BY_SIDE, stereo);
    }

    @Test
    public void exhaustedUnsentPanelRateRetriesStillAcceptGameReadiness() {
        bootstrap();
        ShadowMoonBridge.setVideoModeV2Result(0);
        presenter.onClientRefreshRateChanged(30f);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));
        // Initial attempt plus the one bounded retry both fail before reaching the host.
        assertEquals(3, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertEquals(0, resyncs);

        ShadowMoonBridge.setVideoModeV2Result(1);
        ready(10, 1);
        assertRequest(MoonBridge.SBS_MODE_GAME_SBS, 4);
        assertFalse(source.isReady());
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 3840, 1080);
        frame(3840, 1080);
        ready(11, 1);
        assertTrue(source.isReady());
        assertEquals(SurfaceEntity.StereoMode.SIDE_BY_SIDE, stereo);
        assertEquals(4, ShadowMoonBridge.getSetVideoModeV2CallCount());
    }

    @Test
    public void unsentQualityEditPreservesTheWideningFailureLatch() {
        bootstrap();
        ready(10, 1);
        ack(MoonBridge.VIDEO_MODE_ACK_FAILED, MoonBridge.SBS_MODE_GAME_MONO, 12, 1920, 1080);
        frame(1920, 1080);
        assertTrue(source.hasWidenFailure());

        ShadowMoonBridge.setVideoModeV2Result(0);
        presenter.applyLiveStreamQuality(new StreamQualityTuple("1920x1080", "60", 120000));
        assertTrue(source.hasWidenFailure());
        assertEquals(3, ShadowMoonBridge.getSetVideoModeV2CallCount());
        ShadowMoonBridge.setVideoModeV2Result(1);
        ready(12, 1);
        assertTrue(source.isReady());
        assertTrue(source.hasWidenFailure());
        assertEquals(3, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertEquals(0, resyncs);
    }

    @Test
    public void unsentGameExitKeepsActiveSourceUntilTheRetryQueues() throws Exception {
        bootstrap();
        ready(10, 1);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 3840, 1080);
        frame(3840, 1080);
        ready(11, 1);
        Class<?> itemType = Class.forName("com.limelight.ui.XrStreamPresenter$BarItem");
        Constructor<?> constructor = itemType.getDeclaredConstructor(
                XrStreamPresenter.class, String.class, int.class, PresentationMode.class);
        constructor.setAccessible(true);
        Object movie = constructor.newInstance(presenter, "Movie 3D",
                R.drawable.ic_xr_mode_movie_3d, PresentationMode.MOVIE_3D);
        ReflectionHelpers.setField(presenter, "lastModeSwitchMs", -10000L);
        ShadowMoonBridge.setVideoModeV2Result(0);

        invoke("selectMode", new Class<?>[] {itemType}, movie);

        assertEquals(PresentationMode.GAME_3D,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
        assertFalse(ReflectionHelpers.getField(presenter, "modeSwitchInProgress"));
        assertTrue(source.isReady());
        assertEquals(SurfaceEntity.StereoMode.SIDE_BY_SIDE, stereo);
        assertEquals(0, resyncs);
        presenter.onGameSourceStatus(0, 0, 11, 2, 1920, 1080, 3840, 1080);
        idle();
        assertFalse(source.isReady());
        ready(11, 3);
        assertTrue(source.isReady());

        ShadowMoonBridge.setVideoModeV2Result(1);
        ReflectionHelpers.setField(presenter, "lastModeSwitchMs", -10000L);
        invoke("selectMode", new Class<?>[] {itemType}, movie);
        assertRequest(MoonBridge.SBS_MODE_OFF, 4);
        assertFalse(source.isReady());
        ready(11, 4);
        assertFalse(source.isReady());
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_OFF, 12, 1920, 1080);
        frame(1920, 1080);
        assertEquals(PresentationMode.MOVIE_3D,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
    }

    @Test
    public void unavailableDecoderGateReleasesModeStartSoAnotherTapCanRetry() throws Exception {
        bootstrap();
        Class<?> itemType = Class.forName("com.limelight.ui.XrStreamPresenter$BarItem");
        Constructor<?> constructor = itemType.getDeclaredConstructor(
                XrStreamPresenter.class, String.class, int.class, PresentationMode.class);
        constructor.setAccessible(true);
        Object movie = constructor.newInstance(presenter, "Movie 3D",
                R.drawable.ic_xr_mode_movie_3d, PresentationMode.MOVIE_3D);
        ReflectionHelpers.setField(presenter, "lastModeSwitchMs", -10000L);
        doReturn(0).when(game).beginDecoderPresentationModeTransition();

        invoke("selectMode", new Class<?>[] {itemType}, movie);

        assertFalse(ReflectionHelpers.getField(presenter, "modeSwitchInProgress"));
        assertEquals(PresentationMode.GAME_3D,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
        assertEquals(1, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertEquals(0, persistedQuality);
        assertEquals(0, resyncs);

        doAnswer(call -> {
            sendCountAtDecoderGate = ShadowMoonBridge.getSetVideoModeV2CallCount();
            return ++decoderGeneration;
        }).when(game).beginDecoderPresentationModeTransition();
        // Retry after the normal tap debounce; it must not need a stream restart.
        ReflectionHelpers.setField(presenter, "lastModeSwitchMs", -10000L);
        invoke("selectMode", new Class<?>[] {itemType}, movie);
        assertRequest(MoonBridge.SBS_MODE_OFF, 2);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_OFF, 11, 1920, 1080);
        frame(1920, 1080);
        assertEquals(PresentationMode.MOVIE_3D,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
        assertFalse(ReflectionHelpers.getField(presenter, "modeSwitchInProgress"));
    }

    @Test
    public void packedGameExitUsesAtomicOffAndFreshMonoFrame() throws Exception {
        bootstrap();
        ready(10, 1);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 11, 3840, 1080);
        frame(3840, 1080);
        Class<?> itemType = Class.forName("com.limelight.ui.XrStreamPresenter$BarItem");
        Constructor<?> constructor = itemType.getDeclaredConstructor(
                XrStreamPresenter.class, String.class, int.class, PresentationMode.class);
        constructor.setAccessible(true);
        Object movie = constructor.newInstance(presenter, "Movie 3D",
                R.drawable.ic_xr_mode_movie_3d, PresentationMode.MOVIE_3D);
        ReflectionHelpers.setField(presenter, "lastModeSwitchMs", -10000L);
        invoke("selectMode", new Class<?>[] {itemType}, movie);
        assertRequest(MoonBridge.SBS_MODE_OFF, 3);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_OFF, 12, 1920, 1080);
        assertArrayEquals(new int[] {1920, 1080}, allocatedDimensions);
        frame(1920, 1080);
        assertEquals(PresentationMode.MOVIE_3D,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        ready(11, 9);
        assertEquals(3, ShadowMoonBridge.getSetVideoModeV2CallCount());
    }

    private void bootstrap() {
        presenter.onFirstVideoFrameRendered();
        idle();
        assertRequest(MoonBridge.SBS_MODE_GAME_MONO, 1);
        // A status that arrived before a correlated ACK must not be used later as proof.
        ready(10, 1);
        assertFalse(source.isReady());
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_MONO, 10, 1920, 1080);
        assertEquals(1, decoderCompletions);
        frame(1920, 1080);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
    }

    private View createDumpButton() throws Exception {
        Class<?> itemType = Class.forName("com.limelight.ui.XrStreamPresenter$BarItem");
        Constructor<?> constructor = itemType.getDeclaredConstructor(
                XrStreamPresenter.class, String.class, int.class, PresentationMode.class);
        constructor.setAccessible(true);
        Object item = constructor.newInstance(presenter, "Dump 3D", R.drawable.ic_xr_dump, null);
        View button = new View(game);
        ReflectionHelpers.setField(item, "tapTarget", button);
        ReflectionHelpers.setField(presenter, "dumpItem", item);
        invoke("updateHostDebugDumpAvailability", new Class<?>[0]);
        return button;
    }

    private void reconnectAndRecoverStereo(int previousRequests) throws Exception {
        Intent reconnect = new Intent();
        presenter.captureReconnectViewState(reconnect);
        presenter.onDestroy();
        game.setIntent(reconnect);
        decoderDimensions = new int[] {1920, 1080};
        createPresenter();
        assertFalse(source.hasWidenFailure());
        assertEquals(MoonBridge.SBS_MODE_GAME_MONO, presenter.getInitialHostSbsWireMode());
        presenter.onFirstVideoFrameRendered();
        idle();
        assertRequest(MoonBridge.SBS_MODE_GAME_MONO, previousRequests + 1);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_MONO, 20, 1920, 1080);
        frame(1920, 1080);
        ready(20, 1);
        assertRequest(MoonBridge.SBS_MODE_GAME_SBS, previousRequests + 2);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        ack(MoonBridge.VIDEO_MODE_ACK_APPLIED, MoonBridge.SBS_MODE_GAME_SBS, 21, 3840, 1080);
        assertEquals(SurfaceEntity.StereoMode.MONO, stereo);
        frame(3840, 1080);
        ready(21, 1);
        assertTrue(source.isReady());
        assertEquals(SurfaceEntity.StereoMode.SIDE_BY_SIDE, stereo);
        assertEquals(1, resyncs);
        assertEquals(0, persistedQuality);
    }

    private void ready(int generation, int revision) {
        presenter.onGameSourceStatus(1, 1, generation, revision, 1920, 1080, 3840, 1080);
        idle();
    }

    private void frame(int width, int height) {
        decoderDimensions = new int[] {width, height};
        presenter.onDecoderPresentationModeTransitionOpened(decoderGeneration);
        idle();
    }

    private void ack(int status, int mode, int generation, int width, int height) {
        int request = ShadowMoonBridge.getLastSetVideoModeV2Request()[1];
        presenter.onVideoModeAckV2(status, mode, 0, request, generation,
                1920, 1080, width, height, 6000, 90000);
    }

    private void assertRequest(int mode, int count) {
        assertEquals(count, ShadowMoonBridge.getSetVideoModeV2CallCount());
        int[] request = ShadowMoonBridge.getLastSetVideoModeV2Request();
        assertEquals(mode, request[0]);
        assertArrayEquals(new int[] {1920, 1080}, new int[] {request[2], request[3]});
        if (mode == MoonBridge.SBS_MODE_GAME_SBS || mode == MoonBridge.SBS_MODE_OFF || count == 1) {
            assertEquals(count - 1, sendCountAtDecoderGate);
        }
    }

    private void assertSourceCard(int statusResource, int detailResource) {
        TextView status = ReflectionHelpers.getField(presenter, "gameSourceStatusView");
        TextView detail = ReflectionHelpers.getField(presenter, "gameSourceDetailView");
        assertEquals(game.getString(statusResource), status.getText().toString());
        assertEquals(game.getString(detailResource), detail.getText().toString());
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = XrStreamPresenter.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(presenter, arguments);
    }
}
