package io.dynamis.audio.simulation;

/**
 * Computes a scalar reverb wet gain from room acoustics and source distance.
 *
 * Uses a distance-dependent model based on the acoustic critical distance concept:
 * the point at which direct sound and reverberant field are equal in level.
 *
 *   criticalDistance = sqrt(V / (4pi * meanRt60 * c))
 *   wetGain = 1 - exp(-distance / criticalDistance)
 *
 * where:
 *   V           = room volume in m^3
 *   meanRt60    = mean RT60 across all bands, in seconds (clamped to >= MIN_RT60)
 *   c           = speed of sound (343 m/s)
 *   distance    = emitter-to-listener distance in metres
 *
 * BEHAVIOUR:
 *   At distance = 0:         wetGain -> 0   (no reverb at source position)
 *   At distance >> critical: wetGain -> 1   (fully in reverberant field)
 *   At distance = critical:  wetGain ~= 0.63
 *
 * EDGE CASES:
 *   meanRt60 is clamped to MIN_RT60 (1e-3 s) to prevent divide-by-zero in
 *   extremely dry rooms. wetGain is always clamped to [0..1].
 *
 * ALLOCATION CONTRACT: Zero allocation. Pure float arithmetic.
 */
public final class ReverbWetGainCalculator {

    /** Speed of sound in air at ~20C, metres per second. */
    public static final float SPEED_OF_SOUND_MS = 343.0f;

    /**
     * Minimum RT60 in seconds. Prevents divide-by-zero for extremely dry rooms.
     * Applied to meanRt60 before any computation.
     */
    public static final float MIN_RT60 = 1e-3f;

    private ReverbWetGainCalculator() {}

    /**
     * Computes the reverb wet gain for an emitter at a given distance.
     *
     * @param roomVolume  room volume in m^3; must be > 0; clamped internally if <= 0
     * @param meanRt60    mean RT60 in seconds; clamped to >= MIN_RT60
     * @param distance    emitter-to-listener distance in metres; clamped to >= 0
     * @return wet gain in [0..1]
     */
    public static float compute(float roomVolume, float meanRt60, float distance) {
        // Clamp inputs - prevents NaN on the audio thread
        float safeVolume = Math.max(1f, roomVolume);     // floor at 1 m^3
        float safeRt60 = Math.max(MIN_RT60, meanRt60);   // floor at 1 ms
        float safeDist = Math.max(0f, distance);

        // Critical distance: sqrt(V / (4pi * RT60 * c))
        double denom = 4.0 * Math.PI * safeRt60 * SPEED_OF_SOUND_MS;
        float criticalDist = (float) Math.sqrt(safeVolume / denom);

        // Wet gain ramp: 0 at source, approaching 1 in reverberant field
        float wetGain = 1f - (float) Math.exp(-safeDist / criticalDist);

        return Math.max(0f, Math.min(1f, wetGain));
    }

    /**
     * Computes the acoustic critical distance for a room.
     * Exposed for profiler overlay and tuning purposes.
     *
     * @param roomVolume  room volume in m^3
     * @param meanRt60    mean RT60 in seconds; clamped to >= MIN_RT60
     * @return critical distance in metres
     */
    public static float criticalDistance(float roomVolume, float meanRt60) {
        float safeVolume = Math.max(1f, roomVolume);
        float safeRt60 = Math.max(MIN_RT60, meanRt60);
        double denom = 4.0 * Math.PI * safeRt60 * SPEED_OF_SOUND_MS;
        return (float) Math.sqrt(safeVolume / denom);
    }
}
