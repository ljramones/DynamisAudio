package io.dynamis.audio.dsp;

import io.dynamis.audio.api.AcousticConstants;

/**
 * 8-band parametric equaliser aligned to the acoustic octave band model.
 *
 * Band count is fixed at AcousticConstants.ACOUSTIC_BAND_COUNT (8).
 * Each band implements a second-order biquad filter (peak/shelf/lowpass/highpass).
 * The 8-band layout matches the acoustic simulation frequency bands exactly,
 * enabling direct mapping from per-band occlusion coefficients to EQ gains.
 *
 * BIQUAD IMPLEMENTATION:
 *   Direct Form II Transposed - numerically stable, 2 state variables per band.
 *   Coefficients: b0, b1, b2 (numerator), a1, a2 (denominator, a0 normalised to 1).
 *   Per-channel state: z1[band][ch], z2[band][ch].
 *
 * ALLOCATION CONTRACT:
 *   processInternal() - zero allocation. All state arrays pre-allocated in onPrepare().
 *   setBandGainDb() - no allocation. Updates coefficients in-place.
 *
 * PHASE 0 SCOPE:
 *   Peak filters at each octave band centre frequency.
 *   Bandwidth (Q) fixed at 1.0 per band.
 *   Full parametric filter type selection (shelf, pass, notch) deferred to Phase 1+.
 */
public final class EqNode extends AbstractDspNode {

    private static final int BANDS = AcousticConstants.ACOUSTIC_BAND_COUNT;
    private static final float SAMPLE_RATE = AcousticConstants.SAMPLE_RATE;

    // -- Per-band biquad coefficients ----------------------------------------

    // Each array has BANDS entries; indexed by band 0..7
    private final float[] b0 = new float[BANDS];
    private final float[] b1 = new float[BANDS];
    private final float[] b2 = new float[BANDS];
    private final float[] a1 = new float[BANDS];
    private final float[] a2 = new float[BANDS];

    // -- Per-band, per-channel filter state ----------------------------------

    // Allocated in onPrepare() once channel count is known
    // z1[band][channel], z2[band][channel]
    private float[][] z1;
    private float[][] z2;

    // -- Band gain parameters (dB) -------------------------------------------

    private final float[] bandGainDb = new float[BANDS]; // 0.0 = unity

    // -- Construction ---------------------------------------------------------

    public EqNode(String name) {
        super(name);
        // Initialise all bands to unity (0 dB)
        java.util.Arrays.fill(bandGainDb, 0f);
        for (int band = 0; band < BANDS; band++) {
            computeCoefficients(band);
        }
    }

    // -- Designer API ---------------------------------------------------------

    /**
     * Sets the gain for the given octave band in decibels.
     * Positive = boost, negative = cut. Range typically [-24..+24] dB.
     * Updates biquad coefficients immediately - effective from the next block.
     *
     * Called from designer/game thread.
     *
     * @param band    index 0..ACOUSTIC_BAND_COUNT-1
     * @param gainDb  gain in decibels
     */
    public void setBandGainDb(int band, float gainDb) {
        if (band < 0 || band >= BANDS) {
            throw new IllegalArgumentException("band index out of range: " + band);
        }
        bandGainDb[band] = gainDb;
        computeCoefficients(band);
    }

    /** Returns the current gain in dB for the given band. */
    public float getBandGainDb(int band) {
        return bandGainDb[band];
    }

    /**
     * Sets all 8 band gains from an array of dB values.
     * Array must have length >= ACOUSTIC_BAND_COUNT.
     *
     * Convenience method for applying per-band occlusion from the acoustic simulation.
     */
    public void setBandGains(float[] gainDbPerBand) {
        for (int i = 0; i < BANDS && i < gainDbPerBand.length; i++) {
            bandGainDb[i] = gainDbPerBand[i];
            computeCoefficients(i);
        }
    }

    /**
     * Maximum gain cut applied at full occlusion (1.0). In dB.
     * At occlusion=1.0, band gain = MAX_OCCLUSION_CUT_DB (inaudible).
     * At occlusion=0.0, band gain = 0 dB (unity).
     */
    public static final float MAX_OCCLUSION_CUT_DB = -60.0f;

    /**
     * Maps per-band occlusion values [0..1] to EQ gain cuts and applies them.
     *
     * For each band:
     *   gainDb = occlusion[band] * MAX_OCCLUSION_CUT_DB
     *
     * This is the primary path for acoustic occlusion affecting the mixed signal.
     * Called by SoftwareMixer on each physical voice after EmitterParams are read.
     *
     * ALLOCATION CONTRACT: Zero allocation. Calls setBandGainDb() per band.
     *
     * @param occlusionPerBand float[ACOUSTIC_BAND_COUNT] with values in [0..1]
     */
    public void applyOcclusionPerBand(float[] occlusionPerBand) {
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            float occlusion = Math.max(0f, Math.min(1f, occlusionPerBand[band]));
            setBandGainDb(band, occlusion * MAX_OCCLUSION_CUT_DB);
        }
    }

    // -- DspNode lifecycle ----------------------------------------------------

    @Override
    protected void onPrepare(int maxFrameCount, int channels) {
        z1 = new float[BANDS][channels];
        z2 = new float[BANDS][channels];
    }

    @Override
    protected void onReset() {
        if (z1 != null) {
            for (int b = 0; b < BANDS; b++) {
                java.util.Arrays.fill(z1[b], 0f);
                java.util.Arrays.fill(z2[b], 0f);
            }
        }
    }

    // -- Signal processing ----------------------------------------------------

    @Override
    protected void processInternal(float[] inputBuffer, float[] outputBuffer,
                                   int frameCount, int channels) {
        // Copy input to output - then apply each band filter in-place on output
        System.arraycopy(inputBuffer, 0, outputBuffer, 0, frameCount * channels);

        for (int band = 0; band < BANDS; band++) {
            if (Math.abs(bandGainDb[band]) < 0.01f) {
                continue; // skip near-unity bands
            }

            float cb0 = b0[band], cb1 = b1[band], cb2 = b2[band];
            float ca1 = a1[band], ca2 = a2[band];

            for (int ch = 0; ch < channels; ch++) {
                float sz1 = z1[band][ch];
                float sz2 = z2[band][ch];

                for (int frame = 0; frame < frameCount; frame++) {
                    int idx = frame * channels + ch;
                    float x = outputBuffer[idx];
                    // Direct Form II Transposed
                    float y = cb0 * x + sz1;
                    sz1 = cb1 * x - ca1 * y + sz2;
                    sz2 = cb2 * x - ca2 * y;
                    outputBuffer[idx] = y;
                }

                z1[band][ch] = sz1;
                z2[band][ch] = sz2;
            }
        }
    }

    // -- Coefficient computation ----------------------------------------------

    /**
     * Computes biquad peak filter coefficients for the given band using the
     * Audio EQ Cookbook formula (Robert Bristow-Johnson).
     *
     * Peak EQ:
     *   H(s) = (s^2 + s*(A/Q) + 1) / (s^2 + s/(A*Q) + 1)
     * where A = 10^(dBgain/40), Q = 1.0 (fixed bandwidth).
     */
    private void computeCoefficients(int band) {
        float freq = AcousticConstants.BAND_CENTER_HZ[band];
        float dBgain = bandGainDb[band];
        float Q = 1.0f; // fixed bandwidth

        double A = Math.pow(10.0, dBgain / 40.0);
        double w0 = 2.0 * Math.PI * freq / SAMPLE_RATE;
        double cosw0 = Math.cos(w0);
        double sinw0 = Math.sin(w0);
        double alpha = sinw0 / (2.0 * Q);

        double b0v = 1.0 + alpha * A;
        double b1v = -2.0 * cosw0;
        double b2v = 1.0 - alpha * A;
        double a0v = 1.0 + alpha / A;
        double a1v = -2.0 * cosw0;
        double a2v = 1.0 - alpha / A;

        // Normalise by a0
        b0[band] = (float) (b0v / a0v);
        b1[band] = (float) (b1v / a0v);
        b2[band] = (float) (b2v / a0v);
        a1[band] = (float) (a1v / a0v);
        a2[band] = (float) (a2v / a0v);
    }
}
