package io.dynamis.audio.simulation;

import io.dynamis.audio.api.AcousticSurfaceType;

/**
 * Immutable acoustic proxy triangle.
 */
public final class AcousticProxyTriangle {

    public final float ax;
    public final float ay;
    public final float az;

    public final float bx;
    public final float by;
    public final float bz;

    public final float cx;
    public final float cy;
    public final float cz;

    public final int materialId;
    public final long portalId;
    public final long roomId;
    public final AcousticSurfaceType surfaceType;

    public AcousticProxyTriangle(float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float cx, float cy, float cz,
                                 int materialId,
                                 long portalId,
                                 long roomId,
                                 AcousticSurfaceType surfaceType) {
        validateFinite("ax", ax);
        validateFinite("ay", ay);
        validateFinite("az", az);
        validateFinite("bx", bx);
        validateFinite("by", by);
        validateFinite("bz", bz);
        validateFinite("cx", cx);
        validateFinite("cy", cy);
        validateFinite("cz", cz);
        if (surfaceType == null) {
            throw new IllegalArgumentException("surfaceType must not be null");
        }
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.materialId = materialId;
        this.portalId = portalId;
        this.roomId = roomId;
        this.surfaceType = surfaceType;
    }

    private static void validateFinite(String name, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite vertex component: " + name);
        }
    }

    /**
     * Surface area of this triangle in m^2.
     *
     * Computed from half the magnitude of AB x AC.
     */
    public float area() {
        float abX = bx - ax;
        float abY = by - ay;
        float abZ = bz - az;
        float acX = cx - ax;
        float acY = cy - ay;
        float acZ = cz - az;

        float crossX = abY * acZ - abZ * acY;
        float crossY = abZ * acX - abX * acZ;
        float crossZ = abX * acY - abY * acX;
        return 0.5f * (float) Math.sqrt(
            crossX * crossX + crossY * crossY + crossZ * crossZ);
    }
}
