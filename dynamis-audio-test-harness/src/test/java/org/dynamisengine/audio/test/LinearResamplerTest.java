package org.dynamisengine.audio.test;

import org.dynamisengine.audio.simulation.LinearResampler;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LinearResamplerTest {

    @Test
    void sameRateCopiesInput() {
        float[] in = {1f, 2f, 3f, 4f, 5f, 6f};
        float[] out = new float[6];
        LinearResampler.resample(in, 3, 48000, out, 3, 48000, 2);
        assertThat(out).containsExactly(1f, 2f, 3f, 4f, 5f, 6f);
    }

    @Test
    void sameRateFewerOutputFramesZeroPads() {
        float[] in = {1f, 2f, 3f, 4f};
        float[] out = new float[6]; // 3 frames
        LinearResampler.resample(in, 2, 48000, out, 3, 48000, 2);
        assertThat(out[0]).isEqualTo(1f);
        assertThat(out[1]).isEqualTo(2f);
        assertThat(out[2]).isEqualTo(3f);
        assertThat(out[3]).isEqualTo(4f);
        assertThat(out[4]).isEqualTo(0f); // zero padded
        assertThat(out[5]).isEqualTo(0f);
    }

    @Test
    void upsampleDoubleRate() {
        // Mono: 2 input frames at 24kHz -> 4 output frames at 48kHz
        float[] in = {0f, 1f};
        float[] out = new float[4];
        LinearResampler.resample(in, 2, 24000, out, 4, 48000, 1);
        // outFrame 0: inPos=0.0 -> 0.0
        // outFrame 1: inPos=0.5 -> lerp(0,1,0.5) = 0.5
        // outFrame 2: inPos=1.0 -> 1.0
        // outFrame 3: inPos=1.5 -> lerp(1,1,0.5) = 1.0 (clamped)
        assertThat(out[0]).isCloseTo(0f, within(1e-5f));
        assertThat(out[1]).isCloseTo(0.5f, within(1e-5f));
        assertThat(out[2]).isCloseTo(1.0f, within(1e-5f));
    }

    @Test
    void downsampleHalfRate() {
        // Mono: 4 input frames at 48kHz -> 2 output frames at 24kHz
        float[] in = {0f, 0.5f, 1.0f, 0.5f};
        float[] out = new float[2];
        LinearResampler.resample(in, 4, 48000, out, 2, 24000, 1);
        // outFrame 0: inPos=0 -> 0.0
        // outFrame 1: inPos=2.0 -> 1.0
        assertThat(out[0]).isCloseTo(0f, within(1e-5f));
        assertThat(out[1]).isCloseTo(1.0f, within(1e-5f));
    }

    @Test
    void stereoResamplePreservesChannels() {
        // Stereo: 2 input frames -> 3 output frames (1.5x upsample)
        float[] in = {1f, -1f, 2f, -2f}; // L=1,2 R=-1,-2
        float[] out = new float[6]; // 3 frames * 2 ch
        LinearResampler.resample(in, 2, 32000, out, 3, 48000, 2);
        // Each channel interpolates independently
        assertThat(out[0]).isCloseTo(1f, within(1e-4f)); // frame 0 L
        assertThat(out[1]).isCloseTo(-1f, within(1e-4f)); // frame 0 R
    }

    @Test
    void inputFramesRequiredComputes() {
        int needed = LinearResampler.inputFramesRequired(100, 44100, 48000);
        // ceil(100 * 44100 / 48000) + 1 = ceil(91.875) + 1 = 93
        assertThat(needed).isEqualTo(93);
    }

    @Test
    void inputFramesRequiredSameRate() {
        int needed = LinearResampler.inputFramesRequired(100, 48000, 48000);
        // ceil(100) + 1 = 101
        assertThat(needed).isEqualTo(101);
    }

    @Test
    void emptyInputProducesZeroOutput() {
        float[] in = {};
        float[] out = new float[4];
        // 0 input frames, 2 output frames mono - should clamp and produce 0
        LinearResampler.resample(in, 0, 48000, out, 2, 24000, 2);
        for (float s : out) {
            assertThat(s).isEqualTo(0f);
        }
    }

    @Test
    void singleFrameInputClampsBeyondEnd() {
        float[] in = {0.7f};
        float[] out = new float[3];
        LinearResampler.resample(in, 1, 24000, out, 3, 48000, 1);
        // All output frames map to clamped index 0
        for (float s : out) {
            assertThat(s).isCloseTo(0.7f, within(1e-5f));
        }
    }

    @Test
    void resamplePreservesConstantSignal() {
        float[] in = new float[100];
        java.util.Arrays.fill(in, 0.42f);
        float[] out = new float[200];
        LinearResampler.resample(in, 100, 24000, out, 200, 48000, 1);
        for (float s : out) {
            assertThat(s).isCloseTo(0.42f, within(1e-4f));
        }
    }
}
