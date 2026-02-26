package io.dynamis.audio.api;

/**
 * Definition of a Real-Time Parameter Control.
 *
 * An RtpcParameter is a named, ranged, curve-shaped scalar that game code
 * can set at runtime to modulate audio properties (gain, pitch, send level, etc.).
 *
 * Instances are immutable definitions - they describe the parameter's shape
 * and range but do not hold a current value. Live values are held by RtpcValue
 * instances registered in the RtpcRegistry.
 *
 * NAMING CONVENTION: reverse-dot notation, e.g.
 *   "player.health"       -> drives low-health audio effects
 *   "environment.wetness" -> drives rain/reverb intensity
 *   "combat.intensity"    -> drives music and SFX blend
 *
 * ALLOCATION CONTRACT: evaluate() is zero-allocation - pure float arithmetic.
 */
public final class RtpcParameter {

    private final String name;
    private final float minValue;
    private final float maxValue;
    private final float defaultValue;
    private final RtpcCurve curve;

    /**
     * @param name         unique parameter name; must not be null or blank
     * @param minValue     minimum raw value (world units - e.g. 0.0)
     * @param maxValue     maximum raw value (world units - e.g. 100.0)
     * @param defaultValue initial value; clamped to [minValue..maxValue] at construction
     * @param curve        shape applied during normalisation
     * @throws IllegalArgumentException if name is blank, or maxValue <= minValue
     */
    public RtpcParameter(String name, float minValue, float maxValue,
                         float defaultValue, RtpcCurve curve) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RtpcParameter name must not be blank");
        }
        if (maxValue <= minValue) {
            throw new IllegalArgumentException(
                "maxValue must be > minValue for parameter '" + name + "'");
        }
        this.name = name;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.defaultValue = Math.max(minValue, Math.min(maxValue, defaultValue));
        this.curve = curve;
    }

    /** Convenience constructor with LINEAR curve. */
    public RtpcParameter(String name, float minValue, float maxValue, float defaultValue) {
        this(name, minValue, maxValue, defaultValue, RtpcCurve.LINEAR);
    }

    public String name() { return name; }
    public float minValue() { return minValue; }
    public float maxValue() { return maxValue; }
    public float defaultValue() { return defaultValue; }
    public RtpcCurve curve() { return curve; }

    /**
     * Normalises a raw value to [0..1] using this parameter's range and curve.
     *
     * Step 1: Clamp raw to [minValue..maxValue].
     * Step 2: Linear normalise to [0..1].
     * Step 3: Apply curve shape.
     *
     * ALLOCATION CONTRACT: Zero allocation - pure float arithmetic.
     *
     * @param rawValue raw value in world units
     * @return shaped, normalised value in [0..1]
     */
    public float evaluate(float rawValue) {
        float clamped = Math.max(minValue, Math.min(maxValue, rawValue));
        float normalised = (clamped - minValue) / (maxValue - minValue);
        return curve.apply(normalised);
    }

    /**
     * Maps a normalised value [0..1] back to world units [minValue..maxValue].
     * Inverse of the linear normalisation step - does NOT invert the curve.
     * Useful for displaying parameter values in the profiler overlay.
     */
    public float denormalise(float normalisedValue) {
        float clamped = Math.max(0f, Math.min(1f, normalisedValue));
        return minValue + clamped * (maxValue - minValue);
    }

    @Override
    public String toString() {
        return "RtpcParameter{name='" + name + "', range=[" +
            minValue + ".." + maxValue + "], curve=" + curve + "}";
    }
}
