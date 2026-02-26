package io.dynamis.audio.test;

import io.dynamis.audio.dsp.device.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AudioDeviceTest {

    // -- NullAudioDevice ------------------------------------------------------

    @Test
    void nullDeviceOpensCleanly() throws Exception {
        NullAudioDevice device = new NullAudioDevice();
        device.open(48_000, 2, 256);
        assertThat(device.isOpen()).isTrue();
        device.close();
    }

    @Test
    void nullDeviceIsClosedByDefault() {
        assertThat(new NullAudioDevice().isOpen()).isFalse();
    }

    @Test
    void nullDeviceWriteIncrementsBlockCount() throws Exception {
        NullAudioDevice device = new NullAudioDevice();
        device.open(48_000, 2, 256);
        float[] buf = new float[256 * 2];
        device.write(buf, 256, 2);
        device.write(buf, 256, 2);
        assertThat(device.blocksWritten()).isEqualTo(2L);
        device.close();
    }

    @Test
    void nullDeviceCloseIsIdempotent() throws Exception {
        NullAudioDevice device = new NullAudioDevice();
        device.open(48_000, 2, 256);
        device.close();
        assertThatCode(device::close).doesNotThrowAnyException();
        assertThat(device.isOpen()).isFalse();
    }

    @Test
    void nullDeviceDoubleOpenThrows() throws Exception {
        NullAudioDevice device = new NullAudioDevice();
        device.open(48_000, 2, 256);
        assertThatThrownBy(() -> device.open(48_000, 2, 256))
            .isInstanceOf(AudioDeviceException.class);
        device.close();
    }

    @Test
    void nullDeviceReturnsCorrectSampleRate() throws Exception {
        NullAudioDevice device = new NullAudioDevice();
        device.open(48_000, 2, 256);
        assertThat(device.actualSampleRate()).isEqualTo(48_000);
        device.close();
    }

    @Test
    void nullDeviceDescriptionIsNotNull() throws Exception {
        NullAudioDevice device = new NullAudioDevice();
        device.open(48_000, 2, 256);
        assertThat(device.deviceDescription()).isNotBlank();
        device.close();
    }

    @Test
    void nullDeviceWriteOnClosedDeviceDoesNotThrow() {
        NullAudioDevice device = new NullAudioDevice();
        float[] buf = new float[256 * 2];
        assertThatCode(() -> device.write(buf, 256, 2)).doesNotThrowAnyException();
    }

    // -- PanamaAudioDevice ----------------------------------------------------

    @Test
    void panamaDeviceDetectsPlatform() {
        PanamaAudioDevice device = new PanamaAudioDevice();
        assertThat(device.getPlatform()).isNotNull();
    }

    @Test
    void panamaDeviceOpensOnCurrentPlatform() throws Exception {
        PanamaAudioDevice device = new PanamaAudioDevice();
        device.open(48_000, 2, 256);
        assertThat(device.isOpen()).isTrue();
        device.close();
    }

    @Test
    void panamaDeviceWriteDoesNotThrow() throws Exception {
        PanamaAudioDevice device = new PanamaAudioDevice();
        device.open(48_000, 2, 256);
        float[] buf = new float[256 * 2];
        assertThatCode(() -> device.write(buf, 256, 2)).doesNotThrowAnyException();
        device.close();
    }

    @Test
    void panamaDeviceCloseReleasesOffHeapMemory() throws Exception {
        PanamaAudioDevice device = new PanamaAudioDevice();
        device.open(48_000, 2, 256);
        device.close();
        assertThat(device.isOpen()).isFalse();
        // After close: write() must not crash (arena closed, but close checks isOpen)
        float[] buf = new float[256 * 2];
        assertThatCode(() -> device.write(buf, 256, 2)).doesNotThrowAnyException();
    }

    @Test
    void panamaDeviceOutputLatencyIsNonNegativeAfterOpen() throws Exception {
        PanamaAudioDevice device = new PanamaAudioDevice();
        device.open(48_000, 2, 256);
        assertThat(device.outputLatencyMs()).isGreaterThanOrEqualTo(0f);
        device.close();
    }

    // -- AudioDeviceFactory ---------------------------------------------------

    @Test
    void factoryCreateNullReturnsNullDevice() {
        AudioDevice device = AudioDeviceFactory.createNull();
        assertThat(device).isInstanceOf(NullAudioDevice.class);
    }

    @Test
    void factoryCreatePanamaReturnsPanamaDevice() {
        AudioDevice device = AudioDeviceFactory.createPanama();
        assertThat(device).isInstanceOf(PanamaAudioDevice.class);
    }

    @Test
    void factoryCreateRespectsNullSystemProperty() {
        System.setProperty("dynamis.audio.device", "null");
        try {
            AudioDevice device = AudioDeviceFactory.create();
            assertThat(device).isInstanceOf(NullAudioDevice.class);
        } finally {
            System.clearProperty("dynamis.audio.device");
        }
    }

    @Test
    void factoryCreateReturnsPanamaByDefault() {
        System.clearProperty("dynamis.audio.device");
        AudioDevice device = AudioDeviceFactory.create();
        assertThat(device).isInstanceOf(PanamaAudioDevice.class);
    }
}
