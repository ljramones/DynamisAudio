package io.dynamis.audio.dsp;

/**
 * A simple gain stage DSP node.
 *
 * Multiplies every sample in the input buffer by a target gain, with optional
 * per-block smoothing to prevent zipper noise on rapid gain changes.
 *
 * SMOOTHING MODEL:
 *   Each block, currentGain moves toward targetGain by at most SMOOTH_COEFF per sample.
 *   At 48kHz / 256-sample blocks, a gain change of 1.0 completes in ~= 5ms - imperceptible.
 *   Smoothing is applied per-sample in the inner loop - no per-block allocation.
 *
 * ALLOCATION CONTRACT: Zero allocation in processInternal(). All state is primitive fields.
 */
public final class GainNode extends AbstractDspNode {

    /**
     * Per-sample smoothing coefficient. Controls how fast currentGain tracks targetGain.
     * Value = 1 - exp(-2pi * cutoffHz / sampleRate) approximated for 200Hz cutoff at 48kHz.
     * Higher = faster response, lower = smoother but slower.
     */
    private static final float SMOOTH_COEFF = 0.025f;

    /** The gain to ramp toward. Written by designer/game thread via setTargetGain(). */
    private volatile float targetGain = 1.0f;

    /** Current smoothed gain. Tracked per-sample in processInternal(). */
    private float currentGain = 1.0f;

    public GainNode(String name) {
        super(name);
    }

    public GainNode(String name, float initialGain) {
        super(name);
        this.targetGain = initialGain;
        this.currentGain = initialGain;
    }

    /**
     * Sets the target gain. currentGain ramps toward this value over subsequent blocks.
     * Range [0..infinity) - values > 1.0 amplify. Designer should limit to avoid clipping.
     * Called from designer/game thread.
     */
    public void setTargetGain(float gain) {
        this.targetGain = Math.max(0f, gain);
    }

    /** Returns the current instantaneous gain (may be mid-ramp). */
    public float currentGain() { return currentGain; }

    /** Returns the target gain being ramped toward. */
    public float targetGain() { return targetGain; }

    @Override
    protected void processInternal(float[] inputBuffer, float[] outputBuffer,
                                   int frameCount, int channels) {
        float cg = currentGain;
        float tg = targetGain;
        int len = frameCount * channels;

        for (int i = 0; i < len; i++) {
            // Per-sample gain smoothing - eliminates zipper noise
            cg += (tg - cg) * SMOOTH_COEFF;
            outputBuffer[i] = inputBuffer[i] * cg;
        }
        currentGain = cg;
    }
}
