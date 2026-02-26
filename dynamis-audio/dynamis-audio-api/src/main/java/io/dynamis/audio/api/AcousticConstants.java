package io.dynamis.audio.api;

/**
 * Global constants for the Dynamis Audio Engine.
 *
 * These values are locked. Changing any of them is a breaking change
 * across the entire engine stack. Do not modify without deliberate
 * cross-system impact analysis.
 *
 * Band model: 8 octave bands per ISO 3382.
 * Fits exactly one AVX256 register (8 x float32) for SIMD DSP.
 */
public final class AcousticConstants {

    private AcousticConstants() {}

    // -- Frequency band model -------------------------------------------------

    /**
     * Number of octave bands. LOCKED at 8.
     * Matches ISO 3382: 63 Hz, 125 Hz, 250 Hz, 500 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz.
     * Fits one AVX256 register. All per-band arrays in the engine are sized to this constant.
     */
    public static final int ACOUSTIC_BAND_COUNT = 8;

    /**
     * Center frequencies for each octave band, in Hz.
     * Index 0 = 63 Hz ... index 7 = 8000 Hz.
     * Defensive copy returned -- callers must not mutate.
     */
    public static final float[] BAND_CENTER_HZ = {
        63f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f
    };

    // -- Reverb estimation ----------------------------------------------------

    /**
     * Sabine room acoustics constant.
     * RT60 = SABINE_CONSTANT * volumeMeters3 / totalAbsorption(band)
     *
     * Valid for spaces where band absorption alpha <= 0.3.
     * Use Eyring correction for high-absorption spaces (alpha > 0.3):
     *   RT60_eyring = SABINE_CONSTANT * V / (-S * ln(1 - alpha_mean))
     */
    public static final double SABINE_CONSTANT = 0.161;

    // -- DSP block model ------------------------------------------------------

    /**
     * Default DSP block size in samples. Phase 0 value.
     * Block duration = DSP_BLOCK_SIZE / SAMPLE_RATE ~= 5.33 ms.
     * Move to 128 samples after the Phase 0 mixer is proven correct.
     */
    public static final int DSP_BLOCK_SIZE = 256;

    /**
     * Audio sample rate in Hz.
     */
    public static final int SAMPLE_RATE = 48_000;

    /**
     * Number of DSP blocks between priority score updates per emitter.
     * Default 4 blocks ~= 21.3 ms at 256-sample blocks - not perceptible as demotion lag.
     * Re-validate if DSP_BLOCK_SIZE moves to 128 (4 blocks = ~10.7 ms).
     */
    public static final int SCORE_UPDATE_BLOCKS = 4;

    // -- Voice budget defaults ------------------------------------------------

    /**
     * Default total physical DSP voice slots. Desktop / high-end PC tier.
     * Override via AcousticConfig at engine initialisation.
     */
    public static final int DEFAULT_PHYSICAL_BUDGET = 64;

    /**
     * Default number of DSP slots reserved exclusively for CRITICAL emitters.
     * Must satisfy: criticalReserve <= physicalBudget / 4 (25% hard invariant).
     * Enforced as a hard error at engine startup - not a warning.
     */
    public static final int DEFAULT_CRITICAL_RESERVE = 8;

    // -- Priority scoring weights ---------------------------------------------

    /** Weight for distance factor in priority score. Must sum to 1.0 with siblings. */
    public static final float W_DISTANCE         = 0.40f;

    /** Weight for designer-assigned importance in priority score. */
    public static final float W_IMPORTANCE       = 0.25f;

    /** Weight for perceived audibility (post-occlusion loudness) in priority score. */
    public static final float W_AUDIBILITY       = 0.20f;

    /** Weight for source velocity factor in priority score. */
    public static final float W_VELOCITY         = 0.15f;

    /**
     * Penalty subtracted from priority score for heavily occluded sources.
     * Not a weight - subtracted after the weighted sum.
     * Default: 0.05f.
     */
    public static final float W_OCCLUSION_PENALTY = 0.05f;

    // -- Hysteresis thresholds ------------------------------------------------

    /**
     * Score must exceed this threshold for a VIRTUAL emitter to be promoted to PHYSICAL.
     * Must be greater than DEMOTE_THRESHOLD - the gap between them is the hysteresis band.
     */
    public static final float PROMOTE_THRESHOLD = 0.55f;

    /**
     * Score must fall below this threshold for a PHYSICAL emitter to be demoted to VIRTUAL.
     */
    public static final float DEMOTE_THRESHOLD  = 0.45f;

    /**
     * Floating-point epsilon for priority score tie-breaking comparisons.
     * Scores within this delta are treated as equal and resolved by deterministic tie-break rules.
     */
    public static final float SCORE_EPSILON = 1e-4f;

    // -- Demotion fade --------------------------------------------------------

    /**
     * Default PHYSICAL -> VIRTUAL demotion fade duration in milliseconds.
     * DSP slot remains reserved and unavailable during the fade.
     * Budget calculations must account for in-fade voices.
     */
    public static final int DEMOTION_FADE_MS = 12;

    // -- Event ring buffer ----------------------------------------------------

    /**
     * Capacity of the acoustic event ring buffer. Must be a power of two.
     * Sized for burst scenarios (explosions, mass geometry destruction).
     */
    public static final int EVENT_RING_CAPACITY = 1_024;

    // -- Validation -----------------------------------------------------------

    /**
     * Called at engine startup. Verifies internal consistency of constants.
     * Throws IllegalStateException if any invariant is violated.
     * This is a hard error - the engine must not start with invalid constants.
     */
    public static void validate() {
        if (ACOUSTIC_BAND_COUNT != BAND_CENTER_HZ.length) {
            throw new IllegalStateException(
                "BAND_CENTER_HZ length " + BAND_CENTER_HZ.length +
                " does not match ACOUSTIC_BAND_COUNT " + ACOUSTIC_BAND_COUNT);
        }
        float weightSum = W_DISTANCE + W_IMPORTANCE + W_AUDIBILITY + W_VELOCITY;
        if (Math.abs(weightSum - 1.0f) > 1e-5f) {
            throw new IllegalStateException(
                "Priority score weights must sum to 1.0; actual sum = " + weightSum);
        }
        if (PROMOTE_THRESHOLD <= DEMOTE_THRESHOLD) {
            throw new IllegalStateException(
                "PROMOTE_THRESHOLD must be > DEMOTE_THRESHOLD for hysteresis to function");
        }
        if (DEFAULT_CRITICAL_RESERVE > DEFAULT_PHYSICAL_BUDGET / 4) {
            throw new IllegalStateException(
                "DEFAULT_CRITICAL_RESERVE exceeds 25% of DEFAULT_PHYSICAL_BUDGET");
        }
        if (Integer.bitCount(EVENT_RING_CAPACITY) != 1) {
            throw new IllegalStateException(
                "EVENT_RING_CAPACITY must be a power of two; actual = " + EVENT_RING_CAPACITY);
        }
    }

    static {
        validate();
    }
}
