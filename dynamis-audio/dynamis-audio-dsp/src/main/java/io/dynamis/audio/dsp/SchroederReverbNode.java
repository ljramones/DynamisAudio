package io.dynamis.audio.dsp;

import io.dynamis.audio.api.AcousticConstants;

/**
 * Schroeder algorithmic reverberator.
 *
 * Classic topology: four parallel comb filters feeding two allpass filters in series.
 * Provides a dense, diffuse reverberation tail suitable for mid-sized rooms.
 *
 * TOPOLOGY:
 *   input -> [comb0 + comb1 + comb2 + comb3] -> allpass0 -> allpass1 -> output
 *
 * COMB FILTER DELAYS (prime-factored to minimise metallic resonance):
 *   1557, 1617, 1491, 1422 samples at 48kHz (~ 32-34ms)
 *
 * ALLPASS FILTER DELAYS:
 *   225, 556 samples at 48kHz (~ 4.7ms, 11.6ms)
 *
 * PARAMETERS:
 *   rt60Seconds    - sets comb filter feedback gain. Default 1.5s.
 *   damping        - high-frequency damping on comb feedback [0..1]. Default 0.5.
 *   wetMix         - output wet/dry ratio [0..1]. Default 1.0 (fully wet send).
 *
 * ALLOCATION CONTRACT:
 *   processInternal() - zero allocation. All delay lines pre-allocated in onPrepare().
 *   setRt60() / setDamping() - no allocation. Update coefficients in-place.
 *
 * PHASE 3 SCOPE:
 *   Mono processing (channel 0 drives all combs; output mixed back to all channels).
 *   Per-channel stereo decorrelation deferred to Phase 4.
 */
public class SchroederReverbNode extends AbstractDspNode {

    // -- Comb filter configuration ------------------------------------------

    private static final int NUM_COMBS = 4;
    private static final int[] COMB_DELAYS = {1557, 1617, 1491, 1422};

    // -- Allpass filter configuration ---------------------------------------

    private static final int NUM_ALLPASS = 2;
    private static final int[] ALLPASS_DELAYS = {225, 556};
    private static final float ALLPASS_GAIN = 0.5f;

    // -- Delay line state (allocated in onPrepare) --------------------------

    private float[][] combBuffers;    // [NUM_COMBS][delay length]
    private int[] combIndices;        // write index per comb
    private float[] combFeedback;     // per-comb feedback coefficient
    private float[] combDampState;    // per-comb damping filter state

    private float[][] allpassBuffers; // [NUM_ALLPASS][delay length]
    private int[] allpassIndices;     // write index per allpass

    // -- Parameters ----------------------------------------------------------

    private volatile float rt60Seconds = 1.5f;
    private volatile float damping = 0.5f;
    private volatile float wetMix = 1.0f;

    // -- Construction --------------------------------------------------------

    public SchroederReverbNode(String name) {
        super(name);
    }

    // -- Parameter API -------------------------------------------------------

    /**
     * Sets the RT60 reverberation time. Updates comb feedback coefficients.
     * @param seconds RT60 in seconds; clamped to [0.01..30]
     */
    public void setRt60(float seconds) {
        this.rt60Seconds = Math.max(0.01f, Math.min(30f, seconds));
        recomputeFeedback();
    }

    /**
     * Sets high-frequency damping on comb filter feedback [0..1].
     * 0 = no damping (bright reverb), 1 = maximum damping (dark reverb).
     */
    public void setDamping(float d) {
        this.damping = Math.max(0f, Math.min(1f, d));
    }

    /**
     * Sets the wet mix level [0..1] applied to the reverb output.
     * Typically 1.0 for a dedicated reverb send bus.
     */
    public void setWetMix(float mix) {
        this.wetMix = Math.max(0f, Math.min(1f, mix));
    }

    public float getRt60() { return rt60Seconds; }
    public float getDamping() { return damping; }
    public float getWetMix() { return wetMix; }

    // -- DspNode lifecycle ---------------------------------------------------

    @Override
    protected void onPrepare(int maxFrameCount, int channels) {
        combBuffers = new float[NUM_COMBS][];
        combIndices = new int[NUM_COMBS];
        combFeedback = new float[NUM_COMBS];
        combDampState = new float[NUM_COMBS];

        for (int i = 0; i < NUM_COMBS; i++) {
            combBuffers[i] = new float[COMB_DELAYS[i]];
        }

        allpassBuffers = new float[NUM_ALLPASS][];
        allpassIndices = new int[NUM_ALLPASS];

        for (int i = 0; i < NUM_ALLPASS; i++) {
            allpassBuffers[i] = new float[ALLPASS_DELAYS[i]];
        }

        recomputeFeedback();
    }

    @Override
    protected void onReset() {
        if (combBuffers != null) {
            for (float[] buf : combBuffers) {
                java.util.Arrays.fill(buf, 0f);
            }
            for (float[] buf : allpassBuffers) {
                java.util.Arrays.fill(buf, 0f);
            }
            java.util.Arrays.fill(combIndices, 0);
            java.util.Arrays.fill(allpassIndices, 0);
            java.util.Arrays.fill(combDampState, 0f);
        }
    }

    // -- Signal processing ---------------------------------------------------

    @Override
    protected void processInternal(float[] inputBuffer, float[] outputBuffer,
                                   int frameCount, int channels) {
        float damp = this.damping;
        float wet = this.wetMix;
        float[] fb = combFeedback;
        float[] dstat = combDampState;

        for (int frame = 0; frame < frameCount; frame++) {
            // Mix input channels to mono for reverb processing
            float input = 0f;
            for (int ch = 0; ch < channels; ch++) {
                input += inputBuffer[frame * channels + ch];
            }
            input /= channels;

            // Parallel comb filters
            float combSum = 0f;
            for (int c = 0; c < NUM_COMBS; c++) {
                float[] buf = combBuffers[c];
                int idx = combIndices[c];
                float delayed = buf[idx];
                // Damped feedback
                dstat[c] = delayed * (1f - damp) + dstat[c] * damp;
                buf[idx] = input + dstat[c] * fb[c];
                combIndices[c] = (idx + 1 < buf.length) ? idx + 1 : 0;
                combSum += delayed;
            }

            // Series allpass filters
            float ap = combSum;
            for (int a = 0; a < NUM_ALLPASS; a++) {
                float[] buf = allpassBuffers[a];
                int idx = allpassIndices[a];
                float delayed = buf[idx];
                float out = -ap + delayed;
                buf[idx] = ap + delayed * ALLPASS_GAIN;
                allpassIndices[a] = (idx + 1 < buf.length) ? idx + 1 : 0;
                ap = out;
            }

            // Write wet output to all channels
            float sample = ap * wet;
            for (int ch = 0; ch < channels; ch++) {
                outputBuffer[frame * channels + ch] = sample;
            }
        }
    }

    // -- Coefficient computation --------------------------------------------

    /**
     * Recomputes comb filter feedback coefficients from current rt60Seconds.
     *
     * Feedback per comb: g = 10^(-3 * delaySeconds / rt60)
     * This gives exactly -60dB attenuation after rt60 seconds.
     */
    private void recomputeFeedback() {
        if (combFeedback == null) {
            return; // not yet prepared
        }
        float rt60 = this.rt60Seconds;
        for (int i = 0; i < NUM_COMBS; i++) {
            float delaySecs = (float) COMB_DELAYS[i] / AcousticConstants.SAMPLE_RATE;
            combFeedback[i] = (float) Math.pow(10.0, -3.0 * delaySecs / rt60);
        }
    }
}
