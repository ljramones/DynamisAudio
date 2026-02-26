package io.dynamis.audio.designer;

/**
 * Constants governing structured concurrency region scope boundaries.
 *
 * REGION SCOPE RULE (locked in Phase 1):
 *   Interior spaces: RegionScope maps 1:1 to AcousticRoom (roomId != 0).
 *   Exterior spaces: RegionScope is a distance sphere centred on the listener.
 *
 * This rule is implemented in VoiceManager's scope topology (Phase 1 completion).
 * Constants here are referenced by VoiceManager and any future region culling logic.
 *
 * Rationale:
 *   AcousticRoom provides the natural grouping for interior spaces - portal propagation,
 *   reverb estimation, and occlusion all operate per-room. The distance sphere fallback
 *   for exterior spaces avoids requiring designers to author acoustic rooms for open-world
 *   geometry while still enabling region-level Loom scope cancellation.
 */
public final class RegionScopeConstants {

    private RegionScopeConstants() {}

    /**
     * The room ID value that indicates an emitter is in exterior (non-room) space.
     * Emitters with roomId == EXTERIOR_ROOM_ID are grouped by distance sphere.
     */
    public static final long EXTERIOR_ROOM_ID = 0L;

    /**
     * Default radius of the exterior distance sphere in metres.
     * Emitters beyond this radius from the listener are eligible for region culling.
     * Configurable via AcousticConfig at engine init - this is the fallback default.
     */
    public static final float DEFAULT_EXTERIOR_CULL_RADIUS_METRES = 50.0f;

    /**
     * Minimum allowed exterior cull radius in metres.
     * Prevents accidental near-zero values that would cull nearly all exterior emitters.
     */
    public static final float MIN_EXTERIOR_CULL_RADIUS_METRES = 10.0f;

    /**
     * Maximum allowed exterior cull radius in metres.
     * Prevents values so large the sphere provides no culling benefit.
     */
    public static final float MAX_EXTERIOR_CULL_RADIUS_METRES = 500.0f;
}
