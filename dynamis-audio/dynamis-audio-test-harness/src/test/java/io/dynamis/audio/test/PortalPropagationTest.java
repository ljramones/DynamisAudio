package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticEvent;
import io.dynamis.audio.api.AcousticHit;
import io.dynamis.audio.api.AcousticPortal;
import io.dynamis.audio.core.AcousticEventQueueImpl;
import io.dynamis.audio.core.AcousticSnapshotManager;
import io.dynamis.audio.core.AcousticWorldSnapshotImpl;
import io.dynamis.audio.designer.MixSnapshotManager;
import io.dynamis.audio.dsp.AudioBus;
import io.dynamis.audio.dsp.SoftwareMixer;
import io.dynamis.audio.dsp.device.NullAudioDevice;
import io.dynamis.audio.simulation.AcousticWorldProxy;
import io.dynamis.audio.simulation.AcousticWorldProxyBuilder;
import io.dynamis.audio.simulation.BruteForceRayQueryBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 2 portal propagation integration tests.
 *
 * Verifies that PortalStateChanged events update portal aperture in the snapshot,
 * that BruteForceRayQueryBackend populates portalAperture on portal hits,
 * and that SoftwareMixer.processEvents() correctly dispatches events to the snapshot.
 */
class PortalPropagationTest {

    private AcousticSnapshotManager acousticMgr;
    private AcousticEventQueueImpl eventQueue;
    private SoftwareMixer mixer;

    @BeforeEach
    void setUp() throws Exception {
        acousticMgr = new AcousticSnapshotManager();
        eventQueue = new AcousticEventQueueImpl(64);

        // Publish an initial snapshot
        AcousticWorldSnapshotImpl back = acousticMgr.acquireBackBuffer();
        back.putPortal(stubPortal(42L, 0.5f)); // portal 42, initial aperture 0.5
        acousticMgr.publish();

        NullAudioDevice device = new NullAudioDevice();
        device.open(AcousticConstants.SAMPLE_RATE, 2, AcousticConstants.DSP_BLOCK_SIZE);
        mixer = new SoftwareMixer(acousticMgr, eventQueue, device, new MixSnapshotManager());
    }

    // -- AcousticWorldSnapshotImpl portal aperture -------------------------

    @Test
    void snapshotDefaultsToPortalDefinitionAperture() {
        AcousticWorldSnapshotImpl snap = (AcousticWorldSnapshotImpl) acousticMgr.acquireLatest();
        assertThat(snap.getPortalAperture(42L)).isCloseTo(0.5f, within(1e-5f));
    }

    @Test
    void setPortalApertureOverridesDefinition() {
        AcousticWorldSnapshotImpl back = acousticMgr.acquireBackBuffer();
        back.setPortalAperture(42L, 0.9f);
        assertThat(back.getPortalAperture(42L)).isCloseTo(0.9f, within(1e-5f));
    }

    @Test
    void getPortalApertureForUnknownPortalReturnsOne() {
        AcousticWorldSnapshotImpl snap = (AcousticWorldSnapshotImpl) acousticMgr.acquireLatest();
        assertThat(snap.getPortalAperture(999L)).isEqualTo(1.0f);
    }

    @Test
    void setPortalApertureClampsToRange() {
        AcousticWorldSnapshotImpl back = acousticMgr.acquireBackBuffer();
        back.setPortalAperture(42L, 2.0f);
        assertThat(back.getPortalAperture(42L)).isEqualTo(1.0f);
        back.setPortalAperture(42L, -1.0f);
        assertThat(back.getPortalAperture(42L)).isEqualTo(0.0f);
    }

    @Test
    void clearPortalApertureOverridesRestoresDefinitionAperture() {
        AcousticWorldSnapshotImpl back = acousticMgr.acquireBackBuffer();
        back.putPortal(stubPortal(42L, 0.5f));
        back.setPortalAperture(42L, 0.1f);
        back.clearPortalApertureOverrides();
        // After clear, falls back to portal definition aperture
        assertThat(back.getPortalAperture(42L)).isCloseTo(0.5f, within(1e-5f));
    }

    // -- PortalStateChanged event dispatch ---------------------------------

    @Test
    void renderBlockProcessesPortalStateChangedEvent() {
        // Enqueue a PortalStateChanged event
        eventQueue.enqueue(new AcousticEvent.PortalStateChanged(
            System.nanoTime(), 42L, 0.8f));

        mixer.renderBlock();

        // After render, the snapshot should have the updated aperture
        AcousticWorldSnapshotImpl snap = (AcousticWorldSnapshotImpl) acousticMgr.acquireLatest();
        assertThat(snap.getPortalAperture(42L)).isCloseTo(0.8f, within(1e-5f));
    }

    @Test
    void multiplePortalEventsInOneBlockAllApplied() {
        eventQueue.enqueue(new AcousticEvent.PortalStateChanged(
            System.nanoTime(), 42L, 0.3f));
        // Coalesced - same portalId overwrites in ring
        eventQueue.enqueue(new AcousticEvent.PortalStateChanged(
            System.nanoTime(), 42L, 0.7f));

        mixer.renderBlock();

        AcousticWorldSnapshotImpl snap = (AcousticWorldSnapshotImpl) acousticMgr.acquireLatest();
        // Latest aperture should win (coalesced by AcousticEventQueueImpl)
        assertThat(snap.getPortalAperture(42L)).isCloseTo(0.7f, within(1e-5f));
    }

    @Test
    void geometryDestroyedEventClearsPortalOverrides() {
        // Set an override
        AcousticWorldSnapshotImpl back = acousticMgr.acquireBackBuffer();
        back.setPortalAperture(42L, 0.1f);
        acousticMgr.publish();

        // Enqueue geometry destroyed event
        eventQueue.enqueue(new AcousticEvent.GeometryDestroyedEvent(
            System.nanoTime(), 1L));

        mixer.renderBlock();

        // Overrides should be cleared - aperture falls back to portal definition
        AcousticWorldSnapshotImpl snap = (AcousticWorldSnapshotImpl) acousticMgr.acquireLatest();
        assertThat(snap.getPortalAperture(42L)).isCloseTo(0.5f, within(1e-5f));
    }

    // -- BruteForceRayQueryBackend portal hit ------------------------------

    @Test
    void rayHitOnPortalSurfacePopulatesPortalAperture() {
        // Build a proxy with one portal triangle at z=5
        AcousticWorldProxy proxy = new AcousticWorldProxyBuilder().build(
            new AcousticWorldProxyBuilder.MeshSource() {
                @Override
                public int surfaceCount() { return 1; }

                @Override
                public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                    return new AcousticWorldProxyBuilder.MeshSurface() {
                        @Override public float ax() { return -1f; }
                        @Override public float ay() { return -1f; }
                        @Override public float az() { return 5f; }
                        @Override public float bx() { return 1f; }
                        @Override public float by() { return -1f; }
                        @Override public float bz() { return 5f; }
                        @Override public float cx() { return 0f; }
                        @Override public float cy() { return 1f; }
                        @Override public float cz() { return 5f; }
                        @Override public int materialId() { return 1; }
                        @Override public long portalId() { return 42L; }
                        @Override public long roomId() { return 1L; }
                        @Override public boolean isPortal() { return true; }
                        @Override public boolean isRoomBoundary() { return false; }
                    };
                }
            });

        // Set up snapshot with aperture override
        AcousticWorldSnapshotImpl snap = acousticMgr.acquireBackBuffer();
        snap.setPortalAperture(42L, 0.3f);
        acousticMgr.publish();
        AcousticWorldSnapshotImpl latest = (AcousticWorldSnapshotImpl) acousticMgr.acquireLatest();

        BruteForceRayQueryBackend backend = new BruteForceRayQueryBackend(proxy);
        backend.setSnapshot(latest);

        AcousticHit hit = new AcousticHit();
        backend.traceRay(0f, 0f, 0f, 0f, 0f, 1f, 20f, hit);

        assertThat(hit.hit).isTrue();
        assertThat(hit.isPortalSurface()).isTrue();
        assertThat(hit.portalAperture).isCloseTo(0.3f, within(1e-5f));
    }

    @Test
    void rayHitOnOrdinarySurfaceHasApertureOne() {
        AcousticWorldProxy proxy = new AcousticWorldProxyBuilder().build(
            new AcousticWorldProxyBuilder.MeshSource() {
                @Override
                public int surfaceCount() { return 1; }

                @Override
                public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                    return new AcousticWorldProxyBuilder.MeshSurface() {
                        @Override public float ax() { return -1f; }
                        @Override public float ay() { return -1f; }
                        @Override public float az() { return 5f; }
                        @Override public float bx() { return 1f; }
                        @Override public float by() { return -1f; }
                        @Override public float bz() { return 5f; }
                        @Override public float cx() { return 0f; }
                        @Override public float cy() { return 1f; }
                        @Override public float cz() { return 5f; }
                        @Override public int materialId() { return 2; }
                        @Override public long portalId() { return 0L; }
                        @Override public long roomId() { return 0L; }
                        @Override public boolean isPortal() { return false; }
                        @Override public boolean isRoomBoundary() { return false; }
                    };
                }
            });

        BruteForceRayQueryBackend backend = new BruteForceRayQueryBackend(proxy);
        AcousticHit hit = new AcousticHit();
        backend.traceRay(0f, 0f, 0f, 0f, 0f, 1f, 20f, hit);

        assertThat(hit.hit).isTrue();
        assertThat(hit.isPortalSurface()).isFalse();
        assertThat(hit.portalAperture).isEqualTo(1.0f);
    }

    // -- Helper -------------------------------------------------------------

    private static AcousticPortal stubPortal(long id, float aperture) {
        return new AcousticPortal() {
            @Override public long id() { return id; }
            @Override public long roomA() { return 1L; }
            @Override public long roomB() { return 2L; }
            @Override public float aperture() { return aperture; }
            @Override public float transmissionLossDb(int band) { return 0f; }
        };
    }
}
