package io.dynamis.audio.dsp.device;

/**
 * Abstraction over a platform audio output device.
 *
 * Implementations wrap WASAPI (Windows), CoreAudio (macOS), or ALSA (Linux)
 * via the Panama Foreign Function & Memory API. The Java software mixer writes
 * interleaved float PCM into an off-heap MemorySegment; this interface handles
 * the platform-specific transfer to the hardware output buffer.
 *
 * LIFETIME:
 *   open()  -> write() loop -> close()
 *   open() must be called before any write(). close() must be called exactly once.
 *
 * ALLOCATION CONTRACT:
 *   write() must not allocate on the calling thread (DSP render worker).
 *   open() and close() may allocate - called at startup/shutdown only.
 *
 * THREAD SAFETY:
 *   open() and close() are called from the engine lifecycle thread.
 *   write() is called exclusively from the DSP render worker thread.
 *   No concurrent calls to write() - single DSP thread in Phase 0.
 */
public interface AudioDevice {

    /**
     * Opens the device and prepares it for output.
     *
     * Allocates the off-heap output buffer (MemorySegment) and initialises
     * the platform audio session. Must be called before write().
     *
     * @param sampleRate  sample rate in Hz (must be 48000 for Phase 0)
     * @param channels    number of output channels (must be 2 for Phase 0)
     * @param blockSize   DSP block size in frames - determines buffer sizing
     * @throws AudioDeviceException if the device cannot be opened
     */
    void open(int sampleRate, int channels, int blockSize) throws AudioDeviceException;

    /**
     * Writes one DSP block of interleaved float PCM to the device.
     *
     * The buffer contains frameCount * channels interleaved float samples in [-1..1].
     * Implementations copy from the Java float[] into the off-heap output buffer
     * and submit to the platform audio API.
     *
     * ALLOCATION CONTRACT: Zero allocation. Called on DSP render worker thread.
     *
     * @param buffer     interleaved float PCM; length must be >= frameCount * channels
     * @param frameCount number of sample frames to write
     * @param channels   channel count; must match value passed to open()
     */
    void write(float[] buffer, int frameCount, int channels);

    /**
     * Closes the device and releases all native resources.
     * After close(), write() must not be called.
     * Idempotent - safe to call multiple times.
     */
    void close();

    /**
     * Returns true if the device has been successfully opened and not yet closed.
     */
    boolean isOpen();

    /**
     * Returns a human-readable description of this device (name, driver, platform).
     * Used in profiler overlay and startup log.
     */
    String deviceDescription();

    /**
     * Returns the actual sample rate negotiated with the hardware.
     * May differ from the requested rate if the hardware resampled.
     * Valid only after open().
     */
    int actualSampleRate();

    /**
     * Returns the actual output latency in milliseconds as reported by the driver.
     * Valid only after open(). Returns -1 if not available.
     */
    float outputLatencyMs();
}
