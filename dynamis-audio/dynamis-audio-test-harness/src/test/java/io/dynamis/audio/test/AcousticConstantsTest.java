package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AcousticConstantsTest {

    @Test
    void bandCountMatchesBandCenterArrayLength() {
        assertThat(AcousticConstants.BAND_CENTER_HZ)
            .hasSize(AcousticConstants.ACOUSTIC_BAND_COUNT);
    }

    @Test
    void bandCenterFrequenciesAreCorrectISO3382Values() {
        assertThat(AcousticConstants.BAND_CENTER_HZ)
            .containsExactly(63f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f);
    }

    @Test
    void priorityWeightsSumToOne() {
        float sum = AcousticConstants.W_DISTANCE
                  + AcousticConstants.W_IMPORTANCE
                  + AcousticConstants.W_AUDIBILITY
                  + AcousticConstants.W_VELOCITY;
        assertThat(sum).isCloseTo(1.0f, within(1e-5f));
    }

    @Test
    void hysteresisPromoteThresholdExceedsDemoteThreshold() {
        assertThat(AcousticConstants.PROMOTE_THRESHOLD)
            .isGreaterThan(AcousticConstants.DEMOTE_THRESHOLD);
    }

    @Test
    void defaultCriticalReserveWithinTwentyFivePercentOfBudget() {
        assertThat(AcousticConstants.DEFAULT_CRITICAL_RESERVE)
            .isLessThanOrEqualTo(AcousticConstants.DEFAULT_PHYSICAL_BUDGET / 4);
    }

    @Test
    void eventRingCapacityIsPowerOfTwo() {
        int cap = AcousticConstants.EVENT_RING_CAPACITY;
        assertThat(Integer.bitCount(cap))
            .as("EVENT_RING_CAPACITY must be a power of two")
            .isEqualTo(1);
    }

    @Test
    void dspBlockSizeIsPositive() {
        assertThat(AcousticConstants.DSP_BLOCK_SIZE).isPositive();
    }

    @Test
    void sampleRateIs48kHz() {
        assertThat(AcousticConstants.SAMPLE_RATE).isEqualTo(48_000);
    }

    @Test
    void validateDoesNotThrowWithCorrectDefaults() {
        assertThatCode(AcousticConstants::validate).doesNotThrowAnyException();
    }
}
