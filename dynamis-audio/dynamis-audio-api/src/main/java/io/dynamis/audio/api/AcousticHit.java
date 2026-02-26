package io.dynamis.audio.api;

/**
 * Mutable result object for a single acoustic ray query.
 *
 * ALLOCATION CONTRACT: Pre-allocate one instance per call site on the audio thread.
 * Pass by reference into traceRay(). Never allocate new AcousticHit on the audio thread.
 *
 * Valhalla candidate: flagged for migration to value class when JDK 25 preview
 * value types are stabilised. Until then, caller-allocated mutable object.
 */
public final class AcousticHit {

    /** True if the ray intersected geometry within maxDistance. */
    public boolean hit;

    /** Distance from ray origin to intersection point, in metres. */
    public float distance;

    /** Surface normal at intersection point - X component. */
    public float nx;

    /** Surface normal at intersection point - Y component. */
    public float ny;

    /** Surface normal at intersection point - Z component. */
    public float nz;

    /** Material ID of the intersected surface. 0 if no hit or unknown. */
    public int materialId;

    /** Portal ID of the intersected surface. 0 if surface is not a portal. */
    public long portalId;

    /** Room ID of the intersected surface. 0 if unknown or exterior. */
    public long roomId;

    /**
     * Portal aperture at the time of this hit, if this hit surface is a PORTAL.
     * Range [0..1]. 1.0 = fully open, 0.0 = fully closed.
     * Only meaningful when isPortalSurface() returns true.
     * Set to 1.0 on reset() and on non-portal hits.
     * Phase 3: used to blend portal transmission vs occlusion path.
     */
    public float portalAperture = 1.0f;

    /**
     * True if this surface marks a room boundary.
     * Room boundary surfaces require edge-diffraction evaluation.
     */
    public boolean roomBoundary;

    /** Returns true if this hit surface is a portal surface. */
    public boolean isPortalSurface() { return portalId != 0; }

    /**
     * Returns true if this hit surface marks a room boundary.
     * Convenience accessor - avoids roomBoundary field check scattered across call sites.
     */
    public boolean isRoomBoundary() { return roomBoundary; }

    /**
     * Resets all fields to default values.
     * Call before reuse to prevent stale data from a previous query.
     */
    public void reset() {
        hit = false;
        distance = 0f;
        nx = 0f;
        ny = 0f;
        nz = 0f;
        materialId = 0;
        portalId = 0L;
        roomId = 0L;
        portalAperture = 1.0f;
        roomBoundary = false;
    }
}
