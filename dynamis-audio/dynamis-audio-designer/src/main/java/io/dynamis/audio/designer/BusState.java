package io.dynamis.audio.designer;

/**
 * Immutable per-bus state captured in a MixSnapshot.
 *
 * Records the gain and bypass state for one named bus at the moment the
 * snapshot is defined. MixSnapshotManager interpolates between BusState
 * instances when blending between snapshots.
 *
 * IMMUTABLE: instances are created at snapshot definition time and never mutated.
 */
public final class BusState {

    private final String busName;
    private final float gain;
    private final boolean bypassed;

    /**
     * @param busName  name matching the target AudioBus.name()
     * @param gain     target gain [0..1]; clamped at construction
     * @param bypassed target bypass state
     */
    public BusState(String busName, float gain, boolean bypassed) {
        if (busName == null || busName.isBlank()) {
            throw new IllegalArgumentException("busName must not be blank");
        }
        this.busName = busName;
        this.gain = Math.max(0f, Math.min(1f, gain));
        this.bypassed = bypassed;
    }

    /** Convenience constructor - not bypassed. */
    public BusState(String busName, float gain) {
        this(busName, gain, false);
    }

    public String busName() { return busName; }
    public float gain() { return gain; }
    public boolean bypassed() { return bypassed; }

    /**
     * Linearly interpolates gain between this state and a target state.
     * Bypass state snaps to target when t >= 1.0 (no interpolation for boolean).
     *
     * @param target destination BusState; must have the same busName
     * @param t      blend factor [0..1]; 0 = this, 1 = target
     * @return interpolated BusState
     */
    public BusState lerp(BusState target, float t) {
        if (!this.busName.equals(target.busName())) {
            throw new IllegalArgumentException(
                "Cannot lerp BusState between different buses: " +
                this.busName + " vs " + target.busName());
        }
        float blendedGain = this.gain + (target.gain() - this.gain) * t;
        boolean blendedBypass = (t >= 1.0f) ? target.bypassed() : this.bypassed;
        return new BusState(busName, blendedGain, blendedBypass);
    }

    @Override
    public String toString() {
        return "BusState{bus='" + busName + "', gain=" + gain +
            ", bypassed=" + bypassed + "}";
    }
}
