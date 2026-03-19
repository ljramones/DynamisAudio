package org.dynamisengine.audio.api.device;

/**
 * Handle to an active audio output session.
 *
 * <p>Returned by {@link AudioBackend#openDevice}. Represents a live connection to
 * the platform audio hardware. The native audio thread autonomously invokes the
 * {@link AudioCallback} registered at open time; this handle controls the session lifecycle.
 *
 * <h2>Lifecycle Contract</h2>
 * <pre>{@code
 * openDevice() → [stopped] → start() → [running, callback fires] → stop() → [stopped] → close()
 * }</pre>
 * <ul>
 *   <li>{@link #start()}/{@link #stop()} may be called multiple times before close</li>
 *   <li>{@link #close()} is terminal — releases all native resources, idempotent</li>
 *   <li>Only {@code AudioDeviceManager} calls these methods — backends must not self-manage</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>{@link #start()}, {@link #stop()}, {@link #close()}: called from the engine
 *       lifecycle thread or DSP worker (during hot-swap). Not called concurrently.</li>
 *   <li>Read-only methods ({@link #isActive()}, {@link #negotiatedFormat()}, etc.):
 *       safe from any thread.</li>
 * </ul>
 *
 * <h2>During Hot-Swap</h2>
 * The manager calls {@code stop() → close()} on the old handle, then {@code start()} on
 * the new handle. The old handle must release all native resources in {@code close()}.
 * The new handle must be fully functional after {@code start()}.
 */
public interface AudioDeviceHandle {

    /** Start audio output. The platform begins invoking the AudioCallback. */
    void start();

    /** Stop audio output. The callback stops firing. Resumable via {@link #start()}. */
    void stop();

    /**
     * Close the session and release all native resources.
     * Terminal operation. After close(), no other method may be called.
     * Idempotent: safe to call multiple times.
     */
    void close();

    /** The audio format actually negotiated with the hardware. */
    AudioFormat negotiatedFormat();

    /**
     * Output latency in sample frames as reported by the driver.
     * Valid only after {@link #start()}. Returns 0 if not available.
     */
    int outputLatencyFrames();

    /** Output latency in milliseconds (convenience). */
    default float outputLatencyMs() {
        AudioFormat fmt = negotiatedFormat();
        if (fmt.sampleRate() == 0) return 0f;
        return (float) outputLatencyFrames() / fmt.sampleRate() * 1000f;
    }

    /** True if the session has been started and not yet stopped or closed. */
    boolean isActive();

    /** Backend-reported device description for diagnostics and profiler overlay. */
    String deviceDescription();
}
