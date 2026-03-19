package org.dynamisengine.audio.backend.wasapi;

import org.dynamisengine.audio.api.device.*;

import java.util.List;

/**
 * WASAPI backend for Windows.
 *
 * Uses Windows Audio Session API via COM vtable calls through Panama FFM.
 * All COM interface methods are called via {@link ComHelper} downcall dispatch —
 * no JNI, no native compilation.
 *
 * SUPPORTED MODES:
 *   Shared mode (default): mixes with other apps, 10-30ms latency.
 *   Exclusive mode (opt-in via AudioFormat.exclusiveMode()): direct hardware, 3-10ms.
 *
 * Uses event-driven buffer delivery for lowest latency in both modes.
 *
 * PROVING STATUS: IMPLEMENTED — NOT PROVEN.
 * Developed on macOS where ole32.dll is not available. Compiles and passes
 * structural tests. Requires live Windows hardware proving.
 *
 * Discovered at runtime via ServiceLoader. Only active on Windows.
 */
public final class WasapiBackend implements AudioBackend {

    private static final System.Logger LOG =
            System.getLogger(WasapiBackend.class.getName());

    private static final BackendCapabilities CAPABILITIES = new BackendCapabilities(
            true,   // supportsPullModel — event-driven buffer delivery
            true,   // supportsExclusiveMode — WASAPI exclusive mode
            true,   // supportsDeviceChange — IMMNotificationClient (Phase 1E)
            8,      // maxChannels — 7.1 surround
            new int[]{256, 480, 512, 1024}  // 480 for 10ms at 48kHz
    );

    private volatile DeviceChangeListener deviceChangeListener;

    @Override
    public String name() { return "WASAPI"; }

    @Override
    public int priority() { return 100; }

    @Override
    public BackendCapabilities capabilities() { return CAPABILITIES; }

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return false;

        try {
            return WasapiBindings.loadOle32() != null
                    && WasapiBindings.loadKernel32() != null;
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG,
                    "WASAPI probe failed: {0}", t.getMessage());
            return false;
        }
    }

    @Override
    public List<AudioDeviceInfo> enumerateDevices() {
        // Full enumeration via IMMDeviceEnumerator::EnumAudioEndpoints requires
        // COM initialization. For Phase 1D, return a synthetic default device.
        //
        // Phase 2: Initialize COM, enumerate via EnumAudioEndpoints, read
        // device properties via IMMDevice::OpenPropertyStore.
        return List.of(new AudioDeviceInfo(
                "default",
                "Windows Default Audio Output",
                2,
                new int[]{44_100, 48_000, 96_000},
                true,
                true  // WASAPI supports exclusive mode
        ));
    }

    @Override
    public AudioDeviceHandle openDevice(AudioDeviceInfo device,
                                         AudioFormat requestedFormat,
                                         AudioCallback audioCallback)
            throws AudioDeviceException {
        LOG.log(System.Logger.Level.INFO,
                "Opening WASAPI device: {0} ({1}Hz, {2}ch, {3} frames, exclusive={4})",
                device.displayName(), requestedFormat.sampleRate(),
                requestedFormat.channels(), requestedFormat.blockSize(),
                requestedFormat.exclusiveMode());

        return new WasapiDeviceHandle(requestedFormat, audioCallback, device.displayName());
    }

    @Override
    public void setDeviceChangeListener(DeviceChangeListener listener) {
        this.deviceChangeListener = listener;
        // Phase 1E: Implement IMMNotificationClient COM interface via Panama upcall
        // to detect device changes (plug/unplug, default device change).
    }
}
