package com.limelight.ui;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Looper;
import android.view.Surface;
import android.widget.TextView;

import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.xrcontrols.GameStereoSourceState;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Real presenter live-quality and mode transactions with only the decoder, the stream container
 * and the SceneCore entity mocked. The stream starts in 2D at 1920x1080 with a 90 FPS ceiling.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
public final class XrLiveQualityIntegrationTest {
    private Game game;
    private XrStreamPresenter presenter;
    private PreferenceConfiguration prefs;
    private StreamContainer container;
    private int decoderGeneration;
    private int[] decoderDimensions = {1920, 1080};
    private final List<StreamQualityTuple> persisted = new ArrayList<>();
    private int resyncs;

    @Before
    public void setUp() throws Exception {
        ShadowMoonBridge.reset();
        game = spy(Robolectric.buildActivity(Game.class).get());
        game.setTheme(R.style.AppTheme);
        ReflectionHelpers.setField(game, "connected", true);
        prefs = PreferenceConfiguration.readPreferences(game);
        prefs.width = 1920;
        prefs.height = 1080;
        prefs.fps = 90;
        prefs.bitrate = 100000;
        prefs.enablePerfOverlay = false;
        presenter = new XrStreamPresenter(game, prefs, ignored -> { }, ignored -> { });
        ReflectionHelpers.setField(presenter, "applyRequiresReconnect", false);
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onLiveStreamQualityApplied(PresentationMode mode,
                                                             StreamQualityTuple applied) {
                persisted.add(applied);
            }

            @Override public void onLiveStreamQualityResyncRequired(boolean commit) {
                resyncs++;
            }
        });
        invoke("restoreViewState", new Class<?>[] {PresentationMode.class},
                PresentationMode.NORMAL);
        ReflectionHelpers.setField(presenter, "fullAspect", 16f / 9f);
        SurfaceEntity entity = mock(SurfaceEntity.class);
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        when(entity.getSurface()).thenReturn(surface);
        ReflectionHelpers.setField(presenter, "surfaceEntity", entity);
        container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(presenter);
        doReturn(container).when(game).getStreamContainer();
        doAnswer(call -> ++decoderGeneration).when(game).beginDecoderPresentationModeTransition();
        doNothing().when(game).completeDecoderPresentationModeTransition();
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
        presenter.onFirstVideoFrameRendered();
        idle();
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        game.finish();
    }

    @Test
    public void ackFirstModeSwitchWithoutQualityDeltaKeepsTheDurableCeiling() throws Exception {
        followPanelDownTo72();

        selectMode(PresentationMode.HOST_SBS_AI);
        assertEquals(MoonBridge.SBS_MODE_AI, ShadowMoonBridge.getLastSetVideoModeV2Request()[0]);
        ack(MoonBridge.SBS_MODE_AI, 11, 1920, 1080, 3840, 1080, 7200);
        frame(3840, 1080);

        assertEquals(PresentationMode.HOST_SBS_AI,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
        // The 72 FPS ACK is the effective panel-follow rate only.
        assertEquals(72f, prefs.fps, 0f);
        assertEquals(1, persisted.size());
        assertEquals("90", persisted.get(0).frameRate);
        assertEquals("1920x1080", persisted.get(0).resolution);
        assertEquals(90, panelState().getUserCeilingHz());
        assertEquals(0, resyncs);
    }

    @Test
    public void unsentResolutionRequestReleasesItsDecoderGateForTheNextRequest() {
        ShadowMoonBridge.setVideoModeV2Result(0);
        assertFalse(presenter.applyLiveStreamQuality(
                new StreamQualityTuple("1280x720", "90", 100000)));
        assertEquals(1, decoderGeneration);
        assertEquals(0, generations().currentModeGeneration());

        // An FPS-only request whose ACK raster differs from the decoder output needs the
        // post-ACK decoder confirmation. It must own a fresh decoder transition.
        ShadowMoonBridge.setVideoModeV2Result(1);
        assertTrue(presenter.applyLiveStreamQuality(
                new StreamQualityTuple("1920x1080", "60", 100000)));
        decoderDimensions = new int[] {1280, 720};
        ack(MoonBridge.SBS_MODE_OFF, 10, 1920, 1080, 1920, 1080, 6000);
        assertEquals(2, decoderGeneration);
        frame(1920, 1080);

        assertFalse(presenter.isStreamQualityTransactionBusy());
        assertEquals(60f, prefs.fps, 0f);
        assertEquals(1, persisted.size());
        assertEquals(0, resyncs);
    }

    @Test
    public void sameGeometryClientPackedProofThatNeverSwapsFailsClosed() {
        ReflectionHelpers.setField(presenter, "currentPresenterMode",
                PresentationMode.CLIENT_SBS_AI);
        when(container.completeClientSbsModeSwitchAfterSwap(any())).thenReturn(true);
        startSameGeometryClientProof();

        assertEquals(0, resyncs);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100));

        assertEquals(1, resyncs);
        assertFalse(presenter.isStreamQualityTransactionBusy());
        verify(container).cancelClientSbsModeSwitchCompletion();
    }

    @Test
    public void sameGeometryClientPackedProofThatSwapsSettlesWithoutLaterTimeout() {
        ReflectionHelpers.setField(presenter, "currentPresenterMode",
                PresentationMode.CLIENT_SBS_AI);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        when(container.completeClientSbsModeSwitchAfterSwap(completion.capture()))
                .thenReturn(true);
        startSameGeometryClientProof();

        completion.getValue().run();
        assertFalse(presenter.isStreamQualityTransactionBusy());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100));

        assertEquals(0, resyncs);
        assertEquals(1, persisted.size());
    }

    @Test
    public void unsentGameEntryLeavesTheGameSourceStateUntouched() throws Exception {
        presenter.setGameProviderV1Supported(true);
        GameStereoSourceState source = ReflectionHelpers.getField(presenter, "gameSourceState");
        source.recordWidenFailure();
        game.getIntent().putExtra("xr_game_widen_failure", true);
        ShadowMoonBridge.setVideoModeV2Result(0);

        selectMode(PresentationMode.GAME_3D);

        assertEquals(PresentationMode.NORMAL,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
        assertTrue(source.hasWidenFailure());
        assertTrue(game.getIntent().getBooleanExtra("xr_game_widen_failure", false));
        assertFalse(presenter.isStreamQualityTransactionBusy());
    }

    @Test
    public void unsentHostAiEntryKeepsTheReportedDepthPhase() throws Exception {
        presenter.onDepthStatus(2);
        ShadowMoonBridge.setVideoModeV2Result(0);

        selectMode(PresentationMode.HOST_SBS_AI);

        XrDepthStatusOverlay overlay = ReflectionHelpers.getField(presenter, "depthStatusOverlay");
        assertEquals(2, overlay.phase());
        assertEquals(PresentationMode.NORMAL,
                ReflectionHelpers.getField(presenter, "currentPresenterMode"));
    }

    @Test
    public void glanceStripLabelsTheNegotiatedDynamicRange() throws Exception {
        TextView stream = attachGlanceViews();
        prefs.enableHdr = true;
        doReturn(false).when(game).isStreamHdrActive();
        invoke("updateGlancePanel", new Class<?>[0]);
        assertTrue(stream.getText().toString().endsWith(game.getString(R.string.xr_glance_sdr)));

        prefs.enableHdr = false;
        doReturn(true).when(game).isStreamHdrActive();
        presenter.onHdrModeChanged();
        assertTrue(stream.getText().toString().endsWith(game.getString(R.string.xr_glance_hdr)));
    }

    private void startSameGeometryClientProof() {
        assertTrue(presenter.applyLiveStreamQuality(
                new StreamQualityTuple("1920x1080", "60", 100000)));
        decoderDimensions = new int[] {1280, 720};
        ack(MoonBridge.SBS_MODE_OFF, 10, 1920, 1080, 1920, 1080, 6000);
        frame(1920, 1080);
        verify(container).completeClientSbsModeSwitchAfterSwap(any());
        assertTrue(presenter.isStreamQualityTransactionBusy());
    }

    private TextView attachGlanceViews() {
        Object glance = ReflectionHelpers.getField(presenter, "glanceStrip");
        ReflectionHelpers.setField(glance, "glancePanel", mock(PanelEntity.class));
        for (String name : new String[] {"glanceIdentityView", "glanceModeView",
                "glanceStatusView"}) {
            ReflectionHelpers.setField(glance, name, new TextView(game));
        }
        TextView stream = new TextView(game);
        ReflectionHelpers.setField(glance, "glanceStreamView", stream);
        return stream;
    }

    private DecoderTransitionGenerationGate generations() {
        return ReflectionHelpers.getField(presenter, "decoderTransitionGenerations");
    }

    private void followPanelDownTo72() {
        presenter.onClientRefreshRateChanged(72f);
        idle();
        ack(MoonBridge.SBS_MODE_OFF, 10, 1920, 1080, 1920, 1080, 7200);
        idle();
        assertEquals(72f, prefs.fps, 0f);
        assertEquals(90, panelState().getUserCeilingHz());
        assertEquals(0, persisted.size());
    }

    private PanelRefreshRateState panelState() {
        return ReflectionHelpers.getField(presenter, "panelRefreshRateState");
    }

    private void selectMode(PresentationMode mode) throws Exception {
        Class<?> itemType = Class.forName("com.limelight.ui.XrStreamPresenter$BarItem");
        Constructor<?> constructor = itemType.getDeclaredConstructor(
                XrStreamPresenter.class, String.class, int.class, PresentationMode.class);
        constructor.setAccessible(true);
        Object item = constructor.newInstance(presenter, mode.name(),
                R.drawable.ic_xr_mode_host_sbs, mode);
        ReflectionHelpers.setField(presenter, "lastModeSwitchMs", -10000L);
        invoke("selectMode", new Class<?>[] {itemType}, item);
    }

    private void ack(int mode, int generation, int sourceWidth, int sourceHeight,
                     int encodedWidth, int encodedHeight, int fpsX100) {
        int request = ShadowMoonBridge.getLastSetVideoModeV2Request()[1];
        presenter.onVideoModeAckV2(MoonBridge.VIDEO_MODE_ACK_APPLIED, mode, 0, request,
                generation, sourceWidth, sourceHeight, encodedWidth, encodedHeight,
                fpsX100, 90000);
    }

    private void frame(int width, int height) {
        decoderDimensions = new int[] {width, height};
        presenter.onDecoderPresentationModeTransitionOpened(decoderGeneration);
        idle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        Method method = XrStreamPresenter.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(presenter, arguments);
    }
}
