package io.dynamis.audio.api;

/**
 * Sealed hierarchy for acoustic topology change events.
 *
 * Events are delivered via AcousticEventQueue - a lock-free fixed-capacity ring buffer.
 * The audio thread drains this queue at the start of every DSP block.
 *
 * Event lane handles topology changes that cannot wait for the next snapshot cycle:
 * portal aperture changes, material hot-swaps, geometry destruction.
 *
 * Ring overflow policy: coalesce redundant events for the same portalId (keep latest
 * aperture only). Log overflow via AcousticEventQueue.droppedEventCount().
 * Never silently discard without telemetry.
 */
public sealed interface AcousticEvent
    permits AcousticEvent.PortalStateChanged,
            AcousticEvent.MaterialOverrideChanged,
            AcousticEvent.GeometryDestroyedEvent {

    /** World time at which this event was enqueued, in nanoseconds. */
    long timeNanos();

    /**
     * A portal's aperture changed - door opened/closed, window state changed.
     * Delivered immediately; does not wait for the next snapshot cycle.
     */
    record PortalStateChanged(
        long timeNanos,
        long portalId,
        float aperture
    ) implements AcousticEvent {}

    /**
     * A surface's acoustic material was overridden by a designer at runtime.
     * The snapshot material table will reflect the change on the next snapshot cycle.
     */
    record MaterialOverrideChanged(
        long timeNanos,
        long entityId,
        int newMaterialId
    ) implements AcousticEvent {}

    /**
     * Geometry was destroyed - wall demolition, destructible environment, etc.
     *
     * WARNING: High-cost rare event. May trigger a full AcousticWorld proxy rebuild
     * on the next snapshot cycle. Handlers must not assume cheap processing.
     * Rate-limit in explosion scenarios to prevent proxy rebuild storms.
     */
    record GeometryDestroyedEvent(
        long timeNanos,
        long geometryId
    ) implements AcousticEvent {}
}
