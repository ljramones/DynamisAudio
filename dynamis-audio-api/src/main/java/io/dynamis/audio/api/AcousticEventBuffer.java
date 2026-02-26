package io.dynamis.audio.api;

/**
 * Fixed-capacity pre-allocated buffer for draining acoustic events from the ring.
 *
 * ALLOCATION CONTRACT: Pre-allocate once at startup. Pass to AcousticEventQueue.drainTo()
 * at the start of each DSP block. Never allocate on the audio thread.
 */
public final class AcousticEventBuffer {

    private final AcousticEvent[] events;
    private int count;

    /**
     * Allocates the buffer with the given fixed capacity.
     *
     * @param capacity maximum events this buffer can hold; must be >= 1
     * @throws IllegalArgumentException if capacity < 1
     */
    public AcousticEventBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                "AcousticEventBuffer capacity must be >= 1; got " + capacity);
        }
        this.events = new AcousticEvent[capacity];
        this.count = 0;
    }

    /** Maximum number of events this buffer can hold. */
    public int capacity() { return events.length; }

    /** Number of valid events populated by the most recent drainTo() call. */
    public int count() { return count; }

    /**
     * Returns the event at the given index.
     * Valid for indices 0 .. count()-1 after a drainTo() call.
     */
    public AcousticEvent get(int index) { return events[index]; }

    /**
     * Writes an event into the buffer at the given index.
     * Called by AcousticEventQueue implementations during drainTo(). Not for caller use.
     */
    public void set(int index, AcousticEvent event) { events[index] = event; }

    /**
     * Sets the active count. Called by AcousticEventQueue.drainTo() after populating slots.
     */
    public void setCount(int count) { this.count = count; }

    /** Resets the count to zero. Events are not nulled - they will be overwritten on next drain. */
    public void reset() { this.count = 0; }
}
