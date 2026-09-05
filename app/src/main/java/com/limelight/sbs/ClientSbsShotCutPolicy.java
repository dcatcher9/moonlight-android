package com.limelight.sbs;

/**
 * Single-owner constants and a CPU reference for the Client-SBS shot-relatch policy.
 *
 * <p>The production decision remains GPU-resident in {@link ClientSbsGpuDepthShaders}. Keeping
     * the thresholds and detector-present transition reference here lets numerical JVM tests
     * exercise the same hysteresis without adding a render-path readback or a second source of
     * numeric constants. Detector-loss fallback is covered by shader-contract and device tests.</p>
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

    // Keep this literal in lockstep with Apollo's STRUCTURAL_GEOMETRY_CUT_FLOOR. Geometry is
    // allowed to relatch a shot only when the independent ordinal-color detector corroborates
    // some structural replacement. A structureless reference waives unavailable ordinal evidence;
    // complete detector loss has a separate bounded two-observation fallback in the GPU resolver.
    static final float STRUCTURAL_GEOMETRY_CUT_FLOOR = 0.005f;

    // One per-slot mailbox word carries the mutually exclusive color classification and
    // event-scoped structureless-history transitions without a new buffer, binding, dispatch, or
    // readback. A manual CPU request remains appearance authority and is never converted into the
    // automatic exposure-like veto.
    static final int SCENE_EVIDENCE_APPEARANCE = 1 << 0;
    static final int SCENE_EVIDENCE_EXPOSURE_LIKE = 1 << 1;
    static final int SCENE_EVIDENCE_PERSISTENT_LOW_START = 1 << 2;
    static final int SCENE_EVIDENCE_SUPPORTED_RETURN = 1 << 3;
    static final int LOW_STRUCTURE_SCENE_INACTIVE = 0;
    static final int LOW_STRUCTURE_SCENE_ACTIVE = 1;

    static final int CUT_STATE_SETTLED = 1 << 0;
    static final int CUT_STATE_GEOMETRY_ARMED = 1 << 1;
    static final int CUT_STATE_APPEARANCE_ARMED = 1 << 2;
    static final int CUT_STATE_GEOMETRY_ONE_LOW = 1 << 3;
    static final int CUT_STATE_APPEARANCE_ONE_QUIET = 1 << 4;
    static final int CUT_STATE_GEOMETRY_LATCHED = 1 << 5;
    static final int CUT_STATE_APPEARANCE_LATCHED = 1 << 6;
    static final int CUT_STATE_GEOMETRY_CONFIRMATION_PENDING = 1 << 7;
    static final int CUT_STATE_APPEARANCE_RECOVERY = 1 << 8;

    // Per-result stateFlags.z bits shared by every GPU history consumer. A result may be a valid
    // flat/pending publication without being allowed to advance any reliable history.
    static final int FRAME_STATE_FIRST_DEPTH = 1 << 0;
    static final int FRAME_STATE_HOLD_RELIABLE_HISTORY = 1 << 1;
    static final int FRAME_STATE_HISTORY_ADVANCES = 1 << 2;
    static final int FRAME_STATE_CURRENT_DEPTH_VALID = 1 << 3;
    // Raw completeness and V2 renderer validity are deliberately separate. Apollo still lets a
    // finite collapsed field participate in private normalized cut history, but never lets it
    // acquire or publish the raw shot camera.
    static final int FRAME_STATE_CURRENT_V2_VALID = 1 << 4;
    /** Identifies the structureless-gap reason within the generic reliable-history hold. */
    static final int FRAME_STATE_STRUCTURELESS_GAP = 1 << 5;

    static final int CUT_STATE_STARTUP = 0;
    static final int CUT_STATE_READY = CUT_STATE_SETTLED
            | CUT_STATE_GEOMETRY_ARMED | CUT_STATE_APPEARANCE_ARMED;
    static final int CUT_STATE_LATCHED = CUT_STATE_SETTLED
            | CUT_STATE_GEOMETRY_LATCHED | CUT_STATE_APPEARANCE_LATCHED;

    private ClientSbsShotCutPolicy() {
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

    static boolean isFirstStructurelessHold(int evidence, boolean comparable,
                                            boolean previousStructureSupported,
                                            boolean currentStructureSupported) {
        return isExposureLikeEvidence(evidence) && comparable
                && previousStructureSupported && !currentStructureSupported;
    }

    static boolean isAppearanceRecoveryTail(int cutState, boolean appearanceProposal) {
        return has(cutState, CUT_STATE_APPEARANCE_RECOVERY) && !appearanceProposal;
    }

    static boolean appearanceVeto(int cutState, boolean appearanceProposal,
                                  boolean exposureLikeTransition) {
        return exposureLikeTransition || isAppearanceRecoveryTail(cutState, appearanceProposal);
    }

    static boolean photometricRecoveryVeto(boolean exposureLikeTransition,
                                           boolean firstStructurelessHold) {
        return exposureLikeTransition && !firstStructurelessHold;
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

    static boolean geometryStructureCorroborated(float structuralChangeFraction,
                                                  boolean persistentStructurelessTransition,
                                                  boolean referenceStructureless) {
        return persistentStructurelessTransition || referenceStructureless
                || structuralChangeFraction >= STRUCTURAL_GEOMETRY_CUT_FLOOR;
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

    static boolean isGeometryConfirmationPending(int cutState) {
        return has(cutState, CUT_STATE_GEOMETRY_CONFIRMATION_PENDING);
    }

    /** Geometry-only evidence is deliberately provisional until a compatible next update. */
    static boolean geometryConfirmationCandidate(boolean initialized, int cutState,
                                                  boolean exposureLikeTransition,
                                                 float changeFraction,
                                                 float distributionShift,
                                                 boolean baselineInitialized,
                                                 float geometryBaseline,
                                                 int sourceFrameAge,
                                                 int lowStructureSceneMarker,
                                                 boolean persistentLowStart,
                                                 boolean supportedReturn) {
        return geometryConfirmationCandidate(initialized, cutState, exposureLikeTransition,
                changeFraction, distributionShift, baselineInitialized, geometryBaseline,
                sourceFrameAge, lowStructureSceneMarker, persistentLowStart, supportedReturn,
                1.0f, true);
    }

    static boolean geometryConfirmationCandidate(boolean initialized, int cutState,
                                                 boolean exposureLikeTransition,
                                                 float changeFraction,
                                                 float distributionShift,
                                                 boolean baselineInitialized,
                                                 float geometryBaseline,
                                                 int sourceFrameAge,
                                                 int lowStructureSceneMarker,
                                                 boolean persistentLowStart,
                                                  boolean supportedReturn,
                                                  float structuralChangeFraction,
                                                  boolean currentStructureReliable) {
        boolean referenceStructureless = lowStructureSceneMarker == LOW_STRUCTURE_SCENE_ACTIVE
                || persistentLowStart;
        return geometryConfirmationCandidate(initialized, cutState, exposureLikeTransition,
                changeFraction, distributionShift, baselineInitialized, geometryBaseline,
                sourceFrameAge, lowStructureSceneMarker, persistentLowStart, supportedReturn,
                referenceStructureless, structuralChangeFraction, currentStructureReliable);
    }

    static boolean geometryConfirmationCandidate(boolean initialized, int cutState,
                                                  boolean exposureLikeTransition,
                                                  float changeFraction,
                                                  float distributionShift,
                                                  boolean baselineInitialized,
                                                  float geometryBaseline,
                                                  int sourceFrameAge,
                                                  int lowStructureSceneMarker,
                                                  boolean persistentLowStart,
                                                  boolean supportedReturn,
                                                  boolean referenceStructureless,
                                                  float structuralChangeFraction,
                                                  boolean currentStructureReliable) {
        boolean absoluteCandidate = standaloneGeometryCut(changeFraction, distributionShift);
        boolean lowStructureScene = lowStructureSceneMarker == LOW_STRUCTURE_SCENE_ACTIVE
                || persistentLowStart;
        boolean structureCorroborated = geometryStructureCorroborated(
                structuralChangeFraction, persistentLowStart, referenceStructureless);
        return initialized && !exposureLikeTransition && structureCorroborated
                && ((isGeometryArmed(cutState) && absoluteCandidate)
                || (lowStructureScene && supportedReturn && currentStructureReliable
                && absoluteCandidate)
                || (isGeometryConfirmationPending(cutState) && currentStructureReliable
                && absoluteCandidate)
                || acceptsLatchedGeometryShotCut(initialized, cutState, baselineInitialized,
                sourceFrameAge, false, changeFraction, geometryBaseline));
    }

    static boolean startsGeometryConfirmation(boolean initialized, int cutState,
                                              boolean externalEvidence,
                                              boolean exposureLikeTransition,
                                              float changeFraction,
                                              float distributionShift,
                                              boolean baselineInitialized,
                                              float geometryBaseline,
                                              int sourceFrameAge,
                                              int lowStructureSceneMarker,
                                              boolean persistentLowStart,
                                              boolean supportedReturn) {
        return startsGeometryConfirmation(initialized, cutState, externalEvidence,
                exposureLikeTransition, changeFraction, distributionShift,
                baselineInitialized, geometryBaseline, sourceFrameAge,
                lowStructureSceneMarker, persistentLowStart, supportedReturn, 1.0f, true);
    }

    static boolean startsGeometryConfirmation(boolean initialized, int cutState,
                                              boolean externalEvidence,
                                              boolean exposureLikeTransition,
                                              float changeFraction,
                                              float distributionShift,
                                              boolean baselineInitialized,
                                              float geometryBaseline,
                                              int sourceFrameAge,
                                              int lowStructureSceneMarker,
                                              boolean persistentLowStart,
                                              boolean supportedReturn,
                                              float structuralChangeFraction,
                                              boolean currentStructureReliable) {
        boolean referenceStructureless = lowStructureSceneMarker == LOW_STRUCTURE_SCENE_ACTIVE
                || persistentLowStart;
        return startsGeometryConfirmation(initialized, cutState, externalEvidence,
                exposureLikeTransition, changeFraction, distributionShift,
                baselineInitialized, geometryBaseline, sourceFrameAge,
                lowStructureSceneMarker, persistentLowStart, supportedReturn,
                referenceStructureless, structuralChangeFraction, currentStructureReliable);
    }

    static boolean startsGeometryConfirmation(boolean initialized, int cutState,
                                              boolean externalEvidence,
                                              boolean exposureLikeTransition,
                                              float changeFraction,
                                              float distributionShift,
                                              boolean baselineInitialized,
                                              float geometryBaseline,
                                              int sourceFrameAge,
                                              int lowStructureSceneMarker,
                                              boolean persistentLowStart,
                                              boolean supportedReturn,
                                              boolean referenceStructureless,
                                              float structuralChangeFraction,
                                              boolean currentStructureReliable) {
        boolean candidate = geometryConfirmationCandidate(initialized, cutState,
                exposureLikeTransition, changeFraction, distributionShift,
                baselineInitialized, geometryBaseline, sourceFrameAge,
                lowStructureSceneMarker, persistentLowStart, supportedReturn,
                referenceStructureless, structuralChangeFraction, currentStructureReliable);
        boolean immediateAppearance = acceptsExternalShotCut(initialized, cutState,
                externalEvidence, changeFraction, distributionShift);
        boolean alreadyConfirmed = persistentLowStart && candidate;
        return candidate && !immediateAppearance && !alreadyConfirmed
                && !isGeometryConfirmationPending(cutState);
    }

    static boolean historyAdvances(boolean currentDepthValid, boolean holdReliableHistory,
                                   boolean startGeometryConfirmation) {
        return currentDepthValid && !holdReliableHistory && !startGeometryConfirmation;
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
        return acceptsShotCut(initialized, cutState, externalEvidence, exposureLikeTransition,
                changeFraction, distributionShift, baselineInitialized, geometryBaseline,
                validDepthUpdateAge, lowStructureSceneMarker, persistentLowStart,
                supportedReturn, 1.0f, true);
    }

    static boolean acceptsShotCut(boolean initialized, int cutState,
                                  boolean externalEvidence, boolean exposureLikeTransition,
                                  float changeFraction,
                                  float distributionShift, boolean baselineInitialized,
                                  float geometryBaseline, int validDepthUpdateAge,
                                  int lowStructureSceneMarker, boolean persistentLowStart,
                                  boolean supportedReturn, float structuralChangeFraction,
                                  boolean currentStructureReliable) {
        boolean referenceStructureless = lowStructureSceneMarker == LOW_STRUCTURE_SCENE_ACTIVE
                || persistentLowStart;
        return acceptsShotCut(initialized, cutState, externalEvidence, exposureLikeTransition,
                changeFraction, distributionShift, baselineInitialized, geometryBaseline,
                validDepthUpdateAge, lowStructureSceneMarker, persistentLowStart,
                supportedReturn, referenceStructureless, structuralChangeFraction,
                currentStructureReliable);
    }

    static boolean acceptsShotCut(boolean initialized, int cutState,
                                  boolean externalEvidence, boolean exposureLikeTransition,
                                  float changeFraction,
                                  float distributionShift, boolean baselineInitialized,
                                  float geometryBaseline, int validDepthUpdateAge,
                                  int lowStructureSceneMarker, boolean persistentLowStart,
                                  boolean supportedReturn, boolean referenceStructureless,
                                  float structuralChangeFraction,
                                  boolean currentStructureReliable) {
        boolean geometryCandidate = geometryConfirmationCandidate(initialized, cutState,
                exposureLikeTransition, changeFraction, distributionShift,
                baselineInitialized, geometryBaseline, validDepthUpdateAge,
                lowStructureSceneMarker, persistentLowStart, supportedReturn,
                referenceStructureless, structuralChangeFraction, currentStructureReliable);
        return acceptsExternalShotCut(initialized, cutState, externalEvidence,
                changeFraction, distributionShift)
                || (persistentLowStart && geometryCandidate)
                || (isGeometryConfirmationPending(cutState) && geometryCandidate);
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
        return nextSourceFrameAge(validDepthUpdateAge, initialized, acceptedHardCut, 1);
    }

    static int nextSourceFrameAge(int sourceFrameAge, boolean initialized,
                                  boolean acceptedHardCut, int sourceFrameDelta) {
        if (!initialized || acceptedHardCut) {
            return 0;
        }
        int delta = Math.min(Math.max(sourceFrameDelta, 1), 65535);
        int age = Math.max(sourceFrameAge, 0);
        return age >= 65535 - delta ? 65535 : age + delta;
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
        return nextCutState(cutState, initialized, acceptedHardCut, externalEvidence,
                changeFraction, validDepthUpdateAge, false);
    }

    static int nextCutState(int cutState, boolean initialized, boolean acceptedHardCut,
                            boolean externalEvidence, float changeFraction,
                            int validDepthUpdateAge, boolean startGeometryConfirmation) {
        return nextCutState(cutState, initialized, acceptedHardCut, externalEvidence,
                changeFraction, validDepthUpdateAge, startGeometryConfirmation, false);
    }

    static int nextCutState(int cutState, boolean initialized, boolean acceptedHardCut,
                            boolean externalEvidence, float changeFraction,
                            int validDepthUpdateAge, boolean startGeometryConfirmation,
                            boolean photometricRecoveryVeto) {
        int next = cutState;
        if (!isSettled(next) && initialized
                && validDepthUpdateAge >= CUT_SETTLE_VALID_DEPTH_UPDATES) {
            next = CUT_STATE_READY | (next & CUT_STATE_APPEARANCE_RECOVERY);
        }
        if (acceptedHardCut) {
            return CUT_STATE_LATCHED;
        }
        next &= ~(CUT_STATE_GEOMETRY_CONFIRMATION_PENDING | CUT_STATE_APPEARANCE_RECOVERY);
        if (photometricRecoveryVeto) {
            next |= CUT_STATE_APPEARANCE_RECOVERY;
        }
        if (startGeometryConfirmation) {
            next |= CUT_STATE_GEOMETRY_CONFIRMATION_PENDING;
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
        return nextGeometryBaseline(geometryBaseline, baselineInitialized, acceptedHardCut,
                changeFraction, holdReliableHistory, false, false);
    }

    static float nextGeometryBaseline(float geometryBaseline, boolean baselineInitialized,
                                      boolean acceptedHardCut, float changeFraction,
                                      boolean firstStructurelessHold,
                                      boolean appearanceRecoveryTail,
                                      boolean startGeometryConfirmation) {
        if (firstStructurelessHold || appearanceRecoveryTail || startGeometryConfirmation) {
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
