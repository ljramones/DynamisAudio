package org.dynamisengine.audio.api.device;

/**
 * Listener for audio device topology changes.
 *
 * <p>Registered via {@link AudioBackend#setDeviceChangeListener}.
 * Invoked on a platform notification thread (not the DSP thread, not the
 * engine lifecycle thread). The thread varies by backend:
 * <ul>
 *   <li>CoreAudio: AudioObjectPropertyListener callback thread</li>
 *   <li>WASAPI: IMMNotificationClient COM thread</li>
 *   <li>ALSA: no native notification (future: udev poll thread)</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Invocations are <b>asynchronous</b> — the platform fires whenever it wants</li>
 *   <li>Invocations are <b>not guaranteed serialized</b> — two events may arrive concurrently</li>
 *   <li>The implementation MUST be safe for cross-thread invocation</li>
 *   <li>The implementation MUST NOT open/close devices, allocate significantly, or block</li>
 *   <li>The implementation SHOULD set a flag or enqueue a lightweight event for the
 *       lifecycle thread to process</li>
 * </ul>
 *
 * <p>The canonical implementation ({@code AudioDeviceManager}) performs a single
 * volatile write ({@code state = SWAP_PENDING}) and returns immediately.
 *
 * @see DeviceChangeEvent
 * @see AudioBackend#setDeviceChangeListener
 */
@FunctionalInterface
public interface DeviceChangeListener {

    void onDeviceChange(DeviceChangeEvent event);
}
