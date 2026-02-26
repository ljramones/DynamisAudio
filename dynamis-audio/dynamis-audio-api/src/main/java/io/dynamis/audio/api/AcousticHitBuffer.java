package io.dynamis.audio.api;

/**
 * Fixed-capacity pre-allocated buffer for multi-hit ray queries.
 *
 * ALLOCATION CONTRACT: Pre-allocate once at startup. Reuse across all traceRayMulti() calls.
 * Never allocate a new AcousticHitBuffer on the audio thread.
 *
 * Recommended capacity: 4-6 hits. Sufficient for early reflections in games.
 * Capacity > 8 rarely improves perceptual quality and increases CPU cost.
 */
public final class AcousticHitBuffer {

    private final AcousticHit[] hits;
    private int count;

    /**
     * Allocates the buffer with the given fixed capacity.
     * Pre-allocates all AcousticHit slots - no further allocation occurs during use.
     *
     * @param capacity maximum number of hits this buffer can hold; must be >= 1
     * @throws IllegalArgumentException if capacity < 1
     */
    public AcousticHitBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                "AcousticHitBuffer capacity must be >= 1; got " + capacity);
        }
        this.hits = new AcousticHit[capacity];
        for (int i = 0; i < capacity; i++) {
            this.hits[i] = new AcousticHit();
        }
        this.count = 0;
    }

    /** Maximum number of hits this buffer can hold. */
    public int capacity() { return hits.length; }

    /** Number of valid hits populated by the most recent traceRayMulti() call. */
    public int count() { return count; }

    /**
     * Returns the hit at the given index.
     * Valid for indices 0 .. count()-1 after a traceRayMulti() call.
     * No bounds check on the hot path - caller must respect count().
     */
    public AcousticHit get(int index) { return hits[index]; }

    /**
     * Resets the active count to zero and clears all hit slots.
     * Call before passing to traceRayMulti() to prevent stale data.
     */
    public void reset() {
        for (int i = 0; i < hits.length; i++) {
            hits[i].reset();
        }
        count = 0;
    }

    /**
     * Sets the active count. Called by AcousticWorldSnapshot implementations
     * after populating hit slots. Not for caller use.
     *
     * @param count number of valid hits written; must be 0 .. capacity()
     */
    public void setCount(int count) {
        this.count = count;
    }
}
