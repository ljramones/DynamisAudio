package org.dynamisengine.audio.backend.alsa;

import org.dynamisengine.audio.api.device.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * ALSA backend tests.
 *
 * Structural tests run on all platforms.
 * Device tests run only on Linux (@EnabledOnOs(LINUX)).
 */
class AlsaBackendTest {

    // -- Structural tests (all platforms) ------------------------------------

    @Test
    void backendMetadata() {
        AlsaBackend backend = new AlsaBackend();
        assertThat(backend.name()).isEqualTo("ALSA");
        assertThat(backend.priority()).isEqualTo(100);
    }

    @Test
    void capabilitiesReportCorrectly() {
        AlsaBackend backend = new AlsaBackend();
        BackendCapabilities caps = backend.capabilities();
        assertThat(caps.supportsPullModel()).isFalse(); // ALSA is push-based
        assertThat(caps.supportsExclusiveMode()).isFalse();
        assertThat(caps.supportsDeviceChange()).isFalse();
        assertThat(caps.maxChannels()).isEqualTo(8);
        assertThat(caps.preferredBlockSizes()).contains(256, 512, 1024);
    }

    @Test
    void notAvailableOnNonLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            AlsaBackend backend = new AlsaBackend();
            assertThat(backend.isAvailable()).isFalse();
        }
    }

    @Test
    void enumerateDevicesReturnsSyntheticDefaults() {
        AlsaBackend backend = new AlsaBackend();
        List<AudioDeviceInfo> devices = backend.enumerateDevices();
        assertThat(devices).hasSize(2);

        AudioDeviceInfo defaultDev = devices.getFirst();
        assertThat(defaultDev.id()).isEqualTo("default");
        assertThat(defaultDev.isDefault()).isTrue();
        assertThat(defaultDev.displayName()).contains("ALSA");

        AudioDeviceInfo hwDev = devices.get(1);
        assertThat(hwDev.id()).isEqualTo("plughw:0,0");
        assertThat(hwDev.isDefault()).isFalse();
    }

    @Test
    void constantsAreCorrect() {
        assertThat(AlsaConstants.SND_PCM_STREAM_PLAYBACK).isZero();
        assertThat(AlsaConstants.SND_PCM_ACCESS_RW_INTERLEAVED).isEqualTo(3);
        assertThat(AlsaConstants.SND_PCM_FORMAT_FLOAT_LE).isEqualTo(14);
        assertThat(AlsaConstants.EPIPE).isEqualTo(-32);
        assertThat(AlsaConstants.DEFAULT_DEVICE).isEqualTo("default");
    }

    // -- Linux-only device tests --------------------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void availableOnLinux() {
        AlsaBackend backend = new AlsaBackend();
        assertThat(backend.isAvailable()).isTrue();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void openDefaultDevice() throws AudioDeviceException {
        AlsaBackend backend = new AlsaBackend();
        if (!backend.isAvailable()) return;

        AudioDeviceInfo device = backend.enumerateDevices().getFirst();
        AudioCallback callback = (out, fc, ch) -> out.fill((byte) 0);
        AudioDeviceHandle handle = backend.openDevice(device, AudioFormat.defaultFormat(), callback);

        assertThat(handle.negotiatedFormat().sampleRate()).isGreaterThan(0);
        assertThat(handle.negotiatedFormat().channels()).isEqualTo(2);
        assertThat(handle.deviceDescription()).contains("ALSA");

        handle.close();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void openStartStopCloseLifecycle() throws AudioDeviceException {
        AlsaBackend backend = new AlsaBackend();
        if (!backend.isAvailable()) return;

        AudioDeviceInfo device = backend.enumerateDevices().getFirst();
        AudioCallback callback = (out, fc, ch) -> out.fill((byte) 0);
        AudioDeviceHandle handle = backend.openDevice(device, AudioFormat.defaultFormat(), callback);

        handle.start();
        assertThat(handle.isActive()).isTrue();

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        handle.stop();
        assertThat(handle.isActive()).isFalse();

        handle.close();
    }
}
