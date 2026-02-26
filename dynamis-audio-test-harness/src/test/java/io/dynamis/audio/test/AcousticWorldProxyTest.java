package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticSurfaceType;
import io.dynamis.audio.simulation.AcousticProxyTriangle;
import io.dynamis.audio.simulation.AcousticWorldProxy;
import io.dynamis.audio.simulation.AcousticWorldProxyBuilder;
import io.dynamis.audio.simulation.BruteForceRayQueryBackend;
import io.dynamis.audio.api.AcousticHit;
import io.dynamis.audio.api.AcousticHitBuffer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AcousticWorldProxyTest {

    @Test
    void builderMapsSurfaceTypesAndCounts() {
        AcousticWorldProxyBuilder builder = new AcousticWorldProxyBuilder();
        AcousticWorldProxy proxy = builder.build(source(
            surface(0, 0, 5, 1, 0, 5, 0, 1, 5, 7, 0L, 11L, false, false),
            surface(0, 0, 10, 1, 0, 10, 0, 1, 10, 8, 42L, 12L, true, false),
            surface(0, 0, 15, 1, 0, 15, 0, 1, 15, 9, 0L, 13L, false, true)
        ));

        assertThat(proxy.triangleCount()).isEqualTo(3);
        assertThat(proxy.portalCount()).isEqualTo(1);
        assertThat(proxy.roomBoundaryCount()).isEqualTo(1);
        assertThat(proxy.ordinaryCount()).isEqualTo(1);

        assertThat(proxy.triangle(0).surfaceType).isEqualTo(AcousticSurfaceType.ORDINARY);
        assertThat(proxy.triangle(1).surfaceType).isEqualTo(AcousticSurfaceType.PORTAL);
        assertThat(proxy.triangle(2).surfaceType).isEqualTo(AcousticSurfaceType.ROOM_BOUNDARY);
    }

    @Test
    void builderRejectsPortalWithReservedPortalIdZero() {
        AcousticWorldProxyBuilder builder = new AcousticWorldProxyBuilder();
        assertThatThrownBy(() -> builder.build(source(
            surface(0, 0, 5, 1, 0, 5, 0, 1, 5, 7, 0L, 11L, true, false)
        ))).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("portalId 0");
    }

    @Test
    void triangleRejectsNonFiniteComponents() {
        assertThatThrownBy(() -> new AcousticProxyTriangle(
            Float.NaN, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
            1, 0L, 0L, AcousticSurfaceType.ORDINARY
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ax");
    }

    @Test
    void traceRayReturnsExpectedHitMetadata() {
        AcousticWorldProxy proxy = new AcousticWorldProxyBuilder().build(source(
            surface(-1, -1, 5, 1, -1, 5, 0, 1, 5, 21, 77L, 33L, true, false)
        ));
        BruteForceRayQueryBackend backend = new BruteForceRayQueryBackend(proxy);
        AcousticHit hit = new AcousticHit();

        backend.traceRay(0f, 0f, 0f, 0f, 0f, 1f, 100f, hit);

        assertThat(hit.hit).isTrue();
        assertThat(hit.distance).isCloseTo(5f, within(1e-4f));
        assertThat(hit.materialId).isEqualTo(21);
        assertThat(hit.portalId).isEqualTo(77L);
        assertThat(hit.roomId).isEqualTo(33L);
        assertThat(hit.roomBoundary).isTrue();
    }

    @Test
    void traceRayMissResetsOutput() {
        AcousticWorldProxy proxy = new AcousticWorldProxyBuilder().build(source(
            surface(-1, -1, 5, 1, -1, 5, 0, 1, 5, 21, 77L, 33L, true, false)
        ));
        BruteForceRayQueryBackend backend = new BruteForceRayQueryBackend(proxy);
        AcousticHit hit = new AcousticHit();
        hit.hit = true;
        hit.distance = 10f;

        backend.traceRay(0f, 0f, 0f, 1f, 0f, 0f, 100f, hit);

        assertThat(hit.hit).isFalse();
        assertThat(hit.distance).isEqualTo(0f);
    }

    @Test
    void traceRayMultiReturnsNearestFirst() {
        AcousticWorldProxy proxy = new AcousticWorldProxyBuilder().build(source(
            surface(-1, -1, 10, 1, -1, 10, 0, 1, 10, 1, 0L, 1L, false, false),
            surface(-1, -1, 4, 1, -1, 4, 0, 1, 4, 2, 0L, 2L, false, false),
            surface(-1, -1, 7, 1, -1, 7, 0, 1, 7, 3, 0L, 3L, false, false)
        ));
        BruteForceRayQueryBackend backend = new BruteForceRayQueryBackend(proxy);
        AcousticHitBuffer out = new AcousticHitBuffer(4);

        int count = backend.traceRayMulti(0f, 0f, 0f, 0f, 0f, 1f, 100f, out);

        assertThat(count).isEqualTo(3);
        assertThat(out.get(0).distance).isCloseTo(4f, within(1e-4f));
        assertThat(out.get(1).distance).isCloseTo(7f, within(1e-4f));
        assertThat(out.get(2).distance).isCloseTo(10f, within(1e-4f));
    }

    @Test
    void roomBoundaryFlagTrueForPortalAndRoomBoundaryTypes() {
        AcousticWorldProxy proxy = new AcousticWorldProxyBuilder().build(source(
            surface(-1, -1, 5, 1, -1, 5, 0, 1, 5, 1, 9L, 1L, true, false),
            surface(-1, -1, 8, 1, -1, 8, 0, 1, 8, 2, 0L, 2L, false, true)
        ));
        BruteForceRayQueryBackend backend = new BruteForceRayQueryBackend(proxy);
        AcousticHitBuffer out = new AcousticHitBuffer(4);

        int count = backend.traceRayMulti(0f, 0f, 0f, 0f, 0f, 1f, 100f, out);

        assertThat(count).isEqualTo(2);
        assertThat(out.get(0).isRoomBoundary()).isTrue();
        assertThat(out.get(1).isRoomBoundary()).isTrue();
    }

    private static AcousticWorldProxyBuilder.MeshSource source(AcousticWorldProxyBuilder.MeshSurface... surfaces) {
        return new AcousticWorldProxyBuilder.MeshSource() {
            @Override
            public int surfaceCount() {
                return surfaces.length;
            }

            @Override
            public AcousticWorldProxyBuilder.MeshSurface surface(int index) {
                return surfaces[index];
            }
        };
    }

    private static AcousticWorldProxyBuilder.MeshSurface surface(
        float ax, float ay, float az,
        float bx, float by, float bz,
        float cx, float cy, float cz,
        int materialId,
        long portalId,
        long roomId,
        boolean isPortal,
        boolean isRoomBoundary
    ) {
        return new AcousticWorldProxyBuilder.MeshSurface() {
            @Override public float ax() { return ax; }
            @Override public float ay() { return ay; }
            @Override public float az() { return az; }
            @Override public float bx() { return bx; }
            @Override public float by() { return by; }
            @Override public float bz() { return bz; }
            @Override public float cx() { return cx; }
            @Override public float cy() { return cy; }
            @Override public float cz() { return cz; }
            @Override public int materialId() { return materialId; }
            @Override public long portalId() { return portalId; }
            @Override public long roomId() { return roomId; }
            @Override public boolean isPortal() { return isPortal; }
            @Override public boolean isRoomBoundary() { return isRoomBoundary; }
        };
    }
}
