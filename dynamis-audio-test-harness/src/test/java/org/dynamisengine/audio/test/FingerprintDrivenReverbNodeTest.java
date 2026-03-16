package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.AcousticConstants;
import org.dynamisengine.audio.dsp.FingerprintDrivenReverbNode;
import org.dynamisengine.audio.simulation.FingerprintBlender.MutableAcousticFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FingerprintDrivenReverbNodeTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    private FingerprintDrivenReverbNode node;

    @BeforeEach
    void setUp() {
        node = new FingerprintDrivenReverbNode("test-reverb");
        node.prepare(BLOCK, CH);
    }

    private float[] output() { return new float[LEN]; }

    @Test
    void initialFingerprintIsNull() {
        assertThat(node.getFingerprint()).isNull();
    }

    @Test
    void setAndGetFingerprint() {
        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        node.setFingerprint(fp);
        assertThat(node.getFingerprint()).isSameAs(fp);
    }

    @Test
    void processWithNullFingerprintDoesNotThrow() {
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 0.1f);
        float[] out = output();
        assertThatCode(() -> node.process(in, out, BLOCK, CH))
            .doesNotThrowAnyException();
    }

    @Test
    void processWithFingerprintSmoothsParameters() {
        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        java.util.Arrays.fill(fp.rt60PerBand, 3.0f);
        java.util.Arrays.fill(fp.portalTransmission, 0.5f);
        node.setFingerprint(fp);

        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 0.1f);
        float[] out = output();

        // Process a few blocks - parameters should smooth toward target
        for (int i = 0; i < 10; i++) {
            node.process(in, out, BLOCK, CH);
        }

        // RT60 target = mean(3.0) = 3.0. Should have moved from initial 1.5 toward 3.0
        assertThat(node.getRt60()).isGreaterThan(1.5f);
    }

    @Test
    void highRt60PerBandProducesHighTargetRt60() {
        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        java.util.Arrays.fill(fp.rt60PerBand, 10.0f);
        java.util.Arrays.fill(fp.portalTransmission, 1.0f);
        node.setFingerprint(fp);

        float[] in = new float[LEN];
        float[] out = output();
        for (int i = 0; i < 200; i++) {
            node.process(in, out, BLOCK, CH);
        }
        // After many blocks, RT60 should converge close to target (10.0)
        assertThat(node.getRt60()).isGreaterThan(5.0f);
    }

    @Test
    void dampingDerivedFromLowVsHighBandRatio() {
        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        // Low bands have much higher RT60 than high bands -> high damping
        for (int i = 0; i < 4; i++) fp.rt60PerBand[i] = 4.0f;
        for (int i = 4; i < 8; i++) fp.rt60PerBand[i] = 0.5f;
        java.util.Arrays.fill(fp.portalTransmission, 1.0f);
        node.setFingerprint(fp);

        float[] in = new float[LEN];
        float[] out = output();
        for (int i = 0; i < 200; i++) {
            node.process(in, out, BLOCK, CH);
        }
        // High-frequency decay much faster -> damping should be high
        assertThat(node.getDamping()).isGreaterThan(0.5f);
    }

    @Test
    void wetMixDerivedFromPortalTransmission() {
        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        java.util.Arrays.fill(fp.rt60PerBand, 1.0f);
        java.util.Arrays.fill(fp.portalTransmission, 0.0f); // no transmission
        node.setFingerprint(fp);

        float[] in = new float[LEN];
        float[] out = output();
        for (int i = 0; i < 200; i++) {
            node.process(in, out, BLOCK, CH);
        }
        // With 0 transmission, wetMix target = 0.5 + 0 * 0.5 = 0.5
        assertThat(node.getWetMix()).isCloseTo(0.5f, within(0.1f));
    }

    @Test
    void fullTransmissionProducesHighWetMix() {
        MutableAcousticFingerprint fp = new MutableAcousticFingerprint();
        java.util.Arrays.fill(fp.rt60PerBand, 1.0f);
        java.util.Arrays.fill(fp.portalTransmission, 1.0f);
        node.setFingerprint(fp);

        float[] in = new float[LEN];
        float[] out = output();
        for (int i = 0; i < 200; i++) {
            node.process(in, out, BLOCK, CH);
        }
        // wetMix target = 0.5 + 1.0 * 0.5 = 1.0
        assertThat(node.getWetMix()).isCloseTo(1.0f, within(0.1f));
    }

    @Test
    void bypassCopiesInput() {
        node.setBypassed(true);
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 0.5f);
        float[] out = output();
        node.process(in, out, BLOCK, CH);
        assertThat(out[0]).isCloseTo(0.5f, within(1e-4f));
    }
}
