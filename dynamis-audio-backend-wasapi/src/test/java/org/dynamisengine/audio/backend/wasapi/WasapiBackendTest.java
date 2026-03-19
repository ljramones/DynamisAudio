package org.dynamisengine.audio.backend.wasapi;

import org.dynamisengine.audio.api.device.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * WASAPI backend tests.
 *
 * Structural tests run on all platforms.
 * Device tests run only on Windows (@EnabledOnOs(WINDOWS)).
 */
class WasapiBackendTest {

    // -- Structural tests (all platforms) ------------------------------------

    @Test
    void backendMetadata() {
        WasapiBackend backend = new WasapiBackend();
        assertThat(backend.name()).isEqualTo("WASAPI");
        assertThat(backend.priority()).isEqualTo(100);
    }

    @Test
    void capabilitiesReportCorrectly() {
        WasapiBackend backend = new WasapiBackend();
        BackendCapabilities caps = backend.capabilities();
        assertThat(caps.supportsPullModel()).isTrue();
        assertThat(caps.supportsExclusiveMode()).isTrue();
        assertThat(caps.supportsDeviceChange()).isTrue();
        assertThat(caps.maxChannels()).isEqualTo(8);
        assertThat(caps.preferredBlockSizes()).contains(256, 480, 512, 1024);
    }

    @Test
    void notAvailableOnNonWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            WasapiBackend backend = new WasapiBackend();
            assertThat(backend.isAvailable()).isFalse();
        }
    }

    @Test
    void enumerateDevicesReturnsSyntheticDefault() {
        WasapiBackend backend = new WasapiBackend();
        List<AudioDeviceInfo> devices = backend.enumerateDevices();
        assertThat(devices).hasSize(1);

        AudioDeviceInfo device = devices.getFirst();
        assertThat(device.id()).isEqualTo("default");
        assertThat(device.isDefault()).isTrue();
        assertThat(device.supportsExclusive()).isTrue();
        assertThat(device.displayName()).contains("Windows");
    }

    @Test
    void comHelperVtableEntryDoesNotCrashOnNull() {
        // ComHelper should handle null gracefully in release()
        ComHelper.release(null);
        ComHelper.release(java.lang.foreign.MemorySegment.NULL);
        // No exception = pass
    }

    @Test
    void wasapiConstantsAreCorrect() {
        assertThat(WasapiConstants.S_OK).isZero();
        assertThat(WasapiConstants.eRender).isZero();
        assertThat(WasapiConstants.COINIT_MULTITHREADED).isZero();
        assertThat(WasapiConstants.AUDCLNT_SHAREMODE_SHARED).isZero();
        assertThat(WasapiConstants.AUDCLNT_SHAREMODE_EXCLUSIVE).isEqualTo(1);
        assertThat(WasapiConstants.WAVE_FORMAT_IEEE_FLOAT).isEqualTo((short) 3);
    }

    @Test
    void waveFormatExtensibleAllocation() {
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment wfx =
                    WasapiStructs.allocateWaveFormatFloat(arena, 48_000, 2);

            assertThat(wfx.byteSize()).isEqualTo(WasapiStructs.WAVEFORMATEXTENSIBLE_SIZE);
            assertThat(WasapiStructs.readSampleRate(wfx)).isEqualTo(48_000);
            assertThat(WasapiStructs.readChannels(wfx)).isEqualTo(2);
        }
    }

    @Test
    void guidAllocation() {
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment guid =
                    WasapiStructs.allocateClsidMMDeviceEnumerator(arena);
            assertThat(guid.byteSize()).isEqualTo(WasapiStructs.GUID_LAYOUT.byteSize());
            // First DWORD should be 0xBCDE0395
            assertThat(guid.get(java.lang.foreign.ValueLayout.JAVA_INT, 0))
                    .isEqualTo(0xBCDE0395);
        }
    }

    // -- Windows-only device tests -------------------------------------------

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void availableOnWindows() {
        WasapiBackend backend = new WasapiBackend();
        assertThat(backend.isAvailable()).isTrue();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void openDefaultDevice() throws AudioDeviceException {
        WasapiBackend backend = new WasapiBackend();
        if (!backend.isAvailable()) return;

        AudioDeviceInfo device = backend.enumerateDevices().getFirst();
        AudioCallback callback = (out, fc, ch) -> out.fill((byte) 0);
        AudioDeviceHandle handle = backend.openDevice(device, AudioFormat.defaultFormat(), callback);

        assertThat(handle.negotiatedFormat().sampleRate()).isGreaterThan(0);
        assertThat(handle.deviceDescription()).contains("WASAPI");

        handle.close();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void openStartStopCloseLifecycle() throws AudioDeviceException {
        WasapiBackend backend = new WasapiBackend();
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
