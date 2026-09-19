package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.limelight.ui.PresentationMode;
import com.limelight.ui.xrcontrols.AuthoredStereoModeState.MoviePictureFormat;

import org.junit.Test;

public final class AuthoredStereoModeStateTest {
    @Test
    public void startsAndResetsWithTheWholePictureInBothEyes() {
        AuthoredStereoModeState state = new AuthoredStereoModeState();

        assertSame(MoviePictureFormat.TWO_D, state.getMoviePictureFormat());
        assertFalse(state.isSideBySide(PresentationMode.MOVIE_3D));
        assertFalse(state.isSideBySide(PresentationMode.GAME_3D));

        state.setMoviePictureFormat(MoviePictureFormat.FULL_SBS);
        assertTrue(state.isSideBySide(PresentationMode.MOVIE_3D));
        state.reset();

        assertSame(MoviePictureFormat.TWO_D, state.getMoviePictureFormat());
        assertFalse(state.isSideBySide(PresentationMode.MOVIE_3D));
        assertEquals(16.0f / 9.0f,
                state.presentationAspect(PresentationMode.MOVIE_3D, 16.0f / 9.0f), 0.0f);
    }

    @Test
    public void halfSbsRestoresSqueezedEyesWhileFullSbsSplitsTheWidePicture() {
        AuthoredStereoModeState state = new AuthoredStereoModeState();
        float widescreen = 16.0f / 9.0f;

        state.setMoviePictureFormat(MoviePictureFormat.HALF_SBS);
        assertTrue(state.isSideBySide(PresentationMode.MOVIE_3D));
        assertEquals(widescreen,
                state.presentationAspect(PresentationMode.MOVIE_3D, widescreen), 0.0f);

        state.setMoviePictureFormat(MoviePictureFormat.FULL_SBS);
        assertTrue(state.isSideBySide(PresentationMode.MOVIE_3D));
        assertEquals(widescreen,
                state.presentationAspect(PresentationMode.MOVIE_3D, widescreen * 2.0f), 0.0f);

        state.setMoviePictureFormat(MoviePictureFormat.TWO_D);
        assertFalse(state.isSideBySide(PresentationMode.MOVIE_3D));
        assertEquals(widescreen * 2.0f,
                state.presentationAspect(PresentationMode.MOVIE_3D, widescreen * 2.0f), 0.0f);
    }

    @Test
    public void gameNeverInheritsTheMoviesStereoLayoutOrAspect() {
        AuthoredStereoModeState state = new AuthoredStereoModeState();
        for (MoviePictureFormat format : MoviePictureFormat.values()) {
            state.setMoviePictureFormat(format);
            assertFalse(state.isSideBySide(PresentationMode.GAME_3D));
            assertEquals(16.0f / 9.0f,
                    state.presentationAspect(PresentationMode.GAME_3D, 16.0f / 9.0f), 0.0f);
        }
    }

    @Test
    public void leavesOtherProducerLayoutsToThePresenter() {
        AuthoredStereoModeState state = new AuthoredStereoModeState();
        state.setMoviePictureFormat(MoviePictureFormat.FULL_SBS);
        for (PresentationMode mode : new PresentationMode[] {
                PresentationMode.NORMAL, PresentationMode.HOST_SBS_RAW,
                PresentationMode.HOST_SBS_AI, PresentationMode.CLIENT_SBS_AI}) {
            assertFalse(state.isSideBySide(mode));
            assertEquals(2.0f, state.presentationAspect(mode, 2.0f), 0.0f);
        }
    }

    @Test
    public void invalidAspectCannotCollapseOrPoisonThePresentationQuad() {
        AuthoredStereoModeState state = new AuthoredStereoModeState();
        for (float invalid : new float[] {
                0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.MIN_VALUE}) {
            state.reset();
            assertEquals(16.0f / 9.0f,
                    state.presentationAspect(PresentationMode.MOVIE_3D, invalid), 0.0f);
            state.setMoviePictureFormat(MoviePictureFormat.FULL_SBS);
            assertEquals(8.0f / 9.0f,
                    state.presentationAspect(PresentationMode.MOVIE_3D, invalid), 0.0f);
        }
    }

    @Test
    public void rejectsAnUnknownFormatWithoutChangingTheCurrentInterpretation() {
        AuthoredStereoModeState state = new AuthoredStereoModeState();
        state.setMoviePictureFormat(MoviePictureFormat.HALF_SBS);

        assertThrows(NullPointerException.class, () -> state.setMoviePictureFormat(null));
        assertSame(MoviePictureFormat.HALF_SBS, state.getMoviePictureFormat());
    }
}
