package com.limelight.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;

import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.AuthoredStereoModeState;
import com.limelight.ui.xrcontrols.AuthoredStereoModeState.MoviePictureFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Authored intent must not accidentally enter the legacy Raw or AI transport paths. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrAuthoredPresentationTest {
    private static final PresentationMode[] AUTHORED = {
            PresentationMode.GAME_3D, PresentationMode.MOVIE_3D
    };
    private static final float EPSILON = 0.0001f;

    @Test
    public void glassesAndHeadsetKeepTheirRequestedRasterAcrossStartupAndLiveQuality() {
        for (PresentationMode mode : AUTHORED) {
            for (int[] requested : new int[][] {{1920, 1080}, {3840, 2160}}) {
                for (PreferenceConfiguration.RawSbsPerEyeResolution legacyPacking
                        : PreferenceConfiguration.RawSbsPerEyeResolution.values()) {
                    for (int codec : new int[] {MoonBridge.VIDEO_FORMAT_H264,
                            MoonBridge.VIDEO_FORMAT_H265, MoonBridge.VIDEO_FORMAT_AV1_MAIN10}) {
                        String context = mode + " " + requested[0] + "x" + requested[1]
                                + " codec=" + codec + " legacy=" + legacyPacking;
                        assertArrayEquals(context, requested,
                                XrStreamPresenter.initialSurfacePixelDimensions(mode,
                                        requested[0], requested[1], codec, legacyPacking));
                        assertArrayEquals(context, requested,
                                XrStreamPresenter.decoderStreamDimensions(mode,
                                        requested[0], requested[1], codec, legacyPacking));
                    }
                    assertArrayEquals(requested, XrStreamPresenter.liveVideoModeWireDimensions(
                            mode, requested[0], requested[1], legacyPacking));
                    assertArrayEquals(requested, XrStreamPresenter.liveVideoModeLogicalDimensions(
                            mode, requested[0], requested[1], legacyPacking));
                    assertTrue(XrStreamPresenter.supportsLiveResolutionChange(mode, legacyPacking));
                }
            }
        }
    }

    @Test
    public void authoredIntentUsesOffWireWithoutRawWidthOrH264PackingCost() throws Exception {
        for (PresentationMode mode : AUTHORED) {
            assertEquals(MoonBridge.SBS_MODE_OFF, wireMode(mode));
            assertTrue(XrStreamPresenter.isPresentationModeSupported(mode, false));
            for (PreferenceConfiguration.RawSbsPerEyeResolution legacyPacking
                    : PreferenceConfiguration.RawSbsPerEyeResolution.values()) {
                assertFalse(XrStreamPresenter.usesRawPackedTransport(mode, legacyPacking));
                assertFalse(XrStreamPresenter.usesPackedBitrateCost(mode, legacyPacking));
                assertFalse(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                        PresentationMode.NORMAL, mode, legacyPacking));
                assertFalse(XrStreamPresenter.requiresReconnectBeforeModeSwitch(
                        mode, PresentationMode.NORMAL, legacyPacking));
            }
            assertFalse(XrStreamPresenter.hostSbsFormatChangeRequiresResize(mode,
                    3840, 2160, MoonBridge.VIDEO_FORMAT_H265, MoonBridge.VIDEO_FORMAT_H264));
        }
        // The test's 4K H.264 input still distinguishes this from the AI width-cap path.
        assertTrue(XrStreamPresenter.hostSbsFormatChangeRequiresResize(
                PresentationMode.HOST_SBS_AI, 3840, 2160,
                MoonBridge.VIDEO_FORMAT_H265, MoonBridge.VIDEO_FORMAT_H264));
    }

    @Test
    public void normalAndAuthoredSwitchesReuseTheDecoderWhileAiCrossingsKeepTheirGates() {
        for (PresentationMode from : new PresentationMode[] {
                PresentationMode.NORMAL, PresentationMode.GAME_3D, PresentationMode.MOVIE_3D}) {
            for (PresentationMode to : AUTHORED) {
                assertFalse(XrStreamPresenter.requiresDecoderTransition(from, to));
                assertFalse(XrStreamPresenter.requiresHostSurfaceResize(from, to));
                assertFalse(XrStreamPresenter.requiresAtomicPresentationReconnect(from, to, false));
                assertFalse(XrStreamPresenter.requiresDecoderTransition(to, from));
            }
        }
        for (PresentationMode mode : AUTHORED) {
            assertTrue(XrStreamPresenter.requiresDecoderTransition(mode, PresentationMode.HOST_SBS_AI));
            assertTrue(XrStreamPresenter.requiresDecoderTransition(PresentationMode.HOST_SBS_AI, mode));
            assertTrue(XrStreamPresenter.requiresHostSurfaceResize(PresentationMode.HOST_SBS_AI, mode));
            assertTrue(XrStreamPresenter.requiresAtomicPresentationReconnect(
                    PresentationMode.HOST_SBS_AI, mode, false));
            assertTrue(XrStreamPresenter.requiresDecoderTransition(PresentationMode.CLIENT_SBS_AI, mode));
            assertFalse(XrStreamPresenter.requiresHostSurfaceResize(PresentationMode.CLIENT_SBS_AI, mode));
        }
    }

    @Test
    public void restoredIntentStartsMonoAndDoesNotReuseAnEarlierMovieInterpretation() throws Exception {
        try (PresenterFixture fixture = new PresenterFixture()) {
            for (PresentationMode mode : AUTHORED) {
                fixture.state().setMoviePictureFormat(MoviePictureFormat.FULL_SBS);
                invoke(fixture.presenter, "restoreViewState", new Class<?>[] {PresentationMode.class}, mode);
                assertEquals(mode, field(fixture.presenter, "currentPresenterMode"));
                assertEquals(MoviePictureFormat.TWO_D, fixture.state().getMoviePictureFormat());
                assertEquals(SurfaceEntity.StereoMode.MONO, fixture.stereo(mode));
                assertEquals(16f / 9f, fixture.aspect(mode), EPSILON);
            }
        }
    }

    @Test
    public void explicitMoviePackingChangesOnlyEyeInterpretationAndQuadAspect() throws Exception {
        try (PresenterFixture fixture = new PresenterFixture()) {
            setField(fixture.presenter, "currentPresenterMode", PresentationMode.MOVIE_3D);
            setField(fixture.presenter, "streamPresentationReady", true);
            SurfaceEntity surface = mock(SurfaceEntity.class);
            when(surface.getShape()).thenReturn(new SurfaceEntity.Shape.Quad(new FloatSize2d(32f / 9f, 2f)));
            setField(fixture.presenter, "surfaceEntity", surface);

            assertTrue(fixture.selectMovieFormat("half_sbs"));
            assertEquals(SurfaceEntity.StereoMode.SIDE_BY_SIDE, fixture.stereo(PresentationMode.MOVIE_3D));
            assertEquals(16f / 9f, fixture.aspect(PresentationMode.MOVIE_3D), EPSILON);

            // A native full-SBS raster is twice the aspect of either eye. The format selector
            // interprets an already-wide capture; it does not claim to make that capture wider.
            setField(fixture.presenter, "fullAspect", 32f / 9f);
            assertTrue(fixture.selectMovieFormat("full_sbs"));
            assertEquals(16f / 9f, fixture.aspect(PresentationMode.MOVIE_3D), EPSILON);
            assertEquals(SurfaceEntity.StereoMode.MONO, fixture.stereo(PresentationMode.GAME_3D));
            assertEquals(32f / 9f, fixture.aspect(PresentationMode.GAME_3D), EPSILON);

            assertTrue(fixture.selectMovieFormat("2d"));
            assertEquals(SurfaceEntity.StereoMode.MONO, fixture.stereo(PresentationMode.MOVIE_3D));
            assertEquals(32f / 9f, fixture.aspect(PresentationMode.MOVIE_3D), EPSILON);
            assertFalse(fixture.selectMovieFormat("auto"));
            assertEquals(MoviePictureFormat.TWO_D, fixture.state().getMoviePictureFormat());

            ArgumentCaptor<SurfaceEntity.Shape> shapes = ArgumentCaptor.forClass(SurfaceEntity.Shape.class);
            verify(surface, org.mockito.Mockito.times(3)).setShape(shapes.capture());
            assertEquals(32f / 9f, ((SurfaceEntity.Shape.Quad) shapes.getAllValues().get(0))
                    .getExtents().getWidth(), EPSILON);
            assertEquals(32f / 9f, ((SurfaceEntity.Shape.Quad) shapes.getAllValues().get(1))
                    .getExtents().getWidth(), EPSILON);
            assertEquals(64f / 9f, ((SurfaceEntity.Shape.Quad) shapes.getAllValues().get(2))
                    .getExtents().getWidth(), EPSILON);
            verify(surface, never()).setSurfacePixelDimensions(any(IntSize2d.class));
            assertEquals(1920, fixture.prefs.width);
            assertEquals(1080, fixture.prefs.height);
            assertEquals(MoonBridge.SBS_MODE_OFF, wireMode(PresentationMode.MOVIE_3D));
        }
    }

    private static int wireMode(PresentationMode mode) throws Exception {
        return (Integer) invoke(null, "wireModeFor", new Class<?>[] {PresentationMode.class}, mode);
    }

    private static Object invoke(XrStreamPresenter presenter, String name, Class<?>[] parameters,
                                 Object... arguments) throws Exception {
        Method method = XrStreamPresenter.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(presenter, arguments);
    }

    private static Object field(XrStreamPresenter presenter, String name) throws Exception {
        Field field = XrStreamPresenter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(presenter);
    }

    private static void setField(XrStreamPresenter presenter, String name, Object value) throws Exception {
        Field field = XrStreamPresenter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(presenter, value);
    }

    private static final class PresenterFixture implements AutoCloseable {
        final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        final PreferenceConfiguration prefs;
        final XrStreamPresenter presenter;

        PresenterFixture() throws Exception {
            Activity activity = controller.get();
            activity.setTheme(R.style.AppTheme);
            controller.setup();
            prefs = PreferenceConfiguration.readPreferences(activity);
            prefs.enablePerfOverlay = false;
            prefs.width = 1920;
            prefs.height = 1080;
            presenter = new XrStreamPresenter(activity, prefs, surface -> { }, visible -> { });
            setField(presenter, "fullAspect", 16f / 9f);
        }

        AuthoredStereoModeState state() throws Exception {
            return (AuthoredStereoModeState) field(presenter, "authoredStereoModeState");
        }

        SurfaceEntity.StereoMode stereo(PresentationMode mode) throws Exception {
            return (SurfaceEntity.StereoMode) invoke(presenter, "stereoModeFor",
                    new Class<?>[] {PresentationMode.class}, mode);
        }

        float aspect(PresentationMode mode) throws Exception {
            return (Float) invoke(presenter, "aspectFor", new Class<?>[] {PresentationMode.class}, mode);
        }

        boolean selectMovieFormat(String choice) throws Exception {
            return (Boolean) invoke(presenter, "onMoviePictureFormatSelected",
                    new Class<?>[] {String.class}, choice);
        }

        @Override
        public void close() {
            presenter.onDestroy();
            controller.destroy();
        }
    }
}
