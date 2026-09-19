package com.limelight.ui.xrcontrols;

import com.limelight.ui.PresentationMode;

import java.util.Objects;

/**
 * Transient picture interpretation for authored stereo, independent of selected mode and quality.
 *
 * <p>This state never changes capture or transport dimensions. Selecting Game 3D does not prove
 * that a game is publishing stereo: until provider readiness is negotiated, it remains mono.
 * Movie interpretation changes only through an explicit format choice, not image detection.</p>
 */
public final class AuthoredStereoModeState {
    public enum MoviePictureFormat {
        TWO_D,
        HALF_SBS,
        FULL_SBS
    }

    private static final float DEFAULT_ASPECT = 16.0f / 9.0f;

    private MoviePictureFormat moviePictureFormat = MoviePictureFormat.TWO_D;

    public MoviePictureFormat getMoviePictureFormat() {
        return moviePictureFormat;
    }

    public void setMoviePictureFormat(MoviePictureFormat format) {
        moviePictureFormat = Objects.requireNonNull(format, "format");
    }

    /** Forget the previous picture's interpretation when beginning a new authored-mode entry. */
    public void reset() {
        moviePictureFormat = MoviePictureFormat.TWO_D;
    }

    /** Other stereo producers, including the legacy Raw mode, remain the presenter's concern. */
    public boolean isSideBySide(PresentationMode mode) {
        return mode == PresentationMode.MOVIE_3D
                && moviePictureFormat != MoviePictureFormat.TWO_D;
    }

    /**
     * Returns the eye's display aspect from the normal captured picture's aspect.
     * Half SBS restores squeezed eyes to that normal aspect; Full SBS splits a wide picture.
     * This cannot restore detail already lost when a player scaled video into its desktop.
     */
    public float presentationAspect(PresentationMode mode, float normalAspect) {
        float safeAspect = Float.isFinite(normalAspect) && normalAspect >= Float.MIN_NORMAL
                ? normalAspect : DEFAULT_ASPECT;
        return mode == PresentationMode.MOVIE_3D
                && moviePictureFormat == MoviePictureFormat.FULL_SBS
                ? safeAspect / 2.0f : safeAspect;
    }
}
