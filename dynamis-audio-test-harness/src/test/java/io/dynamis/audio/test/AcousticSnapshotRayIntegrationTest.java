package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticHit;
import io.dynamis.audio.api.AcousticHitBuffer;
import io.dynamis.audio.core.AcousticWorldSnapshotImpl;
import io.dynamis.audio.simulation.AcousticWorldProxy;
import io.dynamis.audio.simulation.AcousticWorldProxyBuilder;
import io.dynamis.audio.simulation.BruteForceRayQueryBackend;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AcousticSnapshotRayIntegrationTest {

    @Test
    void snapshotWithoutBackendStillMisses() {
        AcousticWorldSnapshotImpl snapshot = new AcousticWorldSnapshotImpl();
        AcousticHit hit = new AcousticHit();

        snapshot.traceRay(0f, 0f, 0f, 0f, 0f, 1f, 100f, hit);

        assertThat(hit.hit).isFalse();
    }

    @Test
    void snapshotWithBackendReturnsRealHit() {
        AcousticWorldSnapshotImpl snapshot = new AcousticWorldSnapshotImpl();
        snapshot.setRayQueryBackend(new BruteForceRayQueryBackend(buildProxy()));
        AcousticHit hit = new AcousticHit();

        snapshot.traceRay(0f, 0f, 0f, 0f, 0f, 1f, 100f, hit);

        assertThat(hit.hit).isTrue();
        assertThat(hit.distance).isCloseTo(5f, within(1e-4f));
        assertThat(hit.materialId).isEqualTo(7);
    }

    @Test
    void traceRayMultiDelegatesAndSetsCount() {
        AcousticWorldSnapshotImpl snapshot = new AcousticWorldSnapshotImpl();
        snapshot.setRayQueryBackend(new BruteForceRayQueryBackend(buildProxy()));
        AcousticHitBuffer out = new AcousticHitBuffer(4);

        int count = snapshot.traceRayMulti(0f, 0f, 0f, 0f, 0f, 1f, 100f, out);

        assertThat(count).isEqualTo(2);
        assertThat(out.count()).isEqualTo(2);
        assertThat(out.get(0).distance).isCloseTo(5f, within(1e-4f));
        assertThat(out.get(1).distance).isCloseTo(8f, within(1e-4f));
    }

    @Test
    void resetSemanticsPreventStaleDataAcrossCalls() {
        AcousticWorldSnapshotImpl snapshot = new AcousticWorldSnapshotImpl();
        snapshot.setRayQueryBackend(new BruteForceRayQueryBackend(buildProxy()));
        AcousticHit hit = new AcousticHit();

        snapshot.traceRay(0f, 0f, 0f, 0f, 0f, 1f, 100f, hit);
        assertThat(hit.hit).isTrue();

        snapshot.traceRay(0f, 0f, 0f, 1f, 0f, 0f, 100f, hit);
        assertThat(hit.hit).isFalse();
        assertThat(hit.distance).isEqualTo(0f);
    }

    private static AcousticWorldProxy buildProxy() {
        AcousticWorldProxyBuilder builder = new AcousticWorldProxyBuilder();
        return builder.build(new AcousticWorldProxyBuilder.MeshSource() {
            private final AcousticWorldProxyBuilder.MeshSurface[] surfaces = new AcousticWorldProxyBuilder.MeshSurface[] {
                AcousticSnapshotRayIntegrationTest.surface(
                    -1, -1, 5, 1, -1, 5, 0, 1, 5, 7, 0L, 3L, false, false),
                AcousticSnapshotRayIntegrationTest.surface(
                    -1, -1, 8, 1, -1, 8, 0, 1, 8, 8, 0L, 4L, false, true)
            };

            @Override
            public int surfaceCount() {
                return surfaces.length;
            }

            @Override
            public AcousticWorldProxyBuilder.MeshSurface surface(int index) {
                return surfaces[index];
            }
        });
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
