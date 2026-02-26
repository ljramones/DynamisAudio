package io.dynamis.audio.api;

/**
 * Curve shape applied when mapping a raw RTPC input value [0..1] to a
 * modulation output value [0..1].
 *
 * LINEAR: output = input
 * LOGARITHMIC: output = log10(1 + input * 9) - compresses high end, useful for distance
 * EXPONENTIAL: output = (10^input - 1) / 9  - expands high end, useful for gain perception
 * SQUARED:     output = input * input        - gentle ease-in
 * SQRT:        output = sqrt(input)          - gentle ease-out
 *
 * All curves map [0..1] -> [0..1] and are monotonically increasing.
 * Evaluated by RtpcParameter.applyShape() - zero allocation, pure math.
 */
public enum RtpcCurve {
    LINEAR,
    LOGARITHMIC,
    EXPONENTIAL,
    SQUARED,
    SQRT;

    /**
     * Applies this curve to a normalised input value.
     *
     * @param t normalised input in [0..1]; clamped internally
     * @return  shaped output in [0..1]
     */
    public float apply(float t) {
        t = Math.max(0f, Math.min(1f, t)); // clamp - no exception on audio thread
        return switch (this) {
            case LINEAR      -> t;
            case LOGARITHMIC -> (float) (Math.log10(1.0 + t * 9.0));
            case EXPONENTIAL -> (float) ((Math.pow(10.0, t) - 1.0) / 9.0);
            case SQUARED     -> t * t;
            case SQRT        -> (float) Math.sqrt(t);
        };
    }
}
