package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticHit;
import io.dynamis.audio.api.AcousticHitBuffer;
import io.dynamis.audio.api.AcousticRayQueryBackend;
import io.dynamis.audio.api.AcousticSurfaceType;
import io.dynamis.audio.core.AcousticWorldSnapshotImpl;

/**
 * Brute-force acoustic ray query backend over proxy triangles.
 */
public final class BruteForceRayQueryBackend implements AcousticRayQueryBackend {

    private static final float EPSILON = 1.0e-6f;

    private final AcousticWorldProxy proxy;
    /**
     * Optional snapshot for live portal aperture lookup.
     * If null, portal aperture defaults to AcousticProxyTriangle.portalId presence only.
     * Set via setSnapshot() after construction.
     */
    private volatile AcousticWorldSnapshotImpl snapshot = null;

    public BruteForceRayQueryBackend(AcousticWorldProxy proxy) {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy must not be null");
        }
        this.proxy = proxy;
    }

    /** Sets the snapshot for live portal aperture lookup. May be updated per-block. */
    public void setSnapshot(AcousticWorldSnapshotImpl snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public void traceRay(float ox, float oy, float oz,
                         float dx, float dy, float dz,
                         float maxDistance,
                         AcousticHit outHit) {
        outHit.reset();

        float closest = maxDistance;
        boolean found = false;

        for (int i = 0; i < proxy.triangleCount(); i++) {
            AcousticProxyTriangle tri = proxy.triangle(i);
            float t = intersect(ox, oy, oz, dx, dy, dz, tri);
            if (t >= 0f && t <= closest) {
                closest = t;
                writeHit(outHit, tri, t);
                found = true;
            }
        }

        if (!found) {
            outHit.reset();
        }
    }

    @Override
    public int traceRayMulti(float ox, float oy, float oz,
                             float dx, float dy, float dz,
                             float maxDistance,
                             AcousticHitBuffer outHits) {
        outHits.reset();
        int capacity = outHits.capacity();
        int count = 0;

        for (int i = 0; i < proxy.triangleCount(); i++) {
            AcousticProxyTriangle tri = proxy.triangle(i);
            float t = intersect(ox, oy, oz, dx, dy, dz, tri);
            if (t < 0f || t > maxDistance) {
                continue;
            }

            int insertAt = count;
            while (insertAt > 0 && outHits.get(insertAt - 1).distance > t) {
                insertAt--;
            }

            if (count < capacity) {
                for (int j = count; j > insertAt; j--) {
                    copyHit(outHits.get(j), outHits.get(j - 1));
                }
                writeHit(outHits.get(insertAt), tri, t);
                count++;
            } else if (capacity > 0 && insertAt < capacity) {
                for (int j = capacity - 1; j > insertAt; j--) {
                    copyHit(outHits.get(j), outHits.get(j - 1));
                }
                writeHit(outHits.get(insertAt), tri, t);
            }
        }

        outHits.setCount(count);
        return count;
    }

    private static void copyHit(AcousticHit dst, AcousticHit src) {
        dst.hit = src.hit;
        dst.distance = src.distance;
        dst.nx = src.nx;
        dst.ny = src.ny;
        dst.nz = src.nz;
        dst.materialId = src.materialId;
        dst.portalId = src.portalId;
        dst.roomId = src.roomId;
        dst.portalAperture = src.portalAperture;
        dst.roomBoundary = src.roomBoundary;
    }

    private void writeHit(AcousticHit out, AcousticProxyTriangle tri, float t) {
        out.hit = true;
        out.distance = t;
        writeNormal(out, tri);
        out.materialId = tri.materialId;
        out.portalId = tri.portalId;
        out.roomId = tri.roomId;
        out.portalAperture = 1.0f;
        out.roomBoundary = (tri.surfaceType == AcousticSurfaceType.ROOM_BOUNDARY)
            || (tri.surfaceType == AcousticSurfaceType.PORTAL);

        // Apply portal aperture: if this is a portal hit and aperture < 1.0,
        // the portal is partially closed - adjust the effective hit distance
        // to signal partial blockage. Full propagation model deferred to Phase 3.
        // Phase 2: populate roomBoundary correctly based on surface type.
        if (tri.surfaceType == AcousticSurfaceType.PORTAL) {
            AcousticWorldSnapshotImpl snap = this.snapshot;
            if (snap != null) {
                float aperture = snap.getPortalAperture(tri.portalId);
                // Store aperture as a field on AcousticHit for Phase 3 propagation use.
                // Phase 2: aperture is available but not yet used to modify signal path.
                // PHASE 3: use aperture to blend direct vs occluded path through portal.
                out.portalAperture = aperture;
            }
        }
    }

    private static void writeNormal(AcousticHit out, AcousticProxyTriangle tri) {
        float abx = tri.bx - tri.ax;
        float aby = tri.by - tri.ay;
        float abz = tri.bz - tri.az;

        float acx = tri.cx - tri.ax;
        float acy = tri.cy - tri.ay;
        float acz = tri.cz - tri.az;

        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;

        float lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq > EPSILON) {
            float invLen = 1.0f / (float) Math.sqrt(lenSq);
            out.nx = nx * invLen;
            out.ny = ny * invLen;
            out.nz = nz * invLen;
        } else {
            out.nx = 0f;
            out.ny = 1f;
            out.nz = 0f;
        }
    }

    private static float intersect(float ox, float oy, float oz,
                                   float dx, float dy, float dz,
                                   AcousticProxyTriangle tri) {
        float edge1x = tri.bx - tri.ax;
        float edge1y = tri.by - tri.ay;
        float edge1z = tri.bz - tri.az;

        float edge2x = tri.cx - tri.ax;
        float edge2y = tri.cy - tri.ay;
        float edge2z = tri.cz - tri.az;

        float px = dy * edge2z - dz * edge2y;
        float py = dz * edge2x - dx * edge2z;
        float pz = dx * edge2y - dy * edge2x;

        float det = edge1x * px + edge1y * py + edge1z * pz;
        if (det > -EPSILON && det < EPSILON) {
            return -1f;
        }

        float invDet = 1.0f / det;

        float tx = ox - tri.ax;
        float ty = oy - tri.ay;
        float tz = oz - tri.az;

        float u = (tx * px + ty * py + tz * pz) * invDet;
        if (u < 0.0f || u > 1.0f) {
            return -1f;
        }

        float qx = ty * edge1z - tz * edge1y;
        float qy = tz * edge1x - tx * edge1z;
        float qz = tx * edge1y - ty * edge1x;

        float v = (dx * qx + dy * qy + dz * qz) * invDet;
        if (v < 0.0f || u + v > 1.0f) {
            return -1f;
        }

        float t = (edge2x * qx + edge2y * qy + edge2z * qz) * invDet;
        if (t > EPSILON) {
            return t;
        }
        return -1f;
    }
}
