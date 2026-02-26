package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticHit;
import io.dynamis.audio.api.AcousticHitBuffer;
import io.dynamis.audio.api.AcousticRayQueryBackend;
import io.dynamis.audio.api.AcousticSurfaceType;
import java.util.Optional;
import org.dynamisphysics.api.query.RaycastResult;
import org.dynamisphysics.api.world.PhysicsWorld;
import org.vectrix.core.Vector3f;

/**
 * AcousticRayQueryBackend implementation backed by DynamisPhysics BVH queries.
 */
public final class DynamisCollisionRayBackend implements AcousticRayQueryBackend {

    private static final int ALL_LAYERS_MASK = -1;
    private static final float FAN_SPREAD_RADIANS = (float) (Math.PI / 6.0);
    private static final float[] FAN_AZIMUTHS = {
        0f,
        (float) (Math.PI / 3.0),
        (float) (2.0 * Math.PI / 3.0),
        (float) Math.PI,
        (float) (4.0 * Math.PI / 3.0),
        (float) (5.0 * Math.PI / 3.0)
    };

    private final PhysicsWorld physicsWorld;
    private final AcousticWorldProxy proxy;
    private final boolean fallbackMode;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    public DynamisCollisionRayBackend(PhysicsWorld physicsWorld, AcousticWorldProxy proxy) {
        if (proxy == null) {
            throw new NullPointerException("proxy must not be null");
        }
        this.physicsWorld = physicsWorld;
        this.proxy = proxy;
        this.fallbackMode = physicsWorld == null;
        if (fallbackMode) {
            System.err.println(
                "[DynamisAudio] DynamisCollisionRayBackend: physicsWorld is null; fallback miss mode.");
        }
    }

    @Override
    public void traceRay(float ox, float oy, float oz,
                         float dx, float dy, float dz,
                         float maxDistance, AcousticHit outHit) {
        outHit.reset();
        if (fallbackMode) {
            return;
        }

        Scratch s = scratch.get();
        s.origin.set(ox, oy, oz);
        normaliseInto(dx, dy, dz, s.direction);

        Optional<RaycastResult> result = physicsWorld.raycastClosest(
            s.origin, s.direction, maxDistance, ALL_LAYERS_MASK);
        if (result.isEmpty()) {
            return;
        }
        populateHit(result.get(), maxDistance, outHit);
    }

    @Override
    public int traceRayMulti(float ox, float oy, float oz,
                             float dx, float dy, float dz,
                             float maxDistance, AcousticHitBuffer outHits) {
        outHits.reset();
        if (fallbackMode) {
            return 0;
        }

        Scratch s = scratch.get();
        s.origin.set(ox, oy, oz);

        if (!normaliseInto(dx, dy, dz, s.baseDir)) {
            return 0;
        }
        buildBasis(s.baseDir, s.right, s.up);

        int maxRays = Math.min(outHits.capacity(), FAN_AZIMUTHS.length);
        int count = 0;
        for (int i = 0; i < maxRays; i++) {
            makeFanDirection(s.baseDir, s.right, s.up, FAN_AZIMUTHS[i], FAN_SPREAD_RADIANS, s.direction);
            Optional<RaycastResult> result = physicsWorld.raycastClosest(
                s.origin, s.direction, maxDistance, ALL_LAYERS_MASK);
            if (result.isEmpty()) {
                continue;
            }
            populateHit(result.get(), maxDistance, outHits.get(count));
            count++;
        }

        outHits.setCount(count);
        insertionSortByDistance(outHits, count);
        return count;
    }

    private void populateHit(RaycastResult result, float maxDistance, AcousticHit hit) {
        hit.hit = true;
        hit.distance = Math.max(0f, result.fraction() * maxDistance);

        Vector3f normal = result.normal();
        hit.nx = normal.x();
        hit.ny = normal.y();
        hit.nz = normal.z();
        normaliseHitNormal(hit);

        // Phase 3 mapping note: raycastClosest exposes layer/material/body rather than
        // direct triangle id; layer is used here as proxy triangle index.
        // ASSUMPTION: physics mesh triangle order and acoustic proxy triangle order are identical.
        // This must be preserved by AcousticWorldProxyBuilder.buildFromPhysicsMesh() in Phase 4.
        int triangleIdx = result.layer();
        if (triangleIdx >= 0 && triangleIdx < proxy.triangleCount()) {
            AcousticProxyTriangle tri = proxy.triangle(triangleIdx);
            hit.materialId = tri.materialId;
            hit.portalId = tri.portalId;
            hit.roomId = tri.roomId;
            hit.roomBoundary = (tri.surfaceType == AcousticSurfaceType.ROOM_BOUNDARY)
                || (tri.surfaceType == AcousticSurfaceType.PORTAL);
        }
    }

    private static void insertionSortByDistance(AcousticHitBuffer buf, int count) {
        for (int i = 1; i < count; i++) {
            int j = i;
            while (j > 0 && buf.get(j - 1).distance > buf.get(j).distance) {
                swapHits(buf.get(j - 1), buf.get(j));
                j--;
            }
        }
    }

    private static void swapHits(AcousticHit a, AcousticHit b) {
        boolean tHit = a.hit; a.hit = b.hit; b.hit = tHit;
        float tDist = a.distance; a.distance = b.distance; b.distance = tDist;
        float tNx = a.nx; a.nx = b.nx; b.nx = tNx;
        float tNy = a.ny; a.ny = b.ny; b.ny = tNy;
        float tNz = a.nz; a.nz = b.nz; b.nz = tNz;
        int tMat = a.materialId; a.materialId = b.materialId; b.materialId = tMat;
        long tPortal = a.portalId; a.portalId = b.portalId; b.portalId = tPortal;
        long tRoom = a.roomId; a.roomId = b.roomId; b.roomId = tRoom;
        boolean tRoomBoundary = a.roomBoundary; a.roomBoundary = b.roomBoundary; b.roomBoundary = tRoomBoundary;
        float tAperture = a.portalAperture; a.portalAperture = b.portalAperture; b.portalAperture = tAperture;
    }

    private static void normaliseHitNormal(AcousticHit hit) {
        float lenSq = hit.nx * hit.nx + hit.ny * hit.ny + hit.nz * hit.nz;
        if (lenSq > 1.0e-12f) {
            float invLen = 1.0f / (float) Math.sqrt(lenSq);
            hit.nx *= invLen;
            hit.ny *= invLen;
            hit.nz *= invLen;
        } else {
            hit.nx = 0f;
            hit.ny = 1f;
            hit.nz = 0f;
        }
    }

    private static boolean normaliseInto(float x, float y, float z, Vector3f out) {
        float lenSq = x * x + y * y + z * z;
        if (lenSq <= 1.0e-12f) {
            out.set(0f, 0f, 1f);
            return false;
        }
        float invLen = 1.0f / (float) Math.sqrt(lenSq);
        out.set(x * invLen, y * invLen, z * invLen);
        return true;
    }

    private static void buildBasis(Vector3f baseDir, Vector3f right, Vector3f up) {
        float ax = Math.abs(baseDir.x());
        float ay = Math.abs(baseDir.y());
        float az = Math.abs(baseDir.z());

        if (ax <= ay && ax <= az) {
            right.set(0f, -baseDir.z(), baseDir.y());
        } else if (ay <= az) {
            right.set(baseDir.z(), 0f, -baseDir.x());
        } else {
            right.set(-baseDir.y(), baseDir.x(), 0f);
        }
        right.normalize();
        up.set(baseDir).cross(right).normalize();
    }

    private static void makeFanDirection(Vector3f baseDir, Vector3f right, Vector3f up,
                                         float azimuth, float spread, Vector3f outDir) {
        float cosA = (float) Math.cos(azimuth);
        float sinA = (float) Math.sin(azimuth);
        float rx = right.x() * cosA + up.x() * sinA;
        float ry = right.y() * cosA + up.y() * sinA;
        float rz = right.z() * cosA + up.z() * sinA;

        float cosS = (float) Math.cos(spread);
        float sinS = (float) Math.sin(spread);
        outDir.set(
            baseDir.x() * cosS + rx * sinS,
            baseDir.y() * cosS + ry * sinS,
            baseDir.z() * cosS + rz * sinS
        ).normalize();
    }

    private static final class Scratch {
        final Vector3f origin = new Vector3f();
        final Vector3f direction = new Vector3f();
        final Vector3f baseDir = new Vector3f();
        final Vector3f right = new Vector3f();
        final Vector3f up = new Vector3f();
    }
}
