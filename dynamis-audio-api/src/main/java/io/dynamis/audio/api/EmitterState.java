package io.dynamis.audio.api;

/**
 * Lifecycle states for a LogicalEmitter.
 *
 * The audio engine only mixes emitters in the PHYSICAL state.
 * All others are tracked but consume no DSP resources.
 *
 * Valid transitions:
 *   INACTIVE  -> SPAWNING   (trigger called)
 *   SPAWNING  -> VIRTUAL    (init complete, below promote threshold or budget full)
 *   SPAWNING  -> PHYSICAL   (init complete, above promote threshold and budget available)
 *   VIRTUAL   <-> PHYSICAL   (promotion / demotion with hysteresis)
 *   VIRTUAL   -> RELEASE    (destroyed, one-shot complete, or region culled)
 *   PHYSICAL  -> RELEASE    (destroyed, one-shot complete, or emergency cull)
 *   RELEASE   -> INACTIVE   (release tail complete, resources returned to pools)
 */
public enum EmitterState {

    /**
     * Not yet triggered or fully released.
     * No virtual thread. No position tracking. No AcousticWorld queries. Zero cost.
     */
    INACTIVE,

    /**
     * Virtual thread created. Emitter is initialising: resolving acoustic material,
     * acquiring room membership, evaluating initial priority score.
     * Transitions to VIRTUAL or PHYSICAL immediately on completion.
     */
    SPAWNING,

    /**
     * Tracked but not mixed. Position updated, priority score maintained,
     * occlusion estimated. No DSP slots consumed.
     * Promoted to PHYSICAL when budget allows and priority is sufficient.
     */
    VIRTUAL,

    /**
     * Owns a DSP slot and is actively mixed.
     * Full acoustic simulation: HRTF, occlusion, reflections, portal propagation.
     * Demoted to VIRTUAL under budget pressure or when out of audible range.
     */
    PHYSICAL,

    /**
     * Playback complete or emitter destroyed.
     * Virtual thread executes release tail (fade-out if PHYSICAL, cleanup if VIRTUAL),
     * then terminates. DSP slot returned to pool on demotion, not before fade completes.
     */
    RELEASE
}
