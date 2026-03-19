package org.dynamisengine.audio.api.device;

import java.util.List;

/**
 * Service Provider Interface for platform audio backends.
 *
 * <p>Discovered at runtime via {@link java.util.ServiceLoader}. Each platform ships
 * exactly one backend module (e.g., {@code dynamis-audio-backend-coreaudio} on macOS).
 * The {@code AudioDeviceManager} selects the highest-priority available backend.
 *
 * <h2>Pluggability</h2>
 * Backend modules depend only on {@code dynamis-audio-api}. They have zero
 * dependency on the DSP engine, core, or other backends. Ship only the JAR
 * for your target platform.
 *
 * <h2>Backend Responsibilities</h2>
 * <ul>
 *   <li><b>Probe correctly:</b> {@link #isAvailable()} must be fast, accurate, and not open devices</li>
 *   <li><b>Not cache stale state:</b> {@link #enumerateDevices()} must reflect current hardware</li>
 *   <li><b>Not implement policy:</b> never decide which device, when to swap, or how to retry.
 *       Selection, retry, fallback, and swap decisions belong to AudioDeviceManager.</li>
 *   <li><b>Report capabilities accurately:</b> {@link #capabilities()} must reflect real features</li>
 *   <li><b>Fire change events:</b> when the platform notifies, fire {@link DeviceChangeEvent}
 *       to the registered listener. Do not filter or delay.</li>
 *   <li><b>Own native resources:</b> all native memory/handles scoped to Arena owned by the device handle</li>
 *   <li><b>Never throw from callback:</b> callback exceptions → silence, never propagation</li>
 * </ul>
 *
 * <h2>Prohibited</h2>
 * <ul>
 *   <li>Do not open devices without being asked (lifecycle authority is the manager's)</li>
 *   <li>Do not retry failed operations (retry policy is the manager's)</li>
 *   <li>Do not log, allocate, or block in the audio callback</li>
 *   <li>Do not hold references to the AudioDeviceManager</li>
 * </ul>
 *
 * @see AudioDeviceHandle
 * @see AudioCallback
 * @see BackendCapabilities
 */
public interface AudioBackend {

    /** Human-readable backend name (e.g., "CoreAudio", "WASAPI", "ALSA"). */
    String name();

    /**
     * Priority for backend selection when multiple are discovered.
     * Higher value = preferred. Standard backends use 100.
     * Custom backends (e.g., JACK, PipeWire) can use higher values to override.
     */
    int priority();

    /**
     * Reports the capabilities of this backend.
     * Used by the device manager for policy decisions (fallbacks, feature detection).
     */
    default BackendCapabilities capabilities() {
        return BackendCapabilities.DEFAULT;
    }

    /**
     * Fast probe: is this backend usable on the current platform?
     *
     * Must not open devices or allocate significant resources. Typically checks
     * for the existence of the platform's native audio library.
     * Called once at engine startup for each discovered backend.
     */
    boolean isAvailable();

    /**
     * Enumerate available audio output devices.
     *
     * Returns at least one entry (the system default) if {@link #isAvailable()}
     * returns true. May return an empty list if all devices have been disconnected.
     * Called from the engine lifecycle thread. May allocate.
     */
    List<AudioDeviceInfo> enumerateDevices();

    /**
     * Open a device for audio output.
     *
     * The backend MUST invoke {@code audioCallback.render()} from its native
     * audio thread whenever the hardware needs more samples. The callback fills
     * the provided {@link java.lang.foreign.MemorySegment} with interleaved float32 PCM.
     *
     * The returned handle is in a stopped state. Call {@link AudioDeviceHandle#start()}
     * to begin audio output.
     *
     * @param device          which device to open (from {@link #enumerateDevices()})
     * @param requestedFormat desired audio format (sample rate, channels, block size)
     * @param audioCallback   pull-model render callback invoked from the native thread
     * @return handle to the opened audio session
     * @throws AudioDeviceException if the device cannot be opened
     */
    AudioDeviceHandle openDevice(AudioDeviceInfo device,
                                  AudioFormat requestedFormat,
                                  AudioCallback audioCallback)
            throws AudioDeviceException;

    /**
     * Register a listener for device topology changes (hot-plug, unplug, default change).
     *
     * Called from the engine lifecycle thread. The listener is invoked from a
     * platform notification thread. Pass {@code null} to unregister.
     */
    void setDeviceChangeListener(DeviceChangeListener listener);
}
