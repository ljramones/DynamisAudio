package io.dynamis.audio.dsp;

/**
 * A per-voice reverb send node that scales signal by a wet gain factor
 * before routing to the reverb bus.
 *
 * Each physical voice has one ReverbSendNode in its signal chain.
 * The send level is updated each block from EmitterParams.reverbWetGain.
 *
 * SIGNAL FLOW:
 *   Voice source -> ReverbSendNode -> Reverb bus (summed with other sends)
 *                v
 *           Direct bus (dry path, unaffected)
 *
 * ALLOCATION CONTRACT: Zero allocation in processInternal().
 */
public final class ReverbSendNode extends AbstractDspNode {

    /** Current send level [0..1]. Written by mixer, read in processInternal(). */
    private volatile float sendLevel = 0f;

    public ReverbSendNode(String name) {
        super(name);
    }

    /**
     * Sets the reverb send level for the next block.
     * Called by SoftwareMixer after reading EmitterParams.reverbWetGain.
     */
    public void setSendLevel(float level) {
        this.sendLevel = Math.max(0f, Math.min(1f, level));
    }

    public float getSendLevel() { return sendLevel; }

    @Override
    protected void processInternal(float[] inputBuffer, float[] outputBuffer,
                                   int frameCount, int channels) {
        float level = sendLevel;
        int len = frameCount * channels;
        for (int i = 0; i < len; i++) {
            outputBuffer[i] = inputBuffer[i] * level;
        }
    }
}
