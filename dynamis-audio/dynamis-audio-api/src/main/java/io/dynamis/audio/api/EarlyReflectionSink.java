package io.dynamis.audio.api;

/**
 * API seam for per-voice early reflection tap updates.
 *
 * Core code publishes reflection hits through this interface without depending on
 * concrete DSP node classes. DSP implementations consume the hit buffer and update
 * their internal delay taps allocation-free.
 */
public interface EarlyReflectionSink {

    /**
     * Updates active reflection taps from a multi-hit ray result.
     *
     * @param hits reflection hits sorted nearest-first
     */
    void updateReflections(AcousticHitBuffer hits);

    /** Clears all active reflection taps. */
    void clearReflections();

    /** Returns the currently active reflection tap count. */
    int activeReflectionCount();
}
