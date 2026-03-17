package org.dynamisengine.audio.procedural;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WhiteNoiseGeneratorTest {

    private static final float SAMPLE_RATE = 48_000f;

    @Test
    void outputBoundedByAmplitude() {
        float amp = 0.5f;
        var noise = new WhiteNoiseGenerator(amp, SAMPLE_RATE, 42L);
        for (int i = 0; i < 100_000; i++) {
            float s = noise.nextSample();
            assertThat(s).isBetween(-amp, amp);
        }
    }

    @Test
    void deterministicWithSameSeed() {
        var a = new WhiteNoiseGenerator(1.0f, SAMPLE_RATE, 123L);
        var b = new WhiteNoiseGenerator(1.0f, SAMPLE_RATE, 123L);
        for (int i = 0; i < 1_000; i++) {
            assertThat(a.nextSample()).isEqualTo(b.nextSample());
        }
    }

    @Test
    void meanNearZeroOverManySamples() {
        var noise = new WhiteNoiseGenerator(1.0f, SAMPLE_RATE, 7L);
        double sum = 0;
        int count = 200_000;
        for (int i = 0; i < count; i++) {
            sum += noise.nextSample();
        }
        double mean = sum / count;
        assertThat(mean).isCloseTo(0.0, within(0.01));
    }
}
