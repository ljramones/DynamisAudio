package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.AcousticMaterial;
import io.dynamis.audio.api.EmitterImportance;
import io.dynamis.audio.core.AcousticSnapshotManager;
import io.dynamis.audio.core.AcousticWorldSnapshotImpl;
import io.dynamis.audio.core.EmitterParams;
import io.dynamis.audio.core.LogicalEmitter;
import io.dynamis.audio.simulation.AcousticWorldProxy;
import io.dynamis.audio.simulation.AcousticWorldProxyBuilder;
import io.dynamis.audio.simulation.BruteForceRayQueryBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: LogicalEmitter fires real occlusion ray when snapshot
 * manager is wired with a proxy backend containing geometry.
 */
class LogicalEmitterOcclusionTest {

    private AcousticSnapshotManager snapshotManager;

    @BeforeEach
    void setUp() {
        snapshotManager = new AcousticSnapshotManager();
    }

    /**
     * Builds a minimal scene: one ordinary surface (a triangle) directly between
     * emitter at (0,0,0) and listener at (0,0,10). The surface at z=5 has
     * transmissionLossDb=-20 on all bands. After updateScore fires, occlusionPerBand
     * should be non-zero on all bands.
     */
    @Test
    void emitterOcclusionNonZeroWhenRayHitsSurface() throws InterruptedException {
        // Build a triangle at z=5 spanning the ray path
        AcousticWorldProxyBuilder builder = new AcousticWorldProxyBuilder();
        AcousticWorldProxy proxy = builder.build(new AcousticWorldProxyBuilder.MeshSource() {
            public int surfaceCount() { return 1; }
            public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                return new AcousticWorldProxyBuilder.MeshSurface() {
                    public float ax() { return -1f; } public float ay() { return -1f; } public float az() { return 5f; }
                    public float bx() { return  1f; } public float by() { return -1f; } public float bz() { return 5f; }
                    public float cx() { return  0f; } public float cy() { return  1f; } public float cz() { return 5f; }
                    public int   materialId()    { return 1; }
                    public long  portalId()      { return 0L; }
                    public long  roomId()        { return 0L; }
                    public boolean isPortal()       { return false; }
                    public boolean isRoomBoundary() { return false; }
                };
            }
        });

        // Build backend and wire into snapshot
        BruteForceRayQueryBackend backend = new BruteForceRayQueryBackend(proxy);
        AcousticWorldSnapshotImpl snapshot = snapshotManager.acquireBackBuffer();
        snapshot.setRayQueryBackend(backend);

        // Register a material with non-zero transmission loss
        snapshot.putMaterial(new AcousticMaterial() {
            public int   id()                         { return 1; }
            public float absorption(int band)         { return 0.3f; }
            public float scattering(int band)         { return 0.1f; }
            public float transmissionLossDb(int band) { return -20f; }
        });
        snapshotManager.publish();

        // Create emitter at origin, listener at z=10
        LogicalEmitter emitter = new LogicalEmitter("occlusion-test", EmitterImportance.NORMAL);
        emitter.setPosition(0f, 0f, 0f);
        emitter.setListenerPosition(0f, 0f, 10f);
        emitter.setAcousticSnapshotManager(snapshotManager);

        emitter.trigger();
        Thread.sleep(100); // allow virtual thread to run score update

        EmitterParams params = emitter.acquireParamsForBlock();
        // All bands should have non-zero occlusion due to the -20dB surface hit
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            assertThat(params.occlusionPerBand[band])
                .as("Band %d occlusion should be > 0", band)
                .isGreaterThan(0f);
        }

        emitter.destroy();
    }

    @Test
    void emitterOcclusionZeroWhenNoGeometryInPath() throws InterruptedException {
        // Empty proxy - no triangles
        AcousticWorldProxy emptyProxy = new AcousticWorldProxyBuilder()
            .build(new AcousticWorldProxyBuilder.MeshSource() {
                public int surfaceCount() { return 0; }
                public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                    throw new IndexOutOfBoundsException();
                }
            });

        BruteForceRayQueryBackend backend = new BruteForceRayQueryBackend(emptyProxy);
        AcousticWorldSnapshotImpl snapshot = snapshotManager.acquireBackBuffer();
        snapshot.setRayQueryBackend(backend);
        snapshotManager.publish();

        LogicalEmitter emitter = new LogicalEmitter("no-occlusion-test", EmitterImportance.NORMAL);
        emitter.setPosition(0f, 0f, 0f);
        emitter.setListenerPosition(0f, 0f, 10f);
        emitter.setAcousticSnapshotManager(snapshotManager);

        emitter.trigger();
        Thread.sleep(100);

        EmitterParams params = emitter.acquireParamsForBlock();
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            assertThat(params.occlusionPerBand[band])
                .as("Band %d occlusion should be 0 with empty proxy", band)
                .isEqualTo(0f);
        }

        emitter.destroy();
    }

    @Test
    void emitterWithoutSnapshotManagerUsesZeroOcclusion() throws InterruptedException {
        LogicalEmitter emitter = new LogicalEmitter("no-manager-test", EmitterImportance.NORMAL);
        emitter.setPosition(0f, 0f, 0f);
        emitter.setListenerPosition(0f, 0f, 10f);
        // No snapshot manager wired

        emitter.trigger();
        Thread.sleep(100);

        EmitterParams params = emitter.acquireParamsForBlock();
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            assertThat(params.occlusionPerBand[band]).isEqualTo(0f);
        }

        emitter.destroy();
    }
}
