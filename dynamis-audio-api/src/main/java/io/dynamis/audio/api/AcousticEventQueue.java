package io.dynamis.audio.api;

/**
 * Lock-free fixed-capacity ring buffer for acoustic topology change events.
 *
 * Published by the game thread. Drained by the audio thread at the start of every DSP block.
 *
 * Overflow policy: coalesce redundant PortalStateChanged events for the same portalId
 * (keep latest aperture). Log all drops via droppedEventCount(). Never drop silently.
 *
 * Capacity is AcousticConstants.EVENT_RING_CAPACITY (1024). Fixed at construction.
 */
public interface AcousticEventQueue {

    /**
     * Drains all available events into the provided buffer, up to buffer.capacity().
     * Called by the audio thread at the start of each DSP block.
     *
     * Sets outEvents.count() to the number of events written.
     * Returns that count - use for profiler overlay.
     *
     * ALLOCATION CONTRACT: Zero allocation on the audio thread drain path.
     *
     * @param outEvents pre-allocated buffer to receive events
     * @return number of events drained; 0 if queue was empty
     */
    int drainTo(AcousticEventBuffer outEvents);

    /**
     * Enqueues an event from the game thread.
     * Non-blocking. If the ring is full, the event may be coalesced or dropped.
     * Drops are reflected in droppedEventCount().
     *
     * @param event the event to enqueue; must not be null
     */
    void enqueue(AcousticEvent event);

    /**
     * Cumulative count of events dropped due to ring buffer overflow.
     * Monotonically increasing. Visible in the profiler overlay.
     * A non-zero value during gameplay indicates the ring capacity is too small
     * or the audio thread is not draining fast enough.
     */
    long droppedEventCount();
}
