package com.limelight.ui;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

/**
 * Pure presentation policy for the XR presenter: wire-mode and raster arithmetic, the
 * reconnect/decoder-transition predicates between presentation modes, and validation of the
 * atomic video-mode ACK body. Every method is a static function of its arguments;
 * {@link XrStreamPresenter} remains the owner of the mode state machine that consults them.
 */
final class XrPresentationPolicy {
    private XrPresentationPolicy() {
    }

    /** Host SBS AI makes the host emit a packed 2W' x H' side-by-side frame. */
    static boolean isHostDepthPresenterMode(PresentationMode mode) {
        return mode == PresentationMode.HOST_SBS_AI;
    }

    /** True when a direct-decoder mode switch crosses the Host SBS AI packed-size boundary. */
    static boolean requiresHostSurfaceResize(PresentationMode previousMode, PresentationMode nextMode) {
        return isHostDepthPresenterMode(previousMode) != isHostDepthPresenterMode(nextMode);
    }

    /**
     * Maps the logical quality tuple used by the UI/session store onto the 0x3007 wire geometry.
     * Every mode requests its logical dimensions; null when they are not usable on the wire.
     */
    static int[] liveVideoModeWireDimensions(int logicalWidth, int logicalHeight) {
        return isUsableLiveVideoModeWireDimensions(logicalWidth, logicalHeight)
                ? new int[] {logicalWidth, logicalHeight} : null;
    }

    /**
     * Converts the authoritative 0x3008 wire geometry back into the logical quality tuple.
     * Every ACK status carries the mode that actually remains on the wire, so malformed geometry
     * must fail closed even for a refusal rather than being mistaken for a logical per-eye size.
     */
    static int[] liveVideoModeLogicalDimensions(int wireWidth, int wireHeight) {
        if (!isUsableLiveVideoModeWireDimensions(wireWidth, wireHeight)) {
            return null;
        }
        return new int[] {wireWidth, wireHeight};
    }

    static boolean isUsableLiveVideoModeWireDimensions(int width, int height) {
        int cap = PreferenceConfiguration.MAX_HOST_SBS_PACKED_WIDTH_HEVC_AV1;
        return width >= 2 && height >= 2
                && width <= cap && height <= cap
                && (width & 1) == 0 && (height & 1) == 0;
    }

    /** True when bitrate must be costed against a {@code 2W x H} encoded frame: Host SBS AI. */
    static boolean usesPackedBitrateCost(PresentationMode mode) {
        return mode == PresentationMode.HOST_SBS_AI;
    }

    /** True only when the decoder target or encoded dimensions change across the transition. */
    static boolean requiresDecoderTransition(PresentationMode previousMode, PresentationMode nextMode) {
        boolean crossesClientRenderer = retainsOldPictureUntilFreshTargetFrame(
                previousMode, nextMode);
        return crossesClientRenderer || requiresHostSurfaceResize(previousMode, nextMode);
    }

    /** Client-renderer crossings retain the old SceneCore buffer until the target presents. */
    static boolean retainsOldPictureUntilFreshTargetFrame(
            PresentationMode previousMode, PresentationMode nextMode) {
        return (previousMode == PresentationMode.CLIENT_SBS_AI)
                != (nextMode == PresentationMode.CLIENT_SBS_AI);
    }

    /** Keep the renderer's last picture alive throughout either direction of a surface crossing. */
    static boolean clientSbsActiveDuringSurfaceSwitch(
            boolean wasClientSbs, boolean isClientSbs) {
        return wasClientSbs || isClientSbs;
    }

    /** An inactive Client mode with a live-applicable saved tuple is one fused ACK-first switch. */
    static boolean shouldFuseClientModeEntryQuality(
            PresentationMode previousMode, PresentationMode nextMode,
            boolean atomicPresentationV2Supported,
            boolean otherStagedChangesRequireReconnect,
            ModeStreamQualityModel targetQuality) {
        return previousMode != nextMode
                && nextMode == PresentationMode.CLIENT_SBS_AI
                && atomicPresentationV2Supported
                && !otherStagedChangesRequireReconnect
                && targetQuality != null
                && targetQuality.appliesLiveIfSelected();
    }

    /** Commit reconnect-only settings and target quality before any interim mode ACK. */
    static boolean shouldReconnectBeforeModeEntry(
            PresentationMode previousMode, PresentationMode nextMode,
            boolean otherStagedChangesRequireReconnect,
            ModeStreamQualityModel targetQuality) {
        return previousMode != nextMode
                && (otherStagedChangesRequireReconnect
                || (targetQuality != null && targetQuality.requiresReconnectIfSelected()));
    }

    static boolean canSynchronizeClientSbsHdrTransition(
            PresentationMode mode,
            boolean streamReady,
            boolean modeSwitchInProgress,
            boolean otherDecoderTransactionBusy,
            boolean hdrTransitionInProgress) {
        return mode == PresentationMode.CLIENT_SBS_AI
                && streamReady
                && ((!modeSwitchInProgress && !otherDecoderTransactionBusy)
                || hdrTransitionInProgress);
    }

    static boolean resetsHostDepthStatusAtTransitionStart(
            PresentationMode previousMode, PresentationMode nextMode) {
        return previousMode != nextMode && nextMode == PresentationMode.HOST_SBS_AI;
    }

    static boolean resetsHostDepthStatusAtTransitionCommit(
            PresentationMode previousMode, PresentationMode nextMode) {
        return previousMode != nextMode && previousMode == PresentationMode.HOST_SBS_AI;
    }

    static boolean isPresentationModeSupported(PresentationMode mode,
                                               boolean hostControlExtensionsSupported) {
        return mode != PresentationMode.HOST_SBS_RAW
                && (mode != PresentationMode.HOST_SBS_AI || hostControlExtensionsSupported);
    }

    static boolean requiresAtomicPresentationReconnect(
            PresentationMode previousMode, PresentationMode nextMode,
            boolean atomicPresentationV2Supported) {
        return wireModeFor(previousMode) != wireModeFor(nextMode)
                && !atomicPresentationV2Supported;
    }

    /**
     * Largest offered rate not above {@code effectiveHz}.
     *
     * <p>The panel's own modes are 60/72/90, but {@code getRefreshRate()} also reports a system
     * frame-rate override, which is not restricted to those and can read below 60. Requesting an
     * arbitrary rate would ask the host's virtual display for a mode outside the ladder both ends
     * agree on, so the follow target is snapped down onto it instead.</p>
     */
    static int snapToOfferedFrameRate(int effectiveHz) {
        int[] offered = {30, 60, 72, 90, 120};
        int best = 0;
        for (int rate : offered) {
            if (rate <= effectiveHz && rate > best) {
                best = rate;
            }
        }
        // Never chase an override below the slowest offered rate; hold the floor instead.
        return best == 0 ? offered[0] : best;
    }

    static boolean acknowledgedGeometryAdoptionSucceeded(
            boolean geometryChanged, boolean resizeSucceeded) {
        // The fast FPS/bitrate-only path performs no surface resize and is already adopted.
        return !geometryChanged || resizeSucceeded;
    }

    static boolean postSendGeometryFailureRequiresMandatoryResync(int sendResult) {
        // Once the reliable request is queued, local resize failure cannot prove host rollback.
        return sendResult > 0;
    }

    static boolean isKnownVideoModeAckStatus(int status) {
        return status >= MoonBridge.VIDEO_MODE_ACK_APPLIED
                && status <= MoonBridge.VIDEO_MODE_ACK_FAILED;
    }

    static boolean isValidAtomicPresentationAckBody(
            int flags, int appliedMode, int stateGeneration,
            int appliedSourceWidth, int appliedSourceHeight,
            int exactEncodedWidth, int exactEncodedHeight,
            int appliedFramerateX100, int effectiveEncoderBitrateKbps) {
        if (flags != 0 || stateGeneration == 0
                || (appliedMode != MoonBridge.SBS_MODE_OFF
                && appliedMode != MoonBridge.SBS_MODE_AI
                && appliedMode != MoonBridge.SBS_MODE_GAME_MONO
                && appliedMode != MoonBridge.SBS_MODE_GAME_SBS)
                || !isUsableLiveVideoModeWireDimensions(
                        appliedSourceWidth, appliedSourceHeight)
                || !isUsableLiveVideoModeWireDimensions(
                        exactEncodedWidth, exactEncodedHeight)
                || appliedFramerateX100 <= 0 || effectiveEncoderBitrateKbps <= 0) {
            return false;
        }
        if (appliedMode == MoonBridge.SBS_MODE_GAME_SBS) {
            // Authored eyes are copied exactly. AI's codec-fit rounding is not a Game contract.
            return (long) exactEncodedWidth == (long) appliedSourceWidth * 2
                    && exactEncodedHeight == appliedSourceHeight;
        }
        int encodedViewWidth = appliedMode == MoonBridge.SBS_MODE_AI
                ? exactEncodedWidth / 2 : exactEncodedWidth;
        long crossProductError = Math.abs(
                (long) encodedViewWidth * appliedSourceHeight
                        - (long) exactEncodedHeight * appliedSourceWidth);
        // Host fit-to-cap rounds each scaled axis down to an even encoder dimension. Permit only
        // that bounded rounding error, not a materially different aspect ratio.
        long evenRoundingTolerance =
                2L * (appliedSourceWidth + appliedSourceHeight);
        return crossProductError <= evenRoundingTolerance;
    }

    /** How a correlated atomic presentation ACK settles its outstanding request. */
    enum AtomicAckOutcome {
        /** Unknown status or an unusable body: the host tuple is unknowable. */
        MALFORMED,
        /** The host refused the request and keeps another tuple on the wire. */
        REJECTED,
        /** The host refused the request because it is reachable only by reconnecting. */
        REJECTED_NEEDS_RECONNECT,
        /** APPLIED, but not at the requested wire mode and source, or not a newer generation. */
        MISMATCHED,
        /** APPLIED at the requested wire mode and source with a newer generation. */
        APPLIED,
        /**
         * An automatic Game widening was refused while the ACK proves the retained mono layout
         * at the requested source; the request settles as that mono presentation.
         */
        REFUSED_WIDENING_KEPT_MONO,
    }

    /**
     * Classifies a correlated 0x3008 ACK against the outstanding request. Pure: the presenter
     * applies the outcome.
     *
     * @param automaticGameWidening the request is a Game source request issued while the
     *                              confirmed Game wire mode is mono
     */
    static AtomicAckOutcome classifyAtomicPresentationAck(
            int status, int appliedMode, int flags, int stateGeneration,
            int appliedSourceWidth, int appliedSourceHeight,
            int exactEncodedWidth, int exactEncodedHeight,
            int appliedFramerateX100, int effectiveEncoderBitrateKbps,
            int requestedWireMode, int requestedSourceWidth, int requestedSourceHeight,
            int lastStateGeneration, boolean automaticGameWidening) {
        if (!isKnownVideoModeAckStatus(status)
                || !isValidAtomicPresentationAckBody(
                        flags, appliedMode, stateGeneration,
                        appliedSourceWidth, appliedSourceHeight,
                        exactEncodedWidth, exactEncodedHeight,
                        appliedFramerateX100, effectiveEncoderBitrateKbps)) {
            return AtomicAckOutcome.MALFORMED;
        }
        boolean requestedSource = appliedSourceWidth == requestedSourceWidth
                && appliedSourceHeight == requestedSourceHeight;
        if (status != MoonBridge.VIDEO_MODE_ACK_APPLIED
                && status != MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT
                && automaticGameWidening
                && requestedWireMode == MoonBridge.SBS_MODE_GAME_SBS
                && appliedMode == MoonBridge.SBS_MODE_GAME_MONO
                && requestedSource
                && (stateGeneration == lastStateGeneration
                || isStrictlyNewerUnsignedGeneration(lastStateGeneration, stateGeneration))) {
            return AtomicAckOutcome.REFUSED_WIDENING_KEPT_MONO;
        }
        if (status == MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT) {
            return AtomicAckOutcome.REJECTED_NEEDS_RECONNECT;
        }
        if (status != MoonBridge.VIDEO_MODE_ACK_APPLIED) {
            return AtomicAckOutcome.REJECTED;
        }
        return appliedMode == requestedWireMode && requestedSource
                && isStrictlyNewerUnsignedGeneration(lastStateGeneration, stateGeneration)
                ? AtomicAckOutcome.APPLIED : AtomicAckOutcome.MISMATCHED;
    }

    static boolean isStrictlyNewerUnsignedGeneration(
            int previousGeneration, int candidateGeneration) {
        if (candidateGeneration == 0) {
            return false;
        }
        if (previousGeneration == 0) {
            return true;
        }
        int delta = candidateGeneration - previousGeneration;
        return delta != 0 && Integer.compareUnsigned(delta, Integer.MIN_VALUE) < 0;
    }

    static boolean canSettleAtomicQualityWithoutDecoderTransition(
            boolean modeTransitionPending,
            StreamQualityTuple previous, StreamQualityTuple requested,
            int exactEncodedWidth, int exactEncodedHeight,
            int decoderOutputWidth, int decoderOutputHeight) {
        int[] previousSize = previous != null
                ? parseResolutionSize(previous.resolution) : null;
        int[] requestedSize = requested != null
                ? parseResolutionSize(requested.resolution) : null;
        return !modeTransitionPending
                && previousSize != null && requestedSize != null
                && previousSize[0] == requestedSize[0]
                && previousSize[1] == requestedSize[1]
                && exactEncodedWidth == decoderOutputWidth
                && exactEncodedHeight == decoderOutputHeight;
    }

    /**
     * The host's applied wire mode converted to a logical stream-quality tuple. ACKs carry each
     * mode's logical/base dimensions. {@code bitrateKbps} is the host's post-budget encoder value
     * rather than the requested wire budget. Fractional frame rates retain the
     * hundredths-of-a-Hz precision carried by the ACK. Null when the host reported nothing usable.
     */
    static StreamQualityTuple appliedTuple(
            int wireWidth, int wireHeight, int framerateX100, int bitrateKbps) {
        int[] logicalDimensions = liveVideoModeLogicalDimensions(wireWidth, wireHeight);
        if (logicalDimensions == null || framerateX100 <= 0 || bitrateKbps <= 0) {
            return null;
        }
        return new StreamQualityTuple(
                logicalDimensions[0] + "x" + logicalDimensions[1],
                XrStatsFormat.formatFrameRateX100(framerateX100), bitrateKbps);
    }

    /**
     * Reconciles the host-applied tuple with the user's durable FPS ceiling.
     *
     * <p>The selected FPS is a maximum, so an ACK's lower current FPS is always effective-only.
     * Host-clamped geometry and the requested wire bitrate remain authoritative and durable.</p>
     */
    static StreamQualityTuple durableUserQuality(
            StreamQualityTuple applied, StreamQualityTuple durableRequested) {
        if (applied == null) {
            return null;
        }
        if (durableRequested == null) {
            return applied;
        }
        return new StreamQualityTuple(
                applied.resolution, durableRequested.frameRate, applied.bitrateKbps);
    }

    /**
     * Frame rate on the wire is hundredths of a Hz, so fractional rates (2997 = 29.97) survive
     * the round trip even though the XR picker currently only offers whole values.
     */
    static int frameRateX100(String frameRate, float fallbackFps) {
        float fps;
        try {
            fps = Float.parseFloat(frameRate.trim());
        } catch (RuntimeException e) {
            fps = fallbackFps;
        }
        return Math.max(0, Math.min(0xFFFF, Math.round(fps * 100f)));
    }

    static float parseFrameRate(String frameRate, float fallback) {
        try {
            return Float.parseFloat(frameRate.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Parses a {@code WxH} stream-quality resolution id; null when it is not usable. */
    static int[] parseResolutionSize(String resolution) {
        if (resolution == null) {
            return null;
        }
        try {
            String[] parts = resolution.split("x", 2);
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            return (width > 0 && height > 0) ? new int[] {width, height} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Client entry needs the renderer's packed output, not merely its decoded input frame. */
    static boolean requiresClientPackedSwapProof(PresentationMode currentMode,
                                                 PresentationMode pendingMode) {
        return currentMode != PresentationMode.CLIENT_SBS_AI
                && pendingMode == PresentationMode.CLIENT_SBS_AI;
    }

    static int wireModeFor(PresentationMode mode) {
        return mode == PresentationMode.HOST_SBS_AI
                ? MoonBridge.SBS_MODE_AI : MoonBridge.SBS_MODE_OFF;
    }

    static boolean isPackedWireMode(int wireMode) {
        return wireMode == MoonBridge.SBS_MODE_AI || wireMode == MoonBridge.SBS_MODE_GAME_SBS;
    }

    /** Exact packed output for two full-resolution Client-SBS eyes, or null when invalid. */
    static int[] clientSbsPackedDimensions(int perEyeWidth, int perEyeHeight) {
        long packedWidth = (long) perEyeWidth * 2L;
        if (perEyeWidth <= 0 || perEyeHeight <= 0 || packedWidth > Integer.MAX_VALUE) {
            return null;
        }
        return new int[] {(int) packedWidth, perEyeHeight};
    }

    static boolean hostSbsFormatChangeRequiresResize(
            PresentationMode mode, int width, int height, int oldFormat, int newFormat) {
        if (mode != PresentationMode.HOST_SBS_AI || oldFormat == newFormat) {
            return false;
        }
        int[] oldDimensions = PreferenceConfiguration.hostSbsPackedDimensions(
                width, height, oldFormat);
        int[] newDimensions = PreferenceConfiguration.hostSbsPackedDimensions(
                width, height, newFormat);
        return oldDimensions[0] != newDimensions[0]
                || oldDimensions[1] != newDimensions[1];
    }

    static int[] initialSurfacePixelDimensions(PresentationMode mode,
                                               int logicalWidth,
                                               int logicalHeight,
                                               int hostAiVideoFormat) {
        if (isHostDepthPresenterMode(mode)) {
            return PreferenceConfiguration.hostSbsPackedDimensions(
                    logicalWidth, logicalHeight, hostAiVideoFormat);
        }
        return new int[] {logicalWidth, logicalHeight};
    }

    /** Actual encoded/presentation geometry retained by MediaCodec recovery. */
    static int[] decoderStreamDimensions(PresentationMode mode,
                                         int logicalWidth,
                                         int logicalHeight,
                                         int hostAiVideoFormat) {
        return initialSurfacePixelDimensions(mode, logicalWidth, logicalHeight,
                hostAiVideoFormat);
    }
}
