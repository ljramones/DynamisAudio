package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticRoom;
import io.dynamis.audio.simulation.ReverbEstimator;
import io.dynamis.audio.simulation.ReverbWetGainCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReverbEstimatorTest {

    private static final int BANDS = AcousticConstants.ACOUSTIC_BAND_COUNT;

    // -- ReverbEstimator - formula selection --------------------------------

    @Test
    void eyringApplicableAboveThreshold() {
        assertThat(ReverbEstimator.isEyringApplicable(0.31f)).isTrue();
    }

    @Test
    void eyringNotApplicableBelowThreshold() {
        assertThat(ReverbEstimator.isEyringApplicable(0.29f)).isFalse();
    }

    @Test
    void eyringNotApplicableAtExactThreshold() {
        // threshold is strictly greater-than
        assertThat(ReverbEstimator.isEyringApplicable(
            ReverbEstimator.EYRING_THRESHOLD)).isFalse();
    }

    // -- ReverbEstimator - Sabine path -------------------------------------

    @Test
    void sabineRt60IsPositiveForRealisticRoom() {
        // Room: 100 m^3, uniform sabins=5 per band (lightly absorptive)
        float[] out = new float[BANDS];
        ReverbEstimator.computeRt60(roomWith(100f, 5f), out);
        for (float rt60 : out) {
            assertThat(rt60).isGreaterThan(0f);
            assertThat(rt60).isLessThanOrEqualTo(ReverbEstimator.MAX_RT60_SECONDS);
        }
    }

    @Test
    void sabineLargerRoomHasLongerRt60() {
        float[] small = new float[BANDS];
        float[] large = new float[BANDS];
        ReverbEstimator.computeRt60(roomWith(50f, 5f), small);
        ReverbEstimator.computeRt60(roomWith(500f, 5f), large);
        assertThat(large[0]).isGreaterThan(small[0]);
    }

    @Test
    void sabineMoreAbsorbentRoomHasShorterRt60() {
        float[] low = new float[BANDS];
        float[] high = new float[BANDS];
        ReverbEstimator.computeRt60(roomWith(100f, 5f), low);
        ReverbEstimator.computeRt60(roomWith(100f, 50f), high);
        assertThat(high[0]).isLessThan(low[0]);
    }

    // -- ReverbEstimator - edge cases --------------------------------------

    @Test
    void zeroVolumeRoomReturnsMinRt60() {
        float[] out = new float[BANDS];
        ReverbEstimator.computeRt60(roomWith(0f, 5f), out);
        for (float rt60 : out) {
            assertThat(rt60).isEqualTo(ReverbEstimator.MIN_RT60_SECONDS);
        }
    }

    @Test
    void zeroAbsorptionRoomReturnsMaxRt60() {
        float[] out = new float[BANDS];
        ReverbEstimator.computeRt60(roomWith(100f, 0f), out);
        for (float rt60 : out) {
            assertThat(rt60).isEqualTo(ReverbEstimator.MAX_RT60_SECONDS);
        }
    }

    @Test
    void rt60OutputIsAlwaysFinite() {
        float[] out = new float[BANDS];
        // Pathological inputs - must not produce NaN or Inf
        ReverbEstimator.computeRt60(roomWith(Float.MAX_VALUE, 0.001f), out);
        for (float rt60 : out) {
            assertThat(Float.isFinite(rt60)).isTrue();
        }
    }

    // -- ReverbEstimator - mean RT60 ---------------------------------------

    @Test
    void meanRt60IsWithinPerBandRange() {
        float[] scratch = new float[BANDS];
        float mean = ReverbEstimator.computeMeanRt60(roomWith(100f, 5f), scratch);
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        for (float v : scratch) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertThat(mean).isBetween(min - 1e-4f, max + 1e-4f);
    }

    // -- ReverbWetGainCalculator -------------------------------------------

    @Test
    void wetGainAtZeroDistanceIsZero() {
        float gain = ReverbWetGainCalculator.compute(100f, 0.5f, 0f);
        assertThat(gain).isEqualTo(0f);
    }

    @Test
    void wetGainIncreasesWithDistance() {
        float near = ReverbWetGainCalculator.compute(100f, 0.5f, 1f);
        float far = ReverbWetGainCalculator.compute(100f, 0.5f, 20f);
        assertThat(far).isGreaterThan(near);
    }

    @Test
    void wetGainApproachesOneAtLargeDistance() {
        float gain = ReverbWetGainCalculator.compute(100f, 0.5f, 10000f);
        assertThat(gain).isCloseTo(1.0f, within(0.001f));
    }

    @Test
    void wetGainIsAlwaysInRange() {
        assertThat(ReverbWetGainCalculator.compute(100f, 0.5f, 0f)).isBetween(0f, 1f);
        assertThat(ReverbWetGainCalculator.compute(100f, 0.5f, 5f)).isBetween(0f, 1f);
        assertThat(ReverbWetGainCalculator.compute(100f, 0.5f, 100f)).isBetween(0f, 1f);
    }

    @Test
    void wetGainClampsMeanRt60ToMinimum() {
        // Should not throw or return NaN with zero RT60
        float gain = ReverbWetGainCalculator.compute(100f, 0f, 5f);
        assertThat(Float.isFinite(gain)).isTrue();
        assertThat(gain).isBetween(0f, 1f);
    }

    @Test
    void wetGainClampsVolumeToMinimum() {
        // Zero volume should not throw or return NaN
        float gain = ReverbWetGainCalculator.compute(0f, 0.5f, 5f);
        assertThat(Float.isFinite(gain)).isTrue();
        assertThat(gain).isBetween(0f, 1f);
    }

    @Test
    void criticalDistanceIsPositiveForRealisticInputs() {
        float cd = ReverbWetGainCalculator.criticalDistance(100f, 0.5f);
        assertThat(cd).isGreaterThan(0f);
        assertThat(Float.isFinite(cd)).isTrue();
    }

    @Test
    void largerRoomHasLargerCriticalDistance() {
        float small = ReverbWetGainCalculator.criticalDistance(50f, 0.5f);
        float large = ReverbWetGainCalculator.criticalDistance(500f, 0.5f);
        assertThat(large).isGreaterThan(small);
    }

    @Test
    void longerRt60HasSmallerCriticalDistance() {
        // More reverberant room -> critical distance shrinks
        float dry = ReverbWetGainCalculator.criticalDistance(100f, 0.3f);
        float wet = ReverbWetGainCalculator.criticalDistance(100f, 3.0f);
        assertThat(wet).isLessThan(dry);
    }

    // -- Helper --------------------------------------------------------------

    private static AcousticRoom roomWith(float volumeM3, float uniformSabins) {
        return new AcousticRoom() {
            @Override public long id() { return 1L; }
            @Override public float volumeMeters3() { return volumeM3; }
            @Override public float totalAbsorption(int b) { return uniformSabins; }
            @Override public float surfaceAreaMeters2() {
                // Cube approximation: surface = 6 * cbrt(V^2)
                return 6f * (float) Math.pow(volumeM3, 2.0 / 3.0);
            }
            @Override public int dominantMaterialId() { return 1; }
        };
    }
}
