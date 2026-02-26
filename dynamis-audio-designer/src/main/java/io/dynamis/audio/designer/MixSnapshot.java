package io.dynamis.audio.designer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named collection of BusState values representing a complete mix configuration.
 *
 * A MixSnapshot captures the desired gain and bypass state for every bus in the
 * hierarchy at a given gameplay moment. Named snapshots are defined offline by
 * the sound designer and activated at runtime by game code.
 *
 * PREDEFINED SNAPSHOT NAMES (convention, not enforced):
 *   "default"   - normal gameplay mix
 *   "combat"    - combat music up, ambience down, SFX loud
 *   "stealth"   - music suppressed, footsteps prominent, tension bed up
 *   "cutscene"  - SFX down, music prominent, dialogue clear
 *   "pause"     - all gameplay audio ducked, UI audio active
 *
 * IMMUTABLE: instances are created at definition time and never mutated.
 * HOT RELOAD: MixSnapshotManager.registerSnapshot() replaces definitions at runtime.
 */
public final class MixSnapshot {

    private final String name;
    private final float blendTimeSeconds;
    private final Map<String, BusState> busStates; // busName -> BusState

    private MixSnapshot(Builder builder) {
        this.name = builder.name;
        this.blendTimeSeconds = builder.blendTimeSeconds;
        // LinkedHashMap preserves definition order for deterministic iteration
        this.busStates = Collections.unmodifiableMap(
            new LinkedHashMap<>(builder.busStates));
    }

    /** Unique snapshot name. Used as the activation key. */
    public String name() { return name; }

    /**
     * Time in seconds to blend from the current mix to this snapshot.
     * 0.0 = immediate snap. Values < 0 are treated as 0.
     */
    public float blendTimeSeconds() { return blendTimeSeconds; }

    /** All bus states in this snapshot, indexed by bus name. Unmodifiable. */
    public Map<String, BusState> busStates() { return busStates; }

    /**
     * Returns the BusState for the given bus name.
     * Returns null if this snapshot does not define a state for that bus.
     */
    public BusState busState(String busName) {
        return busStates.get(busName);
    }

    /** Number of buses defined in this snapshot. */
    public int busCount() { return busStates.size(); }

    @Override
    public String toString() {
        return "MixSnapshot{name='" + name + "', blendTime=" +
            blendTimeSeconds + "s, buses=" + busStates.size() + "}";
    }

    // -- Builder --------------------------------------------------------------

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private float blendTimeSeconds = 0.5f; // default blend
        private final Map<String, BusState> busStates = new LinkedHashMap<>();

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("MixSnapshot name must not be blank");
            }
            this.name = name;
        }

        public Builder blendTime(float seconds) {
            this.blendTimeSeconds = Math.max(0f, seconds);
            return this;
        }

        public Builder bus(String busName, float gain) {
            busStates.put(busName, new BusState(busName, gain));
            return this;
        }

        public Builder bus(String busName, float gain, boolean bypassed) {
            busStates.put(busName, new BusState(busName, gain, bypassed));
            return this;
        }

        public Builder bus(BusState state) {
            busStates.put(state.busName(), state);
            return this;
        }

        public MixSnapshot build() {
            return new MixSnapshot(this);
        }
    }
}
