package org.dynamisengine.audio.backend.alsa;

import org.dynamisengine.audio.api.device.*;

import java.util.List;

/**
 * ALSA backend for Linux.
 *
 * Uses libasound.so.2 directly via Panama FFM — bypasses PulseAudio/PipeWire
 * for lowest latency. All native calls are downcall handles; no JNI.
 *
 * ALSA is a push-based API (snd_pcm_writei), unlike CoreAudio's pull model.
 * The {@link AlsaDeviceHandle} bridges this by running a dedicated write thread
 * that invokes the AudioCallback and submits to ALSA.
 *
 * PROVING STATUS: IMPLEMENTED — NOT PROVEN.
 * This backend was developed on macOS where libasound is not available.
 * It compiles and passes structural tests but has NOT been live-proven on Linux hardware.
 * A live proving run on native Linux hardware is required before this backend is trusted.
 *
 * Discovered at runtime via ServiceLoader. Only active on Linux.
 */
public final class AlsaBackend implements AudioBackend {

    private static final System.Logger LOG =
            System.getLogger(AlsaBackend.class.getName());

    private static final BackendCapabilities CAPABILITIES = new BackendCapabilities(
            false,  // supportsPullModel — ALSA is push-based (snd_pcm_writei)
            false,  // supportsExclusiveMode — ALSA always has exclusive PCM access
            false,  // supportsDeviceChange — no built-in hot-plug notification
            8,      // maxChannels — 7.1 surround (device dependent)
            new int[]{256, 512, 1024}
    );

    private volatile DeviceChangeListener deviceChangeListener;

    @Override
    public String name() { return "ALSA"; }

    @Override
    public int priority() { return 100; }

    @Override
    public BackendCapabilities capabilities() { return CAPABILITIES; }

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) return false;

        try {
            return AlsaBindings.loadAlsa() != null;
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG,
                    "ALSA probe failed: {0}", t.getMessage());
            return false;
        }
    }

    @Override
    public List<AudioDeviceInfo> enumerateDevices() {
        // ALSA device enumeration is complex (involves parsing /proc/asound/cards,
        // snd_device_name_hint, etc.). For Phase 1C, return a synthetic default
        // device that maps to the "default" ALSA PCM device.
        //
        // Phase 2: Implement full enumeration via snd_device_name_hint / snd_card_next.
        return List.of(
                new AudioDeviceInfo(
                        AlsaConstants.DEFAULT_DEVICE,
                        "ALSA Default Output",
                        2,
                        new int[]{44_100, 48_000, 96_000},
                        true,
                        false),
                new AudioDeviceInfo(
                        AlsaConstants.PLUGHW_DEVICE,
                        "ALSA Direct Hardware (plughw:0,0)",
                        2,
                        new int[]{44_100, 48_000, 96_000},
                        false,
                        false)
        );
    }

    @Override
    public AudioDeviceHandle openDevice(AudioDeviceInfo device,
                                         AudioFormat requestedFormat,
                                         AudioCallback audioCallback)
            throws AudioDeviceException {
        LOG.log(System.Logger.Level.INFO,
                "Opening ALSA device: {0} ({1}Hz, {2}ch, {3} frames)",
                device.displayName(), requestedFormat.sampleRate(),
                requestedFormat.channels(), requestedFormat.blockSize());

        return new AlsaDeviceHandle(requestedFormat, audioCallback,
                device.displayName(), device.id());
    }

    @Override
    public void setDeviceChangeListener(DeviceChangeListener listener) {
        this.deviceChangeListener = listener;
        // ALSA has no built-in device change notification.
        // Phase 2: Could poll /proc/asound/cards or use udev via inotify.
    }
}
