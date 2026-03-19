package org.dynamisengine.audio.api.device;

/**
 * Describes the capabilities of an {@link AudioBackend} implementation.
 *
 * Used by {@link org.dynamisengine.audio.dsp.device.AudioDeviceManager} to make
 * policy decisions: fallback strategies, format negotiation, feature availability.
 *
 * @param supportsPullModel      true if the backend uses a native pull callback (CoreAudio, WASAPI event-driven)
 * @param supportsExclusiveMode  true if the backend can request exclusive device access (WASAPI)
 * @param supportsDeviceChange   true if the backend can detect hot-plug/unplug events
 * @param maxChannels            maximum output channels the backend supports (0 = unknown)
 * @param preferredBlockSizes    hardware-friendly block sizes (empty = no preference)
 */
public record BackendCapabilities(
        boolean supportsPullModel,
        boolean supportsExclusiveMode,
        boolean supportsDeviceChange,
        int maxChannels,
        int[] preferredBlockSizes) {

    /** Default capabilities — pull model, no exclusive, no device change, 2ch. */
    public static final BackendCapabilities DEFAULT =
            new BackendCapabilities(true, false, false, 2, new int[]{256, 512});

    /** Null backend capabilities — no real hardware features. */
    public static final BackendCapabilities NULL =
            new BackendCapabilities(false, false, false, 2, new int[]{});

    public BackendCapabilities {
        if (preferredBlockSizes == null) preferredBlockSizes = new int[]{};
        preferredBlockSizes = preferredBlockSizes.clone();
    }

    @Override
    public int[] preferredBlockSizes() {
        return preferredBlockSizes.clone();
    }
}
