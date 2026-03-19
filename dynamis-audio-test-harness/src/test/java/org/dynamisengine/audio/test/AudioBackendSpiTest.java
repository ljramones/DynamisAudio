package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.device.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the AudioBackend SPI contracts, supporting records, and NullAudioBackend.
 */
class AudioBackendSpiTest {

    // -- AudioFormat ---------------------------------------------------------

    @Test
    void audioFormatDefaultFactory() {
        AudioFormat fmt = AudioFormat.defaultFormat();
        assertThat(fmt.sampleRate()).isEqualTo(48_000);
        assertThat(fmt.channels()).isEqualTo(2);
        assertThat(fmt.blockSize()).isEqualTo(256);
        assertThat(fmt.exclusiveMode()).isFalse();
    }

    @Test
    void audioFormatStereo48kFactory() {
        AudioFormat fmt = AudioFormat.stereo48k(512);
        assertThat(fmt.sampleRate()).isEqualTo(48_000);
        assertThat(fmt.channels()).isEqualTo(2);
        assertThat(fmt.blockSize()).isEqualTo(512);
    }

    @Test
    void audioFormatRejectsInvalidValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioFormat(0, 2, 256, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioFormat(48000, 0, 256, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioFormat(48000, 2, 0, false));
    }

    // -- AudioDeviceInfo -----------------------------------------------------

    @Test
    void audioDeviceInfoDefensiveCopy() {
        int[] rates = {44100, 48000};
        AudioDeviceInfo info = new AudioDeviceInfo("id1", "Test Device", 2, rates, true, false);

        // Mutate original — should not affect info
        rates[0] = 99999;
        assertThat(info.supportedSampleRates()[0]).isEqualTo(44100);

        // Mutate returned array — should not affect info
        info.supportedSampleRates()[0] = 88888;
        assertThat(info.supportedSampleRates()[0]).isEqualTo(44100);
    }

    @Test
    void audioDeviceInfoRejectsNulls() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AudioDeviceInfo(null, "name", 2, new int[]{48000}, true, false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AudioDeviceInfo("", "name", 2, new int[]{48000}, true, false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AudioDeviceInfo("id", null, 2, new int[]{48000}, true, false));
    }

    // -- DeviceChangeEvent ---------------------------------------------------

    @Test
    void deviceChangeEventSealedTypes() {
        AudioDeviceInfo dev = new AudioDeviceInfo("id1", "Test", 2, new int[]{48000}, true, false);

        DeviceChangeEvent added = new DeviceChangeEvent.DeviceAdded(dev);
        DeviceChangeEvent removed = new DeviceChangeEvent.DeviceRemoved("id1");
        DeviceChangeEvent changed = new DeviceChangeEvent.DefaultDeviceChanged(dev);

        assertThat(added).isInstanceOf(DeviceChangeEvent.DeviceAdded.class);
        assertThat(removed).isInstanceOf(DeviceChangeEvent.DeviceRemoved.class);
        assertThat(changed).isInstanceOf(DeviceChangeEvent.DefaultDeviceChanged.class);
    }

    // -- NullAudioBackend ----------------------------------------------------

    @Test
    void nullBackendIsAlwaysAvailable() {
        NullAudioBackend backend = new NullAudioBackend();
        assertThat(backend.isAvailable()).isTrue();
        assertThat(backend.name()).isEqualTo("Null");
        assertThat(backend.priority()).isZero();
    }

    @Test
    void nullBackendEnumeratesOneDevice() {
        NullAudioBackend backend = new NullAudioBackend();
        List<AudioDeviceInfo> devices = backend.enumerateDevices();
        assertThat(devices).hasSize(1);
        assertThat(devices.getFirst().isDefault()).isTrue();
    }

    @Test
    void nullBackendOpenAndStartStopClose() throws AudioDeviceException {
        NullAudioBackend backend = new NullAudioBackend();
        AudioDeviceInfo device = backend.enumerateDevices().getFirst();
        AudioFormat format = AudioFormat.stereo48k(64); // small block for fast test

        AudioCallback callback = (output, frameCount, channels) -> output.fill((byte) 0);
        AudioDeviceHandle handle = backend.openDevice(device, format, callback);

        assertThat(handle.negotiatedFormat().sampleRate()).isEqualTo(48_000);
        assertThat(handle.negotiatedFormat().blockSize()).isEqualTo(64);
        assertThat(handle.isActive()).isFalse();

        handle.start();
        assertThat(handle.isActive()).isTrue();

        // Let it run briefly
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        handle.stop();
        assertThat(handle.isActive()).isFalse();

        handle.close();
    }

    @Test
    void nullBackendHandleLatencyIsZero() throws AudioDeviceException {
        NullAudioBackend backend = new NullAudioBackend();
        AudioDeviceInfo device = backend.enumerateDevices().getFirst();
        AudioCallback callback = (output, frameCount, channels) -> {};
        AudioDeviceHandle handle = backend.openDevice(device, AudioFormat.defaultFormat(), callback);

        assertThat(handle.outputLatencyFrames()).isZero();
        assertThat(handle.outputLatencyMs()).isZero();
        handle.close();
    }

    // -- AudioDeviceManager --------------------------------------------------

    @Test
    void audioDeviceManagerDiscoversFallbackWhenNoBackends() {
        var manager = new org.dynamisengine.audio.dsp.device.AudioDeviceManager();
        AudioBackend backend = manager.discoverBackend();
        // In test environment, no platform backends are on the module path,
        // so it should fall back to NullAudioBackend (or discover CoreAudio if on macOS).
        assertThat(backend).isNotNull();
        assertThat(backend.isAvailable()).isTrue();
    }
}
