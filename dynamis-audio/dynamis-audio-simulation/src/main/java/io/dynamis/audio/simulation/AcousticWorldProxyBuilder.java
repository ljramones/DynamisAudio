package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticSurfaceType;

/**
 * Builds AcousticWorldProxy instances from abstract mesh sources.
 */
public final class AcousticWorldProxyBuilder {

    /** Abstract mesh source contract for acoustic proxy construction. */
    public interface MeshSource {
        int surfaceCount();
        MeshSurface surface(int index);
    }

    /** Abstract triangle surface contract for acoustic proxy construction. */
    public interface MeshSurface {
        float ax();
        float ay();
        float az();

        float bx();
        float by();
        float bz();

        float cx();
        float cy();
        float cz();

        int materialId();
        long portalId();
        long roomId();

        boolean isPortal();
        boolean isRoomBoundary();
    }

    /**
     * Functional interface for mapping physics mesh triangles to acoustic metadata.
     */
    @FunctionalInterface
    public interface PhysicsSurfaceTagger {
        MeshSurface tag(int bodyId, int triIndex,
                        float ax, float ay, float az,
                        float bx, float by, float bz,
                        float cx, float cy, float cz);
    }

    public AcousticWorldProxy build(MeshSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        int count = source.surfaceCount();
        AcousticProxyTriangle[] triangles = new AcousticProxyTriangle[count];
        int portalCount = 0;
        int roomBoundaryCount = 0;
        int ordinaryCount = 0;

        for (int i = 0; i < count; i++) {
            MeshSurface s = source.surface(i);
            if (s == null) {
                throw new IllegalArgumentException("surface must not be null at index " + i);
            }

            AcousticSurfaceType type;
            if (s.isPortal()) {
                type = AcousticSurfaceType.PORTAL;
                portalCount++;
            } else if (s.isRoomBoundary()) {
                type = AcousticSurfaceType.ROOM_BOUNDARY;
                roomBoundaryCount++;
            } else {
                type = AcousticSurfaceType.ORDINARY;
                ordinaryCount++;
            }

            triangles[i] = new AcousticProxyTriangle(
                s.ax(), s.ay(), s.az(),
                s.bx(), s.by(), s.bz(),
                s.cx(), s.cy(), s.cz(),
                s.materialId(),
                s.portalId(),
                s.roomId(),
                type
            );
        }

        for (int i = 0; i < triangles.length; i++) {
            AcousticProxyTriangle t = triangles[i];
            if (t.surfaceType == AcousticSurfaceType.PORTAL && t.portalId == 0L) {
                throw new IllegalArgumentException(
                    "PORTAL triangle has reserved portalId 0 at index " + i);
            }
        }

        return new AcousticWorldProxy(triangles, portalCount, roomBoundaryCount, ordinaryCount);
    }

    /**
     * Builds an AcousticWorldProxy directly from a DynamisPhysics world mesh source.
     *
     * TRIANGLE ORDERING CONTRACT:
     *   The proxy triangle order must match the physics ray-query indexing used by
     *   DynamisCollisionRayBackend (`RaycastResult.layer()` -> proxy triangle index).
     *   This implementation is a Phase 5 stub because the public PhysicsWorld API does
     *   not currently expose triangle iteration.
     */
    public AcousticWorldProxy buildFromPhysicsMesh(
            org.dynamisphysics.api.world.PhysicsWorld physicsWorld,
            PhysicsSurfaceTagger tagger) {
        if (physicsWorld == null) {
            throw new NullPointerException("physicsWorld");
        }
        if (tagger == null) {
            throw new NullPointerException("tagger");
        }
        // Phase 5 stub: no public triangle iteration API is currently available on PhysicsWorld.
        // Return an empty proxy while preserving the method contract for future backend wiring.
        return build(new MeshSource() {
            @Override
            public int surfaceCount() { return 0; }
            @Override
            public MeshSurface surface(int index) { throw new IndexOutOfBoundsException(); }
        });
    }
}
