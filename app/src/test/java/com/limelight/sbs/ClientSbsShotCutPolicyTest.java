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
        assertEquals(2, sequence.relatchedShots);
        assertEquals(ClientSbsShotCutPolicy.CUT_STATE_LATCHED, sequence.cutState);
    }

    @Test
    public void appearanceRearmsWhileDepthEvidenceRemainsElevated() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);
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
    public void currentTypedSceneEvidenceSupersedesPendingWithoutCrossTypeCollision() {
        int selected = ClientSbsShotCutPolicy.selectSceneEvidence(
                ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE);
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE, selected);
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                selected == ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE,
                selected == ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                1.0f, 1.0f, true, 0.05f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));

        selected = ClientSbsShotCutPolicy.selectSceneEvidence(
                ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE,
                ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE);
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE, selected);
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                true, false, 0.18f, 0.0f, true, 0.05f,
                ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));

        // No current classification preserves either pending type through repeated invalid
        // results; a new current classification replaces it on the next attempt.
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE,
                ClientSbsShotCutPolicy.selectSceneEvidence(
                        0, ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                ClientSbsShotCutPolicy.selectSceneEvidence(
                        0, ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE));

        // Manual appearance and a malformed dual-bit word keep appearance authority.
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE,
                ClientSbsShotCutPolicy.selectSceneEvidence(
                        ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE
                                | ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                        0));
    }

    @Test
    public void invalidDepthCarriesProposalToExactlyTheNextValidUpdate() {
        Sequence sequence = new Sequence(ClientSbsShotCutPolicy.CUT_STATE_READY);

        sequence.invalidDepth(true);
        assertEquals(0, sequence.relatchedShots);
        assertTrue(sequence.pendingExternalEvidence);

        sequence.validDepth(false, 0.24f, 0.08f);
        assertEquals(1, sequence.relatchedShots);
        assertFalse(sequence.pendingExternalEvidence);

        sequence.validDepth(false, 0.24f, 0.08f);
        assertEquals(1, sequence.relatchedShots);
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
        assertEquals("0.125", ClientSbsShotCutPolicy.glsl(
                ClientSbsShotCutPolicy.GEOMETRY_BASELINE_ALPHA));
    }

    private static final class Sequence {
        int cutState;
        int validDepthUpdateAge;
        int profileSceneAge;
        int relatchedShots;
        int anchorGeneration = 7;
        int popGeneration = 11;
        boolean pendingExternalEvidence;
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
            pendingExternalEvidence |= externalEvidence;
        }

        void validDepth(boolean externalEvidence, float changeFraction,
                        float distributionShift) {
            validDepth(externalEvidence, changeFraction, distributionShift, 1);
        }

        void validDepth(boolean externalEvidence, float changeFraction,
                        float distributionShift, int referenceFrameAdvance) {
            externalEvidence |= pendingExternalEvidence;
            pendingExternalEvidence = false;
            int advancedValidDepthUpdateAge =
                    ClientSbsShotCutPolicy.nextValidDepthUpdateAge(
                            validDepthUpdateAge, true, false);
            boolean accepted = ClientSbsShotCutPolicy.acceptsShotCut(
                    true, cutState, externalEvidence, false,
                    changeFraction, distributionShift,
                    baselineInitialized, geometryBaseline, advancedValidDepthUpdateAge);
            geometryBaseline = ClientSbsShotCutPolicy.nextGeometryBaseline(
                    geometryBaseline, baselineInitialized, accepted, changeFraction);
            baselineInitialized = true;
            advance(accepted, externalEvidence, changeFraction, referenceFrameAdvance);
        }

        private void advance(boolean accepted, boolean externalEvidence, float changeFraction,
                             int referenceFrameAdvance) {
            validDepthUpdateAge = ClientSbsShotCutPolicy.nextValidDepthUpdateAge(
                    validDepthUpdateAge, true, accepted);
            cutState = ClientSbsShotCutPolicy.nextCutState(
                    cutState, true, accepted, externalEvidence, changeFraction,
                    validDepthUpdateAge);
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
