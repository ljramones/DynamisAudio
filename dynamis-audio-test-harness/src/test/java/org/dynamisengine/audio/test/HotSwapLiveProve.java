package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.device.*;
import org.dynamisengine.audio.dsp.device.AudioDeviceManager;
import org.dynamisengine.audio.dsp.device.SpscAudioRingBuffer;

import java.util.ServiceLoader;

/**
 * Live hot-swap proving harness.
 *
 * Plays a continuous 440 Hz tone and waits for you to trigger device changes:
 *   - Plug/unplug headphones
 *   - Connect/disconnect a USB DAC or Bluetooth device
 *   - Change the default output in System Preferences
 *
 * The harness logs every device change notification and swap attempt.
 * Press Ctrl+C to stop.
 *
 * Run:
 *   java --enable-preview --enable-native-access=ALL-UNNAMED \
 *       -cp "..." org.dynamisengine.audio.test.HotSwapLiveProve
 */
public final class HotSwapLiveProve {

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BLOCK_SIZE = 256;
    private static final int RING_SLOTS = 4;
    private static final double TONE_HZ = 440.0;
    private static final double AMPLITUDE = 0.25;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hot-Swap Live Proving Harness ===");
        System.out.println("Playing 440 Hz tone. Trigger device changes to test hot-swap.");
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();

        // Discover backend
        AudioBackend backend = null;
        for (AudioBackend b : ServiceLoader.load(AudioBackend.class)) {
            System.out.println("  Discovered: " + b.name() +
                    " (priority=" + b.priority() + ", available=" + b.isAvailable() + ")");
            if (b.isAvailable() && (backend == null || b.priority() > backend.priority())) {
                backend = b;
            }
        }
        if (backend == null) {
            System.out.println("No backend available.");
            return;
        }
        System.out.println("  Selected: " + backend.name());
        System.out.println();

        // Set up ring buffer and feeder
        SpscAudioRingBuffer ring = new SpscAudioRingBuffer(RING_SLOTS, BLOCK_SIZE, CHANNELS);
        float[] block = new float[BLOCK_SIZE * CHANNELS];
        double[] phase = {0.0};

        // Callback: read from ring
        AudioCallback callback = (out, fc, ch) -> {
            if (!ring.read(out)) out.fill((byte) 0);
        };

        // Open default device
        AudioDeviceInfo defaultDev = backend.enumerateDevices().stream()
                .filter(AudioDeviceInfo::isDefault)
                .findFirst()
                .orElse(backend.enumerateDevices().getFirst());

        AudioFormat format = new AudioFormat(SAMPLE_RATE, CHANNELS, BLOCK_SIZE, false);
        AudioDeviceHandle handle = backend.openDevice(defaultDev, format, callback);

        System.out.println("Device: " + handle.deviceDescription());
        System.out.println("Format: " + handle.negotiatedFormat().sampleRate() + "Hz, " +
                handle.negotiatedFormat().channels() + "ch");
        System.out.println();

        // Register device change listener
        backend.setDeviceChangeListener(event -> {
            System.out.println(">>> DEVICE CHANGE EVENT: " + event);
            // In a real system, AudioDeviceManager handles this.
            // Here we just log it to prove the notification arrives.
        });

        // Pre-fill and start
        for (int i = 0; i < 2; i++) {
            generateSine(block, phase, BLOCK_SIZE, CHANNELS);
            ring.write(block, BLOCK_SIZE, CHANNELS);
        }
        handle.start();

        System.out.println("Audio started. Waiting for device changes...");
        System.out.println("  - Plug in headphones");
        System.out.println("  - Unplug headphones");
        System.out.println("  - Change output in System Preferences > Sound");
        System.out.println();

        // Continuous feed loop
        long startTime = System.nanoTime();
        long lastReport = startTime;
        boolean blockReady = false;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            handle.stop();
            handle.close();
            ring.close();
            System.out.println("  Ring underruns: " + ring.underruns());
            System.out.println("  Ring overruns:  " + ring.overruns());
            System.out.println("Done.");
        }));

        while (true) {
            if (!blockReady) {
                generateSine(block, phase, BLOCK_SIZE, CHANNELS);
                blockReady = true;
            }
            if (ring.write(block, BLOCK_SIZE, CHANNELS)) {
                blockReady = false;
            } else {
                Thread.sleep(1);
            }

            // Periodic status report every 5 seconds
            long now = System.nanoTime();
            if (now - lastReport > 5_000_000_000L) {
                long elapsed = (now - startTime) / 1_000_000_000L;
                System.out.println("[" + elapsed + "s] underruns=" + ring.underruns() +
                        " overruns=" + ring.overruns() +
                        " available=" + ring.available() +
                        " hiWater=" + ring.highWatermark() +
                        " loWater=" + ring.lowWatermark());
                lastReport = now;
            }
        }
    }

    private static void generateSine(float[] block, double[] phase, int blockSize, int channels) {
        double phaseInc = 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
        for (int i = 0; i < blockSize; i++) {
            float sample = (float) (Math.sin(phase[0]) * AMPLITUDE);
            for (int ch = 0; ch < channels; ch++) {
                block[i * channels + ch] = sample;
            }
            phase[0] += phaseInc;
        }
        if (phase[0] > 2.0 * Math.PI * 1000) phase[0] -= 2.0 * Math.PI * 1000;
    }
}
