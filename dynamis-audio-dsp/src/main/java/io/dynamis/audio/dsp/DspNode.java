package io.dynamis.audio.dsp;

/**
 * Base contract for all nodes in the DSP processing graph.
 *
 * A DspNode represents one processing stage: a voice source, an effect,
 * a bus submix, or the master output. Nodes are chained in a directed acyclic
 * graph; signal flows from leaf sources through effect nodes to the master bus.
 *
 * ALLOCATION CONTRACT: process() must never allocate. All buffers are pre-allocated
 * and passed in by the mixer. No new, no boxing, no lambda captures on the hot path.
 *
 * THREAD SAFETY: process() is called exclusively by the DSP render worker thread.
 * Configuration (gain, bypass) may be written by the voice manager thread via
 * volatile fields - no locks required for single-word writes.
 */
public interface DspNode {

    /**
     * Unique name for this node. Used in profiler overlay and debug dumps.
     * Must be stable for the lifetime of the node - never null.
     */
    String name();

    /**
     * Processes one DSP block.
     *
     * Reads from inputBuffer, writes processed signal into outputBuffer.
     * Both buffers are pre-allocated by the mixer - do not reassign the references.
     * Buffer length is always frameCount * channels (interleaved stereo = frameCount * 2).
     *
     * ALLOCATION CONTRACT: Zero allocation. Called on DSP render worker thread.
     *
     * @param inputBuffer   interleaved float PCM input; length >= frameCount * channels
     * @param outputBuffer  interleaved float PCM output; length >= frameCount * channels
     * @param frameCount    number of sample frames in this block (typically DSP_BLOCK_SIZE)
     * @param channels      number of audio channels (Phase 0: always 2 for stereo)
     */
    void process(float[] inputBuffer, float[] outputBuffer, int frameCount, int channels);

    /**
     * Returns true if this node is bypassed.
     * A bypassed node copies input to output without processing.
     * Bypass state may be toggled at any time by the designer layer.
     */
    boolean isBypassed();

    /**
     * Sets bypass state. Written by designer/voice manager thread.
     * Effective from the next DSP block.
     */
    void setBypassed(boolean bypassed);

    /**
     * Output gain applied after processing. Linear [0..1], default 1.0.
     * Written by designer/voice manager thread. Read by DSP render worker.
     */
    float getGain();

    /**
     * Sets output gain. Effective from the next DSP block.
     */
    void setGain(float gain);

    /**
     * Called once at engine startup to pre-allocate any internal buffers.
     * maxFrameCount is the largest frameCount that will ever be passed to process().
     * Implementations must not allocate during process() after this is called.
     *
     * @param maxFrameCount maximum frames per block (typically DSP_BLOCK_SIZE)
     * @param channels      number of channels
     */
    void prepare(int maxFrameCount, int channels);

    /**
     * Releases any resources held by this node.
     * Called when the node is removed from the graph.
     * After reset(), process() must not be called.
     */
    void reset();
}
