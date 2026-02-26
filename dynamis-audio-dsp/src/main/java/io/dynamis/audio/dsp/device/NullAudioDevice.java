package io.dynamis.audio.dsp.device;

/**
 * No-op AudioDevice implementation for testing and headless CI environments.
 *
 * Accepts all write() calls and discards the audio data.
 * Simulates the timing contract of a real device by tracking expected
 * block boundaries without actually sleeping (test environments run faster than real-time).
 *
 * This is the device used by the CI no-alloc harness and all unit tests.
 * It exercises the full render path - buffer copies, format conversions, event draining -
 * without requiring audio hardware.
 */
public final class NullAudioDevice implements AudioDevice {

    private boolean open = false;
    private int sampleRate = 0;
    private int channels = 0;
    private int blockSize = 0;
    private long blocksWritten = 0L;

    /** Pre-allocated discard buffer - receives write() output without allocation. */
    private float[] discardBuffer;

    @Override
    public void open(int sampleRate, int channels, int blockSize)
            throws AudioDeviceException {
        if (open) throw new AudioDeviceException("NullAudioDevice is already open");
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.blockSize = blockSize;
        this.discardBuffer = new float[blockSize * channels];
        this.open = true;
    }

    @Override
    public void write(float[] buffer, int frameCount, int channels) {
        if (!open) return;
        // Copy into discard buffer - exercises the copy path without output.
        // Length guard prevents ArrayIndexOutOfBoundsException on malformed calls.
        int len = Math.min(frameCount * channels, discardBuffer.length);
        System.arraycopy(buffer, 0, discardBuffer, 0, len);
        blocksWritten++;
    }

    @Override
    public void close() {
        open = false;
        discardBuffer = null;
    }

    @Override
    public boolean isOpen() { return open; }

    @Override
    public String deviceDescription() {
        return "NullAudioDevice [discard, " + sampleRate + "Hz, " + channels + "ch]";
    }

    @Override
    public int actualSampleRate() { return sampleRate; }

    @Override
    public float outputLatencyMs() { return 0f; }

    /** Total blocks written since open(). Useful for test assertions. */
    public long blocksWritten() { return blocksWritten; }
}
