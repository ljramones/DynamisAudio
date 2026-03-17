package org.dynamisengine.audio.procedural;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OscillatorTest {

    private static final float SAMPLE_RATE = 48_000f;
    private static final float AMP = 0.8f;

    // -- Sine --

    @Test
    void sine_outputBoundedByAmplitude() {
        var osc = new SineOscillator(440f, AMP, SAMPLE_RATE);
        for (int i = 0; i < 48_000; i++) {
            float s = osc.nextSample();
            assertThat(s).isBetween(-AMP, AMP);
        }
    }

    @Test
    void sine_periodMatchesFrequency() {
        float freq = 1000f;
        int samplesPerPeriod = (int) (SAMPLE_RATE / freq);
        var osc = new SineOscillator(freq, 1.0f, SAMPLE_RATE);

        // First sample should be ~0 (sin(0))
        float first = osc.nextSample();
        assertThat(first).isCloseTo(0f, within(0.01f));

        // Skip to next period start
        for (int i = 1; i < samplesPerPeriod; i++) {
            osc.nextSample();
        }
        float nextPeriodStart = osc.nextSample();
        assertThat(nextPeriodStart).isCloseTo(0f, within(0.01f));
    }

    @Test
    void sine_zeroFrequency_producesDc() {
        var osc = new SineOscillator(0f, 1.0f, SAMPLE_RATE);
        // sin(0) = 0 always when frequency is 0
        for (int i = 0; i < 100; i++) {
            assertThat(osc.nextSample()).isCloseTo(0f, within(1e-6f));
        }
    }

    @Test
    void sine_zeroAmplitude_producesSilence() {
        var osc = new SineOscillator(440f, 0f, SAMPLE_RATE);
        for (int i = 0; i < 100; i++) {
            assertThat(osc.nextSample()).isCloseTo(0f, within(1e-6f));
        }
    }

    // -- Square --

    @Test
    void square_outputIsPlusOrMinusAmplitude() {
        var osc = new SquareOscillator(440f, AMP, SAMPLE_RATE);
        for (int i = 0; i < 48_000; i++) {
            float s = osc.nextSample();
            assertThat(Math.abs(s)).isCloseTo(AMP, within(1e-6f));
        }
    }

    @Test
    void square_zeroAmplitude_producesSilence() {
        var osc = new SquareOscillator(440f, 0f, SAMPLE_RATE);
        for (int i = 0; i < 100; i++) {
            assertThat(osc.nextSample()).isCloseTo(0f, within(1e-6f));
        }
    }

    // -- Sawtooth --

    @Test
    void sawtooth_linearRamp() {
        float freq = 100f;
        int samplesPerPeriod = (int) (SAMPLE_RATE / freq);
        var osc = new SawtoothOscillator(freq, 1.0f, SAMPLE_RATE);

        float prev = osc.nextSample();
        // Within one period, each sample should be >= previous (ascending ramp)
        for (int i = 1; i < samplesPerPeriod - 1; i++) {
            float cur = osc.nextSample();
            assertThat(cur).isGreaterThanOrEqualTo(prev);
            prev = cur;
        }
    }

    @Test
    void sawtooth_outputBoundedByAmplitude() {
        var osc = new SawtoothOscillator(440f, AMP, SAMPLE_RATE);
        for (int i = 0; i < 48_000; i++) {
            float s = osc.nextSample();
            assertThat(s).isBetween(-AMP, AMP + 0.001f);
        }
    }

    @Test
    void sawtooth_zeroAmplitude_producesSilence() {
        var osc = new SawtoothOscillator(440f, 0f, SAMPLE_RATE);
        for (int i = 0; i < 100; i++) {
            assertThat(osc.nextSample()).isCloseTo(0f, within(1e-6f));
        }
    }

    // -- Triangle --

    @Test
    void triangle_linearRampUpThenDown() {
        float freq = 100f;
        int samplesPerPeriod = (int) (SAMPLE_RATE / freq);
        int half = samplesPerPeriod / 2;
        var osc = new TriangleOscillator(freq, 1.0f, SAMPLE_RATE);

        // First half: ascending
        float prev = osc.nextSample();
        for (int i = 1; i < half - 1; i++) {
            float cur = osc.nextSample();
            assertThat(cur).isGreaterThanOrEqualTo(prev);
            prev = cur;
        }

        // Second half: descending (skip a couple samples around the peak for rounding)
        osc.nextSample(); // peak area
        osc.nextSample();
        prev = osc.nextSample();
        for (int i = 0; i < half - 4; i++) {
            float cur = osc.nextSample();
            assertThat(cur).isLessThanOrEqualTo(prev + 1e-5f);
            prev = cur;
        }
    }

    @Test
    void triangle_outputBoundedByAmplitude() {
        var osc = new TriangleOscillator(440f, AMP, SAMPLE_RATE);
        for (int i = 0; i < 48_000; i++) {
            float s = osc.nextSample();
            assertThat(s).isBetween(-AMP - 0.001f, AMP + 0.001f);
        }
    }

    @Test
    void triangle_zeroAmplitude_producesSilence() {
        var osc = new TriangleOscillator(440f, 0f, SAMPLE_RATE);
        for (int i = 0; i < 100; i++) {
            assertThat(osc.nextSample()).isCloseTo(0f, within(1e-6f));
        }
    }

    // -- generate() buffer fill --

    @Test
    void generate_fillsBufferRegion() {
        var osc = new SineOscillator(440f, 1.0f, SAMPLE_RATE);
        float[] buf = new float[256];
        osc.generate(buf, 10, 100);
        // Samples 0-9 untouched (0.0)
        assertThat(buf[0]).isEqualTo(0f);
        assertThat(buf[9]).isEqualTo(0f);
        // Samples 110-255 untouched
        assertThat(buf[110]).isEqualTo(0f);
        // At least some generated samples are non-zero
        boolean anyNonZero = false;
        for (int i = 10; i < 110; i++) {
            if (buf[i] != 0f) { anyNonZero = true; break; }
        }
        assertThat(anyNonZero).isTrue();
    }

    // -- Zero frequency DC tests --

    @Test
    void square_zeroFrequency_producesPlusAmplitude() {
        // phase stays 0, which is < 0.5, so output = +amplitude
        var osc = new SquareOscillator(0f, AMP, SAMPLE_RATE);
        for (int i = 0; i < 100; i++) {
            assertThat(osc.nextSample()).isCloseTo(AMP, within(1e-6f));
        }
    }

    @Test
    void sawtooth_zeroFrequency_producesNegativeAmplitude() {
        // phase=0 -> 2*0 - 1 = -1 -> -amplitude
        var osc = new SawtoothOscillator(0f, AMP, SAMPLE_RATE);
        for (int i = 0; i < 100; i++) {
            assertThat(osc.nextSample()).isCloseTo(-AMP, within(1e-6f));
        }
    }

    @Test
    void triangle_zeroFrequency_producesNegativeAmplitude() {
        // phase=0 -> 4*0 - 1 = -1 -> -amplitude
        var osc = new TriangleOscillator(0f, AMP, SAMPLE_RATE);
        for (int i = 0; i < 100; i++) {
            assertThat(osc.nextSample()).isCloseTo(-AMP, within(1e-6f));
        }
    }
}
