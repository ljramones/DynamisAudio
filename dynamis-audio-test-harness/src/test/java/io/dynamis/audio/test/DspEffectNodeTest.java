package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.dsp.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DspEffectNodeTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    private float[] silence() { return new float[LEN]; }

    private float[] unity() {
        float[] b = new float[LEN];
        java.util.Arrays.fill(b, 1.0f);
        return b;
    }

    private float[] output() { return new float[LEN]; }

    // -- GainNode -------------------------------------------------------------

    @Test
    void gainNodeDefaultIsUnity() {
        GainNode g = new GainNode("test-gain");
        g.prepare(BLOCK, CH);
        float[] out = output();
        g.process(unity(), out, BLOCK, CH);
        // After smoothing converges, gain ~= 1.0 - check last sample
        assertThat(out[LEN - 1]).isCloseTo(1.0f, within(0.05f));
    }

    @Test
    void gainNodeZeroGainSilencesOutput() {
        GainNode g = new GainNode("test-gain", 0f);
        g.prepare(BLOCK, CH);
        float[] out = output();
        g.process(unity(), out, BLOCK, CH);
        assertThat(out[LEN - 1]).isCloseTo(0.0f, within(1e-4f));
    }

    @Test
    void gainNodeSmoothsGainChange() {
        GainNode g = new GainNode("test-gain", 1.0f);
        g.prepare(BLOCK, CH);
        g.setTargetGain(0.0f);
        float[] out = output();
        g.process(unity(), out, BLOCK, CH);
        // After one block, gain has moved toward 0 but not reached it
        assertThat(out[0]).isLessThan(1.0f);
        assertThat(out[0]).isGreaterThan(0.0f);
    }

    @Test
    void gainNodeBypassCopiesInput() {
        GainNode g = new GainNode("test-gain", 0f); // gain=0, but bypassed
        g.prepare(BLOCK, CH);
        g.setBypassed(true);
        float[] in = unity();
        float[] out = output();
        g.process(in, out, BLOCK, CH);
        // Bypass copies input (multiplied by node gain=1.0 from AbstractDspNode)
        assertThat(out[0]).isCloseTo(1.0f, within(1e-4f));
    }

    @Test
    void gainNodeNameIsCorrect() {
        assertThat(new GainNode("master-gain").name()).isEqualTo("master-gain");
    }

    @Test
    void gainNodeNegativeGainClampsToZero() {
        GainNode g = new GainNode("test");
        g.setTargetGain(-1.0f);
        assertThat(g.targetGain()).isEqualTo(0.0f);
    }

    // -- EqNode ---------------------------------------------------------------

    @Test
    void eqNodeUnityGainPassesSignalUnchanged() {
        EqNode eq = new EqNode("test-eq");
        eq.prepare(BLOCK, CH);
        float[] in = unity();
        float[] out = output();
        eq.process(in, out, BLOCK, CH);
        // All bands at 0 dB - output should be very close to input
        assertThat(out[LEN / 2]).isCloseTo(1.0f, within(0.01f));
    }

    @Test
    void eqNodeBoostIncreasesOutputLevel() {
        EqNode eq = new EqNode("test-eq");
        eq.prepare(BLOCK, CH);
        eq.setBandGainDb(3, 12f); // +12 dB at 500Hz band
        float[] in = new float[LEN];
        // Fill with a sine at 500Hz to excite the boosted band
        float freq = AcousticConstants.BAND_CENTER_HZ[3];
        for (int i = 0; i < BLOCK; i++) {
            float s = (float) Math.sin(2 * Math.PI * freq * i / AcousticConstants.SAMPLE_RATE);
            in[i * CH] = s;
            in[i * CH + 1] = s;
        }
        float[] out = output();
        eq.process(in, out, BLOCK, CH);
        // RMS of output should be higher than RMS of input due to boost
        float rmsIn = rms(in);
        float rmsOut = rms(out);
        assertThat(rmsOut).isGreaterThan(rmsIn * 1.5f);
    }

    @Test
    void eqNodeCutDecreasesOutputLevel() {
        EqNode eq = new EqNode("test-eq");
        eq.prepare(BLOCK, CH);
        eq.setBandGainDb(3, -12f); // -12 dB at 500Hz
        float[] in = new float[LEN];
        float freq = AcousticConstants.BAND_CENTER_HZ[3];
        for (int i = 0; i < BLOCK; i++) {
            float s = (float) Math.sin(2 * Math.PI * freq * i / AcousticConstants.SAMPLE_RATE);
            in[i * CH] = s;
            in[i * CH + 1] = s;
        }
        float[] out = output();
        eq.process(in, out, BLOCK, CH);
        assertThat(rms(out)).isLessThan(rms(in) * 0.5f);
    }

    @Test
    void eqNodeBandIndexOutOfRangeThrows() {
        EqNode eq = new EqNode("test-eq");
        assertThatThrownBy(() -> eq.setBandGainDb(8, 6f))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eq.setBandGainDb(-1, 6f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eqNodeSilenceInSilenceOut() {
        EqNode eq = new EqNode("test-eq");
        eq.prepare(BLOCK, CH);
        eq.setBandGainDb(0, 24f);
        float[] out = output();
        eq.process(silence(), out, BLOCK, CH);
        for (float s : out) {
            assertThat(s).isCloseTo(0f, within(1e-6f));
        }
    }

    @Test
    void eqNodeSetBandGainsArray() {
        EqNode eq = new EqNode("test-eq");
        float[] gains = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        gains[2] = 6f;
        eq.setBandGains(gains);
        assertThat(eq.getBandGainDb(2)).isEqualTo(6f);
        assertThat(eq.getBandGainDb(0)).isEqualTo(0f);
    }

    // -- CompressorNode -------------------------------------------------------

    @Test
    void compressorDefaultsAreCorrect() {
        CompressorNode c = new CompressorNode("test-comp");
        assertThat(c.getThresholdDb()).isEqualTo(-12f);
        assertThat(c.getRatio()).isEqualTo(4f);
        assertThat(c.getAttackMs()).isEqualTo(10f);
        assertThat(c.getReleaseMs()).isEqualTo(100f);
        assertThat(c.getMakeupGainDb()).isEqualTo(0f);
    }

    @Test
    void compressorSilencePassesThroughUnchanged() {
        CompressorNode c = new CompressorNode("test-comp");
        c.prepare(BLOCK, CH);
        float[] out = output();
        c.process(silence(), out, BLOCK, CH);
        for (float s : out) {
            assertThat(s).isCloseTo(0f, within(1e-6f));
        }
    }

    @Test
    void compressorReducesLoudSignal() {
        CompressorNode c = new CompressorNode("test-comp");
        c.setThresholdDb(-20f);
        c.setRatio(10f);
        c.prepare(BLOCK, CH);

        // Generate a loud signal well above threshold
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 0.9f);

        // Run several blocks to let RMS and gain settle
        float[] out = output();
        for (int i = 0; i < 50; i++) {
            c.process(in, out, BLOCK, CH);
        }

        assertThat(rms(out)).isLessThan(rms(in));
    }

    @Test
    void compressorRatioLessThanOneClampsToOne() {
        CompressorNode c = new CompressorNode("test");
        c.setRatio(0.5f);
        assertThat(c.getRatio()).isEqualTo(1.0f);
    }

    @Test
    void compressorGainReductionDbIsZeroOnSilence() {
        CompressorNode c = new CompressorNode("test");
        c.prepare(BLOCK, CH);
        c.process(silence(), output(), BLOCK, CH);
        assertThat(c.getGainReductionDb()).isCloseTo(0f, within(0.1f));
    }

    @Test
    void compressorNameIsCorrect() {
        assertThat(new CompressorNode("main-comp").name()).isEqualTo("main-comp");
    }

    // -- Helper ---------------------------------------------------------------

    private static float rms(float[] buf) {
        double sum = 0;
        for (float s : buf) {
            sum += s * s;
        }
        return (float) Math.sqrt(sum / buf.length);
    }
}
