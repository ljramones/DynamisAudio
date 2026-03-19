package org.dynamisengine.audio.backend.coreaudio;

import org.dynamisengine.audio.api.device.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * CoreAudio backend unit tests.
 * Runs only on macOS. Tests that don't require audio hardware.
 */
@EnabledOnOs(OS.MAC)
class CoreAudioBackendTest {

    @Test
    void backendIsAvailableOnMacOS() {
        CoreAudioBackend backend = new CoreAudioBackend();
        assertThat(backend.isAvailable()).isTrue();
        assertThat(backend.name()).isEqualTo("CoreAudio");
        assertThat(backend.priority()).isEqualTo(100);
    }

    @Test
    void enumerateDevicesReturnsAtLeastOne() {
        CoreAudioBackend backend = new CoreAudioBackend();
        List<AudioDeviceInfo> devices = backend.enumerateDevices();
        assertThat(devices).isNotEmpty();

        // At least one device should be marked as default
        assertThat(devices.stream().anyMatch(AudioDeviceInfo::isDefault)).isTrue();
    }

    @Test
    void enumeratedDevicesHaveValidProperties() {
        CoreAudioBackend backend = new CoreAudioBackend();
        List<AudioDeviceInfo> devices = backend.enumerateDevices();

        for (AudioDeviceInfo device : devices) {
            assertThat(device.id()).isNotEmpty();
            assertThat(device.displayName()).isNotEmpty();
            assertThat(device.maxChannels()).isGreaterThan(0);
            assertThat(device.supportedSampleRates()).isNotEmpty();
        }
    }
}
