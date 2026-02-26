package io.dynamis.audio.api;

/**
 * Designer-assigned importance level for a LogicalEmitter.
 *
 * Importance determines pool assignment (CRITICAL -> reserved pool) and
 * acts as the first tie-breaker in deterministic priority ordering.
 *
 * Ordinal ordering is used in tie-breaking: higher ordinal = lower importance.
 * Do NOT reorder these constants without updating LogicalEmitter comparator logic.
 *
 * CRITICAL flag behaviour:
 *   - Assigns emitter to the criticalReserve pool, not the normal pool.
 *   - Does NOT override priority score to 1.0 - scoring runs identically within both pools.
 *   - criticalReserve must not exceed 25% of physicalBudget (hard startup invariant).
 *   - Intended for: player voice/footsteps, active dialogue, quest-critical cues, death audio.
 */
public enum EmitterImportance {

    /** Reserved pool. Never silently displaced. Audit if count exceeds 20% of physical budget. */
    CRITICAL,

    /** Above-normal priority within the standard pool. Boss audio, key NPC dialogue. */
    HIGH,

    /** Default for most in-world emitters. */
    NORMAL,

    /** Background ambience, distant environmental sounds. First displaced under pressure. */
    LOW
}
