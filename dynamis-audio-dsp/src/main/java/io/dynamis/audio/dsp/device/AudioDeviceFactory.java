package io.dynamis.audio.dsp.device;

/**
 * Factory for AudioDevice instances.
 *
 * Selects the appropriate platform implementation at runtime, or returns
 * a NullAudioDevice for headless/test environments.
 *
 * Usage:
 *   AudioDevice device = AudioDeviceFactory.create();
 *   device.open(48_000, 2, 256);
 *   // ... render loop ...
 *   device.close();
 */
public final class AudioDeviceFactory {

    private AudioDeviceFactory() {}

    /**
     * Creates the best available AudioDevice for the current environment.
     *
     * If the system property {@code dynamis.audio.device=null} is set,
     * returns a NullAudioDevice regardless of platform. This is the CI/test mode.
     *
     * Otherwise returns a PanamaAudioDevice configured for the detected platform.
     * If the platform is UNKNOWN, PanamaAudioDevice falls back to a silent stub.
     */
    public static AudioDevice create() {
        String override = System.getProperty("dynamis.audio.device", "");
        if ("null".equalsIgnoreCase(override)) {
            return new NullAudioDevice();
        }
        return new PanamaAudioDevice();
    }

    /**
     * Creates a NullAudioDevice unconditionally.
     * Use in tests and CI environments where audio hardware is not available.
     */
    public static AudioDevice createNull() {
        return new NullAudioDevice();
    }

    /**
     * Creates a PanamaAudioDevice unconditionally.
     * Use when hardware output is required regardless of system property overrides.
     */
    public static AudioDevice createPanama() {
        return new PanamaAudioDevice();
    }
}
