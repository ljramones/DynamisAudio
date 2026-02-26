package io.dynamis.audio.core;

import io.dynamis.audio.api.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A logical sound source in the world, owned by one virtual thread.
 *
 * Each LogicalEmitter represents one persistent in-world sound source - a fire,
 * an engine, a speaking character. One-shot sounds (footsteps, impacts) are also
 * LogicalEmitters; they transition to RELEASE immediately after single playback.
 *
 * THREADING MODEL:
 *   - One virtual thread owns this emitter for its entire in-world lifetime.
 *   - The virtual thread drives all state transitions, priority scoring, and
 *     AcousticWorld queries. It does NOT write DSP buffers.
 *   - The DSP render worker reads EmitterParams via acquireParamsForBlock().
 *   - The game thread writes position/RTPC via setPosition() and setRtpc().
 *   - VarHandle acquire/release provides all required memory ordering.
 *     No synchronized blocks. No explicit locks on the hot path.
 *
 * ALLOCATION CONTRACT:
 *   - No allocation after construction. All buffers pre-allocated.
 *   - publishParams() must use a pre-allocated EmitterParamsWriter, not an inline lambda.
 *   - acquireParamsForBlock() is zero-allocation on the DSP read path.
 */
public final class LogicalEmitter {

    // -- Identity -------------------------------------------------------------

    private static final AtomicLong ID_SEQUENCE = new AtomicLong(1L);

    /** Monotonically assigned at construction. Used for deterministic tie-breaking. */
    public final long emitterId;

    /** Human-readable tag from EmitterDef. Used to name the virtual thread. */
    public final String tag;

    /** Designer-assigned importance. Determines pool assignment and tie-break order. */
    public final EmitterImportance importance;

    // -- State ----------------------------------------------------------------

    /**
     * Current lifecycle state. Written only by the owning virtual thread.
     * Read by voice manager and DSP worker - volatile for visibility.
     */
    private volatile EmitterState state = EmitterState.INACTIVE;

    // -- Double-buffered EmitterParams ---------------------------------------

    private static final VarHandle PUBLISHED_IDX;

    static {
        try {
            PUBLISHED_IDX = MethodHandles.lookup()
                .findVarHandle(LogicalEmitter.class, "publishedIndex", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Two pre-allocated parameter snapshots - back and front. */
    final EmitterParams[] params = {
        new EmitterParams(),
        new EmitterParams()
    };

    /**
     * Index of the most recently published (front) parameter buffer.
     * The DSP render worker reads params[publishedIndex].
     * The virtual thread writes into params[1 - publishedIndex] during publishParams(),
     * then flips this index with release semantics to make the write visible.
     * Managed via VarHandle acquire/release - no synchronized, no locks.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int publishedIndex = 0;

    // -- Priority scoring (virtual thread writes, voice manager reads) -------

    /** Cached priority score. Updated every SCORE_UPDATE_BLOCKS DSP blocks. */
    private volatile float score = 0f;

    /** Physical DSP slot assigned by voice manager. -1 = no slot. */
    private volatile int dspSlot = -1;

    // -- Listener position (written by game thread, read by virtual thread) ---

    /**
     * Listener world position - used for occlusion ray direction computation.
     * Written by game thread via setListenerPosition().
     * // PHASE 0 ONLY: writes front+back buffers directly (same pattern as setPosition).
     * // Phase 1+: replace with proper signal mechanism.
     */
    private volatile float listenerX = 0f;
    private volatile float listenerY = 0f;
    private volatile float listenerZ = 0f;

    /** Snapshot manager - provides acoustic world state for occlusion ray queries. */
    private volatile AcousticSnapshotManager acousticSnapshotManager = null;

    /** Pre-allocated scratch buffer for per-band occlusion computation. Zero allocation. */
    private final float[] occlusionScratch =
        new float[AcousticConstants.ACOUSTIC_BAND_COUNT];

    /**
     * Pre-allocated scratch buffer for per-band RT60 computation. Zero allocation.
     * Mirrors ReverbEstimator.computeRt60() output array - see sync comment below.
     */
    private final float[] rt60Scratch =
        new float[AcousticConstants.ACOUSTIC_BAND_COUNT];

    /** Pre-allocated AcousticHit for occlusion ray queries - zero allocation on score path. */
    private final AcousticHit occlusionHit = new AcousticHit();

    /**
     * Pre-allocated hit buffer for multi-hit reflection ray fan.
     * Capacity matches EarlyReflectionNode.MAX_REFLECTIONS.
     */
    private final AcousticHitBuffer reflectionHits = new AcousticHitBuffer(6);

    /**
     * Optional early reflection sink wired when this emitter is PHYSICAL.
     * Core depends on API seam only; DSP provides the implementation.
     */
    private volatile EarlyReflectionSink earlyReflectionSink = null;

    /** Pre-allocated occlusion params writer. Reused every score update. */
    private OcclusionParamsWriter occlusionWriter = null;

    // -- Score update timing --------------------------------------------------

    private static final long SCORE_UPDATE_INTERVAL_NS =
        (long) (AcousticConstants.SCORE_UPDATE_BLOCKS *
                AcousticConstants.DSP_BLOCK_SIZE *
                (1_000_000_000.0 / AcousticConstants.SAMPLE_RATE));

    // -- Construction ---------------------------------------------------------

    /**
     * @param tag        EmitterDef name - used in virtual thread name and profiler
     * @param importance pool assignment and tie-break order
     */
    public LogicalEmitter(String tag, EmitterImportance importance) {
        this.emitterId = ID_SEQUENCE.getAndIncrement();
        this.tag = tag;
        this.importance = importance;
        params[0].reset();
        params[1].reset();
    }

    // -- DSP render worker API (zero-allocation hot path) --------------------

    /**
     * Returns the current stable parameter snapshot for this DSP block.
     *
     * Called by the DSP render worker at the start of every block.
     * The returned EmitterParams is immutable for the duration of one block -
     * the virtual thread will not flip publishedIndex until the next publishParams() call.
     *
     * ALLOCATION CONTRACT: Zero allocation. One VarHandle read. One array index.
     */
    public EmitterParams acquireParamsForBlock() {
        // DSP reads from the most recently published (front) buffer.
        int pi = (int) PUBLISHED_IDX.getAcquire(this);
        return params[pi];
    }

    /**
     * Publishes updated parameters for the next DSP block.
     *
     * Called by the virtual thread when in PHYSICAL state.
     * Writes into the back buffer, then atomically flips the index.
     *
     * ALLOCATION CONTRACT: The writer argument must be a pre-allocated reusable
     * EmitterParamsWriter instance - never an inline lambda. Inline lambdas allocate.
     *
     * @param writer pre-allocated updater that mutates EmitterParams fields
     */
    public void publishParams(EmitterParamsWriter writer) {
        int frontIdx = (int) PUBLISHED_IDX.getAcquire(this);
        int backIdx = 1 - frontIdx;
        // Initialise back buffer from current front so unchanged fields carry over.
        params[backIdx].copyFrom(params[frontIdx]);
        writer.write(params[backIdx]);
        // Flip: back buffer is now the new front - visible to DSP on next acquireParamsForBlock().
        PUBLISHED_IDX.setRelease(this, backIdx);
    }

    // -- Voice manager API ----------------------------------------------------

    /** Returns the current lifecycle state. Safe to read from any thread. */
    public EmitterState getState() { return state; }

    /** Returns the cached priority score. Written by virtual thread, read by voice manager. */
    public float getScore() { return score; }

    /** Returns the assigned DSP slot, or -1 if not currently physical. */
    public int getDspSlot() { return dspSlot; }

    /**
     * Assigns a DSP slot. Called by voice manager on promotion.
     * Must only be called when transitioning to PHYSICAL state.
     */
    public void assignDspSlot(int slot) { this.dspSlot = slot; }

    /**
     * Releases the DSP slot. Called by voice manager on demotion after fade completes.
     */
    public void releaseDspSlot() { this.dspSlot = -1; }

    // -- Game thread API ------------------------------------------------------

    /**
     * Updates emitter world position from the game thread.
     * Written into the back buffer's position fields directly.
     * The virtual thread will incorporate this on its next publishParams() call.
     *
     * This is a best-effort write - no strong ordering required between
     * game thread position updates and DSP block boundaries.
     */
    public void setPosition(float x, float y, float z) {
        // PHASE 0 ONLY: this mirrors writes into the current front buffer as a pragmatic
        // compatibility path. Replace with proper cross-thread signal/update flow in Phase 1.
        // Write into both back and current front snapshots so external game-thread
        // updates are preserved across the front->back copy stage in publishParams().
        int frontIdx = (int) PUBLISHED_IDX.getAcquire(this);
        EmitterParams front = params[frontIdx];
        EmitterParams back = params[1 - frontIdx];
        front.posX = x;
        front.posY = y;
        front.posZ = z;
        back.posX = x;
        back.posY = y;
        back.posZ = z;
    }

    /**
     * Updates the listener world position from the game thread.
     * Used by the virtual thread to compute emitter->listener ray direction for occlusion.
     *
     * // PHASE 0 ONLY: direct volatile write. Phase 1+: replace with signal mechanism.
     */
    public void setListenerPosition(float x, float y, float z) {
        this.listenerX = x;
        this.listenerY = y;
        this.listenerZ = z;
    }

    /** Returns the cached listener X position. Read by virtual thread only. */
    public float listenerX() { return listenerX; }

    /** Returns the cached listener Y position. */
    public float listenerY() { return listenerY; }

    /** Returns the cached listener Z position. */
    public float listenerZ() { return listenerZ; }

    /**
     * Wires the acoustic snapshot manager for occlusion ray queries.
     * Called by VoiceManager or EventSystem after emitter creation.
     * Null-safe - if not set, updateScore() uses Phase 0 flat occlusion approximation.
     */
    public void setAcousticSnapshotManager(AcousticSnapshotManager manager) {
        this.acousticSnapshotManager = manager;
    }

    /**
     * Wires the early reflection sink for this emitter.
     * Pass null on demotion to VIRTUAL to stop reflection computation.
     */
    public void setEarlyReflectionNode(EarlyReflectionSink sink) {
        this.earlyReflectionSink = sink;
    }

    public EarlyReflectionSink getEarlyReflectionNode() {
        return earlyReflectionSink;
    }

    // -- Virtual thread lifecycle --------------------------------------------

    /**
     * Entry point for the owning virtual thread.
     *
     * Drives the full emitter lifecycle from SPAWNING through RELEASE.
     * The virtual thread parks between score updates when VIRTUAL to avoid
     * carrier thread starvation.
     *
     * Thread naming convention: "audio-emitter-{id}-{tag}"
     * This makes JVM thread dumps immediately readable without custom tooling.
     */
    public void run() {
        state = EmitterState.SPAWNING;
        initialize();

        while (state != EmitterState.INACTIVE) {
            switch (state) {
                case SPAWNING -> initialize();

                case VIRTUAL -> {
                    // Park until next score update is due.
                    // Voice manager will unpark us on promotion or state change.
                    LockSupport.parkNanos(SCORE_UPDATE_INTERVAL_NS);
                    updateScore();
                }

                case PHYSICAL -> {
                    // Prepare acoustic parameters for the next DSP block.
                    // DSP render worker reads these via acquireParamsForBlock().
                    prepareAcousticParameters();
                    // Park until voice manager signals next block boundary.
                    // Phase 0: simple timed park approximating block duration.
                    LockSupport.parkNanos(SCORE_UPDATE_INTERVAL_NS);
                }

                case RELEASE -> {
                    executeReleaseTail();
                    state = EmitterState.INACTIVE;
                }

                case INACTIVE -> {
                    // Should not reach here in the run loop - defensive break
                }
            }
        }
    }

    /**
     * Triggers this emitter - transitions from INACTIVE to SPAWNING and starts
     * the owning virtual thread.
     *
     * Called by game code. Returns immediately - the virtual thread runs concurrently.
     */
    public void trigger() {
        if (state != EmitterState.INACTIVE) {
            throw new IllegalStateException(
                "Cannot trigger emitter " + emitterId + " in state " + state);
        }
        state = EmitterState.SPAWNING;
        Thread.ofVirtual()
            .name("audio-emitter-" + emitterId + "-" + tag)
            .start(this::run);
    }

    /**
     * Signals this emitter to release.
     * The virtual thread will complete its current block and then transition to RELEASE.
     */
    public void destroy() {
        state = EmitterState.RELEASE;
    }

    // -- Internal virtual thread operations ----------------------------------

    private void initialize() {
        // Phase 0: minimal init - set default params and move to VIRTUAL.
        // Phase 2+: resolve acoustic material, acquire room membership,
        //           perform initial priority score evaluation.
        params[0].reset();
        params[1].reset();
        updateScore();
        state = EmitterState.VIRTUAL;
    }

    private void updateScore() {
        // Priority score formula per lifecycle spec.
        // All weights are from AcousticConstants.
        EmitterParams p = acquireParamsForBlock();

        float dist = p.distanceMetres;
        float distFactor = 1f / (1f + dist * dist * 0.01f); // DIST_WEIGHT baked into denominator

        // Phase 0: audibility approximated from masterGain; full post-occlusion in Phase 2.
        float audibility = p.masterGain;

        // Phase 0: velocity magnitude from params
        float speed = (float) Math.sqrt(
            p.velX * p.velX + p.velY * p.velY + p.velZ * p.velZ);
        float velFactor = Math.min(1f, speed / 50f); // 50 m/s ~= max audible velocity

        // Importance maps ordinal to [0..1]: CRITICAL=1.0, HIGH=0.75, NORMAL=0.5, LOW=0.25
        float importanceScore = 1f - (importance.ordinal() * 0.25f);

        float raw = distFactor * AcousticConstants.W_DISTANCE
                  + importanceScore * AcousticConstants.W_IMPORTANCE
                  + audibility * AcousticConstants.W_AUDIBILITY
                  + velFactor * AcousticConstants.W_VELOCITY;

        float[] occlusionForScore = p.occlusionPerBand;

        // Occlusion: fire emitter->listener ray if backend available
        AcousticSnapshotManager snapMgr = this.acousticSnapshotManager;
        if (snapMgr != null) {
            AcousticWorldSnapshot snapshot = snapMgr.acquireLatest();
            if (snapshot != null) {
                // Compute ray direction from emitter to listener
                float dx = listenerX - p.posX;
                float dy = listenerY - p.posY;
                float dz = listenerZ - p.posZ;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 0.001f) {
                    dx /= len;
                    dy /= len;
                    dz /= len;

                    // Reuse pre-allocated hit and scratch - zero allocation
                    AcousticHit hit = occlusionHit;
                    resetOcclusion(occlusionScratch);
                    snapshot.traceRay(p.posX, p.posY, p.posZ, dx, dy, dz, len, hit);
                    if (hit.hit) {
                        AcousticMaterial material = snapshot.material(hit.materialId);
                        if (material != null) {
                            computeSingleHitOcclusion(material, occlusionScratch);
                        }
                    }

                    // Publish updated occlusion into params
                    occlusionWriter = ensureOcclusionWriter(occlusionWriter, occlusionScratch);
                    publishParams(occlusionWriter);
                    occlusionForScore = occlusionScratch;
                }
            }
        }

        // Early reflections: fire multi-hit ray fan if emitter is PHYSICAL
        // and an early reflection sink is wired.
        EarlyReflectionSink reflectionSink = this.earlyReflectionSink;
        if (reflectionSink != null && snapMgr != null) {
            AcousticWorldSnapshot snapshot = snapMgr.acquireLatest();
            if (snapshot != null) {
                float dx = listenerX - p.posX;
                float dy = listenerY - p.posY;
                float dz = listenerZ - p.posZ;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 0.001f) {
                    dx /= len;
                    dy /= len;
                    dz /= len;
                    reflectionHits.reset();
                    snapshot.traceRayMulti(
                        p.posX, p.posY, p.posZ,
                        dx, dy, dz,
                        30.0f,
                        reflectionHits);
                    reflectionSink.updateReflections(reflectionHits);
                }
            }
        } else if (reflectionSink != null) {
            reflectionSink.clearReflections();
        }

        // Reverb wet gain: estimate from room acoustics if room data is available.
        // Formula mirrors ReverbWetGainCalculator.compute() - inlined here to preserve
        // module direction (core must not import simulation).
        // SYNC: keep this formula consistent with ReverbWetGainCalculator.compute().
        if (snapMgr != null && p.roomId != 0L) {
            AcousticWorldSnapshot snapshot = snapMgr.acquireLatest();
            if (snapshot != null) {
                AcousticRoom room = snapshot.room(p.roomId);
                if (room != null) {
                    // Compute mean RT60 - inline of ReverbEstimator.computeMeanRt60().
                    // SYNC: keep consistent with ReverbEstimator.computeRt60().
                    float totalAbsorption = 0f;
                    for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
                        totalAbsorption += room.totalAbsorption(band);
                    }
                    float meanSabins = totalAbsorption / AcousticConstants.ACOUSTIC_BAND_COUNT;
                    float volume = room.volumeMeters3();
                    float surfaceArea = Math.max(1f, room.surfaceAreaMeters2());
                    float meanAlpha = (surfaceArea > 0f) ? meanSabins / surfaceArea : 0f;

                    float meanRt60;
                    if (volume <= 0f || meanSabins <= 0f) {
                        meanRt60 = 0.3f;
                    } else if (meanAlpha > 0.3f) {
                        // Eyring - SYNC: keep consistent with ReverbEstimator.computeRt60().
                        // SYNC: ReverbEstimator.EYRING_THRESHOLD.
                        float alpha = Math.min(0.9999f, meanSabins / surfaceArea);
                        double lnTerm = Math.log(1.0 - alpha);
                        meanRt60 = (float) (AcousticConstants.SABINE_CONSTANT
                            * volume / (-surfaceArea * lnTerm));
                    } else {
                        // Sabine - SYNC: keep consistent with ReverbEstimator.computeRt60().
                        meanRt60 = (float) (AcousticConstants.SABINE_CONSTANT * volume / meanSabins);
                    }
                    meanRt60 = Math.max(1e-3f, Math.min(30f, meanRt60));

                    // Wet gain: 1 - exp(-dist / criticalDist)
                    // SYNC: keep consistent with ReverbWetGainCalculator.compute().
                    float sourceDistance = p.distanceMetres;
                    float safeVolume = Math.max(1f, volume);
                    double denom = 4.0 * Math.PI * meanRt60 * 343.0f;
                    float criticalDist = (float) Math.sqrt(safeVolume / denom);
                    float wetGain = 1f - (float) Math.exp(
                        -Math.max(0f, sourceDistance) / criticalDist);
                    wetGain = Math.max(0f, Math.min(1f, wetGain));

                    final float finalWetGain = wetGain;
                    publishParams(params -> params.reverbWetGain = finalWetGain);
                }
            }
        }

        // Flat occlusion penalty for score computation (average of per-band)
        float avgOcclusion = 0f;
        for (int i = 0; i < AcousticConstants.ACOUSTIC_BAND_COUNT; i++) {
            avgOcclusion += occlusionForScore[i];
        }
        avgOcclusion /= AcousticConstants.ACOUSTIC_BAND_COUNT;

        this.score = Math.max(0f, raw - avgOcclusion * AcousticConstants.W_OCCLUSION_PENALTY);
    }

    private void prepareAcousticParameters() {
        // Phase 0: position and gain only.
        // Phase 2+: occlusion rays, HRTF angles, portal propagation, reverb estimation.
        // No-op in Phase 0 - params already written by setPosition() and game thread.
    }

    private void executeReleaseTail() {
        // Phase 0: immediate cleanup.
        // Phase 1+: apply DEMOTION_FADE_MS fade-out before releasing DSP slot.
        releaseDspSlot();
        params[0].reset();
        params[1].reset();
    }

    /** Pre-allocated, reusable EmitterParamsWriter for occlusion updates. */
    private static final class OcclusionParamsWriter implements EmitterParamsWriter {
        float[] occlusion;

        @Override
        public void write(EmitterParams params) {
            System.arraycopy(occlusion, 0, params.occlusionPerBand, 0, occlusion.length);
        }
    }

    private static OcclusionParamsWriter ensureOcclusionWriter(
            OcclusionParamsWriter existing, float[] occlusion) {
        if (existing == null) {
            existing = new OcclusionParamsWriter();
        }
        existing.occlusion = occlusion;
        return existing;
    }

    private static void resetOcclusion(float[] out) {
        for (int i = 0; i < out.length; i++) {
            out[i] = 0f;
        }
    }

    private static void computeSingleHitOcclusion(AcousticMaterial material, float[] out) {
        for (int band = 0; band < AcousticConstants.ACOUSTIC_BAND_COUNT; band++) {
            out[band] = transmissionLossToOcclusion(material.transmissionLossDb(band));
        }
    }

    private static float transmissionLossToOcclusion(float transmissionLossDb) {
        // Keep this conversion in sync with
        // io.dynamis.audio.simulation.OcclusionCalculator.transmissionLossToOcclusion().
        if (transmissionLossDb >= 0f) {
            return 0f;
        }
        if (transmissionLossDb <= -60.0f) {
            return 1f;
        }
        float ratio = (float) Math.pow(10.0, transmissionLossDb / 20.0);
        float occlusion = 1f - ratio;
        if (occlusion < 0f) {
            return 0f;
        }
        if (occlusion > 1f) {
            return 1f;
        }
        return occlusion;
    }

    // -- Deterministic comparator (voice manager use only) -------------------

    /**
     * Compares two emitters for priority ordering.
     *
     * Returns negative if a has higher priority than b (sort descending by score).
     * Tie-breaking is deterministic and never uses mutable state:
     *   1. Higher score wins.
     *   2. Higher importance ordinal (lower enum ordinal = higher importance) wins.
     *   3. Lower emitterId wins (first-spawned, stable).
     */
    public static int compareByPriority(LogicalEmitter a, LogicalEmitter b) {
        float delta = a.score - b.score;
        if (Math.abs(delta) > AcousticConstants.SCORE_EPSILON) {
            return Float.compare(b.score, a.score); // descending
        }
        if (a.importance != b.importance) {
            return a.importance.ordinal() - b.importance.ordinal(); // lower ordinal = higher importance
        }
        return Long.compare(a.emitterId, b.emitterId); // lower id = older = wins
    }
}
