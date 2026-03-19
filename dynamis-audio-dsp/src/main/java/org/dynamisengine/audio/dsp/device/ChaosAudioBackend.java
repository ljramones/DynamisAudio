package org.dynamisengine.audio.dsp.device;

import org.dynamisengine.audio.api.device.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chaos-testing AudioBackend that simulates adverse conditions.
 *
 * Used to stress-test the AudioDeviceManager's state machine, hot-swap logic,
 * watchdog, and failure recovery without requiring real hardware.
 *
 * SIMULATED CONDITIONS:
 * <ul>
 *   <li>Callback jitter (random delay before each callback)</li>
 *   <li>Missed callbacks (skip N callbacks randomly)</li>
 *   <li>Burst callbacks (fire multiple callbacks rapidly)</li>
 *   <li>Fake device change events (random DefaultDeviceChanged / DeviceRemoved)</li>
 *   <li>Open failures (configurable probability)</li>
 *   <li>Render thread death (stop firing callbacks after N invocations)</li>
 * </ul>
 *
 * NOT registered via ServiceLoader — instantiated explicitly in test code.
 */
public final class ChaosAudioBackend implements AudioBackend {

    /** Configuration for chaos simulation behavior. */
    public record ChaosConfig(
            double callbackJitterMs,        // max random delay per callback (0 = no jitter)
            double callbackSkipProbability,  // 0.0-1.0 probability of skipping a callback
            double burstProbability,         // 0.0-1.0 probability of firing 2-3 callbacks rapidly
            double deviceChangeProbability,  // 0.0-1.0 probability of firing fake device change per second
            double openFailureProbability,   // 0.0-1.0 probability that openDevice() throws
            int killCallbackAfter            // stop callback thread after N invocations (0 = never)
    ) {
        public static final ChaosConfig MILD = new ChaosConfig(2.0, 0.01, 0.0, 0.0, 0.0, 0);
        public static final ChaosConfig MODERATE = new ChaosConfig(5.0, 0.05, 0.02, 0.1, 0.0, 0);
        public static final ChaosConfig SEVERE = new ChaosConfig(10.0, 0.10, 0.05, 0.2, 0.1, 0);
        public static final ChaosConfig THREAD_DEATH = new ChaosConfig(0, 0, 0, 0, 0, 200);
    }

    private final ChaosConfig config;
    private volatile DeviceChangeListener deviceChangeListener;
    private volatile Thread chaosEventThread;

    public ChaosAudioBackend(ChaosConfig config) {
        this.config = config;
    }

    @Override
    public String name() { return "Chaos"; }

    @Override
    public int priority() { return -1; } // never selected automatically

    @Override
    public BackendCapabilities capabilities() {
        return new BackendCapabilities(false, false, true, 2, new int[]{256});
    }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public List<AudioDeviceInfo> enumerateDevices() {
        return List.of(new AudioDeviceInfo(
                "chaos-device", "Chaos Simulator",
                2, new int[]{48_000}, true, false));
    }

    @Override
    public AudioDeviceHandle openDevice(AudioDeviceInfo device,
                                         AudioFormat requestedFormat,
                                         AudioCallback audioCallback)
            throws AudioDeviceException {
        // Simulated open failure
        if (ThreadLocalRandom.current().nextDouble() < config.openFailureProbability) {
            throw new AudioDeviceException("Chaos: simulated open failure");
        }
        return new ChaosDeviceHandle(requestedFormat, audioCallback, config);
    }

    @Override
    public void setDeviceChangeListener(DeviceChangeListener listener) {
        this.deviceChangeListener = listener;

        // Stop old chaos event thread
        if (chaosEventThread != null) {
            chaosEventThread.interrupt();
            chaosEventThread = null;
        }

        if (listener != null && config.deviceChangeProbability > 0) {
            chaosEventThread = Thread.ofVirtual()
                    .name("chaos-event-generator")
                    .start(() -> chaosEventLoop(listener));
        }
    }

    private void chaosEventLoop(DeviceChangeListener listener) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000); // check once per second
                if (ThreadLocalRandom.current().nextDouble() < config.deviceChangeProbability) {
                    AudioDeviceInfo fake = new AudioDeviceInfo(
                            "chaos-new-" + System.nanoTime(),
                            "Chaos Device (simulated swap)",
                            2, new int[]{48_000}, true, false);
                    listener.onDeviceChange(
                            new DeviceChangeEvent.DefaultDeviceChanged(fake));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // -- Chaos Device Handle --------------------------------------------------

    private static final class ChaosDeviceHandle implements AudioDeviceHandle {

        private final AudioFormat format;
        private final AudioCallback callback;
        private final ChaosConfig config;
        private volatile boolean active = false;
        private volatile boolean closed = false;
        private volatile long callbacksFired = 0L;
        private Thread callbackThread;

        ChaosDeviceHandle(AudioFormat format, AudioCallback callback, ChaosConfig config) {
            this.format = format;
            this.callback = callback;
            this.config = config;
        }

        @Override
        public void start() {
            if (closed || active) return;
            active = true;
            callbackThread = Thread.ofVirtual()
                    .name("chaos-callback")
                    .start(this::callbackLoop);
        }

        @Override
        public void stop() {
            active = false;
            if (callbackThread != null) {
                callbackThread.interrupt();
                try { callbackThread.join(200); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                callbackThread = null;
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            stop();
        }

        @Override
        public AudioFormat negotiatedFormat() { return format; }

        @Override
        public int outputLatencyFrames() { return format.blockSize(); }

        @Override
        public boolean isActive() { return active && !closed; }

        @Override
        public String deviceDescription() {
            return "Chaos [jitter=" + config.callbackJitterMs + "ms, skip=" +
                    config.callbackSkipProbability + "]";
        }

        private void callbackLoop() {
            long blockDurationNanos = (long) format.blockSize() * 1_000_000_000L / format.sampleRate();

            try (Arena arena = Arena.ofConfined()) {
                long bufferBytes = (long) format.blockSize() * format.channels() * Float.BYTES;
                MemorySegment discardSegment = arena.allocate(bufferBytes, Float.BYTES);
                ThreadLocalRandom rng = ThreadLocalRandom.current();

                while (active && !Thread.currentThread().isInterrupted()) {
                    // Kill after N callbacks (thread death simulation)
                    if (config.killCallbackAfter > 0 && callbacksFired >= config.killCallbackAfter) {
                        return; // thread silently dies
                    }

                    // Jitter
                    if (config.callbackJitterMs > 0) {
                        long jitterNanos = (long) (rng.nextDouble() * config.callbackJitterMs * 1_000_000);
                        Thread.sleep(jitterNanos / 1_000_000, (int) (jitterNanos % 1_000_000));
                    }

                    // Skip
                    if (rng.nextDouble() < config.callbackSkipProbability) {
                        Thread.sleep(blockDurationNanos / 1_000_000);
                        continue; // missed callback
                    }

                    // Normal callback
                    callback.render(discardSegment, format.blockSize(), format.channels());
                    callbacksFired++;

                    // Burst: fire extra callbacks rapidly
                    if (rng.nextDouble() < config.burstProbability) {
                        int burst = rng.nextInt(1, 4);
                        for (int i = 0; i < burst && active; i++) {
                            callback.render(discardSegment, format.blockSize(), format.channels());
                            callbacksFired++;
                        }
                    }

                    // Pace
                    long sleepNanos = blockDurationNanos;
                    if (sleepNanos > 0) {
                        Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
