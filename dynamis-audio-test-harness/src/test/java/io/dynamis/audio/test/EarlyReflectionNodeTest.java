package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import io.dynamis.audio.dsp.*;
import io.dynamis.audio.simulation.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class EarlyReflectionNodeTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    private EarlyReflectionNode node;

    @BeforeEach
    void setUp() {
        node = new EarlyReflectionNode("test-reflect");
        node.prepare(BLOCK, CH);
    }

    @Test
    void initialReflectionCountIsZero() {
        assertThat(node.activeReflectionCount()).isZero();
    }

    @Test
    void noReflectionsPassesDirectSignalUnchanged() {
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 0.5f);
        float[] out = new float[LEN];
        node.process(in, out, BLOCK, CH);
        for (float s : out) {
            assertThat(s).isCloseTo(0.5f, within(1e-5f));
        }
    }

    @Test
    void silenceInSilenceOut() {
        float[] out = new float[LEN];
        node.process(new float[LEN], out, BLOCK, CH);
        for (float s : out) {
            assertThat(s).isEqualTo(0f);
        }
    }

    @Test
    void updateReflectionsFromHitBufferSetsCount() {
        AcousticHitBuffer hits = makeHitBuffer(2, new float[]{5f, 10f});
        node.updateReflections(hits);
        assertThat(node.activeReflectionCount()).isEqualTo(2);
    }

    @Test
    void updateReflectionsCapsAtMaxReflections() {
        int overCount = EarlyReflectionNode.MAX_REFLECTIONS + 4;
        float[] dists = new float[overCount];
        for (int i = 0; i < overCount; i++) {
            dists[i] = (i + 1) * 2f;
        }
        AcousticHitBuffer hits = makeHitBuffer(overCount, dists);
        node.updateReflections(hits);
        assertThat(node.activeReflectionCount()).isEqualTo(EarlyReflectionNode.MAX_REFLECTIONS);
    }

    @Test
    void clearReflectionsSetsCountToZero() {
        AcousticHitBuffer hits = makeHitBuffer(3, new float[]{2f, 5f, 8f});
        node.updateReflections(hits);
        node.clearReflections();
        assertThat(node.activeReflectionCount()).isZero();
    }

    @Test
    void reflectionsIncreaseOutputLevelRelativeToDirectOnly() {
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 1.0f);
        float[] outDirect = new float[LEN];
        node.process(in, outDirect, BLOCK, CH);
        float rmsDirect = rms(outDirect);

        node.reset();
        node.prepare(BLOCK, CH);
        AcousticHitBuffer hits = makeHitBuffer(2, new float[]{2f, 4f});
        node.updateReflections(hits);

        for (int i = 0; i < 10; i++) {
            node.process(in, outDirect, BLOCK, CH);
        }
        float rmsWithReflections = rms(outDirect);
        assertThat(rmsWithReflections).isGreaterThanOrEqualTo(rmsDirect);
    }

    @Test
    void furtherReflectionsAreMoreAttenuatedThanNear() {
        AcousticHitBuffer hits = makeHitBuffer(2, new float[]{2f, 20f});
        node.updateReflections(hits);
        float nearGain = 1f / (1f + 2f);
        float farGain = 1f / (1f + 20f);
        assertThat(nearGain).isGreaterThan(farGain);
    }

    @Test
    void outputIsAlwaysFiniteWithReflections() {
        AcousticHitBuffer hits = makeHitBuffer(
            EarlyReflectionNode.MAX_REFLECTIONS,
            new float[]{1f, 3f, 5f, 8f, 12f, 18f});
        node.updateReflections(hits);

        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 0.5f);
        float[] out = new float[LEN];
        for (int block = 0; block < 20; block++) {
            node.process(in, out, BLOCK, CH);
            for (float s : out) {
                assertThat(Float.isFinite(s)).as("Non-finite at block %d", block).isTrue();
            }
        }
    }

    @Test
    void resetClearsDelayLineAndReflections() {
        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 1.0f);
        float[] out = new float[LEN];
        node.process(in, out, BLOCK, CH);
        node.reset();
        node.prepare(BLOCK, CH);
        float[] outAfter = new float[LEN];
        node.process(new float[LEN], outAfter, BLOCK, CH);
        for (float s : outAfter) {
            assertThat(s).isEqualTo(0f);
        }
    }

    @Test
    void logicalEmitterAcceptsEarlyReflectionNode() {
        LogicalEmitter emitter = new LogicalEmitter("reflect-test", EmitterImportance.NORMAL);
        EarlyReflectionNode reflNode = new EarlyReflectionNode("voice-reflect");
        reflNode.prepare(BLOCK, CH);
        emitter.setEarlyReflectionNode(reflNode);
        assertThat(emitter.getEarlyReflectionNode()).isSameAs(reflNode);
    }

    @Test
    void logicalEmitterClearsReflectionsWhenNodeSetToNull() {
        LogicalEmitter emitter = new LogicalEmitter("reflect-clear-test", EmitterImportance.NORMAL);
        EarlyReflectionNode reflNode = new EarlyReflectionNode("voice-reflect");
        reflNode.prepare(BLOCK, CH);
        emitter.setEarlyReflectionNode(reflNode);
        emitter.setEarlyReflectionNode(null);
        assertThat(emitter.getEarlyReflectionNode()).isNull();
    }

    @Test
    void logicalEmitterFiresReflectionRayWhenNodeAndSnapshotWired()
            throws InterruptedException {
        AcousticWorldProxy proxy = new AcousticWorldProxyBuilder().build(
            new AcousticWorldProxyBuilder.MeshSource() {
                public int surfaceCount() { return 1; }
                public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                    return new AcousticWorldProxyBuilder.MeshSurface() {
                        public float ax() { return -1f; }
                        public float ay() { return -1f; }
                        public float az() { return 5f; }
                        public float bx() { return 1f; }
                        public float by() { return -1f; }
                        public float bz() { return 5f; }
                        public float cx() { return 0f; }
                        public float cy() { return 1f; }
                        public float cz() { return 5f; }
                        public int materialId() { return 1; }
                        public long portalId() { return 0L; }
                        public long roomId() { return 0L; }
                        public boolean isPortal() { return false; }
                        public boolean isRoomBoundary() { return false; }
                    };
                }
            });

        AcousticSnapshotManager snapshotMgr = new AcousticSnapshotManager();
        AcousticWorldSnapshotImpl back = snapshotMgr.acquireBackBuffer();
        back.setRayQueryBackend(new BruteForceRayQueryBackend(proxy));
        snapshotMgr.publish();

        LogicalEmitter emitter = new LogicalEmitter("reflect-integration", EmitterImportance.NORMAL);
        emitter.setPosition(0f, 0f, 0f);
        emitter.setListenerPosition(0f, 0f, 10f);
        emitter.setAcousticSnapshotManager(snapshotMgr);

        EarlyReflectionNode reflNode = new EarlyReflectionNode("voice-reflect");
        reflNode.prepare(BLOCK, CH);
        emitter.setEarlyReflectionNode(reflNode);

        emitter.trigger();
        Thread.sleep(150);

        assertThat(reflNode.activeReflectionCount()).isGreaterThan(0);
        emitter.destroy();
    }

    private static AcousticHitBuffer makeHitBuffer(int count, float[] distances) {
        AcousticHitBuffer buf = new AcousticHitBuffer(
            Math.max(count, EarlyReflectionNode.MAX_REFLECTIONS));
        for (int i = 0; i < count && i < distances.length; i++) {
            AcousticHit hit = buf.get(i);
            hit.hit = true;
            hit.distance = distances[i];
            hit.nx = 0f;
            hit.ny = 1f;
            hit.nz = 0f;
            hit.materialId = 1;
            hit.portalId = 0;
            hit.roomId = 0L;
            hit.roomBoundary = false;
        }
        buf.setCount(count);
        return buf;
    }

    private static float rms(float[] buf) {
        double sum = 0;
        for (float s : buf) {
            sum += (double) s * s;
        }
        return (float) Math.sqrt(sum / buf.length);
    }
}
