package org.dynamisengine.audio.test;

import org.dynamisengine.audio.api.device.*;
import org.dynamisengine.audio.dsp.device.SpscAudioRingBuffer;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Live audio proving harness.
 *
 * Exercises the full end-to-end path:
 *   SineWaveGenerator -> SpscAudioRingBuffer -> AudioBackend callback -> speakers
 *
 * Uses ServiceLoader to discover the platform backend — tests the real production
 * discovery path. On macOS, this discovers CoreAudioBackend. On other platforms,
 * falls back to NullAudioBackend.
 *
 * Run manually (NOT in CI — requires audio hardware):
 *   cd DynamisAudio
 *   mvn -pl dynamis-audio-test-harness -am test-compile
 *   java --enable-preview -cp "dynamis-audio-test-harness/target/test-classes:dynamis-audio-test-harness/target/classes:dynamis-audio-api/target/classes:dynamis-audio-dsp/target/classes:dynamis-audio-core/target/classes:dynamis-audio-designer/target/classes:dynamis-audio-simulation/target/classes:dynamis-audio-backend-coreaudio/target/classes" \
 *       org.dynamisengine.audio.test.CoreAudioLiveProve
 *
 * SUCCESS CRITERIA:
 *   - Audible, clean 440 Hz tone (no clicks, no dropouts)
 *   - Zero underruns during normal feeding
 *   - Callback count ~ expected (sampleRate / blockSize * durationSeconds)
 *   - Ring low watermark > 0 (never ran dry during normal operation)
 *   - Clean shutdown (no native crashes)
 *   - Lifecycle cycles complete without error
 *   - Recovery from feeder stall without crash
 */
public final class CoreAudioLiveProve {

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BLOCK_SIZE = 256;
    private static final int RING_SLOTS = 4;
    private static final double TONE_HZ = 440.0;
    private static final double AMPLITUDE = 0.25;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Audio Backend Live Proving Harness ===");
        System.out.println();

        // Discover backend via ServiceLoader (production path)
        AudioBackend backend = discoverBackend();
        System.out.println("Backend:  " + backend.name());
        System.out.println("Priority: " + backend.priority());
        System.out.println();

        if (!backend.isAvailable()) {
            System.out.println("SKIP: Backend reports not available on this platform");
            return;
        }

        System.out.println("--- Test 1: Basic playback (3s sine wave) ---");
        testBasicPlayback(backend);

        System.out.println();
        System.out.println("--- Test 2: Lifecycle robustness (5 cycles) ---");
        testLifecycleCycles(backend);

        System.out.println();
        System.out.println("--- Test 3: Feeder stall recovery ---");
        testFeederStall(backend);

        System.out.println();
        System.out.println("All tests complete.");
    }

    private static AudioBackend discoverBackend() {
        AudioBackend best = null;
        for (AudioBackend b : ServiceLoader.load(AudioBackend.class)) {
            System.out.println("  Discovered: " + b.name() + " (priority=" + b.priority() +
                    ", available=" + b.isAvailable() + ")");
            if (b.isAvailable() && (best == null || b.priority() > best.priority())) {
                best = b;
            }
        }
        if (best == null) {
            System.out.println("  No platform backend found — using NullAudioBackend");
            best = new NullAudioBackend();
        }
        return best;
    }

    private static AudioDeviceInfo getDefaultDevice(AudioBackend backend) {
        List<AudioDeviceInfo> devices = backend.enumerateDevices();
        System.out.println("  Devices: " + devices.size());
        for (AudioDeviceInfo dev : devices) {
            System.out.println("    " + (dev.isDefault() ? "> " : "  ") +
                    dev.displayName() + " [" + dev.id() + "] " + dev.maxChannels() + "ch");
        }
        return devices.stream()
                .filter(AudioDeviceInfo::isDefault)
                .findFirst().orElse(devices.getFirst());
    }

    // -- Test 1: Basic Playback -----------------------------------------------

    private static void testBasicPlayback(AudioBackend backend) throws Exception {
        AudioDeviceInfo dev = getDefaultDevice(backend);
        AudioFormat format = new AudioFormat(SAMPLE_RATE, CHANNELS, BLOCK_SIZE, false);
        Feeder feeder = new Feeder(RING_SLOTS, BLOCK_SIZE, CHANNELS);

        AudioCallback callback = (out, fc, ch) -> {
            if (!feeder.ring.read(out)) out.fill((byte) 0);
            feeder.callbackCount++;
        };

        long openStart = System.nanoTime();
        AudioDeviceHandle handle = backend.openDevice(dev, format, callback);
        System.out.println("  Opened in " + (System.nanoTime() - openStart) / 1_000_000 + " ms");
        System.out.println("  Negotiated: " + handle.negotiatedFormat().sampleRate() + "Hz, " +
                handle.negotiatedFormat().channels() + "ch, " +
                handle.negotiatedFormat().blockSize() + " frames");
        System.out.println("  Latency: " + String.format("%.2f", handle.outputLatencyMs()) + " ms");

        feeder.prefill(2);
        long startTime = System.nanoTime();
        handle.start();

        double durationSec = 3.0;
        long durationNanos = (long) (durationSec * 1_000_000_000L);
        long blockNanos = (long) BLOCK_SIZE * 1_000_000_000L / SAMPLE_RATE;

        boolean blockReady = false;
        while (System.nanoTime() - startTime < durationNanos) {
            // Only generate a new block if we don't have one pending
            if (!blockReady) {
                feeder.generateBlock();
                blockReady = true;
            }
            if (feeder.ring.write(feeder.block, BLOCK_SIZE, CHANNELS)) {
                feeder.feederBlocks++;
                blockReady = false;
            } else {
                // Ring full — wait rather than spin and waste CPU
                Thread.sleep(1);
            }
        }

        handle.stop();
        handle.close();

        printReport(feeder, durationSec);
        feeder.ring.close();
    }

    // -- Test 2: Lifecycle Cycles ---------------------------------------------

    private static void testLifecycleCycles(AudioBackend backend) throws Exception {
        AudioDeviceInfo dev = getDefaultDevice(backend);

        for (int cycle = 1; cycle <= 5; cycle++) {
            Feeder feeder = new Feeder(RING_SLOTS, BLOCK_SIZE, CHANNELS);
            AudioCallback callback = (out, fc, ch) -> {
                if (!feeder.ring.read(out)) out.fill((byte) 0);
                feeder.callbackCount++;
            };

            AudioDeviceHandle handle = backend.openDevice(dev, AudioFormat.stereo48k(BLOCK_SIZE), callback);
            feeder.prefill(2);
            handle.start();

            long end = System.nanoTime() + 200_000_000L;
            boolean blockReady = false;
            while (System.nanoTime() < end) {
                if (!blockReady) {
                    feeder.generateBlock();
                    blockReady = true;
                }
                if (feeder.ring.write(feeder.block, BLOCK_SIZE, CHANNELS)) {
                    feeder.feederBlocks++;
                    blockReady = false;
                } else {
                    Thread.sleep(1);
                }
            }

            handle.stop();
            handle.close();
            feeder.ring.close();

            System.out.println("  Cycle " + cycle + ": callbacks=" + feeder.callbackCount +
                    " underruns=" + feeder.ring.underruns() +
                    (feeder.ring.underruns() == 0 ? " OK" : " DEGRADED"));
        }
    }

    // -- Test 3: Feeder Stall Recovery ----------------------------------------

    private static void testFeederStall(AudioBackend backend) throws Exception {
        AudioDeviceInfo dev = getDefaultDevice(backend);
        Feeder feeder = new Feeder(RING_SLOTS, BLOCK_SIZE, CHANNELS);
        AudioCallback callback = (out, fc, ch) -> {
            if (!feeder.ring.read(out)) out.fill((byte) 0);
            feeder.callbackCount++;
        };

        AudioDeviceHandle handle = backend.openDevice(dev, AudioFormat.stereo48k(BLOCK_SIZE), callback);
        feeder.prefill(RING_SLOTS);
        handle.start();

        // Normal feed 500ms
        System.out.println("  Phase 1: Normal feed (500ms)");
        feedFor(feeder, 500);
        long underrunsBefore = feeder.ring.underruns();
        System.out.println("    Underruns: " + underrunsBefore);

        // Stall 100ms
        System.out.println("  Phase 2: Feeder stall (100ms)");
        Thread.sleep(100);
        long stallUnderruns = feeder.ring.underruns() - underrunsBefore;
        System.out.println("    Stall underruns: " + stallUnderruns);

        // Recovery 500ms
        System.out.println("  Phase 3: Recovery (500ms)");
        long underrunsAtResume = feeder.ring.underruns();
        feedFor(feeder, 500);
        long recoveryUnderruns = feeder.ring.underruns() - underrunsAtResume;
        System.out.println("    Recovery underruns: " + recoveryUnderruns);

        handle.stop();
        handle.close();
        feeder.ring.close();

        System.out.println("  Status: " +
                (stallUnderruns > 0 && recoveryUnderruns == 0 ? "RECOVERED" : "CHECK RESULTS"));
    }

    // -- Helpers --------------------------------------------------------------

    private static void feedFor(Feeder feeder, long millis) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000L;
        boolean blockReady = false;
        while (System.nanoTime() < end) {
            if (!blockReady) {
                feeder.generateBlock();
                blockReady = true;
            }
            if (feeder.ring.write(feeder.block, BLOCK_SIZE, CHANNELS)) {
                feeder.feederBlocks++;
                blockReady = false;
            } else {
                Thread.sleep(1);
            }
        }
    }

    private static void printReport(Feeder feeder, double durationSec) {
        long expectedCallbacks = (long) (durationSec * SAMPLE_RATE / BLOCK_SIZE);
        System.out.println("  -- Results --");
        System.out.println("  Duration:        " + String.format("%.1f", durationSec) + " s");
        System.out.println("  Callbacks:       " + feeder.callbackCount + " (expected ~" + expectedCallbacks + ")");
        System.out.println("  Feeder blocks:   " + feeder.feederBlocks);
        System.out.println("  Ring underruns:  " + feeder.ring.underruns());
        System.out.println("  Ring overruns:   " + feeder.ring.overruns());
        System.out.println("  Ring high water: " + feeder.ring.highWatermark());
        System.out.println("  Ring low water:  " + feeder.ring.lowWatermark());
        boolean healthy = feeder.ring.underruns() == 0 && feeder.callbackCount > 0;
        System.out.println("  Status:          " + (healthy ? "HEALTHY" : "DEGRADED"));
    }

    private static final class Feeder {
        final SpscAudioRingBuffer ring;
        final float[] block;
        final int blockSize;
        final int channels;
        double phase = 0.0;
        volatile long callbackCount = 0L;
        long feederBlocks = 0L;

        Feeder(int slots, int blockSize, int channels) {
            this.ring = new SpscAudioRingBuffer(slots, blockSize, channels);
            this.block = new float[blockSize * channels];
            this.blockSize = blockSize;
            this.channels = channels;
        }

        void generateBlock() {
            double phaseInc = 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
            for (int i = 0; i < blockSize; i++) {
                float sample = (float) (Math.sin(phase) * AMPLITUDE);
                for (int ch = 0; ch < channels; ch++) {
                    block[i * channels + ch] = sample;
                }
                phase += phaseInc;
            }
            if (phase > 2.0 * Math.PI * 1000) phase -= 2.0 * Math.PI * 1000;
        }

        void prefill(int blocks) {
            for (int i = 0; i < blocks; i++) {
                generateBlock();
                ring.write(block, blockSize, channels);
            }
        }
    }
}
