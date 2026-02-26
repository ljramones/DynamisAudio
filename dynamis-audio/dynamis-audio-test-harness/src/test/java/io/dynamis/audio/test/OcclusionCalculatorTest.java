package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticMaterial;
import io.dynamis.audio.dsp.EqNode;
import io.dynamis.audio.simulation.OcclusionCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OcclusionCalculatorTest {

    private static final int BANDS = AcousticConstants.ACOUSTIC_BAND_COUNT;

    // -- transmissionLossToOcclusion ----------------------------------------

    @Test
    void zeroDblossIsZeroOcclusion() {
        assertThat(OcclusionCalculator.transmissionLossToOcclusion(0f))
            .isEqualTo(0f);
    }

    @Test
    void fullOcclusionThresholdClampedToOne() {
        assertThat(OcclusionCalculator.transmissionLossToOcclusion(
            OcclusionCalculator.FULL_OCCLUSION_DB))
            .isEqualTo(1f);
    }

    @Test
    void belowFullOcclusionThresholdClampedToOne() {
        assertThat(OcclusionCalculator.transmissionLossToOcclusion(-120f))
            .isEqualTo(1f);
    }

    @Test
    void positiveLossClampedToZero() {
        assertThat(OcclusionCalculator.transmissionLossToOcclusion(6f))
            .isEqualTo(0f);
    }

    @Test
    void twentyDbLossIsApproximatelyNinetyPercent() {
        // 10^(-20/20) = 0.1, occlusion = 1 - 0.1 = 0.9
        assertThat(OcclusionCalculator.transmissionLossToOcclusion(-20f))
            .isCloseTo(0.9f, within(0.001f));
    }

    @Test
    void sixDbLossIsApproximatelyFiftyPercent() {
        // 10^(-6/20) ~= 0.501, occlusion ~= 0.499
        assertThat(OcclusionCalculator.transmissionLossToOcclusion(-6f))
            .isCloseTo(0.499f, within(0.01f));
    }

    // -- computeSingleHit ----------------------------------------------------

    @Test
    void computeSingleHitZeroLossYieldsZeroOcclusion() {
        float[] out = new float[BANDS];
        OcclusionCalculator.computeSingleHit(materialWithUniformLoss(0f), out);
        for (float v : out) assertThat(v).isEqualTo(0f);
    }

    @Test
    void computeSingleHitFullLossYieldsOneOcclusion() {
        float[] out = new float[BANDS];
        OcclusionCalculator.computeSingleHit(
            materialWithUniformLoss(OcclusionCalculator.FULL_OCCLUSION_DB), out);
        for (float v : out) assertThat(v).isEqualTo(1f);
    }

    @Test
    void computeSingleHitPerBandValuesAreIndependent() {
        // Each band has different loss
        float[] out = new float[BANDS];
        AcousticMaterial mat = new AcousticMaterial() {
            public int id() { return 1; }
            public float absorption(int b) { return 0f; }
            public float scattering(int b) { return 0f; }
            public float transmissionLossDb(int band) {
                return band == 0 ? -20f : 0f; // only band 0 has loss
            }
        };
        OcclusionCalculator.computeSingleHit(mat, out);
        assertThat(out[0]).isCloseTo(0.9f, within(0.001f));
        for (int b = 1; b < BANDS; b++) assertThat(out[b]).isEqualTo(0f);
    }

    // -- accumulateHit -------------------------------------------------------

    @Test
    void accumulateTwoFullLossHitsStillOne() {
        float[] out = new float[BANDS];
        AcousticMaterial full = materialWithUniformLoss(
            OcclusionCalculator.FULL_OCCLUSION_DB);
        OcclusionCalculator.computeSingleHit(full, out);
        OcclusionCalculator.accumulateHit(full, out);
        for (float v : out) assertThat(v).isEqualTo(1f);
    }

    @Test
    void accumulateTwoIdenticalHitsIsHigherThanOne() {
        // Two -20dB hits: openFrac = 0.1 * 0.1 = 0.01, occlusion = 0.99
        float[] out = new float[BANDS];
        AcousticMaterial mat = materialWithUniformLoss(-20f);
        OcclusionCalculator.computeSingleHit(mat, out);
        OcclusionCalculator.accumulateHit(mat, out);
        for (float v : out) assertThat(v).isCloseTo(0.99f, within(0.001f));
    }

    @Test
    void accumulateZeroLossHasNoEffect() {
        float[] out = new float[BANDS];
        AcousticMaterial noLoss = materialWithUniformLoss(0f);
        OcclusionCalculator.computeSingleHit(materialWithUniformLoss(-20f), out);
        float before = out[0];
        OcclusionCalculator.accumulateHit(noLoss, out);
        assertThat(out[0]).isCloseTo(before, within(1e-5f));
    }

    // -- reset ---------------------------------------------------------------

    @Test
    void resetZerosAllBands() {
        float[] out = new float[BANDS];
        java.util.Arrays.fill(out, 0.5f);
        OcclusionCalculator.reset(out);
        for (float v : out) assertThat(v).isEqualTo(0f);
    }

    // -- EqNode.applyOcclusionPerBand ---------------------------------------

    @Test
    void applyZeroOcclusionSetsZeroDbGain() {
        EqNode eq = new EqNode("test-eq");
        float[] occlusion = new float[BANDS]; // all 0
        eq.applyOcclusionPerBand(occlusion);
        for (int b = 0; b < BANDS; b++) {
            assertThat(eq.getBandGainDb(b)).isEqualTo(0f);
        }
    }

    @Test
    void applyFullOcclusionSetsMaxCutDb() {
        EqNode eq = new EqNode("test-eq");
        float[] occlusion = new float[BANDS];
        java.util.Arrays.fill(occlusion, 1.0f);
        eq.applyOcclusionPerBand(occlusion);
        for (int b = 0; b < BANDS; b++) {
            assertThat(eq.getBandGainDb(b))
                .isCloseTo(EqNode.MAX_OCCLUSION_CUT_DB, within(1e-5f));
        }
    }

    @Test
    void applyHalfOcclusionSetsHalfMaxCut() {
        EqNode eq = new EqNode("test-eq");
        float[] occlusion = new float[BANDS];
        java.util.Arrays.fill(occlusion, 0.5f);
        eq.applyOcclusionPerBand(occlusion);
        float expected = 0.5f * EqNode.MAX_OCCLUSION_CUT_DB;
        for (int b = 0; b < BANDS; b++) {
            assertThat(eq.getBandGainDb(b)).isCloseTo(expected, within(1e-5f));
        }
    }

    @Test
    void applyOcclusionClampsAboveOne() {
        EqNode eq = new EqNode("test-eq");
        float[] occlusion = new float[BANDS];
        java.util.Arrays.fill(occlusion, 2.0f); // above range
        eq.applyOcclusionPerBand(occlusion);
        for (int b = 0; b < BANDS; b++) {
            assertThat(eq.getBandGainDb(b))
                .isCloseTo(EqNode.MAX_OCCLUSION_CUT_DB, within(1e-5f));
        }
    }

    // -- Helper --------------------------------------------------------------

    private static AcousticMaterial materialWithUniformLoss(float lossDb) {
        return new AcousticMaterial() {
            public int id() { return 1; }
            public float absorption(int band) { return 0.1f; }
            public float scattering(int band) { return 0.05f; }
            public float transmissionLossDb(int band) { return lossDb; }
        };
    }
}
