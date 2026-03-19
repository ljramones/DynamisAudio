# DynamisAudio — Panama Native Audio Backend Architecture

Date: 2026-03-17
Status: Phase 1A (SPI Foundation) + Phase 1B (CoreAudio) COMPLETE — live-proven on macOS

---

# 1. Overview

DynamisAudio's native audio output system uses **Panama FFM** (Foreign Function & Memory API) to call platform audio APIs directly from Java — zero JNI, zero native compilation, pure Java distribution.

The architecture follows a **pluggable SPI model**: each platform backend (CoreAudio, WASAPI, ALSA) is a separate Maven module discovered at runtime via `java.util.ServiceLoader`. The DSP engine has no compile-time dependency on any platform backend. Ship only the backend JAR matching your target OS.

The audio data path is **zero-allocation on the hot path**: a wait-free SPSC ring buffer (off-heap `MemorySegment`, `VarHandle` acquire/release) bridges the DSP render worker thread and the platform's real-time audio callback thread.

**Key stats:**
- 9 Maven modules in single reactor
- 485 automated tests, 0 failures
- Live-proven: clean 440 Hz tone on macOS speakers via CoreAudio AudioUnit + Panama upcall
- Zero heap allocation in the render callback path

---

# 2. Module Reactor & Dependency DAG

## 2.1 Modules (Build Order)

```
dynamis-audio-api                    SPI contracts, device abstractions, value types
dynamis-audio-core                   Acoustic event queue, voice manager, emitter params
dynamis-audio-dsp                    Software mixer, DSP nodes, ring buffer, device manager
dynamis-audio-simulation             Acoustic world proxies, ray tracing, fingerprints
dynamis-audio-designer               Mix snapshots, event system, RTPC
dynamis-audio-music                  Music player, layers, scheduler
dynamis-audio-procedural             Oscillators, envelopes, synth voices
dynamis-audio-backend-coreaudio      CoreAudio backend (macOS) — Phase 1B
dynamis-audio-test-harness           Integration tests (never shipped)
```

## 2.2 Dependency Graph

```
                         dynamis-audio-api
                        /    |    |    \    \
                      core  sim  des  music  procedural
                        \   |   /
                       dynamis-audio-dsp
                              |
                       [test-harness]

  dynamis-audio-backend-coreaudio ──→ dynamis-audio-api (ONLY dependency)
```

**Critical property:** Backend modules depend ONLY on `dynamis-audio-api`. No coupling to dsp, core, simulation, or other backends. This is what makes the system pluggable.

---

# 3. Data Flow — End-to-End

```
Game Thread                    DSP Worker Thread              Platform RT Thread
───────────                    ─────────────────              ──────────────────
LogicalEmitter.run()           SoftwareMixer.renderBlock()    CoreAudio callback
  ↓ VarHandle publish            ↓                             ↓
EmitterParams[0/1]             for each voice:                ioData (AudioBufferList*)
                                 updateFromEmitterParams()      ↓
AcousticEventQueue               renderBlock()               ioData.reinterpret(24)
  ↓ enqueue()                    accumulate dry+reverb          ↓
  ↓                            bus graph process              extractBuffer0Data()
  ↓                              ↓                              ↓
  ↓                            masterOutputBuffer             for each blockSize chunk:
  ↓                              ↓                              ringBuffer.read(slice)
  ↓                            ringBuffer.write()                 ↓
  ↓                              ↓                            audioCallback.render()
  ↓                            [VarHandle setRelease]            ↓
  ↓                                                           [VarHandle setRelease]
  ↓                                                              ↓
  ↓                                                           samples → hardware
  ↓
eventQueue.drainTo() ─────→ processEvents() (next renderBlock)
```

**Thread boundaries:**
- Game thread → DSP worker: `VarHandle` acquire/release (EmitterParams, AcousticEventQueue)
- DSP worker → Platform callback: `SpscAudioRingBuffer` (VarHandle acquire/release, off-heap)
- Platform callback → hardware: direct memory write to `AudioBufferList.mBuffers[0].mData`

**Allocation boundaries:**
- Game thread: may allocate (event creation, emitter lifecycle)
- DSP worker: **zero allocation** (all buffers pre-allocated)
- Platform callback: **zero allocation** (ring buffer read only)

---

# 4. SPI Contract — `org.dynamisengine.audio.api.device`

## 4.1 AudioBackend (Service Provider Interface)

```java
public interface AudioBackend {
    String name();                              // "CoreAudio", "WASAPI", "ALSA", "Null"
    int priority();                             // Higher = preferred (100 standard, 0 for Null)
    boolean isAvailable();                      // Fast probe — no device opening
    List<AudioDeviceInfo> enumerateDevices();   // At least one if available
    AudioDeviceHandle openDevice(               // Returns stopped session handle
        AudioDeviceInfo device,
        AudioFormat requestedFormat,
        AudioCallback audioCallback)
        throws AudioDeviceException;
    void setDeviceChangeListener(DeviceChangeListener listener);
}
```

## 4.2 AudioCallback (Pull Model)

```java
@FunctionalInterface
public interface AudioCallback {
    void render(MemorySegment outputBuffer, int frameCount, int channels);
    // Called on platform real-time thread
    // MUST be wait-free: no locks, no allocation, no I/O
}
```

## 4.3 AudioDeviceHandle (Session Lifecycle)

```java
public interface AudioDeviceHandle {
    void start();                         // Callback begins firing
    void stop();                          // Callback stops; resumable
    void close();                         // Terminal; releases native resources
    AudioFormat negotiatedFormat();        // What the hardware actually accepted
    int outputLatencyFrames();            // Driver-reported latency
    default float outputLatencyMs();      // Convenience
    boolean isActive();
    String deviceDescription();
}
```

## 4.4 Supporting Records

```java
public record AudioFormat(int sampleRate, int channels, int blockSize, boolean exclusiveMode) {
    public static AudioFormat defaultFormat()         // 48kHz, stereo, 256 frames
    public static AudioFormat stereo48k(int blockSize)
}

public record AudioDeviceInfo(
    String id, String displayName, int maxChannels,
    int[] supportedSampleRates,                       // defensive copies
    boolean isDefault, boolean supportsExclusive)

public sealed interface DeviceChangeEvent {
    record DeviceAdded(AudioDeviceInfo device)         implements DeviceChangeEvent {}
    record DeviceRemoved(String deviceId)              implements DeviceChangeEvent {}
    record DefaultDeviceChanged(AudioDeviceInfo newDef) implements DeviceChangeEvent {}
}
```

## 4.5 NullAudioBackend (Fallback)

Always available (priority 0). Returns a synthetic device. Simulates callback cadence with a virtual thread that sleeps between blocks. Used in CI and when no platform backend is discovered.

---

# 5. SpscAudioRingBuffer — Wait-Free Off-Heap SPSC

## 5.1 Design

```
                   writePos ──────→
    ┌─────┬─────┬─────┬─────┐
    │ s0  │ s1  │ s2  │ s3  │     Off-heap MemorySegment
    └─────┴─────┴─────┴─────┘     (slotCount * blockSize * channels * 4 bytes)
                   readPos ───────→
```

- **Capacity:** Power-of-two slot count (default 4)
- **Slot size:** `blockSize * channels * Float.BYTES` bytes (2048 for 256-frame stereo)
- **Memory:** Single off-heap `MemorySegment` via `Arena.ofShared()`
- **Synchronization:** `VarHandle` acquire/release on `writePos` and `readPos`
- **No CAS, no locks, no retry loops**

## 5.2 Producer (DSP Worker Thread)

```java
public boolean write(float[] block, int frameCount, int channels) {
    long wp = (long) WRITE_POS_VH.getAcquire(this);
    long rp = (long) READ_POS_VH.getAcquire(this);
    if (wp - rp >= slotCount) { overruns++; return false; }

    int slot = (int) (wp & mask);
    MemorySegment heapView = MemorySegment.ofArray(block);
    buffer.asSlice(slot * blockBytes, byteCount).copyFrom(heapView.asSlice(0, byteCount));

    WRITE_POS_VH.setRelease(this, wp + 1);
    // watermark tracking
    return true;
}
```

## 5.3 Consumer (Platform Callback Thread)

```java
public boolean read(MemorySegment dest) {
    long rp = (long) READ_POS_VH.getAcquire(this);
    long wp = (long) WRITE_POS_VH.getAcquire(this);
    if (rp >= wp) { underruns++; return false; }

    int slot = (int) (rp & mask);
    dest.copyFrom(buffer.asSlice(slot * blockBytes, blockBytes));

    READ_POS_VH.setRelease(this, rp + 1);
    // watermark tracking
    return true;
}
```

## 5.4 Telemetry

| Metric | Purpose |
|--------|---------|
| `available()` | Current blocks in ring (approximate) |
| `freeSlots()` | Current free capacity |
| `underruns()` | Consumer found ring empty (hardware starved) |
| `overruns()` | Producer found ring full (DSP ahead of hardware) |
| `highWatermark()` | Peak occupancy ever observed |
| `lowWatermark()` | Minimum occupancy after first write |

## 5.5 Latency Budget (48 kHz, 256 frames, 4 slots)

| Scenario | Latency |
|----------|---------|
| Per block | 5.33 ms |
| Ring best case (1 block ahead) | 5.33 ms |
| Ring typical (2 blocks ahead) | 10.67 ms |
| Ring worst case (4 blocks ahead) | 21.33 ms |

---

# 6. AudioDeviceManager — Lifecycle Orchestrator

## 6.1 Lifecycle

```
discoverBackend()
    │ ServiceLoader.load(AudioBackend.class)
    │ Select highest-priority available
    ▼
initialize(SoftwareMixer, AudioFormat)
    │ Enumerate devices → select default
    │ Create SpscAudioRingBuffer (4 slots)
    │ backend.openDevice(device, format, callback)
    │ Register device change listener
    ▼
start()
    │ Pre-fill ring (2 blocks silence)
    │ Start DSP worker thread (platform thread, daemon)
    │ activeDevice.start() → callback begins firing
    ▼
[running]
    │ DSP worker: renderBlock() → ring.write() loop
    │ Platform callback: ring.read() → output buffer
    │ Hot-swap handler checks flag each iteration
    ▼
stop()
    │ activeDevice.stop()
    │ Interrupt DSP worker, join
    ▼
shutdown()
    │ Unregister listener
    │ activeDevice.close()
    │ ringBuffer.close()
```

## 6.2 Thread Model

| Thread | Responsibility | Scheduling |
|--------|---------------|------------|
| Engine lifecycle | initialize, start, stop, shutdown | User-controlled |
| DSP worker (`dynamis-dsp-worker`) | renderBlock() + ring write | Platform thread, daemon |
| Platform callback | ring read → output | OS real-time thread (NOT Java) |
| Device notification | Hot-swap events | OS notification thread |

## 6.3 Hot-Swap Sequence

```
1. Platform fires DeviceChangeEvent.DefaultDeviceChanged
2. onDeviceChange() sets hotSwapRequested = true (atomic flag)
3. DSP worker loop detects flag at top of next iteration
4. handleHotSwap():
   a. activeDevice.stop()
   b. activeDevice.close()
   c. Re-enumerate devices
   d. Open new default device
   e. Pre-fill ring (1 block silence)
   f. activeDevice.start()
5. Resume render loop
```

## 6.4 Telemetry

| Metric | Source |
|--------|--------|
| `getCallbackCount()` | Total platform callback invocations |
| `getFeederBlockCount()` | Total DSP blocks rendered |
| `getFeederMaxNanos()` | Worst-case render time |
| `getFeederAvgNanos()` | Average render time |
| `getStartupToFirstAudioMs()` | Time from start() to first rendered block |
| `telemetrySnapshot()` | Human-readable status string |

---

# 7. CoreAudio Backend — `dynamis-audio-backend-coreaudio`

## 7.1 Module Structure

```
dynamis-audio-backend-coreaudio/
├── pom.xml                          (depends only on dynamis-audio-api)
├── src/main/java/
│   ├── module-info.java             (provides AudioBackend with CoreAudioBackend)
│   └── org/dynamisengine/audio/backend/coreaudio/
│       ├── CoreAudioBackend.java        SPI provider — discovery, probe, open
│       ├── CoreAudioDeviceHandle.java   AudioUnit lifecycle + render callback
│       ├── CoreAudioBindings.java       12 Panama downcall handles + upcall factory
│       ├── CoreAudioConstants.java      macOS SDK constants (four-char codes)
│       ├── CoreAudioStructs.java        Off-heap struct layouts (ASBD, ABL, etc.)
│       └── CoreAudioDeviceEnumerator.java  Device enumeration via AudioObject
├── src/main/resources/
│   └── META-INF/services/
│       └── org.dynamisengine.audio.api.device.AudioBackend
└── src/test/java/
    └── org/dynamisengine/audio/backend/coreaudio/
        └── CoreAudioBackendTest.java    (@EnabledOnOs(MAC))
```

## 7.2 Panama Bindings

**Framework loading:**
```java
SymbolLookup.libraryLookup(
    "/System/Library/Frameworks/AudioToolbox.framework/AudioToolbox",
    Arena.global());
SymbolLookup.libraryLookup(
    "/System/Library/Frameworks/CoreAudio.framework/CoreAudio",
    Arena.global());
```

**Downcall handles (12 total):**

| Function | Purpose |
|----------|---------|
| `AudioComponentFindNext` | Find default output AudioComponent |
| `AudioComponentInstanceNew` | Instantiate AudioUnit |
| `AudioComponentInstanceDispose` | Destroy AudioUnit |
| `AudioUnitSetProperty` | Set stream format, render callback |
| `AudioUnitGetProperty` | Read negotiated format |
| `AudioUnitInitialize` | Prepare AudioUnit for rendering |
| `AudioUnitUninitialize` | Tear down AudioUnit |
| `AudioOutputUnitStart` | Start audio output (callback begins) |
| `AudioOutputUnitStop` | Stop audio output |
| `AudioObjectGetPropertyDataSize` | Query property size (enumeration) |
| `AudioObjectGetPropertyData` | Read property data (enumeration) |
| `AudioObjectSetPropertyData` | Write property data |

**Upcall stub (1):**

```java
// AURenderCallback signature:
// OSStatus (*)(void*, AudioUnitRenderActionFlags*, AudioTimeStamp*,
//              UInt32, UInt32, AudioBufferList*)
static MemorySegment createRenderCallbackStub(CoreAudioDeviceHandle handle, Arena arena) {
    MethodHandle mh = lookup.findVirtual(CoreAudioDeviceHandle.class, "renderCallback", ...);
    mh = mh.bindTo(handle);
    return Linker.nativeLinker().upcallStub(mh, RENDER_CALLBACK_DESC, arena);
}
```

## 7.3 AudioUnit Setup Sequence

```
1. AudioComponentFindNext(NULL, {kAudioUnitType_Output, kAudioUnitSubType_DefaultOutput})
2. AudioComponentInstanceNew(component, &audioUnit)
3. AudioUnitSetProperty(audioUnit, kAudioUnitProperty_StreamFormat,
                        kAudioUnitScope_Input, 0, asbd, 40)
4. AudioUnitSetProperty(audioUnit, kAudioUnitProperty_SetRenderCallback,
                        kAudioUnitScope_Input, 0, callbackStruct, 16)
5. AudioUnitInitialize(audioUnit)
6. AudioOutputUnitStart(audioUnit)    → callback begins firing
```

## 7.4 Struct Layouts (Verified via Runtime Diagnostic Dump)

**AudioStreamBasicDescription (ASBD) — 40 bytes:**
```
offset  0: Float64 mSampleRate          (48000.0)
offset  8: UInt32  mFormatID            ('lpcm')
offset 12: UInt32  mFormatFlags         (kFloat | kPacked)
offset 16: UInt32  mBytesPerPacket      (8 for stereo float)
offset 20: UInt32  mFramesPerPacket     (1)
offset 24: UInt32  mBytesPerFrame       (8)
offset 28: UInt32  mChannelsPerFrame    (2)
offset 32: UInt32  mBitsPerChannel      (32)
offset 36: UInt32  mReserved            (0)
```

**AudioBufferList (1 buffer, 64-bit macOS) — 24 bytes:**
```
offset  0: UInt32  mNumberBuffers       (1)
offset  4: [4 bytes padding]           (alignment for AudioBuffer)
offset  8: UInt32  mNumberChannels      (2)
offset 12: UInt32  mDataByteSize        (4096 for 512 frames stereo)
offset 16: void*   mData               (native pointer to PCM buffer)
```

**Verified empirically:** The `ioData` MemorySegment in the render callback arrives with zero length. Must call `ioData.reinterpret(24)` before reading struct fields.

## 7.5 Render Callback — The Critical Path

```java
int renderCallback(MemorySegment inRefCon, MemorySegment ioActionFlags,
                   MemorySegment inTimeStamp, int inBusNumber,
                   int inNumberFrames, MemorySegment ioData) {

    MemorySegment abl = ioData.reinterpret(24);
    MemorySegment outputBuffer = CoreAudioStructs.extractBuffer0Data(abl, totalDataBytes);

    // CoreAudio requests 512 frames; our ring blocks are 256 frames.
    // Read multiple blocks to fill the hardware buffer.
    int blockFrames = negotiatedFormat.blockSize();      // 256
    int framesWritten = 0;
    while (framesWritten + blockFrames <= inNumberFrames) {
        MemorySegment slice = outputBuffer.asSlice(offset, blockBytes);
        audioCallback.render(slice, blockFrames, channels);  // ring.read()
        framesWritten += blockFrames;
    }
    // Fill remainder with silence

    return kAudio_NoError;  // Never fail the callback
}
```

**Key behaviors:**
- Callback increments `nativeCallbackCount` (volatile, no allocation)
- On underrun (ring empty): fills output with silence — no glitch propagation
- On exception: fills silence — never throws from real-time callback
- Phase continuity depends on the feeder generating blocks in order (no regeneration on overrun)

---

# 8. SoftwareMixer Adaptation

## 8.1 Changes for Pull Model

The mixer no longer owns the device write. Instead, it exposes its output buffer for the `AudioDeviceManager` to read:

```java
// NEW: Expose master output buffer for ring buffer feeding
public float[] getMasterOutputBuffer() { return masterOutputBuffer; }

// MODIFIED: Device write is now conditional (legacy push path)
// In renderBlock():
if (audioDevice != null) {
    audioDevice.write(masterOutputBuffer, blockSize, channels);
}
```

## 8.2 Two Operating Modes

| Mode | Who drives timing? | Data flow |
|------|-------------------|-----------|
| **Push (legacy)** | DSP worker calls `audioDevice.write()` | Mixer → AudioDevice.write() → hardware |
| **Pull (new)** | Platform callback requests data | Mixer → ring buffer → callback → hardware |

Pull mode is used when `AudioDeviceManager` is active. Push mode remains for `NullAudioDevice` in tests.

---

# 9. JPMS Module Declarations

```java
// dynamis-audio-api
module org.dynamisengine.audio.api {
    exports org.dynamisengine.audio.api;
    exports org.dynamisengine.audio.api.device;
    uses org.dynamisengine.audio.api.device.AudioBackend;
}

// dynamis-audio-core
module org.dynamisengine.audio.core {
    requires transitive org.dynamisengine.audio.api;
    exports org.dynamisengine.audio.core;
}

// dynamis-audio-dsp
module org.dynamisengine.audio.dsp {
    requires transitive org.dynamisengine.audio.api;
    requires org.dynamisengine.audio.core;
    requires org.dynamisengine.audio.designer;
    requires org.dynamisengine.audio.simulation;
    exports org.dynamisengine.audio.dsp;
    exports org.dynamisengine.audio.dsp.device;
    uses org.dynamisengine.audio.api.device.AudioBackend;
}

// dynamis-audio-backend-coreaudio
module org.dynamisengine.audio.backend.coreaudio {
    requires org.dynamisengine.audio.api;
    provides org.dynamisengine.audio.api.device.AudioBackend
        with org.dynamisengine.audio.backend.coreaudio.CoreAudioBackend;
}
```

The `uses` declaration appears in both `api` and `dsp` — ServiceLoader can discover backends from either module's context.

---

# 10. Test Coverage

## 10.1 Automated Tests (485 total, 0 failures)

| Test Class | Count | Scope |
|-----------|-------|-------|
| `SpscAudioRingBufferTest` | 12 | Construction, read/write, wrap-around, overrun/underrun, concurrent SPSC stress |
| `AudioBackendSpiTest` | 11 | AudioFormat, AudioDeviceInfo, DeviceChangeEvent, NullAudioBackend, AudioDeviceManager discovery |
| `CoreAudioBackendTest` | 3 | macOS availability probe, device enumeration, property validation (`@EnabledOnOs(MAC)`) |
| *Existing test suite* | 459 | DSP nodes, emitters, voice manager, snapshots, reverb, resampling, music, procedural, etc. |

## 10.2 Manual Live Prove (`CoreAudioLiveProve`)

Three-phase manual test harness:

| Phase | Duration | Expected Result |
|-------|----------|-----------------|
| Basic playback | 3 seconds | Clean 440 Hz tone, zero underruns |
| Lifecycle cycles | 5 x 200 ms | Clean open/start/stop/close, no crashes |
| Feeder stall | 500ms + 100ms stall + 500ms | Underruns during stall, zero after recovery |

**Proven results (2026-03-17):**
```
Test 1: 556 callbacks (expected ~562), 0 underruns, low watermark 2   HEALTHY
Test 2: 5 cycles, 36-38 callbacks each, 0 underruns                   OK
Test 3: 0 underruns → 16 stall underruns → 0 recovery underruns       RECOVERED
```

---

# 11. Lessons Learned During CoreAudio Proving

| Issue | Root Cause | Fix |
|-------|-----------|-----|
| Callback never fired (0 callbacks) | `ioData` arrives as zero-length MemorySegment; reading struct fields threw silently | `ioData.reinterpret(24)` before accessing AudioBufferList fields |
| Buzz/distortion instead of clean tone | CoreAudio pulls 512 frames but ring blocks are 256; only half the output buffer was filled | Loop reading multiple ring blocks per callback invocation |
| Still distorted after multi-block fix | Feeder regenerated sine blocks on overrun, causing phase discontinuities | Only generate a new block when the previous one was successfully enqueued |
| AudioBufferList struct offsets | 4 bytes of padding between mNumberBuffers and mBuffers[0] on 64-bit | Verified via raw hex dump: mData at offset 16, total ABL size 24 |

---

# 12. Architecture Principles

1. **Callback stays dumb.** The platform callback does exactly one thing: read from the ring buffer into the output segment. No allocation, no logging, no format conversion, no DSP, no policy decisions.

2. **SPI boundary is sacred.** Backend modules depend only on `dynamis-audio-api`. Adding ALSA or WASAPI requires zero changes to the DSP engine.

3. **Zero allocation on the hot path.** Enforced by CI harness. All buffers pre-allocated. `MemorySegment.copyFrom()` is JDK-intrinsified to `memcpy`.

4. **Ring buffer is the only synchronization point.** DSP worker and platform callback share no other state. VarHandle acquire/release — no locks, no CAS.

5. **Fail silently in the callback.** On underrun: silence. On exception: silence. Never throw, never log, never block on the real-time thread.

6. **Phase continuity matters.** The feeder must not regenerate audio blocks on ring buffer overrun. Overrun means "wait until there's space," not "discard and regenerate."

---

# 13. Hardening Roadmap (Pre-ALSA/WASAPI)

Architect review (2026-03-17) confirmed the architecture is sound. Four hardening items to address before expanding to more platforms:

## 13.1 MemorySegment-First Producer Side

The ring buffer's `write()` currently accepts `float[]` and wraps via `MemorySegment.ofArray()`. This works — JDK 25 intrinsifies the copy — but the ideal end-to-end path is segment-to-segment:

```
Current:   float[] masterOutputBuffer → MemorySegment.ofArray() → ring → callback segment
Target:    MemorySegment masterOutputBuffer → ring → callback segment
```

This means the mixer's master output buffer should be an off-heap `MemorySegment` rather than a `float[]`. Not urgent for the proven slice, but the preferred cleanup direction before the system matures.

## 13.2 Explicit Lifecycle State Machine

`AudioDeviceManager` lifecycle is currently procedural (method calls in expected order). Before ALSA/WASAPI add hot-swap complexity, formalize as a state machine:

```
UNINITIALIZED → INITIALIZED → STARTING → RUNNING → SWAP_PENDING → SWAPPING → RUNNING
                                       → STOPPING → STOPPED → CLOSED
                                       → FAULTED → CLOSED
```

Benefits: prevents illegal transitions, makes hot-swap atomic, enables deterministic error recovery.

## 13.3 Backend Capability Reporting

Add a capability descriptor to the SPI so the manager can make informed policy decisions as platforms diverge:

```java
public interface AudioBackend {
    // ... existing methods ...
    default BackendCapabilities capabilities() {
        return BackendCapabilities.DEFAULT;
    }
}

public record BackendCapabilities(
    boolean supportsPullModel,       // true for CoreAudio, WASAPI event-driven
    boolean supportsExclusiveMode,   // true for WASAPI
    boolean supportsDeviceChange,    // true for CoreAudio, WASAPI
    int maxChannels,                 // platform maximum
    int[] preferredBlockSizes        // hardware-friendly sizes
) {}
```

## 13.4 Deterministic Failure Policy

Document and implement explicit rules for every failure mode:

| Failure | Current Behavior | Target Policy |
|---------|-----------------|---------------|
| Device open failure | Exception propagates | Retry with fallback format, then NullAudioBackend |
| Negotiated format mismatch | Accept silently | Log warning, insert resampler if rate differs |
| Exclusive mode failure | Not yet implemented | Fall back to shared mode automatically |
| Device disappearance mid-run | Hot-swap flag set | State machine transition → SWAP_PENDING → re-enumerate |
| Repeated underruns (>N in M sec) | Counter increments | Log warning, optionally increase ring size |
| Backend fault during startup | Exception propagates | Fall back to NullAudioBackend, log error |

---

# 14. Phase Status

| Phase | Scope | Status |
|-------|-------|--------|
| 1A — SPI Foundation | AudioBackend SPI, ring buffer, device manager, NullAudioBackend | **COMPLETE** |
| 1B — CoreAudio | macOS AudioUnit via Panama, live-proven | **COMPLETE** |
| 1B+ — Hardening | State machine, capabilities, failure policy, MemorySegment-first | **Next** |
| 1C — ALSA | Linux `libasound.so.2` via Panama | Not started |
| 1D — WASAPI | Windows COM vtable calls via Panama | Not started |
| 1E — Hot-swap & Polish | Device change listener, profiler metrics, stress testing | Not started |

Full implementation plan: `docs/panama-native-audio-implementation-plan.md`
