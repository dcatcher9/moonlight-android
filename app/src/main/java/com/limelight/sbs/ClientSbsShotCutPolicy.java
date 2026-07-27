package com.limelight.sbs;

/**
 * Single-owner constants and a CPU reference for the Client-SBS shot-relatch policy.
 *
 * <p>The production decision remains GPU-resident in {@link ClientSbsGpuDepthShaders}. Keeping
 * the thresholds and transition reference here lets numerical JVM tests exercise the same
 * hysteresis without adding a render-path readback or a second source of numeric constants.</p>
 *
 * <p>The state semantics intentionally match Apollo: geometry and qualified appearance have
 * independent arms, every accepted cut latches both, and a genuinely new geometry spike can
 * escape a still-latched state. The numeric entry/exit thresholds remain client-specific because
 * they operate on the smaller model grid rather than Apollo's full capture grid.</p>
 */
final class ClientSbsShotCutPolicy {
    static final float RAW_PIXEL_DEPTH_DELTA = 0.12f;

    static final float STANDALONE_DEPTH_CHANGE_ENTER = 0.58f;
    static final float STANDALONE_DEPTH_CHANGE_WITH_SHIFT_ENTER = 0.42f;
    static final float STANDALONE_DISTRIBUTION_SHIFT_ENTER = 0.10f;

    static final float APPEARANCE_DEPTH_CHANGE_ENTER = 0.18f;
    static final float APPEARANCE_DEPTH_CHANGE_WITH_SHIFT_ENTER = 0.10f;
    static final float APPEARANCE_DISTRIBUTION_SHIFT_ENTER = 0.06f;

    static final float GEOMETRY_CHANGE_EXIT = 0.08f;
    static final float NOVEL_GEOMETRY_CHANGE_MINIMUM = 0.30f;
    static final float NOVEL_GEOMETRY_CHANGE_DELTA = 0.20f;
    static final float NOVEL_GEOMETRY_CHANGE_RATIO = 2.0f;
    static final float GEOMETRY_BASELINE_ALPHA = 0.125f;
    static final int CUT_SETTLE_VALID_DEPTH_UPDATES = 8;

    // One per-slot mailbox word carries the mutually exclusive color classification and
    // event-scoped structureless-history transitions without a new buffer, binding, dispatch, or
    // readback. A manual CPU request remains appearance authority and is never converted into the
    // automatic exposure-like veto.
    static final int SCENE_EVIDENCE_APPEARANCE = 1 << 0;
    static final int SCENE_EVIDENCE_EXPOSURE_LIKE = 1 << 1;
    static final int SCENE_EVIDENCE_PERSISTENT_LOW_START = 1 << 2;
    static final int SCENE_EVIDENCE_SUPPORTED_RETURN = 1 << 3;
    private static final int SCENE_EVIDENCE_CLASSIFICATION_MASK =
            SCENE_EVIDENCE_APPEARANCE | SCENE_EVIDENCE_EXPOSURE_LIKE;
    private static final int SCENE_EVIDENCE_EVENT_MASK =
            SCENE_EVIDENCE_PERSISTENT_LOW_START | SCENE_EVIDENCE_SUPPORTED_RETURN;

    static final int LOW_STRUCTURE_SCENE_INACTIVE = 0;
    static final int LOW_STRUCTURE_SCENE_ACTIVE = 1;

    static final int CUT_STATE_SETTLED = 1 << 0;
    static final int CUT_STATE_GEOMETRY_ARMED = 1 << 1;
    static final int CUT_STATE_APPEARANCE_ARMED = 1 << 2;
    static final int CUT_STATE_GEOMETRY_ONE_LOW = 1 << 3;
    static final int CUT_STATE_APPEARANCE_ONE_QUIET = 1 << 4;
    static final int CUT_STATE_GEOMETRY_LATCHED = 1 << 5;
    static final int CUT_STATE_APPEARANCE_LATCHED = 1 << 6;

    static final int CUT_STATE_STARTUP = 0;
    static final int CUT_STATE_READY = CUT_STATE_SETTLED
            | CUT_STATE_GEOMETRY_ARMED | CUT_STATE_APPEARANCE_ARMED;
    static final int CUT_STATE_LATCHED = CUT_STATE_SETTLED
            | CUT_STATE_GEOMETRY_LATCHED | CUT_STATE_APPEARANCE_LATCHED;

    private ClientSbsShotCutPolicy() {
    }

    /**
     * Selects the one typed color classification consumed by a valid depth update.
     *
     * <p>Any nonzero word from the exact current color frame supersedes the classification carried
     * from an earlier all-invalid inference, including an event-only word whose classification is
     * deliberately empty. Event bits are accumulated because a persistent-low start and its
     * supported return can both occur before depth becomes valid again. Appearance wins malformed
     * dual-classification input, matching the explicit/manual cut's authority.</p>
     */
    static int selectSceneEvidence(int currentEvidence, int pendingEvidence) {
        int current = normalizeSceneEvidence(currentEvidence);
        int pending = normalizeSceneEvidence(pendingEvidence);
        int currentClassification = current & SCENE_EVIDENCE_CLASSIFICATION_MASK;
        int pendingClassification = pending & SCENE_EVIDENCE_CLASSIFICATION_MASK;
        int classification = current != 0
                ? currentClassification : pendingClassification;
        return classification | ((current | pending) & SCENE_EVIDENCE_EVENT_MASK);
    }

    private static int normalizeSceneEvidence(int evidence) {
        int events = evidence & SCENE_EVIDENCE_EVENT_MASK;
        if ((evidence & SCENE_EVIDENCE_APPEARANCE) != 0) {
            return events | SCENE_EVIDENCE_APPEARANCE;
        }
        if ((evidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0) {
            return events | SCENE_EVIDENCE_EXPOSURE_LIKE;
        }
        return events;
    }

    static int encodePendingSceneEvidence(int evidence) {
        int normalized = normalizeSceneEvidence(evidence);
        return normalized == 0 ? 0 : -normalized;
    }

    static int decodePendingSceneEvidence(int encodedEvidence) {
        return encodedEvidence < 0 ? normalizeSceneEvidence(-encodedEvidence) : 0;
    }

    static boolean isAppearanceEvidence(int evidence) {
        return (evidence & SCENE_EVIDENCE_APPEARANCE) != 0;
    }

    static boolean isExposureLikeEvidence(int evidence) {
        return !isAppearanceEvidence(evidence)
                && (evidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0;
    }

    static boolean isPersistentLowStart(int evidence) {
        return (evidence & SCENE_EVIDENCE_PERSISTENT_LOW_START) != 0;
    }

    static boolean isSupportedReturn(int evidence) {
        return (evidence & SCENE_EVIDENCE_SUPPORTED_RETURN) != 0;
    }

    static boolean shouldHoldDepthHistory(int evidence) {
        return isExposureLikeEvidence(evidence);
    }

    static boolean standaloneGeometryCut(float changeFraction, float distributionShift) {
        return changeFraction >= STANDALONE_DEPTH_CHANGE_ENTER
                || (changeFraction >= STANDALONE_DEPTH_CHANGE_WITH_SHIFT_ENTER
                && distributionShift >= STANDALONE_DISTRIBUTION_SHIFT_ENTER);
    }

    static boolean colorGeometryCorroborated(float changeFraction, float distributionShift) {
        return changeFraction >= APPEARANCE_DEPTH_CHANGE_ENTER
                || (changeFraction >= APPEARANCE_DEPTH_CHANGE_WITH_SHIFT_ENTER
                && distributionShift >= APPEARANCE_DISTRIBUTION_SHIFT_ENTER);
    }

    static boolean novelGeometryCut(float changeFraction, float geometryBaseline) {
        return changeFraction >= NOVEL_GEOMETRY_CHANGE_MINIMUM
                && (changeFraction >= geometryBaseline + NOVEL_GEOMETRY_CHANGE_DELTA
                || changeFraction >= geometryBaseline * NOVEL_GEOMETRY_CHANGE_RATIO);
    }

    static boolean acceptsStandaloneGeometryShotCut(boolean initialized, int cutState,
                                                    boolean exposureLikeTransition,
                                                    float changeFraction,
                                                    float distributionShift) {
        return initialized && !exposureLikeTransition && isGeometryArmed(cutState)
                && standaloneGeometryCut(changeFraction, distributionShift);
    }

    static boolean acceptsExternalShotCut(boolean initialized, int cutState,
                                          boolean externalEvidence,
                                          float changeFraction,
                                          float distributionShift) {
        return initialized && isAppearanceArmed(cutState) && externalEvidence
                && colorGeometryCorroborated(changeFraction, distributionShift);
    }

    static boolean acceptsLatchedGeometryShotCut(boolean initialized, int cutState,
                                                 boolean baselineInitialized,
                                                 int validDepthUpdateAge,
                                                 boolean exposureLikeTransition,
                                                 float changeFraction,
                                                 float geometryBaseline) {
        return initialized && baselineInitialized && !exposureLikeTransition
                && isGeometryLatched(cutState)
                && validDepthUpdateAge >= CUT_SETTLE_VALID_DEPTH_UPDATES
                && novelGeometryCut(changeFraction, geometryBaseline);
    }

    static boolean acceptsLowStructureReturnShotCut(boolean initialized,
                                                    int lowStructureSceneMarker,
                                                    boolean persistentLowStart,
                                                    boolean supportedReturn,
                                                    float changeFraction,
                                                    float distributionShift) {
        boolean lowStructureScene = lowStructureSceneMarker
                == LOW_STRUCTURE_SCENE_ACTIVE || persistentLowStart;
        return initialized && lowStructureScene && supportedReturn
                && standaloneGeometryCut(changeFraction, distributionShift);
    }

    static boolean acceptsShotCut(boolean initialized, int cutState,
                                  boolean externalEvidence, boolean exposureLikeTransition,
                                  float changeFraction,
                                  float distributionShift, boolean baselineInitialized,
                                  float geometryBaseline, int validDepthUpdateAge) {
        return acceptsShotCut(initialized, cutState, externalEvidence, exposureLikeTransition,
                changeFraction, distributionShift, baselineInitialized, geometryBaseline,
                validDepthUpdateAge, LOW_STRUCTURE_SCENE_INACTIVE, false, false);
    }

    static boolean acceptsShotCut(boolean initialized, int cutState,
                                  boolean externalEvidence, boolean exposureLikeTransition,
                                  float changeFraction,
                                  float distributionShift, boolean baselineInitialized,
                                  float geometryBaseline, int validDepthUpdateAge,
                                  int lowStructureSceneMarker, boolean persistentLowStart,
                                  boolean supportedReturn) {
        return acceptsStandaloneGeometryShotCut(
                initialized, cutState, exposureLikeTransition,
                changeFraction, distributionShift)
                || acceptsExternalShotCut(
                initialized, cutState, externalEvidence, changeFraction, distributionShift)
                || acceptsLatchedGeometryShotCut(
                initialized, cutState, baselineInitialized, validDepthUpdateAge,
                exposureLikeTransition, changeFraction, geometryBaseline)
                || acceptsLowStructureReturnShotCut(
                initialized, lowStructureSceneMarker, persistentLowStart, supportedReturn,
                changeFraction, distributionShift);
    }

    static int nextLowStructureSceneMarker(int lowStructureSceneMarker,
                                           boolean persistentLowStart,
                                           boolean supportedReturn) {
        if (supportedReturn) {
            return LOW_STRUCTURE_SCENE_INACTIVE;
        }
        if (persistentLowStart) {
            return LOW_STRUCTURE_SCENE_ACTIVE;
        }
        return lowStructureSceneMarker == LOW_STRUCTURE_SCENE_ACTIVE
                ? LOW_STRUCTURE_SCENE_ACTIVE : LOW_STRUCTURE_SCENE_INACTIVE;
    }

    /**
     * Advances the cut detector's age for one valid accepted depth result.
     *
     * <p>This counter is deliberately independent of elapsed time and
     * {@link ClientSbsTemporalTuning#referenceFrameAdvance(long, float)}. The first initialized
     * result and every accepted cut start at age zero; each later valid result advances exactly
     * once. Invalid results never call this transition.</p>
     */
    static int nextValidDepthUpdateAge(int validDepthUpdateAge, boolean initialized,
                                       boolean acceptedHardCut) {
        if (!initialized || acceptedHardCut) {
            return 0;
        }
        return validDepthUpdateAge >= 65535
                ? 65535 : Math.max(validDepthUpdateAge, 0) + 1;
    }

    static boolean isSettled(int cutState) {
        return has(cutState, CUT_STATE_SETTLED);
    }

    static boolean isGeometryArmed(int cutState) {
        return isSettled(cutState) && has(cutState, CUT_STATE_GEOMETRY_ARMED);
    }

    static boolean isAppearanceArmed(int cutState) {
        return isSettled(cutState) && has(cutState, CUT_STATE_APPEARANCE_ARMED);
    }

    static boolean isGeometryLatched(int cutState) {
        return isSettled(cutState) && has(cutState, CUT_STATE_GEOMETRY_LATCHED);
    }

    static boolean isAppearanceLatched(int cutState) {
        return isSettled(cutState) && has(cutState, CUT_STATE_APPEARANCE_LATCHED);
    }

    /**
     * Shader-equivalent accepted-result transition. Geometry and qualified appearance rearm
     * independently: two low-depth updates rearm geometry even while appearance persists, while
     * two appearance-quiet updates rearm appearance even if depth remains elevated. An accepted
     * cut always latches both sources, preventing repeated pulses from persistent evidence.
     */
    static int nextCutState(int cutState, boolean initialized, boolean acceptedHardCut,
                            boolean externalEvidence, float changeFraction,
                            int validDepthUpdateAge) {
        int next = cutState;
        if (next == CUT_STATE_STARTUP && initialized
                && validDepthUpdateAge >= CUT_SETTLE_VALID_DEPTH_UPDATES) {
            next = CUT_STATE_READY;
        }
        if (acceptedHardCut) {
            return CUT_STATE_LATCHED;
        }
        if (!isSettled(next)) {
            return next;
        }

        if (isGeometryLatched(next)) {
            if (changeFraction < GEOMETRY_CHANGE_EXIT) {
                if (has(next, CUT_STATE_GEOMETRY_ONE_LOW)) {
                    next &= ~(CUT_STATE_GEOMETRY_LATCHED | CUT_STATE_GEOMETRY_ONE_LOW);
                    next |= CUT_STATE_GEOMETRY_ARMED;
                } else {
                    next |= CUT_STATE_GEOMETRY_ONE_LOW;
                }
            } else {
                next &= ~CUT_STATE_GEOMETRY_ONE_LOW;
            }
        }

        if (isAppearanceLatched(next)) {
            if (!externalEvidence) {
                if (has(next, CUT_STATE_APPEARANCE_ONE_QUIET)) {
                    next &= ~(CUT_STATE_APPEARANCE_LATCHED
                            | CUT_STATE_APPEARANCE_ONE_QUIET);
                    next |= CUT_STATE_APPEARANCE_ARMED;
                } else {
                    next |= CUT_STATE_APPEARANCE_ONE_QUIET;
                }
            } else {
                next &= ~CUT_STATE_APPEARANCE_ONE_QUIET;
            }
        }
        return next;
    }

    static float nextGeometryBaseline(float geometryBaseline, boolean baselineInitialized,
                                      boolean acceptedHardCut, float changeFraction) {
        return nextGeometryBaseline(geometryBaseline, baselineInitialized, acceptedHardCut,
                changeFraction, false);
    }

    static float nextGeometryBaseline(float geometryBaseline, boolean baselineInitialized,
                                      boolean acceptedHardCut, float changeFraction,
                                      boolean holdReliableHistory) {
        if (holdReliableHistory) {
            return geometryBaseline;
        }
        if (!baselineInitialized || acceptedHardCut) {
            return changeFraction;
        }
        return geometryBaseline
                + GEOMETRY_BASELINE_ALPHA * (changeFraction - geometryBaseline);
    }

    private static boolean has(int cutState, int flag) {
        return (cutState & flag) != 0;
    }

    static String glsl(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("GLSL literal must be finite");
        }
        return Float.toString(value);
    }
}
