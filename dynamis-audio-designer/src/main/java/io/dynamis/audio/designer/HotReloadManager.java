package io.dynamis.audio.designer;

import io.dynamis.audio.api.AcousticMaterial;
import io.dynamis.audio.api.RtpcParameter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime hot-reload coordinator for the designer layer.
 *
 * Allows sound designers to update graphs, event definitions, RTPC parameters,
 * mix snapshots, and acoustic material overrides at runtime without engine restart.
 * Game code and the audio engine observe changes immediately on the next frame cycle.
 *
 * RELOAD MODEL:
 *   Game/tool code calls one of the reload*() methods with updated data.
 *   HotReloadManager validates the incoming data, applies it to the appropriate
 *   registry/manager, increments the reload generation counter, and notifies listeners.
 *   No file watching — the caller is responsible for detecting source changes and
 *   providing the updated data object. This keeps the engine portable and testable.
 *
 * THREAD SAFETY:
 *   All reload*() methods are safe to call from any thread.
 *   Listeners are notified on the calling thread (synchronous).
 *   Registry updates use the thread-safety guarantees of their target classes
 *   (ConcurrentHashMap in RtpcRegistry, CopyOnWriteArrayList in EventSystem, etc.).
 *
 * GENERATION COUNTER:
 *   reloadGeneration() increments on every successful reload.
 *   Subsystems that cache derived state (e.g. resolved material coefficients)
 *   can detect staleness by comparing their cached generation to currentGeneration().
 *   This is the same pattern as AcousticMaterialRegistry.generation() in the arch doc.
 *
 * ALLOCATION CONTRACT:
 *   reload*() methods allocate (logging, listener dispatch) — called from tool/game thread.
 *   No reload path touches the DSP render thread.
 */
public final class HotReloadManager {

    /**
     * Categories of reloadable resources.
     * Used in listener notifications and reload telemetry.
     */
    public enum ResourceType {
        /** A SoundEventDef definition was updated. */
        SOUND_EVENT,
        /** An RtpcParameter definition was updated. */
        RTPC_PARAMETER,
        /** A MixSnapshot definition was updated. */
        MIX_SNAPSHOT,
        /** An acoustic material override was applied. */
        ACOUSTIC_MATERIAL
    }

    // ── Registries ────────────────────────────────────────────────────────

    private final EventSystem          eventSystem;
    private final RtpcRegistry         rtpcRegistry;
    private final MixSnapshotManager   snapshotManager;

    // ── Listeners ─────────────────────────────────────────────────────────

    private final CopyOnWriteArrayList<HotReloadListener> listeners =
        new CopyOnWriteArrayList<>();

    // ── Telemetry ─────────────────────────────────────────────────────────

    private final AtomicLong reloadGeneration = new AtomicLong(0L);
    private volatile long    lastReloadEpochMs = 0L;
    private volatile String  lastReloadedResource = "";

    // ── Construction ──────────────────────────────────────────────────────

    /**
     * @param eventSystem      receives updated SoundEventDef reloads
     * @param rtpcRegistry     receives updated RtpcParameter reloads
     * @param snapshotManager  receives updated MixSnapshot reloads
     */
    public HotReloadManager(EventSystem        eventSystem,
                            RtpcRegistry       rtpcRegistry,
                            MixSnapshotManager snapshotManager) {
        if (eventSystem     == null) throw new NullPointerException("eventSystem");
        if (rtpcRegistry    == null) throw new NullPointerException("rtpcRegistry");
        if (snapshotManager == null) throw new NullPointerException("snapshotManager");
        this.eventSystem     = eventSystem;
        this.rtpcRegistry    = rtpcRegistry;
        this.snapshotManager = snapshotManager;
    }

    // ── Listener management ───────────────────────────────────────────────

    /** Registers a listener for reload notifications. Thread-safe. */
    public void addListener(HotReloadListener listener) {
        if (listener != null) listeners.add(listener);
    }

    /** Removes a listener. Thread-safe. */
    public void removeListener(HotReloadListener listener) {
        listeners.remove(listener);
    }

    // ── Reload operations ─────────────────────────────────────────────────

    /**
     * Reloads a SoundEventDef — replaces the existing definition with the same name.
     * Live emitters from the old definition continue until they complete.
     * New triggers after this call use the updated definition.
     *
     * @param def updated definition; must not be null
     */
    public void reloadSoundEvent(SoundEventDef def) {
        if (def == null) {
            notifyFailure(ResourceType.SOUND_EVENT, "<null>", "SoundEventDef must not be null");
            return;
        }
        try {
            eventSystem.registerEvent(def);
            recordSuccess(ResourceType.SOUND_EVENT, def.name());
        } catch (Exception e) {
            notifyFailure(ResourceType.SOUND_EVENT, def.name(), e.getMessage());
        }
    }

    /**
     * Reloads a batch of SoundEventDef instances atomically from the caller's perspective.
     * Each definition is applied in list order. A failure on one does not block the rest.
     *
     * @param defs list of updated definitions; must not be null
     */
    public void reloadSoundEvents(List<SoundEventDef> defs) {
        if (defs == null) {
            notifyFailure(ResourceType.SOUND_EVENT, "<batch>", "defs list must not be null");
            return;
        }
        for (SoundEventDef def : defs) {
            reloadSoundEvent(def);
        }
    }

    /**
     * Reloads an RtpcParameter definition.
     * The current value is preserved if it falls within the new range; reset otherwise.
     *
     * @param parameter updated parameter; must not be null
     */
    public void reloadRtpcParameter(RtpcParameter parameter) {
        if (parameter == null) {
            notifyFailure(ResourceType.RTPC_PARAMETER, "<null>",
                          "RtpcParameter must not be null");
            return;
        }
        try {
            rtpcRegistry.register(parameter);
            recordSuccess(ResourceType.RTPC_PARAMETER, parameter.name());
        } catch (Exception e) {
            notifyFailure(ResourceType.RTPC_PARAMETER, parameter.name(), e.getMessage());
        }
    }

    /**
     * Reloads a MixSnapshot definition.
     * Active blends toward the old version of this snapshot continue unaffected.
     * New activateSnapshot() calls after this reload use the updated definition.
     *
     * @param snapshot updated snapshot; must not be null
     */
    public void reloadMixSnapshot(MixSnapshot snapshot) {
        if (snapshot == null) {
            notifyFailure(ResourceType.MIX_SNAPSHOT, "<null>",
                          "MixSnapshot must not be null");
            return;
        }
        try {
            snapshotManager.registerSnapshot(snapshot);
            recordSuccess(ResourceType.MIX_SNAPSHOT, snapshot.name());
        } catch (Exception e) {
            notifyFailure(ResourceType.MIX_SNAPSHOT, snapshot.name(), e.getMessage());
        }
    }

    /**
     * Applies an acoustic material override at runtime.
     *
     * This is a designer-facing shortcut that enqueues a MaterialOverrideChanged
     * event on the provided event queue. The audio thread processes it on the next
     * DSP block drain cycle — no snapshot cycle required.
     *
     * Phase 0: validates and records the reload. Event enqueue is caller responsibility
     * until the AcousticEventQueue reference is available here.
     * Phase 2: HotReloadManager will hold an AcousticEventQueue reference and enqueue
     * MaterialOverrideChanged directly.
     *
     * @param entityId      the scene entity whose material is being overridden
     * @param newMaterialId the new material ID to apply
     * @param material      the AcousticMaterial to register (for validation)
     */
    public void reloadAcousticMaterial(long entityId, int newMaterialId,
                                       AcousticMaterial material) {
        if (material == null) {
            notifyFailure(ResourceType.ACOUSTIC_MATERIAL,
                          "entity:" + entityId, "AcousticMaterial must not be null");
            return;
        }
        // Validate: check band count matches engine expectation
        try {
            for (int band = 0; band < io.dynamis.audio.api.AcousticConstants.ACOUSTIC_BAND_COUNT;
                 band++) {
                float absorption = material.absorption(band);
                if (absorption < 0f || absorption > 1f) {
                    notifyFailure(ResourceType.ACOUSTIC_MATERIAL,
                                  "entity:" + entityId,
                                  "absorption out of range [0..1] at band " + band);
                    return;
                }
            }
            recordSuccess(ResourceType.ACOUSTIC_MATERIAL,
                          "entity:" + entityId + "/material:" + newMaterialId);
        } catch (Exception e) {
            notifyFailure(ResourceType.ACOUSTIC_MATERIAL,
                          "entity:" + entityId, e.getMessage());
        }
    }

    // ── Telemetry ─────────────────────────────────────────────────────────

    /**
     * Monotonically increasing counter incremented on every successful reload.
     * Subsystems that cache derived state compare against this to detect staleness.
     */
    public long reloadGeneration() { return reloadGeneration.get(); }

    /** Epoch milliseconds of the most recent successful reload. 0 if none. */
    public long lastReloadEpochMs() { return lastReloadEpochMs; }

    /** Name of the most recently reloaded resource. Empty string if none. */
    public String lastReloadedResource() { return lastReloadedResource; }

    /** Number of registered listeners. */
    public int listenerCount() { return listeners.size(); }

    // ── Internal ──────────────────────────────────────────────────────────

    private void recordSuccess(ResourceType type, String name) {
        reloadGeneration.incrementAndGet();
        lastReloadEpochMs    = System.currentTimeMillis();
        lastReloadedResource = name;
        for (HotReloadListener l : listeners) {
            try { l.onReloaded(type, name); }
            catch (Exception ignored) { /* listener must not crash reload path */ }
        }
    }

    private void notifyFailure(ResourceType type, String name, String reason) {
        for (HotReloadListener l : listeners) {
            try { l.onReloadFailed(type, name, reason); }
            catch (Exception ignored) {}
        }
    }
}
