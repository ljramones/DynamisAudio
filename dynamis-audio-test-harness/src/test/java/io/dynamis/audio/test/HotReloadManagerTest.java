package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.VoiceManager;
import io.dynamis.audio.designer.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class HotReloadManagerTest {

    private RtpcRegistry       rtpcRegistry;
    private EventSystem        eventSystem;
    private MixSnapshotManager snapshotManager;
    private HotReloadManager   hotReload;

    @BeforeEach
    void setUp() {
        rtpcRegistry    = new RtpcRegistry();
        eventSystem     = new EventSystem(new VoiceManager(8, 2), rtpcRegistry);
        snapshotManager = new MixSnapshotManager();
        hotReload       = new HotReloadManager(eventSystem, rtpcRegistry, snapshotManager);
    }

    // ── Construction ──────────────────────────────────────────────────────

    @Test
    void nullEventSystemThrows() {
        assertThatThrownBy(() ->
            new HotReloadManager(null, rtpcRegistry, snapshotManager))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullRtpcRegistryThrows() {
        assertThatThrownBy(() ->
            new HotReloadManager(eventSystem, null, snapshotManager))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullSnapshotManagerThrows() {
        assertThatThrownBy(() ->
            new HotReloadManager(eventSystem, rtpcRegistry, null))
            .isInstanceOf(NullPointerException.class);
    }

    // ── Initial state ─────────────────────────────────────────────────────

    @Test
    void initialGenerationIsZero() {
        assertThat(hotReload.reloadGeneration()).isZero();
    }

    @Test
    void initialLastReloadEpochIsZero() {
        assertThat(hotReload.lastReloadEpochMs()).isZero();
    }

    @Test
    void initialLastReloadedResourceIsEmpty() {
        assertThat(hotReload.lastReloadedResource()).isEmpty();
    }

    // ── SoundEvent reload ─────────────────────────────────────────────────

    @Test
    void reloadSoundEventRegistersDefinition() {
        SoundEventDef def = SoundEventDef.builder("sfx.reload.test").build();
        hotReload.reloadSoundEvent(def);
        assertThat(eventSystem.getDefinition("sfx.reload.test")).isNotNull();
    }

    @Test
    void reloadSoundEventIncrementsGeneration() {
        hotReload.reloadSoundEvent(SoundEventDef.builder("sfx.a").build());
        assertThat(hotReload.reloadGeneration()).isEqualTo(1L);
    }

    @Test
    void reloadSoundEventUpdatesLastReloadedResource() {
        hotReload.reloadSoundEvent(SoundEventDef.builder("sfx.named").build());
        assertThat(hotReload.lastReloadedResource()).isEqualTo("sfx.named");
    }

    @Test
    void reloadSoundEventNullDoesNotIncrementGeneration() {
        hotReload.reloadSoundEvent(null);
        assertThat(hotReload.reloadGeneration()).isZero();
    }

    @Test
    void reloadSoundEventsBatchAppliesAll() {
        var defs = java.util.List.of(
            SoundEventDef.builder("sfx.batch.a").build(),
            SoundEventDef.builder("sfx.batch.b").build(),
            SoundEventDef.builder("sfx.batch.c").build()
        );
        hotReload.reloadSoundEvents(defs);
        assertThat(eventSystem.getDefinition("sfx.batch.a")).isNotNull();
        assertThat(eventSystem.getDefinition("sfx.batch.b")).isNotNull();
        assertThat(eventSystem.getDefinition("sfx.batch.c")).isNotNull();
        assertThat(hotReload.reloadGeneration()).isEqualTo(3L);
    }

    @Test
    void reloadSoundEventHotSwapsDefinition() {
        hotReload.reloadSoundEvent(
            SoundEventDef.builder("sfx.swap").defaultGain(1.0f).build());
        hotReload.reloadSoundEvent(
            SoundEventDef.builder("sfx.swap").defaultGain(0.2f).build());
        assertThat(eventSystem.getDefinition("sfx.swap").defaultGain())
            .isEqualTo(0.2f);
        assertThat(hotReload.reloadGeneration()).isEqualTo(2L);
    }

    // ── RtpcParameter reload ──────────────────────────────────────────────

    @Test
    void reloadRtpcParameterRegistersDefinition() {
        hotReload.reloadRtpcParameter(
            new RtpcParameter("env.rain", 0f, 1f, 0f));
        assertThat(rtpcRegistry.getDefinition("env.rain")).isNotNull();
    }

    @Test
    void reloadRtpcParameterIncrementsGeneration() {
        hotReload.reloadRtpcParameter(new RtpcParameter("x", 0f, 1f, 0f));
        assertThat(hotReload.reloadGeneration()).isEqualTo(1L);
    }

    @Test
    void reloadRtpcParameterNullDoesNotIncrementGeneration() {
        hotReload.reloadRtpcParameter(null);
        assertThat(hotReload.reloadGeneration()).isZero();
    }

    @Test
    void reloadRtpcParameterPreservesValueIfInNewRange() {
        rtpcRegistry.register(new RtpcParameter("vol", 0f, 1f, 0.5f));
        rtpcRegistry.setValue("vol", 0.7f);
        hotReload.reloadRtpcParameter(new RtpcParameter("vol", 0f, 2f, 0.5f));
        assertThat(rtpcRegistry.getValue("vol")).isEqualTo(0.7f);
    }

    // ── MixSnapshot reload ────────────────────────────────────────────────

    @Test
    void reloadMixSnapshotRegistersSnapshot() {
        hotReload.reloadMixSnapshot(
            MixSnapshot.builder("combat").bus("Master", 1.0f).build());
        assertThat(snapshotManager.getSnapshot("combat")).isNotNull();
    }

    @Test
    void reloadMixSnapshotIncrementsGeneration() {
        hotReload.reloadMixSnapshot(MixSnapshot.builder("pause").build());
        assertThat(hotReload.reloadGeneration()).isEqualTo(1L);
    }

    @Test
    void reloadMixSnapshotNullDoesNotIncrementGeneration() {
        hotReload.reloadMixSnapshot(null);
        assertThat(hotReload.reloadGeneration()).isZero();
    }

    @Test
    void reloadMixSnapshotHotSwapsDefinition() {
        hotReload.reloadMixSnapshot(
            MixSnapshot.builder("stealth").bus("SFX", 0.5f).build());
        hotReload.reloadMixSnapshot(
            MixSnapshot.builder("stealth").bus("SFX", 0.1f).build());
        assertThat(snapshotManager.getSnapshot("stealth")
                                  .busState("SFX").gain())
            .isEqualTo(0.1f);
    }

    // ── AcousticMaterial reload ───────────────────────────────────────────

    @Test
    void reloadAcousticMaterialValidMaterialIncrementsGeneration() {
        hotReload.reloadAcousticMaterial(1L, 42, stubMaterial(42));
        assertThat(hotReload.reloadGeneration()).isEqualTo(1L);
    }

    @Test
    void reloadAcousticMaterialNullDoesNotIncrementGeneration() {
        hotReload.reloadAcousticMaterial(1L, 42, null);
        assertThat(hotReload.reloadGeneration()).isZero();
    }

    @Test
    void reloadAcousticMaterialInvalidAbsorptionDoesNotIncrementGeneration() {
        AcousticMaterial bad = new AcousticMaterial() {
            public int   id()                          { return 99; }
            public float absorption(int band)          { return 1.5f; } // out of range
            public float scattering(int band)          { return 0f; }
            public float transmissionLossDb(int band)  { return 0f; }
        };
        hotReload.reloadAcousticMaterial(1L, 99, bad);
        assertThat(hotReload.reloadGeneration()).isZero();
    }

    // ── Listener notifications ────────────────────────────────────────────

    @Test
    void listenerNotifiedOnSuccessfulReload() {
        var notified = new java.util.ArrayList<String>();
        hotReload.addListener(new HotReloadListener() {
            public void onReloaded(HotReloadManager.ResourceType t, String name) {
                notified.add(name);
            }
            public void onReloadFailed(HotReloadManager.ResourceType t,
                                       String name, String reason) {}
        });
        hotReload.reloadSoundEvent(SoundEventDef.builder("sfx.notify.test").build());
        assertThat(notified).containsExactly("sfx.notify.test");
    }

    @Test
    void listenerNotifiedOnFailure() {
        var failures = new java.util.ArrayList<String>();
        hotReload.addListener(new HotReloadListener() {
            public void onReloaded(HotReloadManager.ResourceType t, String name) {}
            public void onReloadFailed(HotReloadManager.ResourceType t,
                                       String name, String reason) {
                failures.add(name);
            }
        });
        hotReload.reloadSoundEvent(null);
        assertThat(failures).containsExactly("<null>");
    }

    @Test
    void removeListenerStopsNotifications() {
        var count = new int[]{0};
        HotReloadListener l = new HotReloadListener() {
            public void onReloaded(HotReloadManager.ResourceType t, String n) { count[0]++; }
            public void onReloadFailed(HotReloadManager.ResourceType t,
                                       String n, String r) {}
        };
        hotReload.addListener(l);
        hotReload.reloadSoundEvent(SoundEventDef.builder("sfx.a").build());
        hotReload.removeListener(l);
        hotReload.reloadSoundEvent(SoundEventDef.builder("sfx.b").build());
        assertThat(count[0]).isEqualTo(1);
    }

    @Test
    void listenerCountReflectsRegistrations() {
        assertThat(hotReload.listenerCount()).isZero();
        HotReloadListener l = new HotReloadListener() {
            public void onReloaded(HotReloadManager.ResourceType t, String n) {}
            public void onReloadFailed(HotReloadManager.ResourceType t,
                                       String n, String r) {}
        };
        hotReload.addListener(l);
        assertThat(hotReload.listenerCount()).isEqualTo(1);
    }

    @Test
    void listenerCrashDoesNotAbortReloadCycle() {
        hotReload.addListener(new HotReloadListener() {
            public void onReloaded(HotReloadManager.ResourceType t, String n) {
                throw new RuntimeException("listener crash");
            }
            public void onReloadFailed(HotReloadManager.ResourceType t,
                                       String n, String r) {}
        });
        // Reload must complete and increment generation despite crashing listener
        assertThatCode(() ->
            hotReload.reloadSoundEvent(SoundEventDef.builder("sfx.crash.test").build()))
            .doesNotThrowAnyException();
        assertThat(hotReload.reloadGeneration()).isEqualTo(1L);
    }

    // ── Generation as staleness detector ─────────────────────────────────

    @Test
    void generationIncreasesMonotonicallyAcrossReloadTypes() {
        hotReload.reloadSoundEvent(SoundEventDef.builder("sfx.gen.a").build());
        hotReload.reloadRtpcParameter(new RtpcParameter("gen.b", 0f, 1f, 0f));
        hotReload.reloadMixSnapshot(MixSnapshot.builder("gen.c").build());
        hotReload.reloadAcousticMaterial(1L, 1, stubMaterial(1));
        assertThat(hotReload.reloadGeneration()).isEqualTo(4L);
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private static AcousticMaterial stubMaterial(int id) {
        return new AcousticMaterial() {
            public int   id()                         { return id; }
            public float absorption(int band)         { return 0.1f; }
            public float scattering(int band)         { return 0.2f; }
            public float transmissionLossDb(int band) { return 3.0f; }
        };
    }
}
