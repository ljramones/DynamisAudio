package io.dynamis.audio.core;

import io.dynamis.audio.api.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.Map;

/**
 * Lock-free fixed-capacity ring buffer implementation of AcousticEventQueue.
 *
 * Published by the game thread. Drained by the audio thread at the start of every DSP block.
 *
 * ALGORITHM: Single-producer (game thread), single-consumer (audio thread) ring buffer.
 * Head and tail are managed via VarHandle acquire/release - no locks, no CAS loops.
 * Capacity is fixed at construction and must be a power of two for bitmask indexing.
 *
 * OVERFLOW POLICY:
 *   PortalStateChanged events for the same portalId are coalesced - only the latest
 *   aperture value is kept. All other overflow increments droppedEventCount.
 *   Overflow is never silent - droppedEventCount() is visible in the profiler overlay.
 *
 * ALLOCATION CONTRACT:
 *   drainTo() - zero allocation on the audio thread drain path.
 *   enqueue()  - may allocate on the game thread (coalescing map lookup). Acceptable.
 */
public final class AcousticEventQueueImpl implements AcousticEventQueue {

    // -- Ring buffer ----------------------------------------------------------

    private final AcousticEvent[] ring;
    private final int mask; // capacity - 1, for bitmask wraparound

    private static final VarHandle HEAD_VH;
    private static final VarHandle TAIL_VH;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HEAD_VH = lookup.findVarHandle(AcousticEventQueueImpl.class, "head", long.class);
            TAIL_VH = lookup.findVarHandle(AcousticEventQueueImpl.class, "tail", long.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Read cursor. Advanced by the audio thread (consumer) during drainTo(). */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long head = 0L;

    /** Write cursor. Advanced by the game thread (producer) during enqueue(). */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long tail = 0L;

    // -- Coalescing state (game thread only) ---------------------------------

    /**
     * Tracks the ring index of the most recently enqueued PortalStateChanged event
     * per portalId, for coalescing. Game thread only - no synchronisation needed.
     */
    private final Map<Long, Integer> portalEventIndex = new HashMap<>();

    // -- Telemetry ------------------------------------------------------------

    private volatile long droppedEvents = 0L;

    // -- Construction ---------------------------------------------------------

    /**
     * @param capacity ring buffer capacity; must be a power of two and >= 2
     * @throws IllegalArgumentException if capacity is not a power of two or < 2
     */
    public AcousticEventQueueImpl(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                "capacity must be a power of two >= 2; got " + capacity);
        }
        this.ring = new AcousticEvent[capacity];
        this.mask = capacity - 1;
    }

    /** Constructs with default capacity from AcousticConstants. */
    public AcousticEventQueueImpl() {
        this(AcousticConstants.EVENT_RING_CAPACITY);
    }

    // -- AcousticEventQueue ---------------------------------------------------

    /**
     * Drains all available events into the buffer, up to buffer.capacity().
     *
     * ALLOCATION CONTRACT: Zero allocation. Reads volatile head/tail, copies references.
     * Called by the audio thread at the start of every DSP block.
     */
    @Override
    public int drainTo(AcousticEventBuffer outEvents) {
        outEvents.reset();
        int drained = 0;
        int limit = outEvents.capacity();

        long currentHead = (long) HEAD_VH.getAcquire(this);
        long currentTail = (long) TAIL_VH.getAcquire(this);

        while (drained < limit && currentHead < currentTail) {
            int slot = (int) (currentHead & mask);
            AcousticEvent event = ring[slot];
            if (event == null) break; // defensive - should not occur in correct usage
            outEvents.set(drained, event);
            drained++;
            currentHead++;
        }

        outEvents.setCount(drained);
        HEAD_VH.setRelease(this, currentHead);
        return drained;
    }

    /**
     * Enqueues an event from the game thread.
     *
     * PortalStateChanged events for the same portalId are coalesced in-place:
     * if a pending (undrained) event exists for the same portalId, its aperture
     * value is updated rather than enqueuing a new event. This prevents portal
     * flapping from flooding the ring during high-frequency aperture updates.
     *
     * All other overflow increments droppedEventCount() - never silent.
     */
    @Override
    public void enqueue(AcousticEvent event) {
        if (event == null) throw new NullPointerException("event must not be null");

        // Coalescing: PortalStateChanged events with matching portalId
        if (event instanceof AcousticEvent.PortalStateChanged psc) {
            Integer existingSlot = portalEventIndex.get(psc.portalId());
            if (existingSlot != null) {
                // Replace in-place with updated aperture - no new ring slot consumed
                ring[existingSlot] = psc;
                return;
            }
        }

        long currentTail = (long) TAIL_VH.getAcquire(this);
        long currentHead = (long) HEAD_VH.getAcquire(this);

        if (currentTail - currentHead >= ring.length) {
            // Ring full - overflow
            droppedEvents++;
            return;
        }

        int slot = (int) (currentTail & mask);
        ring[slot] = event;

        // Track portalId -> slot for coalescing
        if (event instanceof AcousticEvent.PortalStateChanged psc) {
            portalEventIndex.put(psc.portalId(), slot);
        }

        TAIL_VH.setRelease(this, currentTail + 1);
    }

    @Override
    public long droppedEventCount() {
        return droppedEvents;
    }

    // -- Inspection -----------------------------------------------------------

    /** Number of events currently pending in the ring (approximate - no lock). */
    public int pendingCount() {
        long t = (long) TAIL_VH.getAcquire(this);
        long h = (long) HEAD_VH.getAcquire(this);
        return (int) (t - h);
    }

    /** Ring buffer capacity. */
    public int capacity() {
        return ring.length;
    }
}
