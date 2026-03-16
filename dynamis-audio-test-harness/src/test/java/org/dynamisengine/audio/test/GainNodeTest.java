package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.AcousticConstants;
import org.dynamisengine.audio.dsp.GainNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GainNodeTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    private GainNode gain;

    @BeforeEach
    void setUp() {
        gain = new GainNode("test-gain");
        gain.prepare(BLOCK, CH);
    }

    private float[] unity() {
        float[] b = new float[LEN];
        java.util.Arrays.fill(b, 1.0f);
        return b;
    }

    private float[] output() { return new float[LEN]; }

    @Test
    void defaultTargetGainIsUnity() {
        assertThat(gain.targetGain()).isEqualTo(1.0f);
    }

    @Test
    void currentGainStartsAtTargetGain() {
        assertThat(gain.currentGain()).isEqualTo(1.0f);
    }

    @Test
    void constructorWithInitialGainSetsTargetAndCurrent() {
        GainNode g = new GainNode("init", 0.5f);
        assertThat(g.targetGain()).isEqualTo(0.5f);
        assertThat(g.currentGain()).isEqualTo(0.5f);
    }

    @Test
    void setTargetGainAboveOneIsAllowed() {
        gain.setTargetGain(2.0f);
        assertThat(gain.targetGain()).isEqualTo(2.0f);
    }

    @Test
    void setTargetGainNegativeClampsToZero() {
        gain.setTargetGain(-5.0f);
        assertThat(gain.targetGain()).isEqualTo(0.0f);
    }

    @Test
    void smoothingConvergesAfterMultipleBlocks() {
        gain.setTargetGain(0.0f);
        float[] in = unity();
        float[] out = output();
        for (int i = 0; i < 50; i++) {
            gain.process(in, out, BLOCK, CH);
        }
        assertThat(gain.currentGain()).isCloseTo(0.0f, within(1e-3f));
    }

    @Test
    void smoothingIsGradualNotInstant() {
        gain.setTargetGain(0.0f);
        float[] out = output();
        gain.process(unity(), out, BLOCK, CH);
        // First sample should still be close to 1.0
        assertThat(out[0]).isGreaterThan(0.5f);
        // Last sample should be closer to 0
        assertThat(out[LEN - 1]).isLessThan(out[0]);
    }

    @Test
    void amplificationAboveUnityIncreasesOutput() {
        GainNode g = new GainNode("amp", 2.0f);
        g.prepare(BLOCK, CH);
        float[] out = output();
        g.process(unity(), out, BLOCK, CH);
        assertThat(out[LEN - 1]).isGreaterThan(1.5f);
    }

    @Test
    void bypassCopiesInputRegardlessOfNodeGain() {
        gain.setTargetGain(0.0f);
        // Run a few blocks so currentGain converges toward 0
        for (int i = 0; i < 20; i++) {
            gain.process(unity(), output(), BLOCK, CH);
        }
        gain.setBypassed(true);
        float[] out = output();
        gain.process(unity(), out, BLOCK, CH);
        // Bypassed uses AbstractDspNode.gain (default 1.0), not GainNode.currentGain
        assertThat(out[0]).isCloseTo(1.0f, within(1e-4f));
    }

    @Test
    void blankNameThrows() {
        assertThatThrownBy(() -> new GainNode("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullNameThrows() {
        assertThatThrownBy(() -> new GainNode(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void silenceInProducesSilenceOut() {
        float[] out = output();
        gain.process(new float[LEN], out, BLOCK, CH);
        for (float s : out) {
            assertThat(s).isCloseTo(0f, within(1e-6f));
        }
    }

    @Test
    void abstractNodeGainStacksWithGainNodeGain() {
        GainNode g = new GainNode("stacked", 0.5f);
        g.prepare(BLOCK, CH);
        g.setGain(0.5f); // AbstractDspNode gain
        float[] out = output();
        // Run several blocks to converge
        for (int i = 0; i < 50; i++) {
            g.process(unity(), out, BLOCK, CH);
        }
        // GainNode applies ~0.5, then AbstractDspNode applies 0.5 -> ~0.25
        assertThat(out[LEN - 1]).isCloseTo(0.25f, within(0.05f));
    }
}
