package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AcousticSnapshotManagerTest {

    private AcousticSnapshotManager manager;

    @BeforeEach
    void setUp() {
        manager = new AcousticSnapshotManager();
    }

    @Test
    void initialSnapshotVersionIsOne() {
        // First publish stamps version 1
        manager.publish();
        assertThat(manager.currentVersion()).isEqualTo(1L);
    }

    @Test
    void versionIncrementsOnEachPublish() {
        manager.publish();
        manager.publish();
        manager.publish();
        assertThat(manager.currentVersion()).isEqualTo(3L);
    }

    @Test
    void acquireLatestReturnsPublishedSnapshot() {
        AcousticWorldSnapshotImpl back = manager.acquireBackBuffer();
        back.clear();
        manager.publish();

        AcousticWorldSnapshot front = manager.acquireLatest();
        assertThat(front).isNotNull();
        assertThat(front.version()).isEqualTo(1L);
    }

    @Test
    void backBufferIsNotFrontBuffer() {
        manager.publish();
        AcousticWorldSnapshotImpl back = manager.acquireBackBuffer();
        AcousticWorldSnapshot front = manager.acquireLatest();
        // They must be different instances - the double-buffer invariant
        assertThat(back).isNotSameAs(front);
    }

    @Test
    void publishedPortalIsVisibleOnFrontBuffer() {
        AcousticWorldSnapshotImpl back = manager.acquireBackBuffer();
        back.putPortal(stubPortal(42L));
        manager.publish();

        AcousticWorldSnapshot front = manager.acquireLatest();
        assertThat(front.portal(42L)).isNotNull();
        assertThat(front.portal(42L).id()).isEqualTo(42L);
    }

    @Test
    void unpublishedMutationNotVisibleOnFrontBuffer() {
        // Mutate back buffer but do not publish
        manager.acquireBackBuffer().putRoom(stubRoom(99L));
        // Front buffer should not see this room
        assertThat(manager.acquireLatest().room(99L)).isNull();
    }

    @Test
    void traceRayReturnsMissInPhaseZeroStub() {
        manager.publish();
        AcousticWorldSnapshot snap = manager.acquireLatest();
        AcousticHit hit = new AcousticHit();
        snap.traceRay(0, 0, 0, 1, 0, 0, 100f, hit);
        assertThat(hit.hit).isFalse();
    }

    @Test
    void traceRayMultiReturnsZeroInPhaseZeroStub() {
        manager.publish();
        AcousticWorldSnapshot snap = manager.acquireLatest();
        AcousticHitBuffer buf = new AcousticHitBuffer(4);
        int count = snap.traceRayMulti(0, 0, 0, 1, 0, 0, 100f, buf);
        assertThat(count).isZero();
        assertThat(buf.count()).isZero();
    }

    // -- Stub helpers -----------------------------------------------------

    private static AcousticPortal stubPortal(long id) {
        return new AcousticPortal() {
            public long id()   { return id; }
            public long roomA(){ return 0L; }
            public long roomB(){ return 0L; }
            public float aperture() { return 1.0f; }
            public float transmissionLossDb(int band) { return 0f; }
        };
    }

    private static AcousticRoom stubRoom(long id) {
        return new AcousticRoom() {
            public long id()  { return id; }
            public float volumeMeters3() { return 100f; }
            public float totalAbsorption(int band) { return 1f; }
            public float surfaceAreaMeters2() { return 6f * (float) Math.pow(100f, 2.0 / 3.0); }
            public int dominantMaterialId() { return 0; }
        };
    }
}
