package io.dynamis.audio.dsp;

import io.dynamis.audio.api.MixBusControl;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A named mix bus in the DSP graph.
 *
 * An AudioBus receives signal from one or more source nodes (voices or child buses),
 * sums them, applies its effect chain, then passes the result to its parent bus.
 * The master bus has no parent.
 *
 * BUS HIERARCHY EXAMPLE:
 *   Master
 *   |- Music
 *   |- SFX
 *   |   |- Weapons
 *   |   \- Footsteps
 *   \- Ambience
 *
 * ALLOCATION CONTRACT:
 *   renderBlock() may iterate CopyOnWriteArrayList snapshot - acceptable on DSP thread
 *   since the list is rarely modified. No per-frame allocation.
 *   All mix buffers are pre-allocated in prepare().
 *
 * THREAD SAFETY:
 *   sources list: CopyOnWriteArrayList - voice manager adds/removes, DSP thread reads.
 *   effectChain list: CopyOnWriteArrayList - designer thread modifies, DSP thread reads.
 *   gain/bypassed: volatile - safe for cross-thread single-word reads/writes.
 */
public final class AudioBus extends AbstractDspNode implements MixBusControl {

    private final CopyOnWriteArrayList<DspNode> sources = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DspNode> effectChain = new CopyOnWriteArrayList<>();

    /** Pre-allocated accumulator buffer - summed signal from all sources. */
    private float[] accumBuffer;

    /** Pre-allocated scratch buffer - used by each source node during process(). */
    private float[] scratchBuffer;
    /** Externally submitted signal mixed into this bus before source/effect processing. */
    private float[] submittedBuffer;

    public AudioBus(String name) {
        super(name);
    }

    // -- Source management (voice manager thread) -----------------------------

    /** Adds a source node (voice or child bus) to this bus. Thread-safe. */
    public void addSource(DspNode source) {
        sources.add(source);
        // If this bus is already prepared, newly added nodes must be prepared immediately.
        if (preparedFrameCount > 0 && preparedChannels > 0) {
            source.prepare(preparedFrameCount, preparedChannels);
        }
    }

    /** Removes a source node. Thread-safe. */
    public void removeSource(DspNode source) {
        sources.remove(source);
    }

    // -- Effect chain management (designer thread) ----------------------------

    /** Appends an effect node to the end of the effect chain. Thread-safe. */
    public void addEffect(DspNode effect) {
        effectChain.add(effect);
        // If this bus is already prepared, newly added nodes must be prepared immediately.
        if (preparedFrameCount > 0 && preparedChannels > 0) {
            effect.prepare(preparedFrameCount, preparedChannels);
        }
    }

    /** Removes an effect node from the chain. Thread-safe. */
    public void removeEffect(DspNode effect) {
        effectChain.remove(effect);
    }

    // -- DspNode lifecycle ----------------------------------------------------

    @Override
    protected void onPrepare(int maxFrameCount, int channels) {
        int bufLen = maxFrameCount * channels;
        accumBuffer = new float[bufLen];
        scratchBuffer = new float[bufLen];
        submittedBuffer = new float[bufLen];
        // Prepare all sources and effects recursively
        for (DspNode s : sources) s.prepare(maxFrameCount, channels);
        for (DspNode e : effectChain) e.prepare(maxFrameCount, channels);
    }

    @Override
    protected void onReset() {
        for (DspNode s : sources) s.reset();
        for (DspNode e : effectChain) e.reset();
        accumBuffer = null;
        scratchBuffer = null;
        submittedBuffer = null;
    }

    // -- Signal flow ----------------------------------------------------------

    /**
     * Renders one DSP block.
     *
     * 1. Zero the accumulator.
     * 2. For each source: process into scratchBuffer, accumulate into accumBuffer.
     * 3. Run accumBuffer through effect chain in order.
     * 4. Copy accumBuffer into outputBuffer (base class applies gain).
     *
     * ALLOCATION CONTRACT: Zero allocation per block.
     */
    @Override
    protected void processInternal(float[] inputBuffer, float[] outputBuffer,
                                   int frameCount, int channels) {
        int len = frameCount * channels;

        // Zero accumulator
        java.util.Arrays.fill(accumBuffer, 0, len, 0f);
        // Mix externally submitted signal first, then clear for next block.
        if (submittedBuffer != null) {
            for (int i = 0; i < len; i++) {
                accumBuffer[i] += submittedBuffer[i];
                submittedBuffer[i] = 0f;
            }
        }

        // Sum all source nodes into accumulator
        for (DspNode source : sources) {
            java.util.Arrays.fill(scratchBuffer, 0, len, 0f);
            source.process(scratchBuffer, scratchBuffer, frameCount, channels);
            for (int i = 0; i < len; i++) {
                accumBuffer[i] += scratchBuffer[i];
            }
        }

        // Effect chain: process accumBuffer through each effect in sequence
        // For Phase 0: effects list is empty, so this is a no-op pass
        float[] chainIn = accumBuffer;
        float[] chainOut = scratchBuffer;
        for (DspNode effect : effectChain) {
            java.util.Arrays.fill(chainOut, 0, len, 0f);
            effect.process(chainIn, chainOut, frameCount, channels);
            // Swap buffers for next effect in chain
            float[] tmp = chainIn;
            chainIn = chainOut;
            chainOut = tmp;
        }

        // Copy final result into outputBuffer
        System.arraycopy(chainIn, 0, outputBuffer, 0, len);
    }

    /**
     * Submits a pre-mixed signal block into this bus's accumulator input.
     * Samples are added to an internal buffer consumed on the next process() call.
     */
    public void submitBlock(float[] samples, int frameCount, int channels) {
        if (submittedBuffer == null) {
            return;
        }
        int len = frameCount * channels;
        for (int i = 0; i < len; i++) {
            submittedBuffer[i] += samples[i];
        }
    }
}
