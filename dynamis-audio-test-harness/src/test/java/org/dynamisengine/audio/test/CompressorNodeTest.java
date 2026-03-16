package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.AcousticConstants;
import org.dynamisengine.audio.dsp.CompressorNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CompressorNodeTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    private CompressorNode comp;

    @BeforeEach
    void setUp() {
        comp = new CompressorNode("test-comp");
        comp.prepare(BLOCK, CH);
    }

    private float[] loud() {
        float[] b = new float[LEN];
        java.util.Arrays.fill(b, 0.9f);
        return b;
    }

    private float[] output() { return new float[LEN]; }

    private static float rms(float[] buf) {
        double sum = 0;
        for (float s : buf) sum += s * s;
        return (float) Math.sqrt(sum / buf.length);
    }

    @Test
    void defaultThresholdIsMinus12() {
        assertThat(comp.getThresholdDb()).isEqualTo(-12f);
    }

    @Test
    void defaultRatioIsFour() {
        assertThat(comp.getRatio()).isEqualTo(4f);
    }

    @Test
    void setAttackMsClampsToMinimum() {
        comp.setAttackMs(0.001f);
        assertThat(comp.getAttackMs()).isEqualTo(0.1f);
    }

    @Test
    void setReleaseMsClampsToMinimum() {
        comp.setReleaseMs(0.1f);
        assertThat(comp.getReleaseMs()).isEqualTo(1.0f);
    }

    @Test
    void makeupGainBoostsOutput() {
        comp.setThresholdDb(-100f); // well below signal
        comp.setMakeupGainDb(12f);
        comp.setRatio(1.0f); // no compression
        float[] in = loud();
        float[] out = output();
        for (int i = 0; i < 50; i++) {
            comp.process(in, out, BLOCK, CH);
        }
        assertThat(rms(out)).isGreaterThan(rms(in));
    }

    @Test
    void highRatioHeavilyCompresses() {
        comp.setThresholdDb(-30f);
        comp.setRatio(100f);
        float[] in = loud();
        float[] out = output();
        for (int i = 0; i < 100; i++) {
            comp.process(in, out, BLOCK, CH);
        }
        assertThat(rms(out)).isLessThan(rms(in) * 0.5f);
    }

    @Test
    void gainReductionDbIsNegativeOnLoudSignal() {
        comp.setThresholdDb(-30f);
        comp.setRatio(10f);
        float[] in = loud();
        for (int i = 0; i < 100; i++) {
            comp.process(in, output(), BLOCK, CH);
        }
        assertThat(comp.getGainReductionDb()).isLessThan(0f);
    }

    @Test
    void resetClearsRmsAndGainReduction() {
        comp.setThresholdDb(-30f);
        comp.setRatio(10f);
        for (int i = 0; i < 50; i++) {
            comp.process(loud(), output(), BLOCK, CH);
        }
        comp.reset();
        comp.prepare(BLOCK, CH);
        comp.process(new float[LEN], output(), BLOCK, CH);
        assertThat(comp.getGainReductionDb()).isCloseTo(0f, within(0.1f));
    }

    @Test
    void bypassPassesSignalUncompressed() {
        comp.setThresholdDb(-30f);
        comp.setRatio(10f);
        comp.setBypassed(true);
        float[] in = loud();
        float[] out = output();
        comp.process(in, out, BLOCK, CH);
        assertThat(out[0]).isCloseTo(in[0], within(1e-4f));
    }

    @Test
    void quietSignalBelowThresholdPassesUnchanged() {
        comp.setThresholdDb(-6f);
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 0.01f); // very quiet
        float[] out = output();
        for (int i = 0; i < 50; i++) {
            comp.process(in, out, BLOCK, CH);
        }
        // Gain reduction should be near 0 dB
        assertThat(comp.getGainReductionDb()).isCloseTo(0f, within(1.0f));
    }

    @Test
    void ratioOfOneMeansNoCompression() {
        comp.setRatio(1.0f);
        comp.setThresholdDb(-30f);
        float[] in = loud();
        float[] out = output();
        for (int i = 0; i < 50; i++) {
            comp.process(in, out, BLOCK, CH);
        }
        // ratio=1 means gainDb = threshold + (level - threshold)/1 - level = 0
        assertThat(comp.getGainReductionDb()).isCloseTo(0f, within(1.0f));
    }
}
