package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.AcousticConstants;
import org.dynamisengine.audio.dsp.EqNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EqNodeTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    private EqNode eq;

    @BeforeEach
    void setUp() {
        eq = new EqNode("test-eq");
        eq.prepare(BLOCK, CH);
    }

    private float[] output() { return new float[LEN]; }

    private static float rms(float[] buf) {
        double sum = 0;
        for (float s : buf) sum += s * s;
        return (float) Math.sqrt(sum / buf.length);
    }

    private float[] sineAtBand(int band) {
        float[] in = new float[LEN];
        float freq = AcousticConstants.BAND_CENTER_HZ[band];
        for (int i = 0; i < BLOCK; i++) {
            float s = (float) Math.sin(2 * Math.PI * freq * i / AcousticConstants.SAMPLE_RATE);
            in[i * CH] = s;
            in[i * CH + 1] = s;
        }
        return in;
    }

    @Test
    void allBandsDefaultToZeroDb() {
        for (int i = 0; i < AcousticConstants.ACOUSTIC_BAND_COUNT; i++) {
            assertThat(eq.getBandGainDb(i)).isEqualTo(0f);
        }
    }

    @Test
    void setBandGainDbAndGetRoundTrips() {
        eq.setBandGainDb(5, 7.5f);
        assertThat(eq.getBandGainDb(5)).isEqualTo(7.5f);
    }

    @Test
    void setBandGainDbOutOfBoundsHighThrows() {
        assertThatThrownBy(() -> eq.setBandGainDb(AcousticConstants.ACOUSTIC_BAND_COUNT, 0f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setBandGainsArraySetsMultipleBands() {
        float[] gains = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        gains[0] = -3f;
        gains[7] = 6f;
        eq.setBandGains(gains);
        assertThat(eq.getBandGainDb(0)).isEqualTo(-3f);
        assertThat(eq.getBandGainDb(7)).isEqualTo(6f);
        assertThat(eq.getBandGainDb(3)).isEqualTo(0f);
    }

    @Test
    void applyOcclusionPerBandSetsNegativeGains() {
        float[] occlusion = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        occlusion[0] = 1.0f; // full occlusion
        occlusion[4] = 0.5f;
        eq.applyOcclusionPerBand(occlusion);
        assertThat(eq.getBandGainDb(0)).isEqualTo(EqNode.MAX_OCCLUSION_CUT_DB);
        assertThat(eq.getBandGainDb(4)).isCloseTo(EqNode.MAX_OCCLUSION_CUT_DB * 0.5f, within(0.01f));
        assertThat(eq.getBandGainDb(7)).isEqualTo(0f);
    }

    @Test
    void applyOcclusionClampsInputToZeroOne() {
        float[] occlusion = new float[AcousticConstants.ACOUSTIC_BAND_COUNT];
        occlusion[0] = -0.5f; // should clamp to 0
        occlusion[1] = 1.5f;  // should clamp to 1
        eq.applyOcclusionPerBand(occlusion);
        assertThat(eq.getBandGainDb(0)).isEqualTo(0f);
        assertThat(eq.getBandGainDb(1)).isEqualTo(EqNode.MAX_OCCLUSION_CUT_DB);
    }

    @Test
    void resetClearsFilterState() {
        eq.setBandGainDb(3, 24f);
        float[] in = sineAtBand(3);
        float[] out = output();
        eq.process(in, out, BLOCK, CH);
        float rmsBeforeReset = rms(out);

        eq.reset();
        eq.prepare(BLOCK, CH);
        float[] out2 = output();
        eq.process(in, out2, BLOCK, CH);
        // After reset, filter state should start fresh - output should be similar
        assertThat(rms(out2)).isCloseTo(rmsBeforeReset, within(rmsBeforeReset * 0.5f));
    }

    @Test
    void bypassSkipsFiltering() {
        eq.setBandGainDb(3, 24f);
        eq.setBypassed(true);
        float[] in = sineAtBand(3);
        float[] out = output();
        eq.process(in, out, BLOCK, CH);
        // Bypassed: output == input (gain=1.0)
        assertThat(out[10]).isCloseTo(in[10], within(1e-4f));
    }

    @Test
    void nearUnityBandIsSkippedNoProcessing() {
        // Set gain to tiny value below threshold (0.01 dB)
        eq.setBandGainDb(0, 0.005f);
        float[] in = sineAtBand(0);
        float[] out = output();
        eq.process(in, out, BLOCK, CH);
        // Should pass through unchanged since abs(0.005) < 0.01
        assertThat(out[10]).isCloseTo(in[10], within(1e-4f));
    }

    @Test
    void negativeBandGainCutsSignal() {
        eq.setBandGainDb(3, -24f);
        float[] in = sineAtBand(3);
        float[] out = output();
        eq.process(in, out, BLOCK, CH);
        assertThat(rms(out)).isLessThan(rms(in));
    }
}
