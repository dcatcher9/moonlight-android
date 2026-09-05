package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsShotCutPolicyTest {
    @Test
    public void modelGridGeometryThresholdsHaveExactInclusiveBoundaries() {
        assertFalse(ClientSbsShotCutPolicy.standaloneGeometryCut(0.579f, 0.099f));
        assertTrue(ClientSbsShotCutPolicy.standaloneGeometryCut(0.580f, 0.0f));
        assertFalse(ClientSbsShotCutPolicy.standaloneGeometryCut(0.419f, 1.0f));
        assertFalse(ClientSbsShotCutPolicy.standaloneGeometryCut(0.420f, 0.099f));
        assertTrue(ClientSbsShotCutPolicy.standaloneGeometryCut(0.420f, 0.100f));

        assertFalse(ClientSbsShotCutPolicy.colorGeometryCorroborated(0.179f, 0.059f));
        assertTrue(ClientSbsShotCutPolicy.colorGeometryCorroborated(0.180f, 0.0f));
        assertFalse(ClientSbsShotCutPolicy.colorGeometryCorroborated(0.099f, 1.0f));
        assertFalse(ClientSbsShotCutPolicy.colorGeometryCorroborated(0.100f, 0.059f));
        assertTrue(ClientSbsShotCutPolicy.colorGeometryCorroborated(0.100f, 0.060f));
    }

    @Test
    public void novelGeometryNeedsMinimumAndDeltaOrRatioBoundary() {
        assertFalse(ClientSbsShotCutPolicy.novelGeometryCut(0.299f, 0.05f));

        float deltaBaseline = 0.31f;
        float deltaBoundary = deltaBaseline
                + ClientSbsShotCutPolicy.NOVEL_GEOMETRY_CHANGE_DELTA;
        assertFalse(ClientSbsShotCutPolicy.novelGeometryCut(
                deltaBoundary - 0.001f, deltaBaseline));
        assertTrue(ClientSbsShotCutPolicy.novelGeometryCut(
                deltaBoundary, deltaBaseline));

        float ratioBaseline = 0.16f;
        float ratioBoundary = ratioBaseline
                * ClientSbsShotCutPolicy.NOVEL_GEOMETRY_CHANGE_RATIO;
        assertFalse(ClientSbsShotCutPolicy.novelGeometryCut(
                ratioBoundary - 0.001f, ratioBaseline));
        assertTrue(ClientSbsShotCutPolicy.novelGeometryCut(
                ratioBoundary, ratioBaseline));
    }

    @Test
    public void startupBlocksBothBranchesAndSettleUpdateCannotConsumeEvidence() {
        assertFalse(ClientSbsShotCutPolicy.acceptsStandaloneGeometryShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_STARTUP,
                false, 1.0f, 1.0f));
        assertFalse(ClientSbsShotCutPolicy.acceptsExternalShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_STARTUP,
                true, 1.0f, 1.0f));

        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_STARTUP);
        for (int update = 0;
             update < ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES;
             update++) {
            sequence.update(true, 0.24f, 0.08f);
            assertEquals(0, sequence.relatchedShots);
        }

        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_READY, sequence.cutState);
        sequence.update(true, 0.24f, 0.08f);
        assertEquals(1, sequence.relatchedShots);
        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_LATCHED, sequence.cutState);
    }

    @Test
    public void sustainedCombinedEvidenceEmitsOneShotRelatchPulse() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);

        sequence.update(true, 0.24f, 0.08f);
        assertEquals(1, sequence.relatchedShots);
        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_LATCHED, sequence.cutState);
        assertEquals(0, sequence.validDepthUpdateAge);

        for (int update = 0; update < 20; update++) {
            sequence.update(true, 0.30f, 0.10f);
        }

        assertEquals(1, sequence.relatchedShots);
        assertTrue(ClientSbsShotCutPolicy.isGeometryLatched(sequence.cutState));
        assertTrue(ClientSbsShotCutPolicy.isAppearanceLatched(sequence.cutState));
        assertEquals(20, sequence.validDepthUpdateAge);
    }

    @Test
    public void geometryRearmsThroughPersistentAppearanceEvidence() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);
        sequence.update(true, 0.24f, 0.08f);
        assertEquals(1, sequence.relatchedShots);

        sequence.update(true, 0.079f, 0.0f);
        assertTrue(ClientSbsShotCutPolicy.isGeometryLatched(sequence.cutState));
        assertTrue(ClientSbsShotCutPolicy.isAppearanceLatched(sequence.cutState));

        sequence.update(true, 0.079f, 0.0f);
        assertTrue(ClientSbsShotCutPolicy.isGeometryArmed(sequence.cutState));
        assertTrue(ClientSbsShotCutPolicy.isAppearanceLatched(sequence.cutState));

        sequence.update(true, 0.58f, 0.0f);
        assertEquals(1, sequence.relatchedShots);
        assertTrue(ClientSbsShotCutPolicy.isGeometryConfirmationPending(sequence.cutState));
        sequence.update(true, 0.58f, 0.0f);
        assertEquals(2, sequence.relatchedShots);
        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_LATCHED, sequence.cutState);
    }

    @Test
    public void appearanceRearmsWhileDepthEvidenceRemainsElevated() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);
        sequence.update(false, 0.70f, 0.0f);
        assertEquals(0, sequence.relatchedShots);
        assertTrue(ClientSbsShotCutPolicy.isGeometryConfirmationPending(sequence.cutState));
        sequence.update(false, 0.70f, 0.0f);
        assertEquals(1, sequence.relatchedShots);

        sequence.update(false, 0.20f, 0.0f);
        assertTrue(ClientSbsShotCutPolicy.isAppearanceLatched(sequence.cutState));
        sequence.update(false, 0.20f, 0.0f);
        assertTrue(ClientSbsShotCutPolicy.isAppearanceArmed(sequence.cutState));
        assertTrue(ClientSbsShotCutPolicy.isGeometryLatched(sequence.cutState));

        sequence.update(true, 0.18f, 0.0f);
        assertEquals(2, sequence.relatchedShots);
        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_LATCHED, sequence.cutState);
    }

    @Test
    public void latchedGeometryEscapeHasRefractoryAndDoesNotRepeatOnPersistentSpike() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);
        sequence.update(true, 0.18f, 0.0f);
        assertEquals(1, sequence.relatchedShots);
        assertEquals(0.18f, sequence.geometryBaseline, 0.0f);

        // A weak appearance cut can be followed by a large normalization-settling jump. The
        // post-cut refractory prevents that from becoming a second pulse.
        sequence.update(false, 0.45f, 0.0f);
        assertEquals(1, sequence.relatchedShots);

        for (int update = 1;
             update < ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES;
             update++) {
            sequence.update(false, 0.20f, 0.0f);
        }
        assertEquals(ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                sequence.validDepthUpdateAge);
        assertTrue(ClientSbsShotCutPolicy.isGeometryLatched(sequence.cutState));

        sequence.update(false, 0.50f, 0.0f);
        assertEquals(1, sequence.relatchedShots);
        assertTrue(ClientSbsShotCutPolicy.isGeometryConfirmationPending(sequence.cutState));
        sequence.update(false, 0.50f, 0.0f);
        assertEquals(2, sequence.relatchedShots);
        assertEquals(0.50f, sequence.geometryBaseline, 0.0f);

        for (int update = 0; update < 20; update++) {
            sequence.update(false, 0.50f, 0.0f);
        }
        assertEquals(2, sequence.relatchedShots);
    }

    @Test
    public void elapsedTimeAloneNeverRearmsPersistentEvidence() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);
        sequence.update(false, 0.70f, 0.0f);

        for (int update = 0; update < 100; update++) {
            sequence.update(true, 0.30f, 0.0f);
        }

        assertEquals(1, sequence.relatchedShots);
        assertTrue(ClientSbsShotCutPolicy.isGeometryLatched(sequence.cutState));
        assertTrue(ClientSbsShotCutPolicy.isAppearanceLatched(sequence.cutState));
    }

    @Test
    public void brightnessOnlyExternalEvidenceNeverMovesShotLatches() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);
        int anchorGeneration = sequence.anchorGeneration;
        int popGeneration = sequence.popGeneration;

        for (int update = 0; update < 20; update++) {
            sequence.update(true, 0.02f, 0.01f);
        }

        assertEquals(0, sequence.relatchedShots);
        assertEquals(anchorGeneration, sequence.anchorGeneration);
        assertEquals(popGeneration, sequence.popGeneration);
        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_READY, sequence.cutState);
    }

    @Test
    public void exposureLikeTransitionVetoesAbsoluteAndRelativeGeometryAuthority() {
        assertFalse(ClientSbsShotCutPolicy.acceptsStandaloneGeometryShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                true, 1.0f, 1.0f));
        assertTrue(ClientSbsShotCutPolicy.acceptsStandaloneGeometryShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, 0.58f, 0.0f));

        assertFalse(ClientSbsShotCutPolicy.acceptsLatchedGeometryShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                true, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                true, 0.95f, 0.20f));
        assertTrue(ClientSbsShotCutPolicy.acceptsLatchedGeometryShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                true, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                false, 0.95f, 0.20f));

        // The qualified appearance arm remains authoritative. An explicit/manual appearance
        // request is not weakened merely because the automatic classifier also saw brightness.
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                true, true, 0.18f, 0.0f,
                true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));
    }

    @Test
    public void geometryUsesApolloStructuralCorroborationFloorAndStructurelessWaiver() {
        float floor = ClientSbsShotCutPolicy.STRUCTURAL_GEOMETRY_CUT_FLOOR;
        assertEquals(0.005f, floor, 0.0f);
        assertFalse(ClientSbsShotCutPolicy.geometryStructureCorroborated(
                Math.nextDown(floor), false, false));
        assertTrue(ClientSbsShotCutPolicy.geometryStructureCorroborated(
                floor, false, false));
        assertTrue(ClientSbsShotCutPolicy.geometryStructureCorroborated(
                0.0f, true, false));
        assertTrue(ClientSbsShotCutPolicy.geometryStructureCorroborated(
                0.0f, false, true));
    }

    @Test
    public void geometryConfirmationRequiresIndependentStructureOnBothObservations() {
        int ready = ClientSbsShotCutPolicy.CUT_STATE_READY;
        int pending = ClientSbsShotCutPolicy.CUT_STATE_LATCHED
                | ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_CONFIRMATION_PENDING;
        float depth = ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER;

        assertFalse(ClientSbsShotCutPolicy.geometryConfirmationCandidate(
                true, ready, false, depth, 0.0f, true, 0.05f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE,
                false, false, 0.0f, true));
        assertTrue(ClientSbsShotCutPolicy.geometryConfirmationCandidate(
                true, ready, false, depth, 0.0f, true, 0.05f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE,
                false, false,
                ClientSbsShotCutPolicy.STRUCTURAL_GEOMETRY_CUT_FLOOR, true));

        // The pending follow-up also needs reliable current structure, matching Apollo. A weak
        // structural count cannot confirm merely because the depth spike persisted.
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, pending, false, false, depth, 0.0f, true, 0.50f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE,
                false, false, 0.25f, false));
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, pending, false, false, depth, 0.0f, true, 0.50f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE,
                false, false, 0.25f, true));
    }

    @Test
    public void structurelessReferenceWaivesOnlyUnavailableOrdinalCorroboration() {
        int marker = ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_ACTIVE;
        int pending = ClientSbsShotCutPolicy.CUT_STATE_LATCHED
                | ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_CONFIRMATION_PENDING;
        float depth = ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER;

        // A naturally structureless retained reference is distinct from the persistent-low bridge
        // marker. This is Apollo's explicit reference_structureless waiver.
        assertFalse(ClientSbsShotCutPolicy.startsGeometryConfirmation(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, false, depth, 0.0f, true, 0.05f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE,
                false, false, false, 0.0f, true));
        assertTrue(ClientSbsShotCutPolicy.startsGeometryConfirmation(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, false, depth, 0.0f, true, 0.05f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE,
                false, false, true, 0.0f, true));

        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, pending, false, false, depth, 0.0f, true, 0.05f, 0,
                marker, false, true, 0.0f, false));
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, pending, false, false, depth, 0.0f, true, 0.05f, 0,
                marker, false, true, 0.0f, true));

        // The second low-support observation is already the corroborating structureless bridge
        // event, so it retains Apollo's immediate exception even though no ordinal pair exists.
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, false, depth, 0.0f, true, 0.05f, 0,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE,
                true, false, 0.0f, false));
    }

    @Test
    public void supportedTransitionWithoutCommonSupportLeavesStrongGeometryAuthority() {
        // After a bridged gap, the color classifier emits neither typed bit for a supported broad
        // transition without common ordinal support. That ambiguity must not act like the veto.
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, false,
                ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY
                        | ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_CONFIRMATION_PENDING,
                false, false,
                ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                false, false,
                0.50f, 0.0f, true, 0.20f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED
                        | ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_CONFIRMATION_PENDING,
                false, false,
                0.50f, 0.0f, true, 0.20f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));
    }

    @Test
    public void firstStructurelessHoldUsesSupportMetadataNotExposureClassAlone() {
        int exposure = ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE;

        assertFalse(ClientSbsShotCutPolicy.isFirstStructurelessHold(
                exposure, true, true, true));
        assertTrue(ClientSbsShotCutPolicy.isFirstStructurelessHold(
                exposure, true, true, false));
        assertFalse(ClientSbsShotCutPolicy.isFirstStructurelessHold(
                exposure, false, true, false));
        assertFalse(ClientSbsShotCutPolicy.isFirstStructurelessHold(
                ClientSbsShotCutPolicy.SCENE_EVIDENCE_PERSISTENT_LOW_START,
                true, true, false));

        assertTrue(ClientSbsShotCutPolicy.historyAdvances(true, false, false));
        assertFalse(ClientSbsShotCutPolicy.historyAdvances(true, true, false));
        assertFalse(ClientSbsShotCutPolicy.historyAdvances(true, false, true));
        assertFalse(ClientSbsShotCutPolicy.historyAdvances(false, false, false));
    }

    @Test
    public void invalidDepthDoesNotCreatePendingSceneEvidenceAuthority() {
        String range = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String cut = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        assertTrue(cut.contains("uint selectedSceneEvidence = currentSceneEvidence"));
        assertTrue(range.contains("stateCounters.z = 0"));
        assertFalse(cut.contains("pendingSceneEvidence"));
        assertFalse(cut.contains("-int(selectedSceneEvidence)"));
    }

    @Test
    public void persistentLowMarkerGrantsOneReturnDecisionWithoutTimerOrRetrigger() {
        int marker = ClientSbsShotCutPolicy.nextLowStructureSceneMarker(
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE,
                true, false);
        assertEquals(ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_ACTIVE, marker);

        // Later low-support updates carry no return event. Neither age nor repeated geometry can
        // use the event-scoped bypass.
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                false, false, 1.0f, 1.0f, true, 1.0f, 65535,
                marker, false, false));
        assertEquals(marker, ClientSbsShotCutPolicy.nextLowStructureSceneMarker(
                marker, false, false));

        // The first supported return gets the absolute threshold even while latched, but remains
        // an ordinary geometry-only candidate and therefore starts confirmation.
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 1.0f, 0, marker, false, true));
        assertTrue(ClientSbsShotCutPolicy.startsGeometryConfirmation(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 1.0f, 0, marker, false, true));
        marker = ClientSbsShotCutPolicy.nextLowStructureSceneMarker(marker, false, true);
        assertEquals(ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE, marker);

        // The event is consumed, but the pending bit authenticates one compatible next update.
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED
                        | ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_CONFIRMATION_PENDING,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 1.0f, 0, marker, false, false));
    }

    @Test
    public void invalidDepthDropsProposalInsteadOfApplyingItToTheNextValidUpdate() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);

        sequence.invalidDepth(true);
        assertEquals(0, sequence.relatchedShots);

        sequence.validDepth(false, 0.24f, 0.08f);
        assertEquals(0, sequence.relatchedShots);
    }

    @Test
    public void delayedResultCannotSkipStartupValidDepthGuard() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_STARTUP);

        sequence.update(true, 0.24f, 0.08f, 12);
        assertEquals(12, sequence.profileSceneAge);
        assertEquals(1, sequence.validDepthUpdateAge);
        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_STARTUP, sequence.cutState);
        assertEquals(0, sequence.relatchedShots);

        for (int update = 1;
             update < ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES;
             update++) {
            sequence.update(true, 0.24f, 0.08f, 12);
        }
        assertEquals(ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                sequence.validDepthUpdateAge);
        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_READY, sequence.cutState);
        assertEquals(0, sequence.relatchedShots);

        sequence.update(true, 0.24f, 0.08f, 12);
        assertEquals(1, sequence.relatchedShots);
    }

    @Test
    public void delayedResultCannotSkipPostCutValidDepthRefractory() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);
        sequence.update(true, 0.18f, 0.0f);
        assertEquals(1, sequence.relatchedShots);

        // Eight reference frames of wall time may settle pop/anchor classification, but this is
        // still only the first valid depth update after the cut.
        sequence.update(false, 0.50f, 0.0f, 8);
        assertEquals(8, sequence.profileSceneAge);
        assertEquals(1, sequence.validDepthUpdateAge);
        assertEquals(1, sequence.relatchedShots);
        assertTrue(ClientSbsShotCutPolicy.isGeometryLatched(sequence.cutState));
    }

    @Test
    public void geometryBaselineUsesExactAlphaAndResetsOnCut() {
        assertEquals(0.40f, ClientSbsShotCutPolicy.nextGeometryBaseline(
                0.0f, false, false, 0.40f), 0.0f);
        assertEquals(0.45f, ClientSbsShotCutPolicy.nextGeometryBaseline(
                0.40f, true, false, 0.80f), 0.000001f);
        assertEquals(0.80f, ClientSbsShotCutPolicy.nextGeometryBaseline(
                0.40f, true, true, 0.80f), 0.0f);
        assertEquals(0.40f, ClientSbsShotCutPolicy.nextGeometryBaseline(
                0.40f, true, false, 0.95f, true), 0.0f);
        assertEquals("0.125", ClientSbsShotCutPolicy.glsl(
                ClientSbsShotCutPolicy.GEOMETRY_BASELINE_ALPHA));
    }

    @Test
    public void photometricRecoveryVetoesExactlyOneQuietUpdate() {
        assertTrue(ClientSbsShotCutPolicy.photometricRecoveryVeto(true, false));
        assertFalse(ClientSbsShotCutPolicy.photometricRecoveryVeto(true, true));

        int recovered = ClientSbsShotCutPolicy.nextCutState(
                ClientSbsShotCutPolicy.CUT_STATE_READY, true, false,
                false, 0.20f, 9, false, true);
        assertTrue(ClientSbsShotCutPolicy.isAppearanceRecoveryTail(recovered, false));
        assertTrue(ClientSbsShotCutPolicy.appearanceVeto(recovered, false, false));
        assertFalse("a real appearance proposal bypasses the quiet recovery tail",
                ClientSbsShotCutPolicy.appearanceVeto(recovered, true, false));
        assertEquals("the recovery tail must not contaminate the geometry baseline", 0.05f,
                ClientSbsShotCutPolicy.nextGeometryBaseline(
                        0.05f, true, false, 0.80f,
                        false, true, false), 0.0f);

        int consumed = ClientSbsShotCutPolicy.nextCutState(
                recovered, true, false, false, 0.80f, 10, false, false);
        assertFalse(ClientSbsShotCutPolicy.isAppearanceRecoveryTail(consumed, false));
        assertFalse(ClientSbsShotCutPolicy.appearanceVeto(consumed, false, false));

        int startupExposure = ClientSbsShotCutPolicy.nextCutState(
                ClientSbsShotCutPolicy.CUT_STATE_STARTUP, true, false,
                false, 0.10f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                false, true);
        assertTrue(ClientSbsShotCutPolicy.isSettled(startupExposure));
        assertTrue(ClientSbsShotCutPolicy.isAppearanceRecoveryTail(startupExposure, false));
    }

    private static final class Sequence {
        int cutState;
        int validDepthUpdateAge;
        int profileSceneAge;
        int relatchedShots;
        int anchorGeneration = 7;
        int popGeneration = 11;
        boolean baselineInitialized = true;
        float geometryBaseline = 0.05f;

        Sequence(int cutState) {
            this.cutState = cutState;
            validDepthUpdateAge =
                    cutState == ClientSbsShotCutPolicy.CUT_STATE_STARTUP ? 0 : 20;
            profileSceneAge = validDepthUpdateAge;
        }

        void update(boolean externalEvidence, float changeFraction, float distributionShift) {
            update(externalEvidence, changeFraction, distributionShift, 1);
        }

        void update(boolean externalEvidence, float changeFraction, float distributionShift,
                    int referenceFrameAdvance) {
            validDepth(externalEvidence, changeFraction, distributionShift,
                    referenceFrameAdvance);
        }

        void invalidDepth(boolean externalEvidence) {
            // Apollo clears evidence for an invalid depth transaction.
        }

        void validDepth(boolean externalEvidence, float changeFraction,
                        float distributionShift) {
            validDepth(externalEvidence, changeFraction, distributionShift, 1);
        }

        void validDepth(boolean externalEvidence, float changeFraction,
                        float distributionShift, int referenceFrameAdvance) {
            int advancedValidDepthUpdateAge =
                    ClientSbsShotCutPolicy.nextValidDepthUpdateAge(
                            validDepthUpdateAge, true, false);
            boolean startsConfirmation = ClientSbsShotCutPolicy.startsGeometryConfirmation(
                    true, cutState, externalEvidence, false,
                    changeFraction, distributionShift,
                    baselineInitialized, geometryBaseline, advancedValidDepthUpdateAge,
                    ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE, false, false);
            boolean accepted = ClientSbsShotCutPolicy.acceptsShotCut(
                    true, cutState, externalEvidence, false,
                    changeFraction, distributionShift,
                    baselineInitialized, geometryBaseline, advancedValidDepthUpdateAge);
            if (ClientSbsShotCutPolicy.historyAdvances(true, false, startsConfirmation)) {
                geometryBaseline = ClientSbsShotCutPolicy.nextGeometryBaseline(
                        geometryBaseline, baselineInitialized, accepted, changeFraction);
            }
            baselineInitialized = true;
            advance(accepted, startsConfirmation, externalEvidence, changeFraction,
                    referenceFrameAdvance);
        }

        private void advance(boolean accepted, boolean startsConfirmation,
                             boolean externalEvidence, float changeFraction,
                             int referenceFrameAdvance) {
            validDepthUpdateAge = ClientSbsShotCutPolicy.nextValidDepthUpdateAge(
                    validDepthUpdateAge, true, accepted);
            cutState = ClientSbsShotCutPolicy.nextCutState(
                    cutState, true, accepted, externalEvidence, changeFraction,
                    validDepthUpdateAge, startsConfirmation);
            if (accepted) {
                profileSceneAge = 0;
            } else {
                int advance = Math.max(referenceFrameAdvance, 1);
                profileSceneAge = profileSceneAge >= 65535 - advance
                        ? 65535 : profileSceneAge + advance;
            }
            if (accepted) {
                relatchedShots++;
                anchorGeneration++;
                popGeneration++;
            }
        }
    }
}
