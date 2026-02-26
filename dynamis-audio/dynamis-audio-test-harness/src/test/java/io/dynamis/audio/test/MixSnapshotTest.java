package io.dynamis.audio.test;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.core.*;
import io.dynamis.audio.designer.*;
import io.dynamis.audio.dsp.*;
import io.dynamis.audio.dsp.device.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class MixSnapshotTest {

    private MixSnapshotManager snapshotManager;
    private AudioBus masterBus;
    private AudioBus sfxBus;
    private AudioBus musicBus;

    @BeforeEach
    void setUp() {
        snapshotManager = new MixSnapshotManager();
        masterBus = new AudioBus("Master");
        sfxBus = new AudioBus("SFX");
        musicBus = new AudioBus("Music");
        masterBus.prepare(AcousticConstants.DSP_BLOCK_SIZE, 2);
        sfxBus.prepare(AcousticConstants.DSP_BLOCK_SIZE, 2);
        musicBus.prepare(AcousticConstants.DSP_BLOCK_SIZE, 2);
        snapshotManager.registerBus(masterBus);
        snapshotManager.registerBus(sfxBus);
        snapshotManager.registerBus(musicBus);
    }

    // -- BusState -------------------------------------------------------------

    @Test
    void busStateGainClamped() {
        assertThat(new BusState("Master", 2.0f).gain()).isEqualTo(1.0f);
        assertThat(new BusState("Master", -1.0f).gain()).isEqualTo(0.0f);
    }

    @Test
    void busStateLerpMidpoint() {
        BusState a = new BusState("SFX", 0.0f);
        BusState b = new BusState("SFX", 1.0f);
        BusState mid = a.lerp(b, 0.5f);
        assertThat(mid.gain()).isCloseTo(0.5f, within(1e-5f));
    }

    @Test
    void busStateLerpAtOneReturnsTarget() {
        BusState a = new BusState("SFX", 0.0f, false);
        BusState b = new BusState("SFX", 1.0f, true);
        BusState result = a.lerp(b, 1.0f);
        assertThat(result.gain()).isEqualTo(1.0f);
        assertThat(result.bypassed()).isTrue();
    }

    @Test
    void busStateLerpDifferentBusNamesThrows() {
        BusState a = new BusState("SFX", 1.0f);
        BusState b = new BusState("Music", 0.0f);
        assertThatThrownBy(() -> a.lerp(b, 0.5f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // -- MixSnapshot ----------------------------------------------------------

    @Test
    void snapshotBuilderDefaults() {
        MixSnapshot snap = MixSnapshot.builder("test").build();
        assertThat(snap.name()).isEqualTo("test");
        assertThat(snap.blendTimeSeconds()).isEqualTo(0.5f);
        assertThat(snap.busCount()).isZero();
    }

    @Test
    void snapshotBuilderAddsBuses() {
        MixSnapshot snap = MixSnapshot.builder("combat")
            .bus("Master", 1.0f)
            .bus("SFX", 0.8f)
            .bus("Music", 0.4f)
            .build();
        assertThat(snap.busCount()).isEqualTo(3);
        assertThat(snap.busState("SFX").gain()).isEqualTo(0.8f);
    }

    @Test
    void snapshotBusStateNullForUnknownBus() {
        MixSnapshot snap = MixSnapshot.builder("test").bus("SFX", 0.5f).build();
        assertThat(snap.busState("Unknown")).isNull();
    }

    @Test
    void snapshotBlendTimeNegativeClampedToZero() {
        MixSnapshot snap = MixSnapshot.builder("test").blendTime(-1f).build();
        assertThat(snap.blendTimeSeconds()).isEqualTo(0f);
    }

    @Test
    void snapshotIsImmutable() {
        MixSnapshot snap = MixSnapshot.builder("test").bus("SFX", 0.5f).build();
        assertThatThrownBy(() -> snap.busStates().put("X", new BusState("X", 1f)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // -- MixSnapshotManager ---------------------------------------------------

    @Test
    void registerAndRetrieveSnapshot() {
        MixSnapshot snap = MixSnapshot.builder("combat").build();
        snapshotManager.registerSnapshot(snap);
        assertThat(snapshotManager.getSnapshot("combat")).isSameAs(snap);
    }

    @Test
    void activateUnknownSnapshotThrows() {
        assertThatThrownBy(() -> snapshotManager.activateSnapshot("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activateImmediateAppliesGainDirectly() {
        snapshotManager.registerSnapshot(
            MixSnapshot.builder("quiet").bus("SFX", 0.1f).blendTime(0f).build());
        snapshotManager.activateSnapshotImmediate("quiet");
        assertThat(sfxBus.getGain()).isCloseTo(0.1f, within(1e-5f));
    }

    @Test
    void activateWithBlendTimeStartsBlend() {
        snapshotManager.registerSnapshot(
            MixSnapshot.builder("combat").bus("SFX", 0.8f).blendTime(1.0f).build());
        sfxBus.setGain(0.0f);
        snapshotManager.activateSnapshot("combat");
        assertThat(snapshotManager.isBlending()).isTrue();
    }

    @Test
    void updateAdvancesBlendTowardTarget() {
        snapshotManager.registerSnapshot(
            MixSnapshot.builder("combat").bus("SFX", 1.0f).blendTime(1.0f).build());
        sfxBus.setGain(0.0f);
        snapshotManager.activateSnapshot("combat");

        // Run enough blocks to complete the blend (1s / 5.33ms ~= 188 blocks)
        for (int i = 0; i < 250; i++) {
            snapshotManager.update();
        }

        assertThat(snapshotManager.isBlending()).isFalse();
        assertThat(sfxBus.getGain()).isCloseTo(1.0f, within(1e-4f));
    }

    @Test
    void updateWithNoActiveBlendDoesNotThrow() {
        assertThatCode(() -> snapshotManager.update()).doesNotThrowAnyException();
    }

    @Test
    void hotReloadReplacesSnapshot() {
        snapshotManager.registerSnapshot(
            MixSnapshot.builder("combat").bus("SFX", 0.5f).build());
        snapshotManager.registerSnapshot(
            MixSnapshot.builder("combat").bus("SFX", 0.9f).build());
        assertThat(snapshotManager.getSnapshot("combat").busState("SFX").gain())
            .isEqualTo(0.9f);
    }

    @Test
    void unregisterSnapshotRemovesIt() {
        snapshotManager.registerSnapshot(MixSnapshot.builder("temp").build());
        snapshotManager.unregisterSnapshot("temp");
        assertThat(snapshotManager.getSnapshot("temp")).isNull();
    }

    @Test
    void blendProgressIsOneWhenIdle() {
        assertThat(snapshotManager.blendProgress()).isEqualTo(1.0f);
    }

    @Test
    void activeTargetIsNullWhenIdle() {
        assertThat(snapshotManager.activeTarget()).isNull();
    }

    @Test
    void activeTargetClearsAfterBlendCompletes() {
        snapshotManager.registerSnapshot(
            MixSnapshot.builder("test").bus("Music", 0.5f).blendTime(0.1f).build());
        musicBus.setGain(0.0f);
        snapshotManager.activateSnapshot("test");

        for (int i = 0; i < 50; i++) {
            snapshotManager.update();
        }

        assertThat(snapshotManager.activeTarget()).isNull();
    }

    // -- Integration: SoftwareMixer drives blend ------------------------------

    @Test
    void softwareMixerCallsSnapshotManagerUpdateEachBlock() throws Exception {
        AcousticSnapshotManager acousticMgr = new AcousticSnapshotManager();
        acousticMgr.publish();
        AcousticEventQueueImpl queue = new AcousticEventQueueImpl(64);
        NullAudioDevice device = new NullAudioDevice();
        device.open(AcousticConstants.SAMPLE_RATE, 2, AcousticConstants.DSP_BLOCK_SIZE);

        MixSnapshotManager mixMgr = new MixSnapshotManager();
        SoftwareMixer mixer = new SoftwareMixer(acousticMgr, queue, device, mixMgr);

        // Register and activate a snapshot with immediate blend
        AudioBus master = mixer.getMasterBus();
        mixMgr.registerBus(master);
        mixMgr.registerSnapshot(
            MixSnapshot.builder("test").bus("Master", 0.3f).blendTime(0f).build());
        mixMgr.activateSnapshotImmediate("test");

        // After renderBlock, the bus gain should reflect the snapshot
        mixer.renderBlock();
        assertThat(master.getGain()).isCloseTo(0.3f, within(1e-4f));
    }
}
