package io.dynamis.audio.simulation;

/**
 * Immutable acoustic world proxy geometry.
 */
public final class AcousticWorldProxy {

    private final AcousticProxyTriangle[] triangles;
    private final int portalCount;
    private final int roomBoundaryCount;
    private final int ordinaryCount;

    public AcousticWorldProxy(AcousticProxyTriangle[] triangles,
                              int portalCount,
                              int roomBoundaryCount,
                              int ordinaryCount) {
        this.triangles = triangles;
        this.portalCount = portalCount;
        this.roomBoundaryCount = roomBoundaryCount;
        this.ordinaryCount = ordinaryCount;
    }

    public int triangleCount() {
        return triangles.length;
    }

    public int portalCount() {
        return portalCount;
    }

    public int roomBoundaryCount() {
        return roomBoundaryCount;
    }

    public int ordinaryCount() {
        return ordinaryCount;
    }

    public AcousticProxyTriangle triangle(int index) {
        return triangles[index];
    }
}
