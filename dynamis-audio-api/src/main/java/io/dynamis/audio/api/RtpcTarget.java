package io.dynamis.audio.api;

/**
 * Declares which EmitterParams field an RTPC modulation drives.
 *
 * When an RtpcParameter value changes, the emitter's virtual thread resolves
 * all targets mapped to that parameter and applies the shaped value to the
 * corresponding EmitterParams field on the next publishParams() call.
 *
 * MODULATION MODEL:
 *   The shaped value [0..1] is applied to the target field according to the
 *   target's own semantics (multiply, add, or replace). See each constant's
 *   Javadoc for the exact application rule.
 */
public enum RtpcTarget {

    /**
     * Multiplies EmitterParams.masterGain by the shaped value.
     * At shaped=1.0: gain is unaffected. At shaped=0.0: gain is silenced.
     * Use for distance-based volume attenuation or health-driven dampening.
     */
    MASTER_GAIN,

    /**
     * Sets EmitterParams.pitchMultiplier to lerp(0.5, 2.0, shapedValue).
     * At shaped=0.5: unity pitch. Allows +/-1 octave modulation.
     * Use for engine RPM, creature stress, environmental tension.
     */
    PITCH_MULTIPLIER,

    /**
     * Multiplies EmitterParams.reverbWetGain by the shaped value.
     * At shaped=1.0: reverb unaffected. At shaped=0.0: reverb silenced.
     * Use for "dryness" parameters - exterior vs interior transitions.
     */
    REVERB_WET_GAIN,

    /**
     * Scales the per-band occlusion uniformly by (1.0 - shapedValue).
     * At shaped=0.0: no additional occlusion. At shaped=1.0: fully occluded.
     * Use for material-driven low-frequency transmission effects.
     */
    OCCLUSION_SCALE
}
