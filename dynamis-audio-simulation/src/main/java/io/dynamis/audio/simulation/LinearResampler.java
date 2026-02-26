package io.dynamis.audio.simulation;

/**
 * Stateless linear interpolation resampler for interleaved PCM.
 */
public final class LinearResampler {

    private LinearResampler() {}

    public static void resample(float[] in, int inFrames, int inRate,
                                float[] out, int outFrames, int outRate,
                                int channels) {
        if (inRate == outRate) {
            int frames = Math.min(inFrames, outFrames);
            System.arraycopy(in, 0, out, 0, frames * channels);
            if (frames < outFrames) {
                java.util.Arrays.fill(out, frames * channels, outFrames * channels, 0f);
            }
            return;
        }

        double ratio = (double) inRate / (double) outRate;
        for (int outFrame = 0; outFrame < outFrames; outFrame++) {
            double inPos = outFrame * ratio;
            int inIdx = (int) inPos;
            double frac = inPos - inIdx;
            for (int ch = 0; ch < channels; ch++) {
                float a = sampleAt(in, inIdx, ch, channels, inFrames);
                float b = sampleAt(in, inIdx + 1, ch, channels, inFrames);
                out[outFrame * channels + ch] = (float) (a + frac * (b - a));
            }
        }
    }

    private static float sampleAt(float[] buf, int frame, int ch, int channels, int totalFrames) {
        int clamped = Math.min(frame, totalFrames - 1);
        if (clamped < 0) {
            return 0f;
        }
        return buf[clamped * channels + ch];
    }

    public static int inputFramesRequired(int outFrames, int inRate, int outRate) {
        return (int) Math.ceil((double) outFrames * inRate / (double) outRate) + 1;
    }
}
