package io.dynamis.audio.dsp;

/**
 * Feed-forward RMS dynamic range compressor.
 *
 * Reduces gain when the signal level exceeds a threshold, with configurable
 * ratio, attack, release, and makeup gain.
 *
 * ALGORITHM:
 *   1. RMS level detection over a sliding window (approximated with exponential average).
 *   2. Gain computation in dB: gain = threshold + (levelDb - threshold) / ratio.
 *   3. Attack/release envelope on the gain reduction signal.
 *   4. Apply gain reduction x makeup gain to output.
 *
 * PARAMETERS:
 *   thresholdDb  dB level above which compression begins. Default -12 dB.
 *   ratio        compression ratio (e.g. 4.0 = 4:1). Default 4.0.
 *   attackMs     time to reach full compression after threshold crossing. Default 10ms.
 *   releaseMs    time to return to unity after signal drops below threshold. Default 100ms.
 *   makeupGainDb Post-compression gain boost to restore perceived loudness. Default 0 dB.
 *
 * ALLOCATION CONTRACT:
 *   processInternal() - zero allocation. All state is primitive fields.
 *   setters          - no allocation. Update coefficients in-place.
 */
public final class CompressorNode extends AbstractDspNode {

    private static final float SAMPLE_RATE = io.dynamis.audio.api.AcousticConstants.SAMPLE_RATE;

    // -- Parameters (written by designer thread, read by DSP thread) ----------

    private volatile float thresholdDb = -12.0f;
    private volatile float ratio = 4.0f;
    private volatile float attackMs = 10.0f;
    private volatile float releaseMs = 100.0f;
    private volatile float makeupGainDb = 0.0f;

    // -- Derived coefficients (recomputed on parameter change) ----------------

    private float attackCoeff;
    private float releaseCoeff;
    private float makeupGainLinear;

    // -- State ----------------------------------------------------------------

    /** Exponential moving average of squared signal - used for RMS estimation. */
    private float rmsSquared = 0f;

    /** Current gain reduction applied to the signal, in linear scale. */
    private float gainReduction = 1.0f;

    // -- Construction ---------------------------------------------------------

    public CompressorNode(String name) {
        super(name);
        recomputeCoefficients();
    }

    // -- Parameter setters ----------------------------------------------------

    public void setThresholdDb(float db) { this.thresholdDb = db; recomputeCoefficients(); }
    public void setRatio(float r) { this.ratio = Math.max(1f, r); recomputeCoefficients(); }
    public void setAttackMs(float ms) { this.attackMs = Math.max(0.1f, ms); recomputeCoefficients(); }
    public void setReleaseMs(float ms) { this.releaseMs = Math.max(1f, ms); recomputeCoefficients(); }
    public void setMakeupGainDb(float db) { this.makeupGainDb = db; recomputeCoefficients(); }

    public float getThresholdDb() { return thresholdDb; }
    public float getRatio() { return ratio; }
    public float getAttackMs() { return attackMs; }
    public float getReleaseMs() { return releaseMs; }
    public float getMakeupGainDb() { return makeupGainDb; }

    /** Current gain reduction in dB (0 = no reduction). Useful for profiler overlay. */
    public float getGainReductionDb() {
        return gainReduction <= 0f ? -90f : (float) (20.0 * Math.log10(gainReduction));
    }

    // -- DspNode lifecycle ----------------------------------------------------

    @Override
    protected void onReset() {
        rmsSquared = 0f;
        gainReduction = 1.0f;
    }

    // -- Signal processing ----------------------------------------------------

    @Override
    protected void processInternal(float[] inputBuffer, float[] outputBuffer,
                                   int frameCount, int channels) {
        float ac = attackCoeff;
        float rc = releaseCoeff;
        float tDb = thresholdDb;
        float rt = ratio;
        float mg = makeupGainLinear;
        float rmsS = rmsSquared;
        float gr = gainReduction;

        for (int frame = 0; frame < frameCount; frame++) {
            // Sum squared samples across channels for this frame
            float sumSq = 0f;
            for (int ch = 0; ch < channels; ch++) {
                float s = inputBuffer[frame * channels + ch];
                sumSq += s * s;
            }
            float framePower = sumSq / channels;

            // Exponential RMS approximation
            rmsS += (framePower - rmsS) * 0.001f;

            // Level in dB
            float levelDb = rmsS > 1e-10f
                ? (float) (10.0 * Math.log10(rmsS))
                : -100f;

            // Gain computation
            float targetGr;
            if (levelDb > tDb) {
                float gainDb = tDb + (levelDb - tDb) / rt - levelDb;
                targetGr = (float) Math.pow(10.0, gainDb / 20.0);
            } else {
                targetGr = 1.0f;
            }

            // Attack / release envelope
            if (targetGr < gr) {
                gr += (targetGr - gr) * ac; // attack: fast
            } else {
                gr += (targetGr - gr) * rc; // release: slow
            }

            // Apply gain reduction + makeup to all channels
            for (int ch = 0; ch < channels; ch++) {
                int idx = frame * channels + ch;
                outputBuffer[idx] = inputBuffer[idx] * gr * mg;
            }
        }

        rmsSquared = rmsS;
        gainReduction = gr;
    }

    // -- Coefficient computation ----------------------------------------------

    private void recomputeCoefficients() {
        // Attack/release as exponential time constants
        // coeff = 1 - exp(-1 / (timeInSamples))
        float attackSamples = attackMs / 1000f * SAMPLE_RATE;
        float releaseSamples = releaseMs / 1000f * SAMPLE_RATE;
        attackCoeff = 1f - (float) Math.exp(-1.0 / attackSamples);
        releaseCoeff = 1f - (float) Math.exp(-1.0 / releaseSamples);
        makeupGainLinear = (float) Math.pow(10.0, makeupGainDb / 20.0);
    }
}
