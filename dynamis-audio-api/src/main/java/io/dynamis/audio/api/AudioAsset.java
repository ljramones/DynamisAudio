package io.dynamis.audio.api;

/**
 * Abstraction over a decoded audio source for voice playback.
 *
 * Implementations provide PCM frames on demand into caller-supplied buffers.
 * The engine never allocates during readFrames() - all buffers are owned by
 * the caller.
 *
 * SAMPLE RATE CONTRACT:
 *   sampleRate() must equal AcousticConstants.SAMPLE_RATE (48000 Hz).
 *   VoiceNode.setAsset() enforces this with an IllegalArgumentException.
 *   // PHASE 7: runtime resampling - relax this constraint.
 */
public interface AudioAsset {

    /** Sample rate of this asset in Hz. Must equal AcousticConstants.SAMPLE_RATE. */
    int sampleRate();

    /** Number of audio channels (1 = mono, 2 = stereo). */
    int channelCount();

    /** Total frame count of this asset, or -1 if unknown (streaming). */
    long totalFrames();

    /**
     * Reads up to frameCount frames into the provided buffer.
     *
     * Interleaved layout: [ch0, ch1, ch0, ch1, ...] for stereo.
     *
     * @param out destination buffer; length >= frameCount * channelCount()
     * @param frameCount number of frames to read
     * @return frames actually read; 0 = end of stream
     */
    int readFrames(float[] out, int frameCount);

    /** Resets playback to the beginning of the asset. */
    void reset();

    /** Returns true if this asset has reached end of stream and has not been reset. */
    boolean isExhausted();
}
