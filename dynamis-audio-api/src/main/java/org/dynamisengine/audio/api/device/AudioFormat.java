package org.dynamisengine.audio.api.device;

/**
 * Requested or negotiated audio output format.
 *
 * Used both as a request (passed to {@link AudioBackend#openDevice}) and as
 * the negotiated result (returned by {@link AudioDeviceHandle#negotiatedFormat}).
 * The hardware may adjust sample rate or block size; the negotiated format
 * reflects what the driver actually accepted.
 *
 * @param sampleRate     sample rate in Hz (48000 standard for games)
 * @param channels       output channel count (2 = stereo, 6 = 5.1, 8 = 7.1)
 * @param blockSize      frames per callback invocation (256 = ~5.33ms at 48kHz)
 * @param exclusiveMode  request exclusive device access (WASAPI only; ignored elsewhere)
 */
public record AudioFormat(int sampleRate, int channels, int blockSize, boolean exclusiveMode) {

    public AudioFormat {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be positive");
        if (channels <= 0) throw new IllegalArgumentException("channels must be positive");
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize must be positive");
    }

    /** Stereo 48 kHz with the given block size, shared mode. */
    public static AudioFormat stereo48k(int blockSize) {
        return new AudioFormat(48_000, 2, blockSize, false);
    }

    /** Stereo 48 kHz, 256-frame blocks, shared mode. Standard game audio. */
    public static AudioFormat defaultFormat() {
        return new AudioFormat(48_000, 2, 256, false);
    }
}
