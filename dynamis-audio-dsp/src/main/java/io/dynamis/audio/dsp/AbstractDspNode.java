package io.dynamis.audio.dsp;

/**
 * Base implementation of DspNode providing bypass, gain, and prepare scaffolding.
 *
 * Subclasses implement processInternal() - the actual DSP computation.
 * Bypass and gain are handled here, not in subclasses.
 *
 * ALLOCATION CONTRACT: process() and processInternal() must never allocate.
 */
public abstract class AbstractDspNode implements DspNode {

    private final String name;
    private volatile boolean bypassed = false;
    private volatile float gain = 1.0f;

    protected int preparedFrameCount = 0;
    protected int preparedChannels = 0;

    protected AbstractDspNode(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DspNode name must not be null or blank");
        }
        this.name = name;
    }

    @Override public String name() { return name; }
    @Override public boolean isBypassed() { return bypassed; }
    @Override public void setBypassed(boolean b) { this.bypassed = b; }
    @Override public float getGain() { return gain; }
    @Override public void setGain(float g) { this.gain = g; }

    @Override
    public final void process(float[] inputBuffer, float[] outputBuffer,
                              int frameCount, int channels) {
        if (bypassed) {
            // Bypass: copy input to output, apply gain
            int len = frameCount * channels;
            for (int i = 0; i < len; i++) {
                outputBuffer[i] = inputBuffer[i] * gain;
            }
            return;
        }
        processInternal(inputBuffer, outputBuffer, frameCount, channels);
        // Apply output gain
        if (gain != 1.0f) {
            int len = frameCount * channels;
            for (int i = 0; i < len; i++) {
                outputBuffer[i] *= gain;
            }
        }
    }

    @Override
    public void prepare(int maxFrameCount, int channels) {
        this.preparedFrameCount = maxFrameCount;
        this.preparedChannels = channels;
        onPrepare(maxFrameCount, channels);
    }

    @Override
    public void reset() {
        onReset();
        preparedFrameCount = 0;
        preparedChannels = 0;
    }

    /**
     * Subclass DSP implementation. Input is never null; output is pre-allocated.
     * Gain is applied by the base class after this returns - do not apply gain here.
     *
     * ALLOCATION CONTRACT: Zero allocation.
     */
    protected abstract void processInternal(float[] inputBuffer, float[] outputBuffer,
                                            int frameCount, int channels);

    /**
     * Called during prepare(). Override to pre-allocate internal scratch buffers.
     * Default: no-op.
     */
    protected void onPrepare(int maxFrameCount, int channels) {}

    /**
     * Called during reset(). Override to release internal resources.
     * Default: no-op.
     */
    protected void onReset() {}
}
