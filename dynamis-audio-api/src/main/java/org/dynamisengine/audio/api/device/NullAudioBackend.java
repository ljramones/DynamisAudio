package org.dynamisengine.audio.api.device;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * No-op AudioBackend for testing and headless CI environments.
 *
 * Always available. Returns a single dummy device. The opened handle
 * fires the AudioCallback at the expected cadence but discards all output.
 * Used as the fallback when no platform backend is discovered.
 */
public final class NullAudioBackend implements AudioBackend {

    @Override
    public String name() { return "Null"; }

    @Override
    public int priority() { return 0; }

    @Override
    public BackendCapabilities capabilities() { return BackendCapabilities.NULL; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public List<AudioDeviceInfo> enumerateDevices() {
        return List.of(new AudioDeviceInfo(
                "null-device", "NullAudioDevice (discard)",
                2, new int[]{48_000}, true, false));
    }

    @Override
    public AudioDeviceHandle openDevice(AudioDeviceInfo device,
                                         AudioFormat requestedFormat,
                                         AudioCallback audioCallback) {
        return new NullDeviceHandle(requestedFormat, audioCallback);
    }

    @Override
    public void setDeviceChangeListener(DeviceChangeListener listener) {
        // No device changes in null backend
    }

    // -- NullDeviceHandle ---------------------------------------------------

    private static final class NullDeviceHandle implements AudioDeviceHandle {

        private final AudioFormat format;
        private final AudioCallback callback;
        private volatile boolean active = false;
        private volatile boolean closed = false;
        private volatile long blocksRendered = 0L;
        private Thread renderThread;

        NullDeviceHandle(AudioFormat format, AudioCallback callback) {
            this.format = format;
            this.callback = callback;
        }

        @Override
        public void start() {
            if (closed) return;
            active = true;
            // Start a virtual thread that simulates the callback cadence
            renderThread = Thread.ofVirtual()
                    .name("null-audio-callback")
                    .start(this::callbackLoop);
        }

        @Override
        public void stop() {
            active = false;
            if (renderThread != null) {
                renderThread.interrupt();
                try { renderThread.join(100); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                renderThread = null;
            }
        }

        @Override
        public void close() {
            stop();
            closed = true;
        }

        @Override
        public AudioFormat negotiatedFormat() { return format; }

        @Override
        public int outputLatencyFrames() { return 0; }

        @Override
        public boolean isActive() { return active && !closed; }

        @Override
        public String deviceDescription() {
            return "NullAudioDevice [discard, " + format.sampleRate() + "Hz, " +
                   format.channels() + "ch]";
        }

        /** Total callback invocations since start. Useful for test assertions. */
        public long blocksRendered() { return blocksRendered; }

        private void callbackLoop() {
            long blockDurationNanos = (long) format.blockSize() * 1_000_000_000L / format.sampleRate();
            // Small off-heap segment for the callback to write into (discarded)
            try (var arena = java.lang.foreign.Arena.ofConfined()) {
                long bufferBytes = (long) format.blockSize() * format.channels() * Float.BYTES;
                MemorySegment discardSegment = arena.allocate(bufferBytes, Float.BYTES);

                while (active && !Thread.currentThread().isInterrupted()) {
                    long start = System.nanoTime();
                    callback.render(discardSegment, format.blockSize(), format.channels());
                    blocksRendered++;
                    long elapsed = System.nanoTime() - start;
                    long sleepNanos = blockDurationNanos - elapsed;
                    if (sleepNanos > 0) {
                        try {
                            Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }
    }
}
