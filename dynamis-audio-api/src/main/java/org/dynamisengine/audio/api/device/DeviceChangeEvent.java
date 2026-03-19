package org.dynamisengine.audio.api.device;

/**
 * Device topology change notification.
 *
 * <p>Fired by the platform backend when audio devices are added, removed, or the
 * system default changes. Delivered to a {@link DeviceChangeListener} registered
 * via {@link AudioBackend#setDeviceChangeListener}.
 *
 * <h2>Swap Trigger Rules</h2>
 * <ul>
 *   <li>{@link DefaultDeviceChanged}: <b>triggers hot-swap</b> — manager transitions to SWAP_PENDING</li>
 *   <li>{@link DeviceRemoved}: <b>triggers hot-swap</b> if the removed device is the active device</li>
 *   <li>{@link DeviceAdded}: <b>does not trigger swap</b> — logged only</li>
 * </ul>
 *
 * <p>Sealed to ensure exhaustive pattern matching in the manager's event handler.
 *
 * @see DeviceChangeListener
 */
public sealed interface DeviceChangeEvent {

    /** A new audio output device was connected (e.g., USB DAC plugged in). */
    record DeviceAdded(AudioDeviceInfo device) implements DeviceChangeEvent {}

    /** An audio output device was disconnected (e.g., headphones unplugged). */
    record DeviceRemoved(String deviceId) implements DeviceChangeEvent {}

    /** The system default output device changed (e.g., headphones plugged in overrides speakers). */
    record DefaultDeviceChanged(AudioDeviceInfo newDefault) implements DeviceChangeEvent {}
}
