package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticRoom;

/**
 * Stateless utility for estimating per-band RT60 reverberation time.
 *
 * Implements both Sabine and Eyring formulas and selects between them at runtime
 * based on the mean absorption coefficient across all bands.
 *
 * FORMULA SELECTION:
 *   Mean alpha = sum(totalAbsorption(band)) / ACOUSTIC_BAND_COUNT / surfaceArea
 *   If mean alpha > EYRING_THRESHOLD -> Eyring (more accurate for absorptive rooms)
 *   Otherwise -> Sabine (accurate for lightly absorptive rooms, alpha <= 0.3)
 *
 * SABINE:  RT60 = SABINE_CONSTANT * V / (S * alpha)
 * EYRING:  RT60 = SABINE_CONSTANT * V / (-S * ln(1 - alpha))
 *
 * where V = room volume (m^3), S = total surface area (m^2), alpha = per-band mean absorption.
 * SABINE_CONSTANT = 0.161 (from AcousticConstants).
 *
 * ALLOCATION CONTRACT: Zero allocation. All outputs written into caller-supplied arrays.
 *
 * NOTE: AcousticRoom.totalAbsorption(band) returns the product S*alpha (sabins) for that band,
 * not alpha alone. The estimator uses it directly - no surface area multiplication needed.
 */
public final class ReverbEstimator {

    /**
     * Mean absorption threshold above which Eyring formula is used.
     * Below this threshold Sabine is sufficiently accurate.
     */
    public static final float EYRING_THRESHOLD = 0.3f;

    /**
     * Minimum RT60 value in seconds. Prevents divide-by-zero in wet gain calculation
     * for extremely dry or zero-volume rooms.
     */
    public static final float MIN_RT60_SECONDS = 0.01f;

    /**
     * Maximum RT60 value in seconds. Caps unrealistic values for empty/huge rooms.
     */
    public static final float MAX_RT60_SECONDS = 30.0f;

    private ReverbEstimator() {}

    /**
     * Computes per-band RT60 estimates for the given room.
     *
     * Writes ACOUSTIC_BAND_COUNT RT60 values (seconds) into outRt60.
     * Values are clamped to [MIN_RT60_SECONDS .. MAX_RT60_SECONDS].
     *
     * ALLOCATION CONTRACT: Zero allocation.
     *
     * @param room      the acoustic room; must not be null
     * @param outRt60   pre-allocated float[ACOUSTIC_BAND_COUNT]; overwritten with result
     */
    public static void computeRt60(AcousticRoom room, float[] outRt60) {
        float volume = room.volumeMeters3();
        if (volume <= 0f) {
            java.util.Arrays.fill(outRt60, MIN_RT60_SECONDS);
            return;
        }

        // Compute mean absorption across all bands to select formula
        float totalAbsorption = 0f;
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            totalAbsorption += room.totalAbsorption(band);
        }
        float meanSabins = totalAbsorption / AcousticConstants.ACOUSTIC_BAND_COUNT;
        float surfaceArea = Math.max(1f, room.surfaceAreaMeters2());
        float meanAlpha = (surfaceArea > 0f) ? meanSabins / surfaceArea : 0f;

        boolean useEyring = meanAlpha > EYRING_THRESHOLD;

        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            float sabins = room.totalAbsorption(band);
            float rt60;
            if (sabins <= 0f) {
                rt60 = MAX_RT60_SECONDS;
            } else if (useEyring) {
                // Eyring: estimate alpha for this band
                float bandSurface = surfaceArea;
                float alpha = Math.min(0.9999f, sabins / bandSurface);
                double lnTerm = Math.log(1.0 - alpha);
                rt60 = (float) (AcousticConstants.SABINE_CONSTANT * volume
                    / (-bandSurface * lnTerm));
            } else {
                // Sabine: RT60 = 0.161 * V / sabins
                rt60 = (float) (AcousticConstants.SABINE_CONSTANT * volume / sabins);
            }
            outRt60[band] = Math.max(MIN_RT60_SECONDS,
                Math.min(MAX_RT60_SECONDS, rt60));
        }
    }

    /**
     * Computes a single mean RT60 value (average across all bands).
     * Convenience method for callers that need one scalar RT60 estimate.
     *
     * ALLOCATION CONTRACT: Zero allocation - uses caller-supplied scratch buffer.
     *
     * @param room      the acoustic room
     * @param scratch   pre-allocated float[ACOUSTIC_BAND_COUNT] scratch buffer
     * @return mean RT60 in seconds, clamped to [MIN_RT60_SECONDS .. MAX_RT60_SECONDS]
     */
    public static float computeMeanRt60(AcousticRoom room, float[] scratch) {
        computeRt60(room, scratch);
        float sum = 0f;
        for (float v : scratch) {
            sum += v;
        }
        return sum / scratch.length;
    }

    /**
     * Returns true if Eyring formula would be selected for the given mean absorption.
     * Exposed for testing and profiler overlay display.
     */
    public static boolean isEyringApplicable(float meanAlpha) {
        return meanAlpha > EYRING_THRESHOLD;
    }
}
