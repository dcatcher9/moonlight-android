package com.limelight.ui;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.SessionExt;
import androidx.xr.scenecore.Space;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Cinema View state: whether the enlarged preset is applied, the pose and height to restore on
 * exit, and the environment override that accompanies it. {@link XrStreamPresenter} keeps the
 * video quad, its height and the Cinema tile and options controls; this class reaches the quad
 * through {@link Host}.
 */
final class XrCinemaController {
    /** The quad and session state the preset reads and writes through its owner. */
    interface Host {
        Session session();

        SurfaceEntity videoEntity();

        boolean hostActivityStarted();

        boolean controlTransportOpen();

        PresentationMode currentPresenterMode();

        float aspectFor(PresentationMode mode);

        float panelHeightMeters();

        void setPanelHeightMeters(float heightMeters);

        void applyResizeBounds(float aspect);

        Pose currentHeadPose();

        void repositionControlBar(float videoHeightMeters);

        void saveHeight(float heightMeters);
    }

    private final PreferenceConfiguration prefConfig;
    private final Host host;

    XrCinemaController(PreferenceConfiguration prefConfig, Host host) {
        this.prefConfig = prefConfig;
        this.host = host;
    }

    private static final float CINEMA_PRESET_DISTANCE_METERS = 2.0f;
    private static final float DEFAULT_PANEL_HEIGHT_METERS =
            XrViewStateStore.DEFAULT_HEIGHT_METERS;

    private boolean cinemaViewExpanded;
    private XrCinemaEnvironmentOverride cinemaBackgroundOverride;
    private Session cinemaBackgroundSession;
    private float cinemaRestoreHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
    private Pose cinemaRestorePose;

    boolean isExpanded() {
        return cinemaViewExpanded;
    }

    /** Forgets the applied preset and its restore target without touching the quad. */
    void resetViewState() {
        cinemaViewExpanded = false;
        cinemaRestorePose = null;
        cinemaRestoreHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
    }

    /**
     * Cinema preset helper that toggles between the large preset and the last transform/height.
     * The enlarged preset keeps the panel at the tuned cinematic distance and orientation.
     */
    void applyCinemaViewPreset() {
        if (host.videoEntity() == null || host.videoEntity().isDisposed()) {
            return;
        }
        cinemaRestoreHeightMeters = host.panelHeightMeters();
        try {
            cinemaRestorePose = host.videoEntity().getPose(Space.REAL_WORLD);
        } catch (Throwable error) {
            LimeLog.warning("XR cinema view: current pose capture failed (" + error + ")");
            cinemaRestorePose = null;
        }
        host.videoEntity().setScale(1.0f);
        float aspect = host.aspectFor(host.currentPresenterMode());
        float height = DEFAULT_PANEL_HEIGHT_METERS;
        host.setPanelHeightMeters(height);
        host.videoEntity().setShape(new SurfaceEntity.Shape.Quad(new FloatSize2d(height * aspect, height)));
        host.applyResizeBounds(aspect);

        // Place the quad ~2 m in front of the user's CURRENT head pose (not the activity-space
        // origin, which is fixed at session start — that's why a plain reset lands off to the side
        // after you've turned). Fall back to activity-space-forward if the head pose isn't available.
        Pose placed = null;
        try {
            Pose head = host.currentHeadPose();
            if (head != null) {
                // Level the placement: keep only the head's yaw (heading), discarding pitch and
                // roll. Otherwise clicking Cinema View while looking down (e.g. at the control bar
                // below the quad) tilts the panel up to face the eyes and drops it below eye level,
                // so it reads as "looking down at a tilted screen". Round-tripping the Y (yaw) euler
                // component zeroes pitch/roll regardless of angle units, and reusing the proven
                // compose(-2 m forward) keeps the panel facing the user at eye height, 2 m ahead.
                Vector3 euler = head.getRotation().getEulerAngles();
                Pose level = new Pose(head.getTranslation(),
                        Quaternion.fromEulerAngles(0.0f, euler.getY(), 0.0f));
                Pose inFront = level.compose(
                        new Pose(
                                new Vector3(0.0f, 0.0f, -CINEMA_PRESET_DISTANCE_METERS),
                                Quaternion.Identity));
                host.videoEntity().setPose(inFront, Space.REAL_WORLD);
                placed = inFront;
            }
        } catch (Throwable t) {
            LimeLog.warning("XR cinema view: current head pose unavailable (" + t + ")");
        }
        if (placed == null) {
            host.videoEntity().setPose(new Pose(
                    new Vector3(0.0f, 0.0f, -CINEMA_PRESET_DISTANCE_METERS),
                    Quaternion.Identity));
        }
        host.repositionControlBar(height);
        cinemaViewExpanded = true;
    }

    /** Height written to the per-PC store: the pre-Cinema height while the preset is applied. */
    static float durableHeightMeters(boolean cinemaViewExpanded,
                                     float cinemaRestoreHeightMeters,
                                     float panelHeightMeters) {
        return cinemaViewExpanded && cinemaRestoreHeightMeters > 0.0f
                ? cinemaRestoreHeightMeters : panelHeightMeters;
    }

    float durableHeightMeters() {
        return durableHeightMeters(cinemaViewExpanded, cinemaRestoreHeightMeters,
                host.panelHeightMeters());
    }

    /** Cinema keeps its original size/pose behavior even if environment control is unavailable. */
    void applyCinemaBackground() {
        if (!cinemaViewExpanded || !host.hostActivityStarted() || !host.controlTransportOpen()
                || host.session() == null || cinemaBackgroundOverride != null) {
            return;
        }
        try {
            Session targetSession = host.session();
            cinemaBackgroundSession = targetSession;
            cinemaBackgroundOverride = new XrCinemaEnvironmentOverride(SessionExt.getScene(host.session()),
                    prefConfig.cinemaEnvironment,
                    () -> cinemaViewExpanded && host.hostActivityStarted() && host.controlTransportOpen()
                            && host.session() == targetSession && cinemaBackgroundOverride != null
                            && cinemaBackgroundOverride.belongsTo(SessionExt.getScene(host.session())),
                    this::restoreCinemaBackground);
            if (!cinemaBackgroundOverride.begin()) {
                restoreCinemaBackground();
            }
        } catch (RuntimeException | LinkageError e) {
            LimeLog.warning("XR cinema background unavailable: " + e);
            restoreCinemaBackground();
        }
    }

    void restoreCinemaBackground() {
        XrCinemaEnvironmentOverride previous = cinemaBackgroundOverride;
        if (previous == null) {
            return;
        }
        cinemaBackgroundOverride = null;
        boolean sameScene = false;
        try {
            sameScene = host.session() != null && host.session() == cinemaBackgroundSession
                    && previous.belongsTo(SessionExt.getScene(host.session()));
        } catch (RuntimeException | LinkageError e) {
            LimeLog.warning("XR cinema background identity unavailable: " + e);
        }
        cinemaBackgroundSession = null;
        previous.restore(sameScene);
    }

    void restoreCinemaView() {
        if (host.videoEntity() == null || host.videoEntity().isDisposed()) {
            return;
        }
        host.videoEntity().setScale(1.0f);
        float restoredHeight = cinemaRestoreHeightMeters > 0.0f
                ? cinemaRestoreHeightMeters
                : DEFAULT_PANEL_HEIGHT_METERS;
        float aspect = host.aspectFor(host.currentPresenterMode());
        host.setPanelHeightMeters(restoredHeight);
        host.videoEntity().setShape(new SurfaceEntity.Shape.Quad(new FloatSize2d(
                host.panelHeightMeters() * aspect, host.panelHeightMeters())));
        host.applyResizeBounds(aspect);
        if (cinemaRestorePose != null) {
            host.videoEntity().setPose(cinemaRestorePose, Space.REAL_WORLD);
        } else {
            host.videoEntity().setPose(new Pose(
                    new Vector3(0.0f, 0.0f, -CINEMA_PRESET_DISTANCE_METERS),
                    Quaternion.Identity), Space.REAL_WORLD);
        }
        host.repositionControlBar(host.panelHeightMeters());
        host.saveHeight(host.panelHeightMeters());
        cinemaRestorePose = null;
        cinemaRestoreHeightMeters = DEFAULT_PANEL_HEIGHT_METERS;
        cinemaViewExpanded = false;
    }
}
