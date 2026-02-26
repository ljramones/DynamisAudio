package io.dynamis.audio.designer;

import io.dynamis.audio.api.AcousticConstants;
import io.dynamis.audio.api.MixBusControl;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages named MixSnapshot definitions and drives blend interpolation.
 *
 * Game code activates snapshots by name. The manager interpolates bus gains
 * from the current state toward the target snapshot over the configured blend time.
 * Interpolation runs in the DSP render loop via update(), called once per block.
 *
 * BLEND MODEL:
 *   When activateSnapshot() is called:
 *     - blendOrigin = current per-bus gain values (captured immediately)
 *     - blendTarget = target snapshot's BusState values
 *     - blendProgress = 0.0 -> advances to 1.0 over blendTimeSeconds
 *   Each update() call advances blendProgress by blockDurationSeconds.
 *   At blendProgress = 1.0: gains snap to exact target values.
 *
 * BUS RESOLUTION:
 *   Buses are registered by name via registerBus(). Unresolved bus names in a
 *   snapshot are silently skipped - allows snapshots to be defined before the
 *   full bus hierarchy exists.
 *
 * THREAD SAFETY:
 *   activateSnapshot() - called from game thread.
 *   update()           - called from DSP render worker thread.
 *   VarHandle or volatile fields guard the blend state crossing thread boundary.
 *   snapshots / buses maps: ConcurrentHashMap - safe for concurrent access.
 *
 * ALLOCATION CONTRACT:
 *   update() - no allocation. Iterates pre-built maps, writes float fields on buses.
 *   activateSnapshot() - allocates blend origin snapshot; called from game thread only.
 */
public final class MixSnapshotManager {

    // -- Snapshot registry ----------------------------------------------------

    private final ConcurrentHashMap<String, MixSnapshot> snapshots = new ConcurrentHashMap<>();

    // -- Bus registry ---------------------------------------------------------

    /** Buses managed by this snapshot system, indexed by AudioBus.name(). */
    private final ConcurrentHashMap<String, MixBusControl> buses = new ConcurrentHashMap<>();

    // -- Blend state (written by game thread, read by DSP thread) ------------

    /** The snapshot currently being blended toward. Null = no active blend. */
    private volatile MixSnapshot blendTarget = null;

    /** Per-bus gain values at the moment the blend started. */
    private volatile float[] originGains = new float[0];

    /** Bus names in the same order as originGains. Set once per activateSnapshot(). */
    private volatile String[] originBusNames = new String[0];

    /** Blend progress [0..1]. Advances each update() call. */
    private volatile float blendProgress = 1.0f; // 1.0 = no blend active

    /** Blend increment per DSP block - derived from blendTimeSeconds at activation. */
    private volatile float blendIncrement = 0f;

    // -- Constants ------------------------------------------------------------

    private static final float BLOCK_DURATION_SECONDS =
        (float) AcousticConstants.DSP_BLOCK_SIZE / AcousticConstants.SAMPLE_RATE;

    // -- Snapshot management --------------------------------------------------

    /**
     * Registers a snapshot definition.
     * Replaces any existing definition with the same name (hot-reload path).
     */
    public void registerSnapshot(MixSnapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot must not be null");
        }
        snapshots.put(snapshot.name(), snapshot);
    }

    /** Returns the snapshot definition for the given name, or null. */
    public MixSnapshot getSnapshot(String name) {
        return snapshots.get(name);
    }

    /** Removes a snapshot definition. Active blends toward this snapshot continue. */
    public void unregisterSnapshot(String name) {
        snapshots.remove(name);
    }

    /** Number of registered snapshots. */
    public int snapshotCount() { return snapshots.size(); }

    // -- Bus registration -----------------------------------------------------

    /**
     * Registers a bus for snapshot control.
     * Must be called before any snapshot that targets this bus is activated.
     */
    public void registerBus(MixBusControl bus) {
        if (bus == null) {
            throw new NullPointerException("bus must not be null");
        }
        buses.put(bus.name(), bus);
    }

    /** Removes a bus from snapshot control. */
    public void unregisterBus(String busName) {
        buses.remove(busName);
    }

    // -- Activation -----------------------------------------------------------

    /**
     * Activates a named snapshot, beginning interpolation toward its bus states.
     *
     * Captures the current bus gains as the blend origin.
     * Sets blendProgress = 0 and computes blendIncrement from snapshot's blendTimeSeconds.
     *
     * Called from game thread.
     *
     * @param name name of a registered MixSnapshot
     * @throws IllegalArgumentException if name is not registered
     */
    public void activateSnapshot(String name) {
        MixSnapshot target = snapshots.get(name);
        if (target == null) {
            throw new IllegalArgumentException(
                "MixSnapshot not registered: '" + name + "'");
        }

        // Capture current bus gains as origin - allocates here (game thread, not DSP)
        int busCount = buses.size();
        String[] names = buses.keySet().toArray(new String[busCount]);
        float[] gains = new float[busCount];
        for (int i = 0; i < busCount; i++) {
            MixBusControl bus = buses.get(names[i]);
            gains[i] = (bus != null) ? bus.getGain() : 1.0f;
        }

        float blendTime = target.blendTimeSeconds();
        this.originBusNames = names;
        this.originGains = gains;
        this.blendTarget = target;
        this.blendProgress = 0.0f;
        this.blendIncrement = (blendTime > 0f)
            ? BLOCK_DURATION_SECONDS / blendTime
            : 1.0f; // immediate snap
    }

    /**
     * Activates a snapshot immediately with no blend (blendTime = 0).
     * Equivalent to activateSnapshot but ignores the snapshot's blendTimeSeconds.
     */
    public void activateSnapshotImmediate(String name) {
        MixSnapshot target = snapshots.get(name);
        if (target == null) {
            throw new IllegalArgumentException(
                "MixSnapshot not registered: '" + name + "'");
        }
        // Apply gains directly - no blend loop needed
        for (Map.Entry<String, BusState> entry : target.busStates().entrySet()) {
            MixBusControl bus = buses.get(entry.getKey());
            if (bus != null) {
                bus.setGain(entry.getValue().gain());
                bus.setBypassed(entry.getValue().bypassed());
            }
        }
        blendProgress = 1.0f;
        blendTarget = null;
    }

    // -- DSP update (called per block by SoftwareMixer) ----------------------

    /**
     * Advances the blend interpolation by one DSP block.
     *
     * Called by the DSP render worker thread at the start of renderBlock(),
     * before the bus graph is processed.
     *
     * ALLOCATION CONTRACT: Zero allocation. Reads volatile fields, iterates arrays,
     * writes float/boolean fields on registered AudioBus instances.
     */
    public void update() {
        MixSnapshot target = blendTarget;
        if (target == null || blendProgress >= 1.0f) {
            return;
        }

        blendProgress = Math.min(1.0f, blendProgress + blendIncrement);
        float t = blendProgress;

        String[] names = originBusNames;
        float[] origins = originGains;

        for (int i = 0; i < names.length; i++) {
            BusState targetState = target.busState(names[i]);
            if (targetState == null) {
                continue;
            }

            MixBusControl bus = buses.get(names[i]);
            if (bus == null) {
                continue;
            }

            float originGain = origins[i];
            float blended = originGain + (targetState.gain() - originGain) * t;
            bus.setGain(blended);

            if (t >= 1.0f) {
                bus.setBypassed(targetState.bypassed());
            }
        }

        if (blendProgress >= 1.0f) {
            blendTarget = null; // blend complete - clear reference
        }
    }

    // -- Status ---------------------------------------------------------------

    /** True if a blend is currently in progress. */
    public boolean isBlending() { return blendProgress < 1.0f; }

    /** Blend progress [0..1]. 1.0 = complete or no blend active. */
    public float blendProgress() { return blendProgress; }

    /** The snapshot currently being blended toward, or null if idle. */
    public MixSnapshot activeTarget() { return blendTarget; }
}
