package org.dynamisengine.audio.api.device;

/**
 * Immutable descriptor for a discovered audio output device.
 *
 * Returned by {@link AudioBackend#enumerateDevices()}. Passed back to
 * {@link AudioBackend#openDevice} to select which device to open.
 *
 * @param id                   platform-unique device identifier
 * @param displayName          human-readable name ("MacBook Pro Speakers", "Focusrite Scarlett 2i2")
 * @param maxChannels          maximum supported output channels
 * @param supportedSampleRates sample rates the device advertises (e.g., {44100, 48000, 96000})
 * @param isDefault            true if this is the current system default output device
 * @param supportsExclusive    true if the device supports exclusive / low-latency mode
 */
public record AudioDeviceInfo(
        String id,
        String displayName,
        int maxChannels,
        int[] supportedSampleRates,
        boolean isDefault,
        boolean supportsExclusive) {

    public AudioDeviceInfo {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id must not be empty");
        if (displayName == null) throw new IllegalArgumentException("displayName must not be null");
        if (supportedSampleRates == null) throw new IllegalArgumentException("supportedSampleRates must not be null");
        supportedSampleRates = supportedSampleRates.clone(); // defensive copy
    }

    @Override
    public int[] supportedSampleRates() {
        return supportedSampleRates.clone(); // defensive copy on read
    }
}
