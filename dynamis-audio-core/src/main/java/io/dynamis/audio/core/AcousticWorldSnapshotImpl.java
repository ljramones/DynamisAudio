package io.dynamis.audio.core;

import io.dynamis.audio.api.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable implementation of AcousticWorldSnapshot.
 *
 * Two instances are maintained by AcousticSnapshotManager in a double-buffer.
 * The game thread mutates the back buffer. The audio thread reads the front buffer.
 * Never expose this type across the audio thread boundary - expose only AcousticWorldSnapshot.
 *
 * THREAD SAFETY: Mutation (game thread) and reads (audio thread) are separated by the
 * VarHandle acquire/release fence in AcousticSnapshotManager. No internal synchronisation
 * is required within this class provided the double-buffer protocol is respected.
 */
public final class AcousticWorldSnapshotImpl implements AcousticWorldSnapshot {

    private long version;
    private long timeNanos;
    private volatile AcousticRayQueryBackend rayBackend;

    // Simple map-backed storage for Phase 0.
    // Phase 2 replaces portals/rooms with spatial index structures.
    private final ConcurrentHashMap<Long, AcousticPortal> portals = new ConcurrentHashMap<>();
    /**
     * Live portal aperture overrides. Written by audio thread when processing
     * PortalStateChanged events. Read by simulation queries.
     * Key = portalId, Value = aperture [0..1].
     * ConcurrentHashMap - audio thread writes, virtual threads read.
     */
    private final ConcurrentHashMap<Long, Float> portalApertureOverrides =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AcousticRoom> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AcousticMaterial> materials = new ConcurrentHashMap<>();

    // -- Mutation API (game thread only) ---------------------------------

    /** Sets the version and timestamp for this snapshot before publishing. */
    public void setVersionAndTime(long version, long timeNanos) {
        this.version = version;
        this.timeNanos = timeNanos;
    }

    /** Registers or replaces a portal in this snapshot. */
    public void putPortal(AcousticPortal portal) {
        portals.put(portal.id(), portal);
    }

    /** Removes a portal by ID. */
    public void removePortal(long portalId) {
        portals.remove(portalId);
    }

    /**
     * Sets a live aperture override for the given portal.
     * Called by the audio thread when processing PortalStateChanged events.
     * Overrides the AcousticPortal.aperture() value returned by portal(id).
     *
     * @param portalId portal identifier; must be non-zero
     * @param aperture aperture [0..1]; clamped internally
     */
    public void setPortalAperture(long portalId, float aperture) {
        float clamped = Math.max(0f, Math.min(1f, aperture));
        portalApertureOverrides.put(portalId, clamped);
    }

    /**
     * Returns the live aperture for the given portal.
     * Returns the override if present; otherwise returns AcousticPortal.aperture()
     * from the portal definition; returns 1.0 (fully open) if portal is unknown.
     *
     * ALLOCATION CONTRACT: ConcurrentHashMap.get() - no allocation.
     */
    public float getPortalAperture(long portalId) {
        Float override = portalApertureOverrides.get(portalId);
        if (override != null) return override;
        AcousticPortal def = portals.get(portalId);
        return def != null ? def.aperture() : 1.0f;
    }

    /**
     * Clears all portal aperture overrides.
     * Called when a full proxy rebuild occurs (GeometryDestroyedEvent).
     */
    public void clearPortalApertureOverrides() {
        portalApertureOverrides.clear();
    }

    /** Registers or replaces a room in this snapshot. */
    public void putRoom(AcousticRoom room) {
        rooms.put(room.id(), room);
    }

    /** Removes a room by ID. */
    public void removeRoom(long roomId) {
        rooms.remove(roomId);
    }

    /** Registers or replaces a material in this snapshot. */
    public void putMaterial(AcousticMaterial material) {
        materials.put(material.id(), material);
    }

    /**
     * Clears all portals, rooms, and materials.
     * Called before repopulating from a full scene rebuild.
     */
    public void clear() {
        portals.clear();
        rooms.clear();
        materials.clear();
    }

    /** Sets the ray query backend used by traceRay and traceRayMulti. */
    public void setRayQueryBackend(AcousticRayQueryBackend backend) {
        this.rayBackend = backend;
    }

    // -- AcousticWorldSnapshot (audio thread read path) ------------------

    @Override public long version()    { return version; }
    @Override public long timeNanos()  { return timeNanos; }

    /**
     * Phase 0 stub: always writes hit=false into outHit.
     * Phase 2 replaces with DynamisCollision ray cast against acoustic proxy geometry.
     *
     * ALLOCATION CONTRACT: writes into pre-allocated outHit - no allocation.
     */
    @Override
    public void traceRay(float ox, float oy, float oz,
                         float dx, float dy, float dz,
                         float maxDistance,
                         AcousticHit outHit) {
        outHit.reset();
        AcousticRayQueryBackend backend = rayBackend;
        if (backend != null) {
            backend.traceRay(ox, oy, oz, dx, dy, dz, maxDistance, outHit);
        }
    }

    /**
     * Phase 0 stub: writes count=0 into outHits.
     * Phase 2 replaces with multi-hit DynamisCollision query.
     */
    @Override
    public int traceRayMulti(float ox, float oy, float oz,
                             float dx, float dy, float dz,
                             float maxDistance,
                             AcousticHitBuffer outHits) {
        outHits.reset();
        AcousticRayQueryBackend backend = rayBackend;
        if (backend != null) {
            return backend.traceRayMulti(ox, oy, oz, dx, dy, dz, maxDistance, outHits);
        }
        return 0;
    }

    @Override
    public AcousticPortal portal(long portalId) {
        return portals.get(portalId);
    }

    @Override
    public AcousticRoom room(long roomId) {
        return rooms.get(roomId);
    }

    @Override
    public AcousticMaterial material(int materialId) {
        return materials.get(materialId);
    }
}
