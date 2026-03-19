# Panama Native Audio Backend Implementation Plan

Date: 2026-03-17
Status: Phase 1A-1E COMPLETE. Phase 1F (native hardware proving) PENDING.
Scope: Replace PanamaAudioDevice monolith with pluggable, pro-grade native audio backends

---

# 1. Vision

Deliver a **professional-grade, zero-GC, pluggable native audio output system** for the Dynamis Engine that rivals commercial engines (FMOD, Wwise, Unreal Audio) in latency, reliability, and platform coverage.

The system must:

- Achieve **sub-10ms round-trip latency** on all platforms (target: 5-7ms)
- **Zero heap allocation** on the DSP render thread — enforced by existing CI harness
- Be **fully pluggable** via JPMS ServiceLoader — ship only the backend for your target OS
- Support **device hot-swap** (headphones unplugged, USB DAC connected) without audio glitches
- Support both **push and pull** models to match each platform's native paradigm
- Use **Panama FFM exclusively** — no JNI, no native compilation, pure Java distribution

---

# 2. Architectural Overview

## 2.1 Current State (Phase 0)

```
SoftwareMixer.renderBlock()
    → audioDevice.write(masterOutputBuffer, blockSize, channels)
        → PanamaAudioDevice.write()     ← MONOLITHIC, switch on platform
            → writeCoreAudio()           ← STUB (no-op)
            → writeWasapi()              ← STUB (no-op)
            → writeAlsa()                ← STUB (no-op)
```

Problems:
- Single class with all platform logic — untestable, violates SRP
- Push-only `write()` contract — fights CoreAudio's pull model
- No device enumeration, no hot-swap, no format negotiation handshake
- No exclusive mode support (WASAPI)
- `AudioDeviceFactory` hardcodes selection logic

## 2.2 Target State (Phase 1)

```
dynamis-audio-api
    └── AudioBackend (SPI interface)
    └── AudioDeviceInfo (device descriptor record)
    └── AudioFormat (negotiated format record)
    └── AudioCallback (pull-model render callback)

dynamis-audio-backend-coreaudio     ← separate Maven module
    └── CoreAudioBackend implements AudioBackend
    └── module-info.java: provides AudioBackend with CoreAudioBackend

dynamis-audio-backend-wasapi        ← separate Maven module
    └── WasapiBackend implements AudioBackend
    └── module-info.java: provides AudioBackend with WasapiBackend

dynamis-audio-backend-alsa          ← separate Maven module
    └── AlsaBackend implements AudioBackend
    └── module-info.java: provides AudioBackend with AlsaBackend

dynamis-audio-dsp
    └── AudioDeviceManager           ← ServiceLoader discovery + lifecycle
    └── SpscRingBuffer               ← MemorySegment-backed, wait-free
    └── SoftwareMixer                ← adapted for pull-model callback
```

Discovery at runtime:
```java
ServiceLoader<AudioBackend> backends = ServiceLoader.load(AudioBackend.class);
AudioBackend backend = backends.findFirst().orElse(new NullAudioBackend());
```

Only the backend module on the module path gets loaded. Ship `dynamis-audio-backend-coreaudio` for macOS builds, `dynamis-audio-backend-wasapi` for Windows, etc. **Zero platform-specific code in the core audio engine.**

---

# 3. SPI Contract Design

## 3.1 AudioBackend — The Core SPI

```java
package org.dynamisengine.audio.api.device;

/**
 * Service Provider Interface for platform audio backends.
 *
 * Discovered via ServiceLoader. Each platform provides exactly one implementation.
 * The AudioDeviceManager selects the highest-priority available backend.
 *
 * LIFETIME: constructed by ServiceLoader → probe() → enumerateDevices() →
 *           openDevice() → [render loop via callback] → closeDevice() → GC
 */
public interface AudioBackend {

    /** Human-readable backend name (e.g., "CoreAudio", "WASAPI", "ALSA"). */
    String name();

    /**
     * Priority for backend selection when multiple are available.
     * Higher = preferred. Default backends: CoreAudio=100, WASAPI=100, ALSA=100.
     * Users can register custom backends at higher priority (e.g., JACK=200).
     */
    int priority();

    /**
     * Fast probe: is this backend usable on the current platform?
     * Must not open devices or allocate significant resources.
     * Called once at engine startup for each discovered backend.
     */
    boolean isAvailable();

    /**
     * Enumerate available output devices.
     * Returns at least one entry (the system default) if isAvailable() is true.
     * Called from engine lifecycle thread. May allocate.
     */
    List<AudioDeviceInfo> enumerateDevices();

    /**
     * Open a device for output. Returns a handle for the active audio session.
     *
     * The backend MUST call audioCallback.render() from its native audio thread
     * whenever it needs more samples. The callback fills the provided MemorySegment
     * with interleaved float PCM.
     *
     * @param device        which device to open (from enumerateDevices())
     * @param requestedFormat  desired format (sample rate, channels, block size)
     * @param audioCallback    pull-model render callback — called from native thread
     * @return handle to the open device session
     * @throws AudioDeviceException if the device cannot be opened
     */
    AudioDeviceHandle openDevice(AudioDeviceInfo device,
                                  AudioFormat requestedFormat,
                                  AudioCallback audioCallback)
            throws AudioDeviceException;

    /**
     * Register a listener for device topology changes (hot-plug/unplug).
     * Called from engine lifecycle thread. Listener invoked from platform thread.
     */
    void setDeviceChangeListener(DeviceChangeListener listener);
}
```

## 3.2 AudioDeviceHandle — Active Session

```java
package org.dynamisengine.audio.api.device;

/**
 * Handle to an active audio output session.
 * Returned by AudioBackend.openDevice(). Thread-safe.
 *
 * The native audio thread calls AudioCallback.render() autonomously.
 * The engine controls start/stop/close via this handle.
 */
public interface AudioDeviceHandle {

    /** Start audio output. Callback begins firing. */
    void start();

    /** Stop audio output. Callback stops firing. Resumable via start(). */
    void stop();

    /** Close the session and release all native resources. Terminal. */
    void close();

    /** The format actually negotiated with the hardware. */
    AudioFormat negotiatedFormat();

    /** Output latency in sample frames as reported by the driver. */
    int outputLatencyFrames();

    /** Output latency in milliseconds (convenience). */
    default float outputLatencyMs() {
        AudioFormat fmt = negotiatedFormat();
        return (float) outputLatencyFrames() / fmt.sampleRate() * 1000f;
    }

    /** True if the session is active (started and not closed). */
    boolean isActive();

    /** Backend-reported device description string. */
    String deviceDescription();
}
```

## 3.3 AudioCallback — Pull-Model Render

```java
package org.dynamisengine.audio.api.device;

import java.lang.foreign.MemorySegment;

/**
 * Callback interface invoked by the native audio thread when it needs samples.
 *
 * This is the bridge between the platform audio API and the DSP engine.
 * The implementation MUST be wait-free — no locks, no allocation, no I/O.
 *
 * Called on the platform's real-time audio thread (CoreAudio IOProc,
 * WASAPI event thread, ALSA period callback). Blocking here causes audio glitches.
 */
@FunctionalInterface
public interface AudioCallback {

    /**
     * Fill the output buffer with rendered audio.
     *
     * @param outputBuffer  off-heap MemorySegment to fill with interleaved float32 PCM.
     *                      Size: frameCount * channels * Float.BYTES bytes.
     *                      The segment is only valid for the duration of this call.
     * @param frameCount    number of sample frames requested
     * @param channels      number of interleaved channels
     */
    void render(MemorySegment outputBuffer, int frameCount, int channels);
}
```

## 3.4 Supporting Records

```java
/** Immutable descriptor for a discovered audio device. */
public record AudioDeviceInfo(
    String id,              // platform-unique device ID
    String displayName,     // human-readable name ("MacBook Pro Speakers")
    int maxChannels,        // max supported output channels
    int[] supportedSampleRates,  // e.g., {44100, 48000, 96000}
    boolean isDefault,      // true if this is the system default
    boolean supportsExclusive    // true if exclusive/low-latency mode available
) {}

/** Requested or negotiated audio format. */
public record AudioFormat(
    int sampleRate,         // Hz (48000 standard)
    int channels,           // 2 = stereo, 6 = 5.1, 8 = 7.1
    int blockSize,          // frames per callback (256 = ~5.33ms at 48kHz)
    boolean exclusiveMode   // request exclusive access (WASAPI only, ignored elsewhere)
) {
    public static AudioFormat stereo48k(int blockSize) {
        return new AudioFormat(48_000, 2, blockSize, false);
    }
}

/** Device topology change notification. */
public sealed interface DeviceChangeEvent {
    record DeviceAdded(AudioDeviceInfo device) implements DeviceChangeEvent {}
    record DeviceRemoved(String deviceId) implements DeviceChangeEvent {}
    record DefaultDeviceChanged(AudioDeviceInfo newDefault) implements DeviceChangeEvent {}
}

@FunctionalInterface
public interface DeviceChangeListener {
    void onDeviceChange(DeviceChangeEvent event);
}
```

---

# 4. The SPSC Ring Buffer — Heart of Zero-GC Audio

## 4.1 Why a Ring Buffer?

The pull-model callback fires on the **platform's real-time audio thread** (not a Java thread we control). The DSP engine runs `renderBlock()` on its own worker thread. These two threads must exchange audio data **without locks, without allocation, and without blocking.**

The existing `AcousticEventQueueImpl` uses exactly this pattern (VarHandle acquire/release SPSC). We extend it to float PCM sample transfer using off-heap `MemorySegment`.

## 4.2 Design

```java
package org.dynamisengine.audio.dsp.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Wait-free single-producer single-consumer ring buffer backed by off-heap memory.
 *
 * Producer: DSP render worker thread (writes rendered blocks)
 * Consumer: Platform audio callback thread (reads blocks to submit to hardware)
 *
 * MEMORY LAYOUT:
 *   Off-heap MemorySegment of size: slotCount * blockBytes bytes.
 *   Each slot holds one DSP block of interleaved float32 PCM.
 *   slotCount MUST be a power of two for bitmask indexing.
 *
 * ORDERING:
 *   VarHandle acquire/release on writePos/readPos indices.
 *   No CAS. No locks. No retry loops. Both sides make progress in O(1).
 *
 * LATENCY:
 *   With 3 slots: worst-case 3 blocks of latency (~16ms at 256 frames / 48kHz).
 *   With 2 slots: 2 blocks (~10.6ms). This is the minimum for glitch-free operation.
 *   Recommended: 4 slots (power of two, ~21ms worst case, handles scheduling jitter).
 */
public final class SpscAudioRingBuffer {

    private final MemorySegment buffer;       // off-heap: slotCount * blockBytes
    private final Arena arena;                // owns buffer lifetime
    private final int slotCount;              // power of two
    private final int mask;                   // slotCount - 1
    private final long blockBytes;            // blockSize * channels * Float.BYTES
    private final int blockSize;              // frames per block
    private final int channels;

    // VarHandle-managed cursors
    private static final VarHandle WRITE_POS_VH;
    private static final VarHandle READ_POS_VH;

    static {
        try {
            var lookup = MethodHandles.lookup();
            WRITE_POS_VH = lookup.findVarHandle(SpscAudioRingBuffer.class, "writePos", long.class);
            READ_POS_VH = lookup.findVarHandle(SpscAudioRingBuffer.class, "readPos", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long writePos = 0L;      // producer cursor
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long readPos = 0L;       // consumer cursor

    // Telemetry (non-critical, relaxed reads OK)
    private volatile long underruns = 0L;     // consumer found ring empty
    private volatile long overruns = 0L;      // producer found ring full

    public SpscAudioRingBuffer(int slotCount, int blockSize, int channels) { ... }

    /**
     * Producer: write one block of interleaved float PCM into the ring.
     * Called by DSP render worker. Returns false if ring is full (overrun).
     * ZERO ALLOCATION.
     */
    public boolean write(float[] block, int frameCount, int channels) {
        long wp = (long) WRITE_POS_VH.getAcquire(this);
        long rp = (long) READ_POS_VH.getAcquire(this);
        if (wp - rp >= slotCount) {
            overruns++;
            return false;  // ring full — DSP is ahead of hardware
        }
        int slot = (int) (wp & mask);
        long offset = slot * blockBytes;
        // Bulk copy float[] → off-heap via MemorySegment.copyFrom()
        // JDK 25 intrinsifies this to a single memcpy — zero element-wise loop
        MemorySegment heapView = MemorySegment.ofArray(block);
        long byteCount = (long) frameCount * channels * Float.BYTES;
        buffer.asSlice(offset, byteCount).copyFrom(heapView.asSlice(0, byteCount));
        WRITE_POS_VH.setRelease(this, wp + 1);
        return true;
    }

    /**
     * Consumer: read one block from the ring into the provided off-heap segment.
     * Called by platform audio callback. Returns false if ring is empty (underrun).
     * ZERO ALLOCATION. Wait-free.
     */
    public boolean read(MemorySegment dest) {
        long rp = (long) READ_POS_VH.getAcquire(this);
        long wp = (long) WRITE_POS_VH.getAcquire(this);
        if (rp >= wp) {
            underruns++;
            return false;  // ring empty — hardware is ahead of DSP
        }
        int slot = (int) (rp & mask);
        long offset = slot * blockBytes;
        dest.copyFrom(buffer.asSlice(offset, blockBytes));
        READ_POS_VH.setRelease(this, rp + 1);
        return true;
    }

    /** Available blocks ready to read. */
    public int available() {
        long wp = (long) WRITE_POS_VH.getAcquire(this);
        long rp = (long) READ_POS_VH.getAcquire(this);
        return (int) (wp - rp);
    }

    public long underruns() { return underruns; }
    public long overruns() { return overruns; }

    /** Release off-heap memory. Call from engine shutdown only. */
    public void close() { arena.close(); }
}
```

## 4.3 Integration with SoftwareMixer

The pull model inverts the control flow. Instead of `SoftwareMixer.renderBlock()` pushing to `AudioDevice.write()`, the platform callback pulls from the ring buffer, and a separate DSP worker keeps the ring fed:

```
DSP Worker Thread (Java, Loom virtual thread or platform thread):
    loop {
        mixer.renderBlock()          // renders into masterOutputBuffer
        ringBuffer.write(masterOutputBuffer, blockSize, channels)
        // pace: sleep/park until ring has free slots
    }

Platform Audio Callback (native real-time thread):
    AudioCallback.render(outputSegment, frameCount, channels) {
        if (!ringBuffer.read(outputSegment)) {
            // underrun — fill with silence
            outputSegment.fill((byte) 0);
        }
    }
```

This decoupling means:
- The DSP worker thread can have brief scheduling delays without glitching (ring absorbs jitter)
- The platform callback is trivially simple — just a ring buffer read
- The ring buffer is the **only** synchronization point between Java and native

---

# 5. AudioDeviceManager — Lifecycle Orchestrator

```java
package org.dynamisengine.audio.dsp.device;

/**
 * Discovers, selects, and manages the active audio backend and device.
 *
 * Lifecycle:
 *   1. ServiceLoader discovery of AudioBackend implementations
 *   2. Probe each backend (isAvailable())
 *   3. Select highest-priority available backend
 *   4. Enumerate devices, select default (or user-configured device)
 *   5. Open device with AudioCallback wired to SpscAudioRingBuffer
 *   6. Start DSP worker thread (feeds ring buffer from SoftwareMixer)
 *   7. Handle hot-swap events (close old device, open new default)
 *
 * THREAD MODEL:
 *   - Construction + open/close: engine lifecycle thread
 *   - DSP worker: dedicated platform thread (or virtual thread in test mode)
 *   - Device change listener: platform notification thread → posts to event queue
 *   - Audio callback: platform real-time thread (NOT a Java thread)
 */
public final class AudioDeviceManager {

    private AudioBackend activeBackend;
    private AudioDeviceHandle activeDevice;
    private SpscAudioRingBuffer ringBuffer;
    private Thread dspWorker;
    private volatile boolean running;

    // ... discovery, open, start, stop, close, hot-swap methods ...
}
```

---

# 6. Platform Backend Designs

## 6.1 CoreAudio Backend (macOS) — PRIORITY 1

### Why AudioUnit (not AudioQueue)

AudioQueue Services is Apple's push-based API — simpler but adds an extra buffer copy and ~10-20ms latency. For pro-grade audio:

- **AudioUnit** (Audio HAL) gives us a **render callback on the real-time thread**
- Direct access to the hardware buffer — potential for zero-copy
- Industry standard: Logic Pro, Ableton, Pro Tools all use AudioUnit
- Supports **aggregate devices** (multiple interfaces combined)

### Panama Binding Strategy

All calls through `java.lang.foreign.Linker.nativeLinker().downcallHandle()`. Struct layouts via `MemoryLayout.structLayout()`. No jextract — hand-written bindings for the ~15 functions we need.

### Native Functions Required

```
AudioToolbox.framework / CoreAudio.framework:

Initialization:
  AudioComponentFindNext(NULL, &desc)           → find kAudioUnitType_Output
  AudioComponentInstanceNew(component, &unit)    → instantiate output unit
  AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, ...)
  AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, ...)
  AudioUnitInitialize(unit)
  AudioOutputUnitStart(unit)

Shutdown:
  AudioOutputUnitStop(unit)
  AudioUnitUninitialize(unit)
  AudioComponentInstanceDispose(unit)

Device Enumeration:
  AudioObjectGetPropertyDataSize / AudioObjectGetPropertyData
  with kAudioHardwarePropertyDevices, kAudioDevicePropertyDeviceName, etc.

Hot-Swap:
  AudioObjectAddPropertyListener(kAudioObjectSystemObject,
      kAudioHardwarePropertyDefaultOutputDevice, callback, userData)
```

### Struct Layouts (Off-Heap)

```java
// AudioStreamBasicDescription (ASBD) - 40 bytes
MemoryLayout ASBD = MemoryLayout.structLayout(
    ValueLayout.JAVA_DOUBLE.withName("mSampleRate"),           // 8
    ValueLayout.JAVA_INT.withName("mFormatID"),                // 4 (kAudioFormatLinearPCM)
    ValueLayout.JAVA_INT.withName("mFormatFlags"),             // 4
    ValueLayout.JAVA_INT.withName("mBytesPerPacket"),          // 4
    ValueLayout.JAVA_INT.withName("mFramesPerPacket"),         // 4 (1 for PCM)
    ValueLayout.JAVA_INT.withName("mBytesPerFrame"),           // 4
    ValueLayout.JAVA_INT.withName("mChannelsPerFrame"),        // 4
    ValueLayout.JAVA_INT.withName("mBitsPerChannel"),          // 4 (32 for float)
    ValueLayout.JAVA_INT.withName("mReserved")                 // 4
);

// AURenderCallbackStruct - 16 bytes
MemoryLayout CALLBACK_STRUCT = MemoryLayout.structLayout(
    ValueLayout.ADDRESS.withName("inputProc"),                  // 8 (function pointer)
    ValueLayout.ADDRESS.withName("inputProcRefCon")             // 8 (user data)
);

// AudioComponentDescription - 20 bytes
MemoryLayout COMPONENT_DESC = MemoryLayout.structLayout(
    ValueLayout.JAVA_INT.withName("componentType"),             // kAudioUnitType_Output
    ValueLayout.JAVA_INT.withName("componentSubType"),          // kAudioUnitSubType_DefaultOutput
    ValueLayout.JAVA_INT.withName("componentManufacturer"),     // kAudioUnitManufacturer_Apple
    ValueLayout.JAVA_INT.withName("componentFlags"),            // 0
    ValueLayout.JAVA_INT.withName("componentFlagsMask")         // 0
);
```

### Render Callback via Panama Upcall

This is the critical piece. CoreAudio calls a C function pointer on its real-time thread. Panama provides `Linker.nativeLinker().upcallStub()` to create a native function pointer that calls back into Java:

```java
// The Java method that will be called from the CoreAudio real-time thread
private int renderCallback(MemorySegment userData, MemorySegment actionFlags,
                           MemorySegment timeStamp, int busNumber,
                           int frameCount, MemorySegment bufferList) {
    // Read from ring buffer into the AudioBufferList's mData
    MemorySegment mData = extractMDataFromBufferList(bufferList);
    if (!ringBuffer.read(mData)) {
        mData.fill((byte) 0);  // underrun: silence
    }
    return 0;  // noErr
}

// Create the native function pointer
FunctionDescriptor callbackDesc = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,       // return: OSStatus
    ValueLayout.ADDRESS,        // inRefCon (user data)
    ValueLayout.ADDRESS,        // ioActionFlags
    ValueLayout.ADDRESS,        // inTimeStamp
    ValueLayout.JAVA_INT,       // inBusNumber
    ValueLayout.JAVA_INT,       // inNumberFrames
    ValueLayout.ADDRESS          // ioData (AudioBufferList*)
);
MethodHandle callback = MethodHandles.lookup()
    .findVirtual(CoreAudioBackend.class, "renderCallback", ...);
MemorySegment callbackPtr = Linker.nativeLinker()
    .upcallStub(callback.bindTo(this), callbackDesc, arena);
```

### Latency Target

- AudioUnit default buffer: 512 frames = 10.67ms at 48kHz
- With `kAudioDevicePropertyBufferFrameSize` set to 256: **5.33ms**
- Total round-trip with ring buffer (2 slots): **~10.66ms** (meets target)

---

## 6.2 WASAPI Backend (Windows) — PRIORITY 3

### Shared vs Exclusive Mode

| Mode | Latency | Sharing | Use Case |
|------|---------|---------|----------|
| Shared | 10-30ms | Yes (Windows mixer) | Default, safe, all apps hear audio |
| Exclusive | 3-10ms | No (direct hardware) | VR, competitive FPS, pro audio |

We implement both. Shared mode is default. Exclusive mode is opt-in via `AudioFormat.exclusiveMode()`.

### COM via Panama — The Hard Part

WASAPI is a COM API. COM vtable calls from Panama require:

1. Load the vtable pointer from the interface pointer
2. Load the function pointer from the vtable at the correct offset
3. Call via `downcallHandle` with the interface pointer as first argument (`this` in COM)

```java
// COM vtable call pattern:
// pInterface → vtable → vtable[methodIndex] → function pointer
MemorySegment vtable = pInterface.get(ValueLayout.ADDRESS, 0);
MemorySegment fnPtr = vtable.get(ValueLayout.ADDRESS, methodIndex * ADDRESS.byteSize());
MethodHandle method = Linker.nativeLinker().downcallHandle(fnPtr, descriptor);
int hr = (int) method.invoke(pInterface, ...args...);
if (hr < 0) throw new AudioDeviceException("COM call failed: 0x" + Integer.toHexString(hr));
```

### WASAPI Event-Driven Mode (Pull-Based)

WASAPI exclusive mode supports event-driven buffer delivery:

```
open():
  CoInitializeEx(NULL, COINIT_MULTITHREADED)
  CoCreateInstance(CLSID_MMDeviceEnumerator) → IMMDeviceEnumerator
  enumerator.GetDefaultAudioEndpoint(eRender, eConsole) → IMMDevice
  device.Activate(IID_IAudioClient) → IAudioClient
  client.Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE,
      AUDCLNT_STREAMFLAGS_EVENTCALLBACK, duration, duration, &wfx, NULL)
  client.SetEventHandle(hEvent)         // ← event-driven: OS signals when buffer ready
  client.GetService(IID_IAudioRenderClient) → IAudioRenderClient
  client.Start()

render loop (Java thread waiting on event):
  WaitForSingleObject(hEvent, timeout)   // OS signals "need more samples"
  renderClient.GetBuffer(frameCount) → native buffer ptr
  ringBuffer.read(nativeBuffer)          // zero-copy if buffer size matches
  renderClient.ReleaseBuffer(frameCount, 0)

close():
  client.Stop()
  Release all COM interfaces
  CoUninitialize()
```

### Device Enumeration & Hot-Swap

```
IMMDeviceEnumerator::EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)
IMMDeviceEnumerator::RegisterEndpointNotificationCallback(listener)
```

The notification callback fires on a COM thread — we post a `DeviceChangeEvent` to the engine's event queue (zero allocation, same ring buffer pattern).

---

## 6.3 ALSA Backend (Linux) — PRIORITY 2

### Why ALSA Direct (Not PulseAudio/PipeWire)

- PulseAudio adds 20-50ms latency and another mixing stage
- PipeWire is better but API stability varies across distros
- ALSA `snd_pcm_writei` with proper period/buffer sizing gives **3-10ms latency**
- Games that need low latency should bypass the sound server

We can add a PipeWire backend later as a separate module at higher priority.

### Panama Bindings

ALSA is a clean C API — straightforward Panama bindings:

```
libasound.so.2:

  snd_pcm_open(&handle, "default", SND_PCM_STREAM_PLAYBACK, 0)
  snd_pcm_hw_params_malloc(&params)
  snd_pcm_hw_params_any(handle, params)
  snd_pcm_hw_params_set_access(handle, params, SND_PCM_ACCESS_RW_INTERLEAVED)
  snd_pcm_hw_params_set_format(handle, params, SND_PCM_FORMAT_FLOAT_LE)
  snd_pcm_hw_params_set_channels(handle, params, 2)
  snd_pcm_hw_params_set_rate_near(handle, params, &rate, NULL)
  snd_pcm_hw_params_set_period_size_near(handle, params, &period, NULL)
  snd_pcm_hw_params_set_buffer_size_near(handle, params, &bufferSize)
  snd_pcm_hw_params(handle, params)
  snd_pcm_hw_params_free(params)
  snd_pcm_prepare(handle)

Write loop:
  snd_pcm_writei(handle, ringBuffer.readAddress(), frameCount)
  On -EPIPE: snd_pcm_prepare(handle) + retry    // underrun recovery

Shutdown:
  snd_pcm_drain(handle)     // play remaining samples
  snd_pcm_close(handle)

Latency query:
  snd_pcm_delay(handle, &frames)
```

### ALSA Callback Mode (snd_async_add_pcm_handler)

ALSA supports async notification when the hardware needs more samples. This gives us pull-model parity with CoreAudio:

```
snd_async_add_pcm_handler(&handler, handle, callback, userData)
```

The callback fires when ALSA needs samples — we read from the ring buffer, same pattern as CoreAudio.

If async mode is unavailable (some ALSA configurations), fall back to a Java thread doing blocking `snd_pcm_writei` in a loop with `snd_pcm_wait` for pacing.

---

# 7. SoftwareMixer Adaptation

## 7.1 Changes Required

The mixer's `renderBlock()` currently ends with `audioDevice.write()`. In the new architecture, the mixer doesn't know about the device at all — it just renders into a buffer, and the `AudioDeviceManager` feeds that buffer into the ring.

```java
// BEFORE (push):
public void renderBlock() {
    // ... render ...
    audioDevice.write(masterOutputBuffer, blockSize, channels);  // ← remove
}

// AFTER (pull-compatible):
public void renderBlock() {
    // ... render ...
    // masterOutputBuffer is now read by AudioDeviceManager
}

public float[] getMasterOutputBuffer() {
    return masterOutputBuffer;
}
```

The `AudioDeviceManager` drives the render loop:

```java
// In AudioDeviceManager's DSP worker thread:
while (running) {
    mixer.renderBlock();
    boolean written = ringBuffer.write(mixer.getMasterOutputBuffer(), blockSize, channels);
    if (!written) {
        // Ring full — DSP is too far ahead. Pace.
        // In practice this rarely happens with 4 slots.
        Thread.onSpinWait();
    }
}
```

## 7.2 Backward Compatibility

The existing push-based `AudioDevice` interface and `NullAudioDevice` remain for tests. `AudioDeviceManager` can operate in push mode (calling `AudioDevice.write()` directly) when the backend doesn't support pull. This is a compile-time choice per backend.

```java
// AudioDeviceManager internal:
if (activeBackend.supportsPullModel()) {
    // Use ring buffer + callback
    startPullMode(mixer);
} else {
    // Legacy push mode (NullAudioDevice, or future custom backends)
    startPushMode(mixer, legacyDevice);
}
```

---

# 8. Device Hot-Swap

When the user plugs in headphones or disconnects a USB DAC:

1. Platform fires `DeviceChangeEvent.DefaultDeviceChanged`
2. `AudioDeviceManager.onDeviceChange()` is called on a platform thread
3. Manager enqueues a hot-swap request (atomic flag, not allocation)
4. DSP worker checks the flag at the top of each render loop iteration
5. If set:
   a. Stop old device (`activeDevice.stop()`)
   b. Drain ring buffer
   c. Close old device (`activeDevice.close()`)
   d. Re-enumerate devices
   e. Open new default device with same format
   f. Pre-fill ring buffer with 1-2 blocks of silence
   g. Start new device
   h. Resume render loop

**Glitch budget:** The hot-swap should complete within 50-100ms. Users expect a brief audio dropout when switching devices — this is normal behavior even in AAA engines.

---

# 9. Module Structure & Maven Layout

## 9.1 New Modules

```
DynamisAudio/
├── dynamis-audio-api/                    ← ADD: AudioBackend SPI, AudioCallback, etc.
├── dynamis-audio-core/                   (unchanged)
├── dynamis-audio-dsp/                    ← MODIFY: SpscAudioRingBuffer, AudioDeviceManager
├── dynamis-audio-backend-coreaudio/      ← NEW MODULE
│   ├── pom.xml
│   ├── src/main/java/
│   │   ├── module-info.java
│   │   └── org/dynamisengine/audio/backend/coreaudio/
│   │       ├── CoreAudioBackend.java
│   │       ├── CoreAudioDeviceHandle.java
│   │       ├── CoreAudioBindings.java     (Panama downcall/upcall handles)
│   │       ├── CoreAudioStructs.java      (ASBD, AudioComponentDescription layouts)
│   │       └── CoreAudioDeviceEnumerator.java
│   └── src/test/java/
│       └── org/dynamisengine/audio/backend/coreaudio/
│           └── CoreAudioBackendTest.java
├── dynamis-audio-backend-wasapi/         ← NEW MODULE
│   ├── pom.xml
│   └── src/main/java/
│       ├── module-info.java
│       └── org/dynamisengine/audio/backend/wasapi/
│           ├── WasapiBackend.java
│           ├── WasapiDeviceHandle.java
│           ├── WasapiBindings.java
│           ├── WasapiComHelper.java       (COM vtable call utilities)
│           ├── WasapiStructs.java         (WAVEFORMATEX, etc.)
│           └── WasapiDeviceEnumerator.java
├── dynamis-audio-backend-alsa/           ← NEW MODULE
│   ├── pom.xml
│   └── src/main/java/
│       ├── module-info.java
│       └── org/dynamisengine/audio/backend/alsa/
│           ├── AlsaBackend.java
│           ├── AlsaDeviceHandle.java
│           ├── AlsaBindings.java
│           └── AlsaDeviceEnumerator.java
├── dynamis-audio-designer/               (unchanged)
├── dynamis-audio-music/                  (unchanged)
├── dynamis-audio-procedural/             (unchanged)
├── dynamis-audio-simulation/             (unchanged)
└── dynamis-audio-test-harness/           ← MODIFY: add backend integration tests
```

## 9.2 Module Descriptors

```java
// dynamis-audio-api (additions)
module org.dynamisengine.audio.api {
    exports org.dynamisengine.audio.api;
    exports org.dynamisengine.audio.api.device;  // ← NEW
    uses org.dynamisengine.audio.api.device.AudioBackend;  // ← SPI declaration
}

// dynamis-audio-backend-coreaudio
module org.dynamisengine.audio.backend.coreaudio {
    requires org.dynamisengine.audio.api;
    provides org.dynamisengine.audio.api.device.AudioBackend
        with org.dynamisengine.audio.backend.coreaudio.CoreAudioBackend;
}

// dynamis-audio-backend-wasapi
module org.dynamisengine.audio.backend.wasapi {
    requires org.dynamisengine.audio.api;
    provides org.dynamisengine.audio.api.device.AudioBackend
        with org.dynamisengine.audio.backend.wasapi.WasapiBackend;
}

// dynamis-audio-backend-alsa
module org.dynamisengine.audio.backend.alsa {
    requires org.dynamisengine.audio.api;
    provides org.dynamisengine.audio.api.device.AudioBackend
        with org.dynamisengine.audio.backend.alsa.AlsaBackend;
}

// dynamis-audio-dsp (additions)
module org.dynamisengine.audio.dsp {
    requires transitive org.dynamisengine.audio.api;
    requires org.dynamisengine.audio.core;
    requires org.dynamisengine.audio.designer;
    requires org.dynamisengine.audio.simulation;
    exports org.dynamisengine.audio.dsp;
    exports org.dynamisengine.audio.dsp.device;
    uses org.dynamisengine.audio.api.device.AudioBackend;  // ← runtime discovery
}
```

## 9.3 Maven Dependencies

Each backend module depends only on `dynamis-audio-api` — no dependency on `dsp`, `core`, or other backends. This is critical for modularity.

```xml
<!-- dynamis-audio-backend-coreaudio/pom.xml -->
<dependencies>
    <dependency>
        <groupId>org.dynamisengine.audio</groupId>
        <artifactId>dynamis-audio-api</artifactId>
    </dependency>
</dependencies>
```

---

# 10. Testing Strategy

## 10.1 Unit Tests (All Platforms, CI)

| Component | Test Approach |
|-----------|--------------|
| `SpscAudioRingBuffer` | Write/read correctness, underrun/overrun counters, power-of-two enforcement, concurrent producer/consumer stress test |
| `AudioDeviceManager` | Mock `AudioBackend`, verify ServiceLoader selection, verify hot-swap sequence |
| `AudioCallback` adapter | Verify ring buffer read → segment write, verify silence on underrun |
| SPI records | `AudioDeviceInfo`, `AudioFormat`, `DeviceChangeEvent` construction and equality |

## 10.2 Platform Integration Tests (Platform-Specific)

These tests run only on the target OS (controlled by Maven profile or `@EnabledOnOs`):

| Backend | Test |
|---------|------|
| CoreAudio | `isAvailable()` returns true on macOS, enumerate returns >= 1 device, open/start/write-silence/stop/close cycle completes without error |
| WASAPI | Same pattern, COM initialization/teardown verified |
| ALSA | Same pattern, `libasound.so.2` found and loaded |

## 10.3 Latency Measurement Harness

A manual test harness that:
1. Opens the real audio device
2. Renders a click track (single-sample impulse every 500ms)
3. Logs `System.nanoTime()` at render and at callback
4. Reports measured latency per block

This is not CI — it requires human ears or a loopback cable + input capture.

## 10.4 Allocation Enforcement

The existing `AllocationEnforcementTest` CI harness must be extended to cover:
- `SpscAudioRingBuffer.write()` — zero allocation
- `SpscAudioRingBuffer.read()` — zero allocation
- `AudioCallback.render()` implementation — zero allocation

---

# 11. Implementation Phases

## Phase 1A — SPI Foundation (All Platforms)
**Scope:** Define contracts, build ring buffer, refactor mixer
**Estimated files:** ~15 new, ~5 modified
**No platform-specific code yet**

1. Add `org.dynamisengine.audio.api.device` package to `dynamis-audio-api`:
   - `AudioBackend.java` (SPI interface)
   - `AudioDeviceHandle.java`
   - `AudioCallback.java`
   - `AudioDeviceInfo.java` (record)
   - `AudioFormat.java` (record)
   - `DeviceChangeEvent.java` (sealed interface)
   - `DeviceChangeListener.java`
   - `NullAudioBackend.java` (test/CI fallback — implements AudioBackend)

2. Add to `dynamis-audio-dsp`:
   - `SpscAudioRingBuffer.java`
   - `AudioDeviceManager.java`

3. Modify `SoftwareMixer.java`:
   - Extract `masterOutputBuffer` access
   - Remove direct `audioDevice.write()` call
   - Add `getMasterOutputBuffer()` for ring buffer feed

4. Update `module-info.java` for `api` and `dsp`

5. Update `NullAudioDevice` to implement both old and new contracts (adapter)

6. Full test suite for `SpscAudioRingBuffer` and `AudioDeviceManager`

**Gate:** All existing 514 tests pass. New tests pass. Zero-alloc harness passes.

---

## Phase 1B — CoreAudio Backend (macOS)
**Scope:** First real native backend
**Estimated files:** ~6 new

1. Create `dynamis-audio-backend-coreaudio` module (pom.xml, module-info.java)
2. Implement `CoreAudioBindings.java`:
   - `SymbolLookup` for AudioToolbox framework
   - Downcall handles for all ~15 CoreAudio functions
   - Upcall stub for render callback
3. Implement `CoreAudioStructs.java`:
   - `AudioStreamBasicDescription` layout
   - `AudioComponentDescription` layout
   - `AURenderCallbackStruct` layout
   - `AudioBufferList` layout
4. Implement `CoreAudioBackend.java`:
   - `isAvailable()` — try to load AudioToolbox
   - `enumerateDevices()` — AudioObjectGetPropertyData
   - `openDevice()` — full AudioUnit setup with render callback
   - `setDeviceChangeListener()` — AudioObjectAddPropertyListener
5. Implement `CoreAudioDeviceHandle.java`:
   - `start()` / `stop()` / `close()`
   - Format negotiation result
   - Latency query
6. Tests: unit + integration (`@EnabledOnOs(MAC)`)

**Gate:** Audible output on macOS. Sine wave plays without glitches. Latency < 10ms.

---

## Phase 1C — ALSA Backend (Linux)
**Scope:** Second native backend
**Estimated files:** ~5 new

1. Create `dynamis-audio-backend-alsa` module
2. `AlsaBindings.java` — `SymbolLookup` for `libasound.so.2`
3. `AlsaBackend.java` — full ALSA PCM setup
4. `AlsaDeviceHandle.java`
5. Async callback mode with fallback to blocking `snd_pcm_writei` loop
6. Underrun recovery: detect `-EPIPE`, `snd_pcm_prepare`, retry

**Gate:** Audible output on Linux. Handles underrun recovery gracefully.

---

## Phase 1D — WASAPI Backend (Windows)
**Scope:** Third native backend, most complex due to COM
**Estimated files:** ~7 new

1. Create `dynamis-audio-backend-wasapi` module
2. `WasapiComHelper.java` — COM vtable call abstraction
3. `WasapiBindings.java` — downcall handles for COM functions
4. `WasapiStructs.java` — WAVEFORMATEXTENSIBLE, PROPERTYKEY layouts
5. `WasapiBackend.java`:
   - Shared mode (default)
   - Exclusive mode (opt-in)
   - Event-driven buffer delivery
6. `WasapiDeviceEnumerator.java` — IMMDeviceEnumerator
7. Device change notification via IMMNotificationClient

**Gate:** Audible output on Windows. Shared mode works. Exclusive mode works with opt-in.

---

## Phase 1E — Hot-Swap & Polish — **COMPLETE** (2026-03-17)

All items delivered:

1. ~~Implement hot-swap in `AudioDeviceManager`~~ — 8-state lifecycle machine with DEGRADED, FAULTED
2. ~~Add profiler overlay metrics~~ — `AudioTelemetry` record with structured snapshot, statusLine, detailedReport
3. ~~Stress test~~ — `ChaosAudioBackend` with 4 presets, 8 soak tests, startup race regression test
4. CoreAudio `AudioObjectAddPropertyListener` wired for default device change
5. Cross-backend lifecycle contract ratified (`docs/audio-device-lifecycle-and-hotswap.md`)
6. Dead-thread watchdog, listener exception containment, swap generation telemetry

**Gate result:** Hot-swap state machine chaos-validated. Race condition found and fixed.
Device unplug → re-plug proving deferred to hardware availability.

---

## Phase 1F — Native Hardware Proving (NEXT)

**Status: PENDING — requires native hardware access**

Remaining proving that cannot be done on macOS alone:

1. **CoreAudio hot-plug proving** — USB-C headset or Bluetooth connect/disconnect during playback.
   Verify: DeviceChangeEvent fires, swap generation increments, tone resumes on new device,
   no underrun explosion, no FAULTED. Use `HotSwapLiveProve` harness.

2. **ALSA native proving** — on real Linux hardware. Run `CoreAudioLiveProve`-equivalent
   with ALSA backend. Verify: clean tone, underrun recovery, snd_pcm_writei working,
   device enumeration returns real devices.

3. **WASAPI native proving** — on real Windows hardware. Verify: COM initialization,
   event-driven render loop, shared mode output, device enumeration via IMMDeviceEnumerator.

4. **Long-running soak** — 30+ minutes sustained playback with chaos events on each platform.

**Gate:** Clean tone on all three platforms. Hot-plug works on CoreAudio. No FAULTED under normal operation.

---

## Phase 2 (Future) — Advanced Features

Not in scope for Phase 1 but architecturally accounted for:

- **Multi-channel output** (5.1, 7.1, Atmos) — `AudioFormat.channels` already supports it
- **PipeWire backend** — new module at priority 150 (preferred over ALSA when available)
- **JACK backend** — new module for pro-audio Linux setups
- **AAudio/Oboe backend** — Android support
- **CoreAudio aggregate devices** — combine multiple interfaces
- **WASAPI loopback capture** — for voice chat echo cancellation
- **Sample-accurate synchronization** — tie audio clock to game tick for music games
- **HRTF spatialization** — per-voice binaural rendering (needs HrtfNode in DSP chain)

---

# 12. Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Panama upcall latency on real-time thread | Audio glitches | Benchmark upcall overhead; if > 1us, consider dedicated native shim library as escape hatch |
| GC pause during audio callback | Audio dropout | ZGC (already configured in Surefire); ring buffer absorbs jitter; callback itself does no allocation |
| COM vtable offsets wrong on WASAPI | Crash | Validate against Windows SDK headers; test on multiple Windows versions |
| CoreAudio API changes in future macOS | Build break | Pin to stable AudioUnit API (unchanged since macOS 10.0); avoid deprecated AudioHardware functions |
| ALSA configuration variety | Silent failure on some distros | Robust fallback: if hw: fails, try plughw:, then default; log everything |
| JDK Panama FFM API changes | Source break | JDK 25 FFM is stable (finalized in JDK 22); minimal risk |
| Ring buffer sizing too small | Glitches under load | Default 4 slots; configurable; telemetry reports underruns to profiler |

---

# 13. Cleanup — PanamaAudioDevice Retirement

Once all three backends are verified:

1. Delete `PanamaAudioDevice.java` (monolithic stub)
2. Delete platform switch logic from `AudioDeviceFactory.java`
3. `AudioDeviceFactory` becomes a thin wrapper over `AudioDeviceManager.create()`
4. Keep `NullAudioDevice` for tests (implements `AudioBackend` as `NullAudioBackend`)
5. Update `AGENTS.md` and `README.md`

---

# 14. Success Criteria

| Metric | Target |
|--------|--------|
| Round-trip latency (CoreAudio, 256 frames) | < 8ms |
| Round-trip latency (WASAPI shared, 256 frames) | < 15ms |
| Round-trip latency (WASAPI exclusive, 256 frames) | < 8ms |
| Round-trip latency (ALSA, 256 frames) | < 10ms |
| Heap allocation in render callback | 0 bytes |
| Heap allocation in ring buffer read/write | 0 bytes |
| Device hot-swap recovery time | < 100ms |
| Underruns under normal load (10min stress) | 0 |
| Glitch-free operation after 1hr continuous | Pass |
| All existing 514 tests pass | Pass |
| New test count (unit + integration) | >= 80 |
| Backend module size (JAR, each) | < 50KB |

---

# 15. Reference Material

### CoreAudio
- Apple Audio Unit Hosting Guide
- Core Audio Overview (Apple Developer)
- `AudioUnit.h`, `AudioComponent.h`, `AudioHardware.h` headers

### WASAPI
- Microsoft WASAPI documentation (learn.microsoft.com)
- `audioclient.h`, `mmdeviceapi.h` headers
- Larry Osterman's blog (Microsoft audio team)

### ALSA
- ALSA Library API Reference (alsa-project.org)
- `pcm.h`, `pcm_plugin.h` headers

### Panama FFM
- JEP 454: Foreign Function & Memory API (finalized JDK 22)
- `java.lang.foreign` Javadoc (JDK 25)
- Maurizio Cimadamore's Panama talks (JVM Language Summit)

---

*This plan is the single source of truth for the Panama native audio implementation.
Update this document as decisions are made and phases are completed.*
