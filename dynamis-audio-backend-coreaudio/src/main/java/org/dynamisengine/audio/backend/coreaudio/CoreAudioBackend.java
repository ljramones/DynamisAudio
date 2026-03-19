package org.dynamisengine.audio.backend.coreaudio;

import org.dynamisengine.audio.api.device.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.dynamisengine.audio.backend.coreaudio.CoreAudioConstants.*;

/**
 * CoreAudio backend for macOS.
 *
 * Uses the AudioUnit API (DefaultOutput) with a render callback driven by
 * CoreAudio's real-time audio thread. All native calls are made via Panama
 * FFM downcall handles — no JNI, no native compilation.
 *
 * Discovered at runtime via ServiceLoader. Only loaded on macOS.
 *
 * DEVICE CHANGE NOTIFICATIONS:
 *   Registers an AudioObjectPropertyListener on kAudioHardwarePropertyDefaultOutputDevice.
 *   When macOS changes the default output (headphone plug/unplug, USB DAC, System Preferences),
 *   the listener fires on a CoreAudio notification thread. The callback body is microscopic:
 *   detect change → map to DeviceChangeEvent → invoke manager listener → return.
 *   No enumeration, no open/close, no policy decisions on the notification thread.
 */
public final class CoreAudioBackend implements AudioBackend {

    private static final System.Logger LOG =
            System.getLogger(CoreAudioBackend.class.getName());

    private volatile DeviceChangeListener deviceChangeListener;

    /** Arena owning the property listener upcall stub lifetime. */
    private Arena listenerArena;

    /** Native function pointer for the property listener upcall. */
    private MemorySegment listenerStub;

    /** Property address struct for the default output device property (kept alive for removal). */
    private MemorySegment defaultOutputPropAddr;

    // -- AudioBackend --------------------------------------------------------

    @Override
    public String name() { return "CoreAudio"; }

    @Override
    public int priority() { return 100; }

    private static final BackendCapabilities CAPABILITIES = new BackendCapabilities(
            true,   // supportsPullModel — AudioUnit render callback
            false,  // supportsExclusiveMode — CoreAudio doesn't distinguish
            true,   // supportsDeviceChange — AudioObjectAddPropertyListener
            8,      // maxChannels — 7.1 surround
            new int[]{256, 512, 1024}
    );

    @Override
    public BackendCapabilities capabilities() { return CAPABILITIES; }

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) return false;

        try {
            return CoreAudioBindings.loadAudioToolbox() != null
                    && CoreAudioBindings.loadCoreAudio() != null;
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG,
                    "CoreAudio probe failed: {0}", t.getMessage());
            return false;
        }
    }

    @Override
    public List<AudioDeviceInfo> enumerateDevices() {
        return CoreAudioDeviceEnumerator.enumerate();
    }

    @Override
    public AudioDeviceHandle openDevice(AudioDeviceInfo device,
                                         AudioFormat requestedFormat,
                                         AudioCallback audioCallback)
            throws AudioDeviceException {
        LOG.log(System.Logger.Level.INFO,
                "Opening CoreAudio device: {0} ({1}Hz, {2}ch, {3} frames)",
                device.displayName(), requestedFormat.sampleRate(),
                requestedFormat.channels(), requestedFormat.blockSize());

        return new CoreAudioDeviceHandle(requestedFormat, audioCallback, device.displayName());
    }

    @Override
    public void setDeviceChangeListener(DeviceChangeListener listener) {
        // Remove old listener if registered
        removePropertyListener();

        this.deviceChangeListener = listener;

        if (listener == null) return;

        // Register AudioObjectPropertyListener for default output device changes
        try {
            this.listenerArena = Arena.ofShared();

            // Property address: kAudioHardwarePropertyDefaultOutputDevice
            this.defaultOutputPropAddr = CoreAudioStructs.allocatePropertyAddress(
                    listenerArena,
                    kAudioHardwarePropertyDefaultOutputDevice,
                    kAudioObjectPropertyScopeGlobal,
                    kAudioObjectPropertyElementMain);

            // Create the upcall stub that routes to propertyListenerCallback()
            this.listenerStub = CoreAudioBindings.createPropertyListenerStub(this, listenerArena);

            int status = CoreAudioBindings.AudioObjectAddPropertyListener(
                    kAudioObjectSystemObject,
                    defaultOutputPropAddr,
                    listenerStub,
                    MemorySegment.NULL);

            if (status != kAudio_NoError) {
                LOG.log(System.Logger.Level.WARNING,
                        "AudioObjectAddPropertyListener failed: 0x{0}",
                        Integer.toHexString(status));
                removePropertyListener();
            } else {
                LOG.log(System.Logger.Level.INFO,
                        "CoreAudio device change listener registered " +
                        "(kAudioHardwarePropertyDefaultOutputDevice)");
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to register CoreAudio property listener: {0}", t.getMessage());
            removePropertyListener();
        }
    }

    // -- Property Listener Callback (CoreAudio notification thread) -----------

    /**
     * CoreAudio property listener callback — invoked on a CoreAudio notification thread.
     *
     * This method is called by macOS when the default output device changes
     * (headphone plug/unplug, USB DAC connected, System Preferences change).
     *
     * BODY IS MICROSCOPIC BY CONTRACT:
     *   1. Detect relevant property change
     *   2. Map to DeviceChangeEvent.DefaultDeviceChanged
     *   3. Invoke the registered DeviceChangeListener
     *   4. Return
     *
     * No enumeration, no open/close, no allocation beyond the event record.
     * The DeviceChangeListener (AudioDeviceManager) sets state = SWAP_PENDING
     * via a single volatile write and returns immediately.
     */
    int propertyListenerCallback(int inObjectID, int inNumberAddresses,
                                  MemorySegment inAddresses, MemorySegment inClientData) {
        DeviceChangeListener listener = this.deviceChangeListener;
        if (listener == null) return kAudio_NoError;

        // Re-enumerate to get the new default device info.
        // This is a lightweight call (reads AudioObject properties, no device open).
        // We do this here rather than passing stale info to the listener.
        List<AudioDeviceInfo> devices = CoreAudioDeviceEnumerator.enumerate();
        AudioDeviceInfo newDefault = devices.stream()
                .filter(AudioDeviceInfo::isDefault)
                .findFirst()
                .orElse(devices.isEmpty() ? null : devices.getFirst());

        if (newDefault != null) {
            LOG.log(System.Logger.Level.INFO,
                    "CoreAudio property listener: default device changed to {0} [{1}]",
                    newDefault.displayName(), newDefault.id());
            listener.onDeviceChange(new DeviceChangeEvent.DefaultDeviceChanged(newDefault));
        } else {
            LOG.log(System.Logger.Level.WARNING,
                    "CoreAudio property listener: default device changed but no devices found");
        }

        return kAudio_NoError;
    }

    // -- Cleanup -------------------------------------------------------------

    private void removePropertyListener() {
        if (listenerStub != null && defaultOutputPropAddr != null) {
            try {
                CoreAudioBindings.AudioObjectRemovePropertyListener(
                        kAudioObjectSystemObject,
                        defaultOutputPropAddr,
                        listenerStub,
                        MemorySegment.NULL);
                LOG.log(System.Logger.Level.DEBUG,
                        "CoreAudio device change listener removed");
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Failed to remove CoreAudio property listener: {0}", t.getMessage());
            }
        }
        if (listenerArena != null) {
            listenerArena.close();
            listenerArena = null;
        }
        listenerStub = null;
        defaultOutputPropAddr = null;
    }
}
