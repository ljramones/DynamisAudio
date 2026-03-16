package org.dynamisengine.audio.test;

import org.dynamisengine.audio.simulation.ReverbWetGainCalculator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReverbWetGainCalculatorTest {

    @Test
    void atDistanceZeroWetGainIsZero() {
        float wet = ReverbWetGainCalculator.compute(100f, 1.5f, 0f);
        assertThat(wet).isCloseTo(0f, within(1e-5f));
    }

    @Test
    void atLargeDistanceWetGainApproachesOne() {
        float wet = ReverbWetGainCalculator.compute(100f, 1.5f, 1000f);
        assertThat(wet).isCloseTo(1.0f, within(0.01f));
    }

    @Test
    void atCriticalDistanceWetGainIsAboutPointSixThree() {
        float cd = ReverbWetGainCalculator.criticalDistance(100f, 1.5f);
        float wet = ReverbWetGainCalculator.compute(100f, 1.5f, cd);
        // 1 - exp(-1) ~= 0.632
        assertThat(wet).isCloseTo(0.632f, within(0.01f));
    }

    @Test
    void resultIsAlwaysClampedBetweenZeroAndOne() {
        float wet = ReverbWetGainCalculator.compute(1f, 0.001f, 50f);
        assertThat(wet).isBetween(0f, 1f);
    }

    @Test
    void negativeDistanceClampedToZero() {
        float wet = ReverbWetGainCalculator.compute(100f, 1.5f, -10f);
        assertThat(wet).isCloseTo(0f, within(1e-5f));
    }

    @Test
    void zeroRt60ClampedToMinRt60() {
        // Should not throw or produce NaN
        float wet = ReverbWetGainCalculator.compute(100f, 0f, 5f);
        assertThat(wet).isNotNaN();
        assertThat(wet).isBetween(0f, 1f);
    }

    @Test
    void zeroVolumeClampedToOne() {
        float wet = ReverbWetGainCalculator.compute(0f, 1.5f, 5f);
        assertThat(wet).isNotNaN();
        assertThat(wet).isBetween(0f, 1f);
    }

    @Test
    void negativeVolumeClampedToOne() {
        float wet = ReverbWetGainCalculator.compute(-100f, 1.5f, 5f);
        assertThat(wet).isNotNaN();
        assertThat(wet).isBetween(0f, 1f);
    }

    @Test
    void largerRoomHasLargerCriticalDistance() {
        float cdSmall = ReverbWetGainCalculator.criticalDistance(10f, 1.0f);
        float cdLarge = ReverbWetGainCalculator.criticalDistance(1000f, 1.0f);
        assertThat(cdLarge).isGreaterThan(cdSmall);
    }

    @Test
    void longerRt60HasSmallerCriticalDistance() {
        float cdShort = ReverbWetGainCalculator.criticalDistance(100f, 0.5f);
        float cdLong = ReverbWetGainCalculator.criticalDistance(100f, 5.0f);
        assertThat(cdShort).isGreaterThan(cdLong);
    }

    @Test
    void wetGainIncreasesMonotonicallyWithDistance() {
        float prev = 0f;
        for (float d = 0f; d <= 50f; d += 5f) {
            float wet = ReverbWetGainCalculator.compute(100f, 1.5f, d);
            assertThat(wet).isGreaterThanOrEqualTo(prev);
            prev = wet;
        }
    }

    @Test
    void criticalDistanceIsPositive() {
        float cd = ReverbWetGainCalculator.criticalDistance(50f, 2.0f);
        assertThat(cd).isGreaterThan(0f);
    }
}
