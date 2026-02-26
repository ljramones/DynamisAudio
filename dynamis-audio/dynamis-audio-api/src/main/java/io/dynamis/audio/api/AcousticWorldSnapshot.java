package io.dynamis.audio.api;

/**
 * An immutable, versioned read-only view of the acoustic scene.
 *
 * Published by the game thread via AcousticSnapshotManager at 30-60 Hz.
 * Consumed by the audio thread. No locking required on the consumer path.
 *
 * ALLOCATION CONTRACT: All query methods are zero-allocation on the audio thread.
 * Callers must pre-allocate AcousticHit and AcousticHitBuffer before calling.
 */
public interface AcousticWorldSnapshot {

    /**
     * Monotonically increasing version counter.
     * Audio thread can detect stale snapshots by comparing consecutive versions.
     */
    long version();

    /** World time at which this snapshot was published, in nanoseconds. */
    long timeNanos();

    /**
     * Single-hit ray cast against acoustic proxy geometry.
     *
     * ALLOCATION CONTRACT: outHit must be pre-allocated by the caller.
     * This method writes into outHit - it does not allocate.
     * Call outHit.reset() before each invocation to prevent stale data.
     *
     * @param ox ray origin X, world space metres
     * @param oy ray origin Y, world space metres
     * @param oz ray origin Z, world space metres
     * @param dx ray direction X, must be normalised
     * @param dy ray direction Y, must be normalised
     * @param dz ray direction Z, must be normalised
     * @param maxDistance maximum ray travel distance in metres
     * @param outHit pre-allocated result object; populated on return
     */
    void traceRay(float ox, float oy, float oz,
                  float dx, float dy, float dz,
                  float maxDistance,
                  AcousticHit outHit);

    /**
     * Multi-hit ray cast against acoustic proxy geometry.
     *
     * Returns the number of hits written into outHits (0..outHits.capacity()).
     * Recommended max hits: 4-6 for early reflections. Capacity > 8 is rarely beneficial.
     *
     * ALLOCATION CONTRACT: outHits must be pre-allocated by the caller.
     * Call outHits.reset() before each invocation.
     *
     * @param ox ray origin X, world space metres
     * @param oy ray origin Y, world space metres
     * @param oz ray origin Z, world space metres
     * @param dx ray direction X, must be normalised
     * @param dy ray direction Y, must be normalised
     * @param dz ray direction Z, must be normalised
     * @param maxDistance maximum ray travel distance in metres
     * @param outHits pre-allocated hit buffer; populated on return
     * @return number of hits written; 0 if no intersections
     */
    int traceRayMulti(float ox, float oy, float oz,
                      float dx, float dy, float dz,
                      float maxDistance,
                      AcousticHitBuffer outHits);

    /**
     * Returns the portal with the given ID, or null if not present in this snapshot.
     * Null indicates the portal was destroyed or not yet registered.
     */
    AcousticPortal portal(long portalId);

    /**
     * Returns the room with the given ID, or null if not present in this snapshot.
     */
    AcousticRoom room(long roomId);

    /**
     * Returns the material with the given ID, or null if not registered.
     * Audio subsystems that cache resolved materials must invalidate when
     * AcousticMaterialRegistry.generation() changes.
     */
    AcousticMaterial material(int materialId);
}
