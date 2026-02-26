package io.dynamis.audio.core;

import io.dynamis.audio.api.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the promotion and demotion of LogicalEmitters between VIRTUAL and PHYSICAL states.
 *
 * Runs on the voice manager thread - a single dedicated thread that evaluates the
 * priority queue every SCORE_UPDATE_BLOCKS DSP blocks.
 *
 * BUDGET MODEL:
 *   Two pools: normal (normalBudget slots) and critical (criticalReserve slots).
 *   CRITICAL emitters compete only within criticalReserve.
 *   Normal emitters compete only within normalBudget.
 *   Neither pool can consume the other's slots.
 *
 * THREAD SAFETY:
 *   emitters list: CopyOnWriteArrayList - game thread adds/removes, voice manager reads.
 *   Promotion/demotion decisions: single-threaded on voice manager thread.
 *   DSP slot assignment: written by voice manager, read by DSP worker via volatile int.
 *
 * ALLOCATION CONTRACT:
 *   evaluateBudget() may allocate (sorts a list) - runs on voice manager thread, not DSP thread.
 *   No allocation on the DSP render thread. VoiceManager never touches DSP buffers directly.
 */
public final class VoiceManager {

    // -- Configuration --------------------------------------------------------

    private final int physicalBudget;
    private final int criticalReserve;
    private final int normalBudget;

    // -- Emitter registry -----------------------------------------------------

    /**
     * All live emitters. Game thread appends; voice manager iterates.
     * CopyOnWriteArrayList trades write cost for lock-free reads on the hot evaluation path.
     */
    private final CopyOnWriteArrayList<LogicalEmitter> emitters = new CopyOnWriteArrayList<>();

    // -- DSP slot tracking ----------------------------------------------------

    /** Slots currently occupied in the normal pool. */
    private int normalSlotsUsed = 0;

    /** Slots currently occupied in the critical pool. */
    private int criticalSlotsUsed = 0;

    // -- Profiler metrics (volatile - read by profiler overlay thread) -------

    private volatile int lastPhysicalCount = 0;
    private volatile int lastVirtualCount = 0;
    private volatile int promotionsPerCycle = 0;
    private volatile int demotionsPerCycle = 0;
    /** Optional external cap from VoicePool free-count/capacity; Integer.MAX_VALUE disables. */
    private volatile int voicePoolCapacity = Integer.MAX_VALUE;

    // -- Construction ---------------------------------------------------------

    /**
     * @param physicalBudget  total DSP slots available
     * @param criticalReserve slots reserved exclusively for CRITICAL emitters;
     *                        must be <= physicalBudget / 4 (hard invariant)
     * @throws IllegalArgumentException if budget invariants are violated
     */
    public VoiceManager(int physicalBudget, int criticalReserve) {
        if (criticalReserve > physicalBudget / 4) {
            throw new IllegalArgumentException(
                "criticalReserve " + criticalReserve +
                " exceeds 25% of physicalBudget " + physicalBudget);
        }
        if (physicalBudget < 1) {
            throw new IllegalArgumentException("physicalBudget must be >= 1");
        }
        this.physicalBudget = physicalBudget;
        this.criticalReserve = criticalReserve;
        this.normalBudget = physicalBudget - criticalReserve;
    }

    /** Constructs with default budget from AcousticConstants. */
    public VoiceManager() {
        this(AcousticConstants.DEFAULT_PHYSICAL_BUDGET,
             AcousticConstants.DEFAULT_CRITICAL_RESERVE);
    }

    // -- Emitter lifecycle ----------------------------------------------------

    /**
     * Registers an emitter with the voice manager.
     * Called by game thread when an emitter is triggered.
     * Thread-safe: CopyOnWriteArrayList.
     */
    public void register(LogicalEmitter emitter) {
        emitters.add(emitter);
    }

    /**
     * Unregisters an emitter. Called after its virtual thread completes (INACTIVE state).
     * Thread-safe: CopyOnWriteArrayList.
     */
    public void unregister(LogicalEmitter emitter) {
        emitters.remove(emitter);
        // Ensure slot accounting is consistent if emitter released without going through
        // normal demotion path (e.g. emergency cull)
        if (emitter.getDspSlot() >= 0) {
            returnSlot(emitter);
        }
    }

    // -- Budget evaluation (voice manager thread) ----------------------------

    /**
     * Evaluates the priority queue and performs promotions and demotions.
     *
     * Called by the voice manager thread every SCORE_UPDATE_BLOCKS DSP blocks.
     * May allocate (sort) - must never be called from the DSP render thread.
     *
     * Algorithm:
     *   1. Partition emitters into critical and normal lists.
     *   2. Sort each list by priority (descending score, then tie-break rules).
     *   3. Promote top-N from each list to fill available slots.
     *   4. Demote any PHYSICAL emitter that is now below its pool's cut line.
     *   5. Update profiler metrics.
     */
    public void evaluateBudget() {
        List<LogicalEmitter> criticalList = new ArrayList<>();
        List<LogicalEmitter> normalList = new ArrayList<>();

        int promotions = 0;
        int demotions = 0;

        // Partition live emitters by pool
        for (LogicalEmitter e : emitters) {
            EmitterState s = e.getState();
            if (s == EmitterState.INACTIVE || s == EmitterState.RELEASE) continue;
            if (e.importance == EmitterImportance.CRITICAL) {
                criticalList.add(e);
            } else {
                normalList.add(e);
            }
        }

        criticalList.sort(Comparator.comparingInt((LogicalEmitter e) -> e.hashCode()).thenComparing(
            (a, b) -> LogicalEmitter.compareByPriority(a, b)));
        criticalList.sort((a, b) -> LogicalEmitter.compareByPriority(a, b));
        normalList.sort((a, b) -> LogicalEmitter.compareByPriority(a, b));

        int currentPhysical = 0;
        for (LogicalEmitter e : emitters) {
            if (e.getState() == EmitterState.PHYSICAL) currentPhysical++;
        }
        int availableByPoolCap = Math.max(0, voicePoolCapacity - currentPhysical);
        if (voicePoolCapacity != Integer.MAX_VALUE) {
            int promotionCandidates = 0;
            for (LogicalEmitter e : criticalList) {
                if (e.getState() == EmitterState.VIRTUAL
                    && e.getScore() >= AcousticConstants.PROMOTE_THRESHOLD) {
                    promotionCandidates++;
                }
            }
            for (LogicalEmitter e : normalList) {
                if (e.getState() == EmitterState.VIRTUAL
                    && e.getScore() >= AcousticConstants.PROMOTE_THRESHOLD) {
                    promotionCandidates++;
                }
            }
            if (promotionCandidates > availableByPoolCap) {
                System.err.println("[DynamisAudio] VoiceManager: "
                    + promotionCandidates + " promotion candidates exceed VoicePool capacity ("
                    + availableByPoolCap + "). Trimming.");
            }
        }

        // Evaluate critical pool
        demotions += evaluatePool(criticalList, criticalReserve);
        int criticalPromotions = promotePool(
            criticalList, Math.min(criticalReserve, availableByPoolCap));
        promotions += criticalPromotions;
        availableByPoolCap = Math.max(0, availableByPoolCap - criticalPromotions);

        // Evaluate normal pool
        demotions += evaluatePool(normalList, normalBudget);
        promotions += promotePool(normalList, Math.min(normalBudget, availableByPoolCap));

        // Update profiler metrics
        int physical = 0;
        int virtual = 0;
        for (LogicalEmitter e : emitters) {
            if (e.getState() == EmitterState.PHYSICAL) physical++;
            else if (e.getState() == EmitterState.VIRTUAL) virtual++;
        }
        lastPhysicalCount = physical;
        lastVirtualCount = virtual;
        promotionsPerCycle = promotions;
        demotionsPerCycle = demotions;

        // Warn if CRITICAL pool overflowed
        if (criticalList.size() > criticalReserve) {
            // Phase 1+: emit JFR BudgetExhaustedEvent and profiler warning
            // Phase 0: silent - profiler overlay will show CRITICAL count
        }
    }

    /**
     * Demotes PHYSICAL emitters in this pool that fall below DEMOTE_THRESHOLD
     * or are beyond the pool capacity cut line.
     *
     * @return number of demotions performed
     */
    private int evaluatePool(List<LogicalEmitter> sorted, int poolCapacity) {
        int demotions = 0;
        for (int i = 0; i < sorted.size(); i++) {
            LogicalEmitter e = sorted.get(i);
            if (e.getState() != EmitterState.PHYSICAL) continue;
            boolean belowThreshold = e.getScore() < AcousticConstants.DEMOTE_THRESHOLD;
            boolean beyondCapacity = i >= poolCapacity;
            if (belowThreshold || beyondCapacity) {
                demote(e);
                demotions++;
            }
        }
        return demotions;
    }

    /**
     * Promotes the highest-scoring VIRTUAL emitters in this pool up to poolCapacity.
     *
     * @return number of promotions performed
     */
    private int promotePool(List<LogicalEmitter> sorted, int poolCapacity) {
        int promotions = 0;
        int physicalInPool = 0;

        for (LogicalEmitter e : sorted) {
            if (e.getState() == EmitterState.PHYSICAL) physicalInPool++;
        }

        for (LogicalEmitter e : sorted) {
            if (physicalInPool >= poolCapacity) break;
            if (e.getState() == EmitterState.VIRTUAL
                    && e.getScore() >= AcousticConstants.PROMOTE_THRESHOLD) {
                promote(e);
                physicalInPool++;
                promotions++;
            }
        }
        return promotions;
    }

    // -- Promotion / demotion -------------------------------------------------

    private void promote(LogicalEmitter emitter) {
        int slot = allocateSlot(emitter);
        if (slot < 0) return; // no slot available - should not happen after evaluatePool guard
        emitter.assignDspSlot(slot);
        // State transition: virtual thread will observe state change on next park/unpark
        // Phase 0: direct state manipulation acceptable; Phase 1+ uses structured signal
        setState(emitter, EmitterState.PHYSICAL);
    }

    private void demote(LogicalEmitter emitter) {
        // Phase 0: immediate demotion - no fade applied yet.
        // Phase 1+: apply DEMOTION_FADE_MS fade before returnSlot().
        returnSlot(emitter);
        setState(emitter, EmitterState.VIRTUAL);
    }

    private int allocateSlot(LogicalEmitter emitter) {
        if (emitter.importance == EmitterImportance.CRITICAL) {
            if (criticalSlotsUsed < criticalReserve) {
                return criticalSlotsUsed++;
            }
            return -1;
        } else {
            if (normalSlotsUsed < normalBudget) {
                return criticalReserve + normalSlotsUsed++;
            }
            return -1;
        }
    }

    private void returnSlot(LogicalEmitter emitter) {
        if (emitter.importance == EmitterImportance.CRITICAL) {
            if (criticalSlotsUsed > 0) criticalSlotsUsed--;
        } else {
            if (normalSlotsUsed > 0) normalSlotsUsed--;
        }
        emitter.releaseDspSlot();
    }

    private static void setState(LogicalEmitter emitter, EmitterState newState) {
        // Reflective state write is avoided - LogicalEmitter exposes destroy() and trigger().
        // Phase 0: VoiceManager signals state change via destroy() for RELEASE only.
        // VIRTUAL/PHYSICAL transitions in Phase 0 are approximated by the emitter's own
        // run() loop. This will be replaced by a proper signal mechanism in Phase 1.
        // For now, this method is a placeholder for the Phase 1 signalling contract.
    }

    // -- Profiler metrics (read by overlay thread) ----------------------------

    /** Current number of PHYSICAL voices across both pools. */
    public int getPhysicalCount() { return lastPhysicalCount; }

    /** Current number of VIRTUAL (tracked but unmixed) emitters. */
    public int getVirtualCount() { return lastVirtualCount; }

    /** Promotions performed in the most recent evaluateBudget() cycle. */
    public int getPromotionsPerCycle() { return promotionsPerCycle; }

    /** Demotions performed in the most recent evaluateBudget() cycle. */
    public int getDemotionsPerCycle() { return demotionsPerCycle; }

    /** Total physical budget (both pools combined). */
    public int getPhysicalBudget() { return physicalBudget; }

    /** Slots reserved for CRITICAL emitters. */
    public int getCriticalReserve() { return criticalReserve; }

    /** Slots available for normal emitters. */
    public int getNormalBudget() { return normalBudget; }

    /** Total live registered emitters (VIRTUAL + PHYSICAL + SPAWNING). */
    public int getRegisteredCount() { return emitters.size(); }

    /** Optional pool-capacity hint used to trim promotions when pool is full. */
    public void setVoicePoolCapacity(int capacity) {
        this.voicePoolCapacity = (capacity <= 0) ? 0 : capacity;
    }

    /** Exposes registered emitters for integration tests and diagnostics. */
    public List<LogicalEmitter> emitters() {
        return java.util.Collections.unmodifiableList(emitters);
    }
}
