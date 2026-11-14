package com.limelight;

import com.limelight.sbs.HostSbsTelemetrySnapshot;
import com.limelight.ui.XrStreamPresenter;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forwards the 3D host callbacks that {@link Game} receives as an
 * {@link com.limelight.nvstream.NvConnectionListener} to the XR presenter. The callbacks arrive
 * on connection threads; each is re-posted to the UI thread and dropped there unless the
 * connection UI is still active and a presenter exists.
 */
final class XrHostCallbackRouter {
    private final Executor uiThread;
    private final BooleanSupplier connectionUiActive;
    private final Supplier<XrStreamPresenter> presenterSupplier;

    XrHostCallbackRouter(Executor uiThread, BooleanSupplier connectionUiActive,
                         Supplier<XrStreamPresenter> presenterSupplier) {
        this.uiThread = uiThread;
        this.connectionUiActive = connectionUiActive;
        this.presenterSupplier = presenterSupplier;
    }

    void depthStatus(int phase) {
        postToPresenter(presenter -> presenter.onDepthStatus(phase));
    }

    void onGameSourceStatus(int state, int provider, int presentationGeneration,
                            int sourceRevision, int sourceWidth, int sourceHeight,
                            int packedWidth, int packedHeight) {
        postToPresenter(presenter -> presenter.onGameSourceStatus(state, provider,
                presentationGeneration, sourceRevision,
                sourceWidth, sourceHeight, packedWidth, packedHeight));
    }

    void videoModeAckV2(int status, int appliedMode, int flags, int requestId,
                        int stateGeneration, int appliedSourceWidth,
                        int appliedSourceHeight, int exactEncodedWidth,
                        int exactEncodedHeight, int appliedFramerateX100,
                        int effectiveEncoderBitrateKbps) {
        postToPresenter(presenter -> presenter.onVideoModeAckV2(
                status, appliedMode, flags, requestId, stateGeneration,
                appliedSourceWidth, appliedSourceHeight,
                exactEncodedWidth, exactEncodedHeight,
                appliedFramerateX100, effectiveEncoderBitrateKbps));
    }

    void hostSbsTelemetryState(byte[] payload) {
        final HostSbsTelemetrySnapshot telemetry;
        try {
            telemetry = HostSbsTelemetrySnapshot.parse(payload);
        } catch (IllegalArgumentException error) {
            LimeLog.warning("Dropping malformed host SBS telemetry: " + error.getMessage());
            return;
        }
        final long receivedAtMs = android.os.SystemClock.uptimeMillis();
        postToPresenter(presenter -> presenter.onHostSbsTelemetryState(
                telemetry, receivedAtMs));
    }

    /**
     * Re-posts a native 3D callback to the UI thread and delivers it only while this connection's
     * UI is still active and an XR presenter exists.
     */
    private void postToPresenter(Consumer<XrStreamPresenter> action) {
        uiThread.execute(() -> {
            XrStreamPresenter presenter = connectionUiActive.getAsBoolean()
                    ? presenterSupplier.get() : null;
            if (presenter != null) {
                action.accept(presenter);
            }
        });
    }
}
