package org.dynamisengine.audio.backend.wasapi;

import org.dynamisengine.audio.api.device.*;

import java.lang.foreign.*;

import static java.lang.foreign.ValueLayout.*;

/**
 * Active WASAPI audio output session.
 *
 * Supports two modes:
 * <ul>
 *   <li><b>Shared mode (default):</b> Audio mixes with other apps through the Windows mixer.
 *       Latency: 10-30ms. Format: whatever the mixer supports.</li>
 *   <li><b>Exclusive mode (opt-in):</b> Direct hardware access, lowest latency.
 *       Latency: 3-10ms. Format: must match hardware exactly.</li>
 * </ul>
 *
 * Uses event-driven buffer delivery ({@code AUDCLNT_STREAMFLAGS_EVENTCALLBACK}):
 * a Windows event is signaled when the device needs more samples. A dedicated
 * render thread waits on the event, then fills the buffer via the AudioCallback.
 *
 * COM LIFECYCLE:
 *   CoInitializeEx → CoCreateInstance(MMDeviceEnumerator) →
 *   GetDefaultAudioEndpoint → Activate(IAudioClient) →
 *   Initialize → GetService(IAudioRenderClient) →
 *   SetEventHandle → Start → [render loop] → Stop → Release all
 *
 * PROVING STATUS: IMPLEMENTED — NOT PROVEN.
 * Developed on macOS. Requires live Windows hardware proving.
 */
final class WasapiDeviceHandle implements AudioDeviceHandle {

    private static final System.Logger LOG =
            System.getLogger(WasapiDeviceHandle.class.getName());

    private final Arena arena;
    private final AudioFormat negotiatedFormat;
    private final AudioCallback audioCallback;
    private final String deviceDescription;
    private final boolean exclusiveMode;

    // COM interface pointers (released in close())
    private MemorySegment pEnumerator;
    private MemorySegment pDevice;
    private MemorySegment pAudioClient;
    private MemorySegment pRenderClient;
    private MemorySegment hEvent;   // Windows event handle for buffer notification
    private int bufferFrameCount;   // Total buffer size negotiated with device

    private volatile boolean active = false;
    private volatile boolean closed = false;
    private volatile long nativeWriteCount = 0L;
    private Thread renderThread;

    WasapiDeviceHandle(AudioFormat requestedFormat,
                       AudioCallback audioCallback,
                       String deviceName) throws AudioDeviceException {
        this.audioCallback = audioCallback;
        this.exclusiveMode = requestedFormat.exclusiveMode();
        this.arena = Arena.ofShared();

        try {
            // 1. Initialize COM on this thread
            int hr = WasapiBindings.CoInitializeEx(MemorySegment.NULL,
                    WasapiConstants.COINIT_MULTITHREADED);
            ComHelper.checkHr(hr, "CoInitializeEx");

            // 2. Create MMDeviceEnumerator
            MemorySegment clsid = WasapiStructs.allocateClsidMMDeviceEnumerator(arena);
            MemorySegment iid = WasapiStructs.allocateIidIMMDeviceEnumerator(arena);
            MemorySegment ppEnumerator = arena.allocate(ADDRESS);

            hr = WasapiBindings.CoCreateInstance(clsid, MemorySegment.NULL,
                    WasapiConstants.CLSCTX_ALL, iid, ppEnumerator);
            ComHelper.checkHr(hr, "CoCreateInstance(MMDeviceEnumerator)");
            this.pEnumerator = ppEnumerator.get(ADDRESS, 0);

            // 3. Get default audio endpoint
            MemorySegment ppDevice = arena.allocate(ADDRESS);
            ComHelper.callChecked(pEnumerator,
                    WasapiConstants.IMMDEVICEENUMERATOR_GETDEFAULTAUDIOENDPOINT,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
                    "GetDefaultAudioEndpoint",
                    pEnumerator, WasapiConstants.eRender, WasapiConstants.eConsole, ppDevice);
            this.pDevice = ppDevice.get(ADDRESS, 0);

            // 4. Activate IAudioClient
            MemorySegment iidAudioClient = WasapiStructs.allocateIidIAudioClient(arena);
            MemorySegment ppAudioClient = arena.allocate(ADDRESS);
            ComHelper.callChecked(pDevice,
                    WasapiConstants.IMMDEVICE_ACTIVATE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    "IMMDevice::Activate(IAudioClient)",
                    pDevice, iidAudioClient, WasapiConstants.CLSCTX_ALL,
                    MemorySegment.NULL, ppAudioClient);
            this.pAudioClient = ppAudioClient.get(ADDRESS, 0);

            // 5. Set up WAVEFORMATEXTENSIBLE
            int channels = requestedFormat.channels();
            int sampleRate = requestedFormat.sampleRate();
            MemorySegment wfx = WasapiStructs.allocateWaveFormatFloat(arena, sampleRate, channels);

            // 6. Initialize the audio client
            int shareMode = exclusiveMode
                    ? WasapiConstants.AUDCLNT_SHAREMODE_EXCLUSIVE
                    : WasapiConstants.AUDCLNT_SHAREMODE_SHARED;
            int streamFlags = WasapiConstants.AUDCLNT_STREAMFLAGS_EVENTCALLBACK;

            // Buffer duration in 100-nanosecond units (10ms default for shared mode)
            long bufferDuration = 100_000L; // 10ms in 100ns units

            ComHelper.callChecked(pAudioClient,
                    WasapiConstants.IAUDIOCLIENT_INITIALIZE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS),
                    "IAudioClient::Initialize",
                    pAudioClient, shareMode, streamFlags,
                    bufferDuration, 0L, wfx, MemorySegment.NULL);

            // 7. Get the buffer size
            MemorySegment pBufferFrames = arena.allocate(JAVA_INT);
            ComHelper.callChecked(pAudioClient,
                    WasapiConstants.IAUDIOCLIENT_GETBUFFERSIZE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                    "IAudioClient::GetBufferSize",
                    pAudioClient, pBufferFrames);
            this.bufferFrameCount = pBufferFrames.get(JAVA_INT, 0);

            // 8. Create event handle for buffer notifications
            this.hEvent = WasapiBindings.CreateEventW(
                    MemorySegment.NULL, 0, 0, MemorySegment.NULL);

            // 9. Set the event handle on the audio client
            ComHelper.callChecked(pAudioClient,
                    WasapiConstants.IAUDIOCLIENT_SETEVENTHANDLE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                    "IAudioClient::SetEventHandle",
                    pAudioClient, hEvent);

            // 10. Get IAudioRenderClient
            MemorySegment iidRenderClient = WasapiStructs.allocateIidIAudioRenderClient(arena);
            MemorySegment ppRenderClient = arena.allocate(ADDRESS);
            ComHelper.callChecked(pAudioClient,
                    WasapiConstants.IAUDIOCLIENT_GETSERVICE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    "IAudioClient::GetService(IAudioRenderClient)",
                    pAudioClient, iidRenderClient, ppRenderClient);
            this.pRenderClient = ppRenderClient.get(ADDRESS, 0);

            // Read back negotiated format (for shared mode, device may change rate)
            this.negotiatedFormat = new AudioFormat(
                    sampleRate, channels,
                    Math.min(requestedFormat.blockSize(), bufferFrameCount),
                    exclusiveMode);

            this.deviceDescription = "WASAPI [" + deviceName +
                    (exclusiveMode ? ", exclusive" : ", shared") + ", " +
                    sampleRate + "Hz, " + channels + "ch, buf=" + bufferFrameCount + "]";

            LOG.log(System.Logger.Level.INFO, "WASAPI device opened: {0}", deviceDescription);

        } catch (AudioDeviceException e) {
            releaseAll();
            throw e;
        } catch (Throwable t) {
            releaseAll();
            throw new AudioDeviceException("WASAPI initialization failed", t);
        }
    }

    // -- AudioDeviceHandle ---------------------------------------------------

    @Override
    public void start() {
        if (closed || active) return;
        active = true;

        renderThread = Thread.ofPlatform()
                .name("wasapi-render-thread")
                .daemon(true)
                .start(this::renderLoop);

        try {
            ComHelper.callChecked(pAudioClient,
                    WasapiConstants.IAUDIOCLIENT_START,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS),
                    "IAudioClient::Start",
                    pAudioClient);
        } catch (Throwable t) {
            active = false;
            LOG.log(System.Logger.Level.ERROR, "Failed to start WASAPI: " + t.getMessage(), t);
        }

        LOG.log(System.Logger.Level.INFO, "WASAPI started");
    }

    @Override
    public void stop() {
        if (!active) return;
        active = false;

        if (renderThread != null) {
            renderThread.interrupt();
            try { renderThread.join(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            renderThread = null;
        }

        try {
            ComHelper.call(pAudioClient,
                    WasapiConstants.IAUDIOCLIENT_STOP,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS),
                    pAudioClient);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "IAudioClient::Stop failed: {0}", t.getMessage());
        }

        LOG.log(System.Logger.Level.INFO, "WASAPI stopped");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        stop();
        releaseAll();
        LOG.log(System.Logger.Level.INFO, "WASAPI device closed");
    }

    @Override
    public AudioFormat negotiatedFormat() { return negotiatedFormat; }

    @Override
    public int outputLatencyFrames() {
        try {
            MemorySegment pLatency = arena.allocate(JAVA_LONG);
            int hr = ComHelper.call(pAudioClient,
                    WasapiConstants.IAUDIOCLIENT_GETSTREAMLATENCY,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                    pAudioClient, pLatency);
            if (hr >= 0) {
                long latency100ns = pLatency.get(JAVA_LONG, 0);
                return (int) (latency100ns * negotiatedFormat.sampleRate() / 10_000_000L);
            }
        } catch (Throwable ignored) {}
        return bufferFrameCount;
    }

    @Override
    public boolean isActive() { return active && !closed; }

    @Override
    public String deviceDescription() { return deviceDescription; }

    public long nativeWriteCount() { return nativeWriteCount; }

    // -- Render Loop (event-driven) ------------------------------------------

    /**
     * Event-driven render loop.
     *
     * Waits for the Windows event signal (device needs more data), then:
     * 1. Query current padding (frames already in device buffer)
     * 2. Calculate available frames
     * 3. GetBuffer from IAudioRenderClient
     * 4. Fill via AudioCallback
     * 5. ReleaseBuffer
     */
    private void renderLoop() {
        // COM must be initialized on this thread too
        try {
            WasapiBindings.CoInitializeEx(MemorySegment.NULL,
                    WasapiConstants.COINIT_MULTITHREADED);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "COM init failed on render thread", t);
            return;
        }

        try (Arena loopArena = Arena.ofConfined()) {
            MemorySegment pPadding = loopArena.allocate(JAVA_INT);
            MemorySegment ppData = loopArena.allocate(ADDRESS);
            int channels = negotiatedFormat.channels();

            while (active && !Thread.currentThread().isInterrupted()) {
                // Wait for event signal (device needs data)
                int waitResult = WasapiBindings.WaitForSingleObject(hEvent, 2000);
                if (waitResult == WasapiBindings.WAIT_TIMEOUT) continue;
                if (waitResult != WasapiBindings.WAIT_OBJECT_0) break;

                // Get current padding (frames already queued in device)
                int hr = ComHelper.call(pAudioClient,
                        WasapiConstants.IAUDIOCLIENT_GETCURRENTPADDING,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                        pAudioClient, pPadding);
                if (hr < 0) continue;

                int padding = pPadding.get(JAVA_INT, 0);
                int framesAvailable = bufferFrameCount - padding;
                if (framesAvailable <= 0) continue;

                // Get buffer from render client
                hr = ComHelper.call(pRenderClient,
                        WasapiConstants.IAUDIORENDERCLIENT_GETBUFFER,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                        pRenderClient, framesAvailable, ppData);
                if (hr < 0) continue;

                MemorySegment pData = ppData.get(ADDRESS, 0);
                int dataBytes = framesAvailable * channels * Float.BYTES;
                MemorySegment outputBuffer = pData.reinterpret(dataBytes);

                // Fill via callback — may read multiple ring buffer blocks
                int blockSize = negotiatedFormat.blockSize();
                int blockBytes = blockSize * channels * Float.BYTES;
                int framesWritten = 0;

                while (framesWritten + blockSize <= framesAvailable) {
                    long offset = (long) framesWritten * channels * Float.BYTES;
                    MemorySegment slice = outputBuffer.asSlice(offset, blockBytes);
                    audioCallback.render(slice, blockSize, channels);
                    framesWritten += blockSize;
                }

                // Fill remainder with silence
                if (framesWritten < framesAvailable) {
                    long offset = (long) framesWritten * channels * Float.BYTES;
                    long remaining = (long) (framesAvailable - framesWritten) * channels * Float.BYTES;
                    outputBuffer.asSlice(offset, remaining).fill((byte) 0);
                }

                // Release the buffer
                ComHelper.call(pRenderClient,
                        WasapiConstants.IAUDIORENDERCLIENT_RELEASEBUFFER,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                        pRenderClient, framesAvailable, 0);

                nativeWriteCount++;
            }
        } catch (Throwable t) {
            if (active) {
                LOG.log(System.Logger.Level.ERROR,
                        "WASAPI render loop error: " + t.getMessage(), t);
            }
        } finally {
            try { WasapiBindings.CoUninitialize(); } catch (Throwable ignored) {}
        }
    }

    // -- Cleanup -------------------------------------------------------------

    private void releaseAll() {
        ComHelper.release(pRenderClient);
        pRenderClient = null;
        ComHelper.release(pAudioClient);
        pAudioClient = null;
        ComHelper.release(pDevice);
        pDevice = null;
        ComHelper.release(pEnumerator);
        pEnumerator = null;

        if (hEvent != null && !hEvent.equals(MemorySegment.NULL)) {
            try { WasapiBindings.CloseHandle(hEvent); } catch (Throwable ignored) {}
            hEvent = null;
        }

        try { WasapiBindings.CoUninitialize(); } catch (Throwable ignored) {}

        arena.close();
    }
}
