package org.dynamisengine.audio.api.device;

import java.lang.foreign.MemorySegment;

/**
 * Pull-model render callback invoked by the native audio thread.
 *
 * This is the bridge between the platform audio API and the DSP engine.
 * Implementations MUST be wait-free: no locks, no heap allocation, no I/O.
 *
 * Called on the platform's real-time audio thread (CoreAudio IOProc,
 * WASAPI event thread, ALSA period callback). Blocking here causes audible glitches.
 *
 * ALLOCATION CONTRACT: Zero heap allocation. The implementation reads from a
 * pre-populated ring buffer into the provided off-heap segment.
 */
@FunctionalInterface
public interface AudioCallback {

    /**
     * Fill the output buffer with rendered audio.
     *
     * @param outputBuffer off-heap MemorySegment to fill with interleaved float32 PCM.
     *                     Size: {@code frameCount * channels * Float.BYTES} bytes.
     *                     Valid only for the duration of this call.
     * @param frameCount   number of sample frames requested by the hardware
     * @param channels     number of interleaved channels
     */
    void render(MemorySegment outputBuffer, int frameCount, int channels);
}
