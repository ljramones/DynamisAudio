package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import io.dynamis.audio.designer.MixSnapshotManager;
import io.dynamis.audio.dsp.*;
import io.dynamis.audio.dsp.device.NullAudioDevice;
import io.dynamis.audio.simulation.*;
import io.dynamis.audio.simulation.FingerprintBlender.MutableAcousticFingerprint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class Phase5VoiceChainTest {

    private static final int BLOCK = AcousticConstants.DSP_BLOCK_SIZE;
    private static final int CH = 2;
    private static final int LEN = BLOCK * CH;

    @Test
    void voiceNodePreparesAllChildNodes() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        assertThat(voice.isPrepared()).isTrue();
    }

    @Test
    void voiceNodeRenderBlockProducesFiniteOutput() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);

        float[] in = new float[LEN];
        float[] dry = new float[LEN];
        float[] reverb = new float[LEN];
        java.util.Arrays.fill(in, 0.5f);

        voice.renderBlock(in, dry, reverb, BLOCK, CH);

        for (float s : dry) assertThat(Float.isFinite(s)).isTrue();
        for (float s : reverb) assertThat(Float.isFinite(s)).isTrue();
    }

    @Test
    void voiceNodeBindsAndUnbindsEmitter() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);

        LogicalEmitter emitter = new LogicalEmitter("test-emitter", EmitterImportance.NORMAL);
        voice.bind(emitter);

        assertThat(voice.isBound()).isTrue();
        assertThat(voice.boundEmitter()).isSameAs(emitter);
        assertThat(emitter.getEarlyReflectionNode()).isNotNull();

        voice.unbind();
        assertThat(voice.isBound()).isFalse();
        assertThat(emitter.getEarlyReflectionNode()).isNull();
    }

    @Test
    void voiceNodeUpdateFromEmitterParamsAppliesOcclusion() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);

        LogicalEmitter emitter = new LogicalEmitter("occlusion-test", EmitterImportance.NORMAL);
        voice.bind(emitter);

        voice.updateFromEmitterParams();
        for (int b = 0; b < AcousticConstants.ACOUSTIC_BAND_COUNT; b++) {
            assertThat(voice.eqNode().getBandGainDb(b)).isEqualTo(0f);
        }

        voice.unbind();
    }

    @Test
    void voiceNodeResetClearsState() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);

        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 1.0f);
        float[] dry = new float[LEN];
        float[] rev = new float[LEN];
        voice.renderBlock(in, dry, rev, BLOCK, CH);

        voice.reset();

        float[] silence = new float[LEN];
        float[] outAfter = new float[LEN];
        float[] revAfter = new float[LEN];
        voice.renderBlock(silence, outAfter, revAfter, BLOCK, CH);
        for (float s : outAfter) assertThat(s).isEqualTo(0f);
    }

    @Test
    void voiceNodeReverbSendLevelZeroProducesSilentReverbOutput() {
        VoiceNode voice = new VoiceNode(0);
        voice.prepare(BLOCK, CH);
        voice.reverbSendNode().setSendLevel(0f);

        float[] in = new float[LEN];
        java.util.Arrays.fill(in, 1.0f);
        float[] dry = new float[LEN];
        float[] rev = new float[LEN];
        voice.renderBlock(in, dry, rev, BLOCK, CH);
        for (float s : rev) assertThat(s).isEqualTo(0f);
    }

    @Test
    void voicePoolConstructsWithCorrectCapacity() {
        VoicePool pool = new VoicePool(8, BLOCK, CH);
        assertThat(pool.capacity()).isEqualTo(8);
        assertThat(pool.freeCount()).isEqualTo(8);
        assertThat(pool.acquiredCount()).isZero();
    }

    @Test
    void voicePoolAcquireBindsVoiceToEmitter() {
        VoicePool pool = new VoicePool(4, BLOCK, CH);
        LogicalEmitter emitter = new LogicalEmitter("pool-test", EmitterImportance.NORMAL);
        VoiceNode voice = pool.acquire(emitter);

        assertThat(voice).isNotNull();
        assertThat(voice.isBound()).isTrue();
        assertThat(pool.acquiredCount()).isEqualTo(1);
        assertThat(pool.freeCount()).isEqualTo(3);
        pool.release(voice);
    }

    @Test
    void voicePoolReleaseUnbindsVoice() {
        VoicePool pool = new VoicePool(4, BLOCK, CH);
        LogicalEmitter emitter = new LogicalEmitter("pool-release", EmitterImportance.NORMAL);
        VoiceNode voice = pool.acquire(emitter);
        pool.release(voice);

        assertThat(voice.isBound()).isFalse();
        assertThat(pool.acquiredCount()).isZero();
        assertThat(pool.freeCount()).isEqualTo(4);
    }

    @Test
    void voicePoolReturnsNullWhenExhausted() {
        VoicePool pool = new VoicePool(2, BLOCK, CH);
        LogicalEmitter e1 = new LogicalEmitter("e1", EmitterImportance.NORMAL);
        LogicalEmitter e2 = new LogicalEmitter("e2", EmitterImportance.NORMAL);
        LogicalEmitter e3 = new LogicalEmitter("e3", EmitterImportance.NORMAL);

        VoiceNode v1 = pool.acquire(e1);
        VoiceNode v2 = pool.acquire(e2);
        VoiceNode v3 = pool.acquire(e3);
        assertThat(v1).isNotNull();
        assertThat(v2).isNotNull();
        assertThat(v3).isNull();

        pool.release(v1);
        pool.release(v2);
    }

    @Test
    void voicePoolReleasedVoiceCanBeReacquired() {
        VoicePool pool = new VoicePool(1, BLOCK, CH);
        LogicalEmitter e1 = new LogicalEmitter("e1", EmitterImportance.NORMAL);
        LogicalEmitter e2 = new LogicalEmitter("e2", EmitterImportance.NORMAL);

        VoiceNode v1 = pool.acquire(e1);
        pool.release(v1);
        VoiceNode v2 = pool.acquire(e2);
        assertThat(v2).isNotNull();
        assertThat(v2.boundEmitter()).isSameAs(e2);
        pool.release(v2);
    }

    @Test
    void voicePoolZeroCapacityThrows() {
        assertThatThrownBy(() -> new VoicePool(0, BLOCK, CH))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintMfpPerBandLengthIsCorrect() {
        AcousticFingerprint fp = new AcousticFingerprint(
            1L, 100f, 60f, 5f, 20f,
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 1.5f),
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 1f),
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 4f));
        assertThat(fp.mfpPerBand.length).isEqualTo(AcousticConstants.ACOUSTIC_BAND_COUNT);
    }

    @Test
    void backwardCompatConstructorDerivesUniformMfpPerBand() {
        float scalar = 5f;
        AcousticFingerprint fp = new AcousticFingerprint(
            1L, 100f, 60f, scalar, 20f,
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 1.5f),
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 1f));
        for (float v : fp.mfpPerBand) assertThat(v).isEqualTo(scalar);
    }

    @Test
    void builderComputesPerBandMfpWithScattering() {
        AcousticFingerprintBuilder builder = new AcousticFingerprintBuilder();
        AcousticSnapshotManager mgr = new AcousticSnapshotManager();
        AcousticWorldSnapshotImpl back = mgr.acquireBackBuffer();
        back.putMaterial(new AcousticMaterial() {
            public int id() { return 1; }
            public float absorption(int b) { return 0.3f; }
            public float scattering(int b) { return b < 4 ? 0f : 0.5f; }
            public float transmissionLossDb(int b) { return 0f; }
        });
        mgr.publish();

        AcousticRoom room = new AcousticRoom() {
            public long id() { return 1L; }
            public float volumeMeters3() { return 100f; }
            public float surfaceAreaMeters2() { return 60f; }
            public float totalAbsorption(int b) { return 5f; }
            public int dominantMaterialId() { return 1; }
        };

        AcousticFingerprint fp = builder.build(room, emptyProxy(), mgr.acquireLatest());

        float scalarMfp = 4f * 100f / 60f;
        assertThat(fp.mfpPerBand[0]).isCloseTo(scalarMfp, within(0.01f));
        assertThat(fp.mfpPerBand[4]).isLessThan(fp.mfpPerBand[0]);
    }

    @Test
    void blenderInterpolatesMfpPerBand() {
        float[] mfpA = uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 2f);
        float[] mfpB = uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 4f);
        AcousticFingerprint a = new AcousticFingerprint(
            1L, 100f, 60f, 2f, 10f,
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 1f),
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 1f),
            mfpA);
        AcousticFingerprint b = new AcousticFingerprint(
            2L, 200f, 80f, 4f, 10f,
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 1f),
            uniformArray(AcousticConstants.ACOUSTIC_BAND_COUNT, 1f),
            mfpB);
        MutableAcousticFingerprint out = new MutableAcousticFingerprint();
        FingerprintBlender.blend(a, b, 0.5f, out);
        for (float v : out.mfpPerBand) assertThat(v).isCloseTo(3f, within(1e-4f));
    }

    @Test
    void softwareMixerRenderBlockWithVoicePoolDoesNotThrow() throws Exception {
        AcousticSnapshotManager acousticMgr = new AcousticSnapshotManager();
        acousticMgr.publish();
        AcousticEventQueueImpl queue = new AcousticEventQueueImpl(64);
        NullAudioDevice device = new NullAudioDevice();
        device.open(AcousticConstants.SAMPLE_RATE, 2, AcousticConstants.DSP_BLOCK_SIZE);
        MixSnapshotManager mixMgr = new MixSnapshotManager();
        SoftwareMixer mixer = new SoftwareMixer(acousticMgr, queue, device, mixMgr);

        assertThatCode(() -> {
            for (int i = 0; i < 10; i++) mixer.renderBlock();
        }).doesNotThrowAnyException();
    }

    @Test
    void voicePoolExposedFromMixer() throws Exception {
        AcousticSnapshotManager acousticMgr = new AcousticSnapshotManager();
        acousticMgr.publish();
        AcousticEventQueueImpl queue = new AcousticEventQueueImpl(64);
        NullAudioDevice device = new NullAudioDevice();
        device.open(AcousticConstants.SAMPLE_RATE, 2, AcousticConstants.DSP_BLOCK_SIZE);
        SoftwareMixer mixer = new SoftwareMixer(acousticMgr, queue, device, new MixSnapshotManager());

        assertThat(mixer.getVoicePool()).isNotNull();
        assertThat(mixer.getVoicePool().capacity())
            .isEqualTo(AcousticConstants.DEFAULT_PHYSICAL_BUDGET);
    }

    @Test
    void proxyBuilderAcceptsPhysicsSurfaceTaggerInterface() {
        AcousticWorldProxyBuilder.PhysicsSurfaceTagger tagger =
            (bodyId, triIdx, ax, ay, az, bx, by, bz, cx, cy, cz) ->
                new AcousticWorldProxyBuilder.MeshSurface() {
                    public float ax() { return ax; }
                    public float ay() { return ay; }
                    public float az() { return az; }
                    public float bx() { return bx; }
                    public float by() { return by; }
                    public float bz() { return bz; }
                    public float cx() { return cx; }
                    public float cy() { return cy; }
                    public float cz() { return cz; }
                    public int materialId() { return 1; }
                    public long portalId() { return 0L; }
                    public long roomId() { return 0L; }
                    public boolean isPortal() { return false; }
                    public boolean isRoomBoundary() { return false; }
                };
        assertThat(tagger).isNotNull();
    }

    private static float[] uniformArray(int len, float val) {
        float[] arr = new float[len];
        java.util.Arrays.fill(arr, val);
        return arr;
    }

    private static AcousticWorldProxy emptyProxy() {
        return new AcousticWorldProxyBuilder().build(
            new AcousticWorldProxyBuilder.MeshSource() {
                public int surfaceCount() { return 0; }
                public AcousticWorldProxyBuilder.MeshSurface surface(int i) {
                    throw new IndexOutOfBoundsException();
                }
            });
    }
}
