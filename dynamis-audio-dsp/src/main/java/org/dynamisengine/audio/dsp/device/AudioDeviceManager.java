package org.dynamisengine.audio.dsp.device;

import org.dynamisengine.audio.api.AcousticConstants;
import org.dynamisengine.audio.api.device.AudioBackend;
import org.dynamisengine.audio.api.device.AudioCallback;
import org.dynamisengine.audio.api.device.AudioDeviceException;
import org.dynamisengine.audio.api.device.AudioDeviceHandle;
import org.dynamisengine.audio.api.device.AudioDeviceInfo;
import org.dynamisengine.audio.api.device.AudioFormat;
import org.dynamisengine.audio.api.device.DeviceChangeEvent;
import org.dynamisengine.audio.api.device.DeviceChangeListener;
import org.dynamisengine.audio.api.device.NullAudioBackend;
import org.dynamisengine.audio.dsp.SoftwareMixer;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Sole lifecycle authority for audio device management.
 *
 * <p>This class is the single point of control for backend discovery, device
 * selection, hot-swap orchestration, and failure recovery. No backend, device
 * handle, listener, or external code may independently initiate lifecycle
 * transitions. All device changes are mediated through this manager.
 *
 * <h2>Lifecycle Contract</h2>
 * <pre>{@code
 * UNINITIALIZED ──initialize()──→ INITIALIZED ──start()──→ RUNNING
 * RUNNING ──device change──→ SWAP_PENDING ──DSP worker──→ SWAPPING ──success──→ RUNNING
 * SWAPPING ──no devices──→ DEGRADED ──device appears──→ SWAP_PENDING
 * SWAPPING ──failure──→ FAULTED
 * RUNNING | SWAP_PENDING | DEGRADED | FAULTED ──stop()/shutdown()──→ CLOSED
 * }</pre>
 * Illegal transitions throw {@link IllegalStateException}. CLOSED is terminal.
 * DEGRADED is recoverable (devices may reappear). FAULTED requires shutdown + re-initialize.
 * See {@code docs/audio-device-lifecycle-and-hotswap.md} for the full contract.
 *
 * <h2>Core Invariants</h2>
 * <ul>
 *   <li><b>Callback is transport-only:</b> reads ring buffer, fills silence on underrun.
 *       No allocation, no logging, no synchronization, no DSP, no policy.</li>
 *   <li><b>Ring buffer is the only RT boundary:</b> DSP worker and callback share no
 *       other mutable state. VarHandle acquire/release. No locks, no CAS.</li>
 *   <li><b>Backends must not implement policy:</b> they probe, enumerate, open, and fire
 *       events. Selection, retry, fallback, and swap decisions belong here.</li>
 *   <li><b>Failure is deterministic:</b> device open failure → NullAudioBackend fallback.
 *       Hot-swap failure → FAULTED. Underruns → logged, not faulted.</li>
 * </ul>
 *
 * <h2>Thread Model</h2>
 * <ul>
 *   <li><b>Engine lifecycle thread:</b> {@link #initialize}, {@link #start}, {@link #stop}, {@link #shutdown}</li>
 *   <li><b>DSP worker ({@code dynamis-dsp-worker}):</b> {@code renderBlock()} + ring write + hot-swap execution.
 *       Platform thread, daemon.</li>
 *   <li><b>Platform callback:</b> ring buffer read → output segment. OS real-time thread (CoreAudio)
 *       or Java platform thread (ALSA write thread, WASAPI event thread).</li>
 *   <li><b>Platform notification:</b> fires {@link DeviceChangeEvent} → sets {@code state = SWAP_PENDING}
 *       via single volatile write. Does not open/close devices or block.</li>
 * </ul>
 *
 * <h2>Hot-Swap Sequence</h2>
 * Executed on the DSP worker thread when {@code state == SWAP_PENDING}:
 * stop old device → close old device → re-enumerate → select new default →
 * open new device → pre-fill ring → start new device → {@code state = RUNNING}.
 * Budget: &lt;100ms. Brief silence is expected and normal.
 */
public final class AudioDeviceManager implements DeviceChangeListener {

    private static final System.Logger LOG =
            System.getLogger(AudioDeviceManager.class.getName());

    /** Default ring buffer slot count — 4 blocks absorbs scheduling jitter. */
    private static final int DEFAULT_RING_SLOTS = 4;

    // -- Lifecycle state machine ----------------------------------------------

    /**
     * Explicit lifecycle states for the AudioDeviceManager.
     * Prevents illegal transitions and makes hot-swap behavior deterministic.
     */
    public enum State {
        /** Constructed but not yet initialized. */
        UNINITIALIZED,
        /** Backend discovered, device opened, ring buffer created. Ready to start. */
        INITIALIZED,
        /** DSP worker running, device active, audio flowing. */
        RUNNING,
        /** Hot-swap requested — will execute at top of next feeder loop iteration. */
        SWAP_PENDING,
        /** Actively performing device hot-swap (stop old → open new → start). */
        SWAPPING,
        /** Swap completed but no devices available. DSP worker continues rendering silence.
         *  Recoverable: a DeviceAdded or DefaultDeviceChanged event transitions to SWAP_PENDING. */
        DEGRADED,
        /** A non-recoverable error occurred. Must shutdown() and re-initialize. */
        FAULTED,
        /** Stopped and all resources released. Terminal state. */
        CLOSED
    }

    private volatile State state = State.UNINITIALIZED;

    // -- State ---------------------------------------------------------------

    private AudioBackend activeBackend;
    private AudioDeviceHandle activeDevice;
    private SpscAudioRingBuffer ringBuffer;
    private SoftwareMixer mixer;
    private Thread dspWorker;

    /**
     * Persistent MemorySegment wrapping the mixer's masterOutputBuffer float[].
     * Created once in initialize() — avoids per-block MemorySegment.ofArray() allocation.
     * The underlying float[] is the same object for the mixer's entire lifetime.
     */
    private java.lang.foreign.MemorySegment masterBufferSegment;

    // -- Swap generation & thread watchdog ------------------------------------

    /**
     * Monotonically increasing swap generation ID. Incremented each time a swap
     * is initiated. Used in telemetry and logging to correlate swap events with
     * device change notifications and detect stale-assumption bugs.
     */
    private volatile long swapGeneration = 0L;

    /**
     * Timestamp of last known activity from the platform callback or render thread.
     * Updated by the callback (callbackCount increment) and checked by the DSP worker.
     * If no callback activity is observed for THREAD_WATCHDOG_TIMEOUT_NS while state is
     * RUNNING, the DSP worker transitions to FAULTED (dead render thread detection).
     */
    private volatile long lastCallbackActivityNanos = 0L;

    /** Watchdog timeout: if no callback fires for 5 seconds while RUNNING, assume thread death. */
    private static final long THREAD_WATCHDOG_TIMEOUT_NS = 5_000_000_000L;

    // -- Telemetry -----------------------------------------------------------

    private volatile long callbackCount = 0L;
    private volatile long feederBlockCount = 0L;
    private volatile long feederMaxNanos = 0L;
    private volatile long feederTotalNanos = 0L;
    private volatile long startupTimeNanos = 0L;
    private volatile long firstAudioTimeNanos = 0L;

    // -- Failure policy ------------------------------------------------------

    /** Consecutive underrun window: if underruns exceed this within the window, log a warning. */
    private static final long UNDERRUN_WARN_THRESHOLD = 50;
    private static final long UNDERRUN_WARN_WINDOW_NS = 5_000_000_000L; // 5 seconds

    private volatile long lastUnderrunCheckCount = 0L;
    private volatile long lastUnderrunCheckTimeNanos = 0L;

    /**
     * Deterministic failure policy for audio device errors.
     *
     * DEVICE OPEN FAILURE:    Falls back to NullAudioBackend. Logs error.
     * FORMAT MISMATCH:        Accepts negotiated format. Logs warning if rate differs.
     * EXCLUSIVE MODE FAILURE: Not yet applicable (CoreAudio has no exclusive mode).
     * DEVICE DISAPPEARANCE:   State → SWAP_PENDING → re-enumerate → open new default.
     * REPEATED UNDERRUNS:     Logs warning per 5-second window. Does not fault — underruns
     *                         are expected during load spikes and device transitions.
     * BACKEND STARTUP FAULT:  State → FAULTED. Logs error. Must shutdown() and re-initialize.
     * HOT-SWAP FAILURE:       State → FAULTED. Logs error.
     */
    private void checkUnderrunHealth() {
        if (ringBuffer == null) return;
        long currentUnderruns = ringBuffer.underruns();
        long now = System.nanoTime();

        if (lastUnderrunCheckTimeNanos == 0L) {
            lastUnderrunCheckCount = currentUnderruns;
            lastUnderrunCheckTimeNanos = now;
            return;
        }

        long elapsed = now - lastUnderrunCheckTimeNanos;
        if (elapsed >= UNDERRUN_WARN_WINDOW_NS) {
            long delta = currentUnderruns - lastUnderrunCheckCount;
            if (delta > UNDERRUN_WARN_THRESHOLD) {
                LOG.log(System.Logger.Level.WARNING,
                        "Audio underrun warning: {0} underruns in last {1}ms (total: {2})",
                        delta, elapsed / 1_000_000, currentUnderruns);
            }
            lastUnderrunCheckCount = currentUnderruns;
            lastUnderrunCheckTimeNanos = now;
        }
    }

    /**
     * Dead-thread watchdog for the platform callback / render thread.
     *
     * If state is RUNNING and no callback has fired for THREAD_WATCHDOG_TIMEOUT_NS,
     * the render thread is presumed dead. Transitions to FAULTED.
     *
     * Does NOT fire during DEGRADED (no device = no callback expected) or during
     * the first few seconds after start (lastCallbackActivityNanos == 0).
     */
    private void checkCallbackWatchdog() {
        if (state != State.RUNNING) return;
        long lastActivity = lastCallbackActivityNanos;
        if (lastActivity == 0L) return; // no callback yet — still starting up
        long elapsed = System.nanoTime() - lastActivity;
        if (elapsed > THREAD_WATCHDOG_TIMEOUT_NS) {
            LOG.log(System.Logger.Level.ERROR,
                    "Callback watchdog: no callback activity for {0}ms — render thread presumed dead. " +
                    "Transitioning to FAULTED.",
                    elapsed / 1_000_000);
            state = State.FAULTED;
        }
    }

    // -- Discovery & Selection -----------------------------------------------

    /**
     * Discover and select the best available audio backend via ServiceLoader.
     *
     * Probes all discovered backends and selects the highest-priority one that
     * reports {@link AudioBackend#isAvailable()}.
     *
     * @return the selected backend, or a {@link NullAudioBackend} if none available
     */
    public AudioBackend discoverBackend() {
        ServiceLoader<AudioBackend> loader = ServiceLoader.load(AudioBackend.class);

        AudioBackend best = null;
        for (AudioBackend backend : loader) {
            LOG.log(System.Logger.Level.INFO,
                    "Discovered audio backend: {0} (priority={1})", backend.name(), backend.priority());
            if (backend.isAvailable()) {
                if (best == null || backend.priority() > best.priority()) {
                    best = backend;
                }
            } else {
                LOG.log(System.Logger.Level.DEBUG,
                        "Audio backend {0} not available on this platform", backend.name());
            }
        }

        if (best == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "No platform audio backend available — falling back to NullAudioBackend");
            best = new NullAudioBackend();
        }

        LOG.log(System.Logger.Level.INFO, "Selected audio backend: {0}", best.name());
        this.activeBackend = best;
        return best;
    }

    // -- Initialization ------------------------------------------------------

    /**
     * Initialize the audio device manager: discover backend, open default device,
     * create ring buffer, wire callback.
     *
     * @param mixer the SoftwareMixer that will produce audio blocks
     * @throws AudioDeviceException if the device cannot be opened
     */
    public void initialize(SoftwareMixer mixer) throws AudioDeviceException {
        initialize(mixer, AudioFormat.defaultFormat());
    }

    /**
     * Initialize with a specific audio format.
     *
     * @param mixer  the SoftwareMixer that will produce audio blocks
     * @param format requested audio format
     * @throws AudioDeviceException if the device cannot be opened
     */
    public void initialize(SoftwareMixer mixer, AudioFormat format) throws AudioDeviceException {
        if (state != State.UNINITIALIZED) {
            throw new IllegalStateException(
                    "Cannot initialize from state " + state + " (expected UNINITIALIZED)");
        }
        this.mixer = mixer;

        // 1. Discover backend
        if (activeBackend == null) {
            discoverBackend();
        }

        // 2. Enumerate devices and find default
        List<AudioDeviceInfo> devices = activeBackend.enumerateDevices();
        if (devices.isEmpty()) {
            throw new AudioDeviceException("No audio output devices found");
        }
        AudioDeviceInfo defaultDevice = devices.stream()
                .filter(AudioDeviceInfo::isDefault)
                .findFirst()
                .orElse(devices.getFirst());

        LOG.log(System.Logger.Level.INFO, "Opening audio device: {0}", defaultDevice.displayName());

        // 3. Create ring buffer
        this.ringBuffer = new SpscAudioRingBuffer(
                DEFAULT_RING_SLOTS, format.blockSize(), format.channels());

        // 4a. Wrap the mixer's masterOutputBuffer once — avoids per-block ofArray() allocation.
        // The mixer's float[] is allocated once in its constructor and never replaced.
        this.masterBufferSegment = java.lang.foreign.MemorySegment.ofArray(
                mixer.getMasterOutputBuffer());

        // 4b. Open device with callback wired to ring buffer consumer.
        // FAILURE POLICY: If device open fails and this isn't already the null backend,
        // fall back to NullAudioBackend rather than propagating the exception.
        AudioCallback callback = this::audioCallback;
        try {
            this.activeDevice = activeBackend.openDevice(defaultDevice, format, callback);
        } catch (AudioDeviceException e) {
            if (!(activeBackend instanceof NullAudioBackend)) {
                LOG.log(System.Logger.Level.ERROR,
                        "Device open failed ({0}): {1} — falling back to NullAudioBackend",
                        activeBackend.name(), e.getMessage());
                this.activeBackend = new NullAudioBackend();
                AudioDeviceInfo nullDevice = activeBackend.enumerateDevices().getFirst();
                this.activeDevice = activeBackend.openDevice(nullDevice, format, callback);
            } else {
                throw e; // NullAudioBackend itself failed — truly unrecoverable
            }
        }

        LOG.log(System.Logger.Level.INFO,
                "Audio device opened: {0} (negotiated: {1}Hz, {2}ch, {3} frames, latency={4}ms)",
                activeDevice.deviceDescription(),
                activeDevice.negotiatedFormat().sampleRate(),
                activeDevice.negotiatedFormat().channels(),
                activeDevice.negotiatedFormat().blockSize(),
                activeDevice.outputLatencyMs());

        // 5. Register for hot-swap notifications
        activeBackend.setDeviceChangeListener(this);

        state = State.INITIALIZED;
    }

    // -- DSP Worker ----------------------------------------------------------

    /**
     * Start the DSP worker thread and begin audio output.
     *
     * The worker thread calls {@link SoftwareMixer#renderBlock()} in a loop
     * and feeds the ring buffer. The platform audio callback reads from the ring.
     */
    public void start() {
        if (state != State.INITIALIZED) {
            throw new IllegalStateException(
                    "Cannot start from state " + state + " (expected INITIALIZED)");
        }

        // Pre-fill the ring with silence to give the callback a head start
        float[] silence = new float[ringBuffer.blockSize() * ringBuffer.channels()];
        for (int i = 0; i < DEFAULT_RING_SLOTS / 2; i++) {
            ringBuffer.write(silence, ringBuffer.blockSize(), ringBuffer.channels());
        }

        // Transition to RUNNING before starting the DSP worker thread.
        // The worker loop condition checks state — must be RUNNING before thread starts.
        state = State.RUNNING;
        startupTimeNanos = System.nanoTime();

        // Start the DSP worker thread — platform thread for consistent scheduling
        dspWorker = Thread.ofPlatform()
                .name("dynamis-dsp-worker")
                .daemon(true)
                .start(this::dspWorkerLoop);

        // Start the device — callback begins firing
        activeDevice.start();
        LOG.log(System.Logger.Level.INFO, "Audio device started (state=RUNNING)");
    }

    /**
     * Stop audio output and the DSP worker thread.
     */
    public void stop() {
        if (state != State.RUNNING && state != State.SWAP_PENDING
                && state != State.DEGRADED && state != State.FAULTED) {
            return; // Already stopped or never started
        }
        state = State.CLOSED; // Prevent re-entry

        if (activeDevice != null && activeDevice.isActive()) {
            activeDevice.stop();
        }

        if (dspWorker != null) {
            dspWorker.interrupt();
            try { dspWorker.join(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dspWorker = null;
        }

        LOG.log(System.Logger.Level.INFO, "Audio device stopped");
    }

    /**
     * Shut down completely: stop, close device, release ring buffer.
     */
    public void shutdown() {
        stop();

        if (activeBackend != null) {
            activeBackend.setDeviceChangeListener(null);
        }
        if (activeDevice != null) {
            activeDevice.close();
            activeDevice = null;
        }
        if (ringBuffer != null) {
            ringBuffer.close();
            ringBuffer = null;
        }

        state = State.CLOSED;
        LOG.log(System.Logger.Level.INFO, "AudioDeviceManager shut down (state=CLOSED)");
    }

    // -- Audio Callback (platform real-time thread) --------------------------

    /**
     * Invoked by the platform audio thread when it needs more samples.
     * Reads from the ring buffer into the output segment.
     * On underrun, fills with silence.
     *
     * ALLOCATION CONTRACT: Zero heap allocation.
     */
    private void audioCallback(MemorySegment outputBuffer, int frameCount, int channels) {
        callbackCount++;
        lastCallbackActivityNanos = System.nanoTime(); // watchdog heartbeat (no allocation)
        if (!ringBuffer.read(outputBuffer)) {
            outputBuffer.fill((byte) 0);
        }
    }

    // -- DSP Worker Loop -----------------------------------------------------

    private void dspWorkerLoop() {
        try {
            dspWorkerLoopInternal();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR,
                    "DSP worker thread crashed: " + t.getMessage(), t);
            if (state == State.RUNNING || state == State.SWAP_PENDING) {
                state = State.FAULTED;
            }
        }
    }

    private void dspWorkerLoopInternal() {
        while (state == State.RUNNING || state == State.SWAP_PENDING || state == State.DEGRADED) {
            if (Thread.currentThread().isInterrupted()) break;

            // Check for hot-swap request
            if (state == State.SWAP_PENDING) {
                state = State.SWAPPING;
                handleHotSwap();
                if (state == State.SWAPPING) {
                    state = State.RUNNING; // swap succeeded
                }
                continue; // re-check state
            }

            // Wait for ring space BEFORE rendering. This prevents the mixer from
            // advancing voice state for blocks that would be discarded (overrun).
            // Without this, the DSP worker spins too fast, generating and discarding
            // blocks, which causes audio to skip forward and sound distorted.
            int waitCycles = 0;
            while (ringBuffer.freeSlots() == 0) {
                if (state != State.RUNNING && state != State.SWAP_PENDING
                        && state != State.DEGRADED) break;
                waitCycles++;
                // If ring hasn't drained for a long time, the callback thread may be dead
                if (waitCycles > 10_000) { // ~5 seconds at 0.5ms sleep
                    checkCallbackWatchdog();
                    if (state == State.FAULTED) break;
                    waitCycles = 0;
                }
                try { Thread.sleep(0, 500_000); } // 0.5ms
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Render a block and measure feeder timing
            long t0 = System.nanoTime();
            mixer.renderBlock();
            long renderNanos = System.nanoTime() - t0;

            feederBlockCount++;
            feederTotalNanos += renderNanos;
            if (renderNanos > feederMaxNanos) feederMaxNanos = renderNanos;
            if (firstAudioTimeNanos == 0L && startupTimeNanos > 0L) {
                firstAudioTimeNanos = System.nanoTime();
            }

            // Feed into ring buffer — should succeed since we waited for space above.
            ringBuffer.writeFrom(masterBufferSegment);

            // Periodic health checks (every 256 blocks, not every block)
            if ((feederBlockCount & 0xFF) == 0) {
                checkUnderrunHealth();
                checkCallbackWatchdog();
            }
        }
    }

    // -- Device Hot-Swap -----------------------------------------------------

    /**
     * Handles device change notifications from the platform backend.
     *
     * <p>Called on the platform notification thread (not DSP, not lifecycle).
     * This method MUST NOT throw — listener exceptions never affect device lifecycle.
     * Duplicate notifications for the same physical event are tolerated: only the
     * first {@code RUNNING → SWAP_PENDING} or {@code DEGRADED → SWAP_PENDING}
     * transition takes effect (INV-8). Subsequent notifications during SWAP_PENDING
     * or SWAPPING are silently ignored.
     *
     * <p>Delivery is best-effort from the platform. If a notification is missed,
     * the system continues operating with the current device. Manual intervention
     * (shutdown + re-initialize) is always available as a recovery path.
     */
    @Override
    public void onDeviceChange(DeviceChangeEvent event) {
        try {
            switch (event) {
                case DeviceChangeEvent.DefaultDeviceChanged ddc -> {
                    LOG.log(System.Logger.Level.INFO,
                            "Default audio device changed: {0}", ddc.newDefault().displayName());
                    // INV-8: Only the first SWAP_PENDING transition is honored.
                    // DEGRADED also transitions — device reappeared.
                    if (state == State.RUNNING || state == State.DEGRADED) {
                        state = State.SWAP_PENDING;
                    }
                }
                case DeviceChangeEvent.DeviceRemoved dr -> {
                    LOG.log(System.Logger.Level.INFO,
                            "Audio device removed: {0}", dr.deviceId());
                    if (state == State.RUNNING && activeDevice != null
                            && activeDevice.deviceDescription().contains(dr.deviceId())) {
                        state = State.SWAP_PENDING;
                    }
                }
                case DeviceChangeEvent.DeviceAdded da -> {
                    LOG.log(System.Logger.Level.INFO,
                            "Audio device added: {0}", da.device().displayName());
                    // If degraded (no devices), a new device arrival triggers swap
                    if (state == State.DEGRADED) {
                        state = State.SWAP_PENDING;
                    }
                }
            }
        } catch (Throwable t) {
            // Listener exceptions NEVER affect device lifecycle.
            // Swallow and log — the platform notification thread must not be disrupted.
            LOG.log(System.Logger.Level.WARNING,
                    "Exception in device change listener (swallowed): " + t.getMessage(), t);
        }
    }

    private void handleHotSwap() {
        long gen = ++swapGeneration;
        String oldDeviceDesc = activeDevice != null ? activeDevice.deviceDescription() : "none";
        LOG.log(System.Logger.Level.INFO,
                "Hot-swap gen={0}: starting. Backend={1}, oldDevice={2}",
                gen, activeBackend.name(), oldDeviceDesc);
        try {
            // Stop old device
            if (activeDevice != null && activeDevice.isActive()) {
                activeDevice.stop();
            }
            if (activeDevice != null) {
                activeDevice.close();
            }

            // Re-enumerate and open new default
            List<AudioDeviceInfo> devices = activeBackend.enumerateDevices();
            if (devices.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING,
                        "Hot-swap gen={0}: no devices found — entering DEGRADED", gen);
                state = State.DEGRADED;
                return;
            }

            AudioDeviceInfo newDefault = devices.stream()
                    .filter(AudioDeviceInfo::isDefault)
                    .findFirst()
                    .orElse(devices.getFirst());

            AudioFormat format = AudioFormat.stereo48k(
                    AcousticConstants.DSP_BLOCK_SIZE);

            LOG.log(System.Logger.Level.INFO,
                    "Hot-swap gen={0}: opening new device {1} [{2}]",
                    gen, newDefault.displayName(), newDefault.id());

            activeDevice = activeBackend.openDevice(newDefault, format, this::audioCallback);

            // Reset watchdog — new device, fresh callback expected
            lastCallbackActivityNanos = 0L;

            // Pre-fill ring with silence
            float[] silence = new float[ringBuffer.blockSize() * ringBuffer.channels()];
            ringBuffer.write(silence, ringBuffer.blockSize(), ringBuffer.channels());

            activeDevice.start();
            LOG.log(System.Logger.Level.INFO,
                    "Hot-swap gen={0}: complete. Now using {1} [{2}]. state→RUNNING",
                    gen, newDefault.displayName(), newDefault.id());

        } catch (AudioDeviceException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "Hot-swap gen=" + swapGeneration + " FAILED: " + e.getMessage() +
                    " — state→FAULTED", e);
            state = State.FAULTED;
        }
    }

    // -- Diagnostics ---------------------------------------------------------

    /**
     * Override the backend for testing. Must be called before {@link #initialize}.
     * Not for production use — use ServiceLoader discovery instead.
     */
    public void setBackendForTesting(AudioBackend backend) {
        this.activeBackend = backend;
    }

    /** Returns the active backend, or null if not initialized. */
    public AudioBackend getActiveBackend() { return activeBackend; }

    /** Returns the active device handle, or null if not initialized. */
    public AudioDeviceHandle getActiveDevice() { return activeDevice; }

    /** Returns the ring buffer, or null if not initialized. */
    public SpscAudioRingBuffer getRingBuffer() { return ringBuffer; }

    /**
     * Capture a structured telemetry snapshot.
     * Safe to call from any thread. Allocates one {@link AudioTelemetry} record.
     * Not for use on the RT hot path.
     */
    public AudioTelemetry captureTelemetry() {
        var dev = activeDevice;
        var rb = ringBuffer;
        var fmt = dev != null ? dev.negotiatedFormat() : null;
        return new AudioTelemetry(
                state,
                activeBackend != null ? activeBackend.name() : "none",
                dev != null ? dev.deviceDescription() : "none",
                fmt,
                dev != null ? dev.outputLatencyMs() : 0f,
                swapGeneration,
                callbackCount,
                feederBlockCount,
                getFeederAvgNanos(),
                feederMaxNanos,
                getStartupToFirstAudioMs(),
                rb != null ? rb.available() : 0,
                rb != null ? rb.slotCount() : 0,
                rb != null ? rb.highWatermark() : 0,
                rb != null ? rb.lowWatermark() : 0,
                rb != null ? rb.underruns() : 0,
                rb != null ? rb.overruns() : 0);
    }

    /** True if the DSP worker is running (audio may be flowing or degraded). */
    public boolean isRunning() {
        return state == State.RUNNING || state == State.SWAP_PENDING || state == State.DEGRADED;
    }

    /** Returns the current lifecycle state. */
    public State getState() { return state; }

    /** Current swap generation (incremented each hot-swap attempt). */
    public long getSwapGeneration() { return swapGeneration; }

    /** Total callback invocations (on the native audio thread). */
    public long getCallbackCount() { return callbackCount; }

    /** Total blocks rendered by the DSP feeder worker. */
    public long getFeederBlockCount() { return feederBlockCount; }

    /** Max render time of a single feeder block in nanoseconds. */
    public long getFeederMaxNanos() { return feederMaxNanos; }

    /** Average render time per feeder block in nanoseconds. Returns 0 if no blocks rendered. */
    public long getFeederAvgNanos() {
        long count = feederBlockCount;
        return count > 0 ? feederTotalNanos / count : 0;
    }

    /** Time from start() to first audio block rendered, in milliseconds. Returns -1 if not yet measured. */
    public float getStartupToFirstAudioMs() {
        if (startupTimeNanos == 0 || firstAudioTimeNanos == 0) return -1f;
        return (firstAudioTimeNanos - startupTimeNanos) / 1_000_000f;
    }

    /**
     * Returns a human-readable telemetry snapshot string.
     * Suitable for profiler overlay or diagnostic logging.
     */
    public String telemetrySnapshot() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("=== AudioDeviceManager Telemetry ===\n");

        if (activeBackend != null) {
            sb.append("Backend:           ").append(activeBackend.name()).append('\n');
        }
        if (activeDevice != null) {
            sb.append("Device:            ").append(activeDevice.deviceDescription()).append('\n');
            AudioFormat fmt = activeDevice.negotiatedFormat();
            sb.append("Format:            ").append(fmt.sampleRate()).append("Hz, ")
              .append(fmt.channels()).append("ch, ").append(fmt.blockSize()).append(" frames\n");
            sb.append("Output latency:    ").append(String.format("%.2f", activeDevice.outputLatencyMs()))
              .append(" ms\n");
        }

        sb.append("State:             ").append(state).append('\n');
        sb.append("Swap generation:   ").append(swapGeneration).append('\n');
        sb.append("Callback count:    ").append(callbackCount).append('\n');
        sb.append("Feeder blocks:     ").append(feederBlockCount).append('\n');
        sb.append("Feeder avg:        ").append(String.format("%.3f", getFeederAvgNanos() / 1_000_000.0))
          .append(" ms\n");
        sb.append("Feeder max:        ").append(String.format("%.3f", feederMaxNanos / 1_000_000.0))
          .append(" ms\n");

        if (ringBuffer != null) {
            sb.append("Ring occupancy:    ").append(ringBuffer.available()).append(" / ")
              .append(ringBuffer.slotCount()).append('\n');
            sb.append("Ring high water:   ").append(ringBuffer.highWatermark()).append('\n');
            sb.append("Ring low water:    ").append(ringBuffer.lowWatermark()).append('\n');
            sb.append("Ring underruns:    ").append(ringBuffer.underruns()).append('\n');
            sb.append("Ring overruns:     ").append(ringBuffer.overruns()).append('\n');
        }

        float startupMs = getStartupToFirstAudioMs();
        if (startupMs >= 0) {
            sb.append("Startup→audio:     ").append(String.format("%.2f", startupMs)).append(" ms\n");
        }

        return sb.toString();
    }
}
