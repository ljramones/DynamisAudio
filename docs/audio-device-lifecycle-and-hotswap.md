# Audio Device Lifecycle & Hot-Swap Contract

Date: 2026-03-17
Status: RATIFIED — governs all backend implementations
Scope: Cross-backend lifecycle semantics, hot-swap behavior, failure policy, threading model

---

# 1. Purpose

This document defines the **deterministic lifecycle and hot-swap contract** that all audio backend implementations must obey. It is the architectural reference for the audio device subsystem.

The contract exists because:

- Three backends (CoreAudio, ALSA, WASAPI) have different native execution models
- Hot-swap introduces asynchronous cross-platform lifecycle complexity
- Without a locked contract, backend behavior will drift per platform
- The callback thread is a real-time boundary where policy contamination is fatal

This contract is **not aspirational**. It describes what the code must do. If the code and this document disagree, **this document is wrong and must be updated** — the Javadoc on `AudioDeviceManager` is the enforcement layer.

---

# 2. Core Invariants

These are non-negotiable. Every design decision, every backend implementation, every future change must preserve these.

**INV-1: The callback is transport-only.**
The `AudioCallback.render()` method reads from the ring buffer and writes to the output segment. It does not allocate, log, synchronize, convert formats, perform DSP, or make policy decisions. On underrun, it fills silence. On exception, it fills silence. It never throws.

**INV-2: AudioDeviceManager is the sole lifecycle authority.**
No backend, no device handle, no listener, and no external code may independently initiate device open, close, start, stop, or swap. All lifecycle transitions are mediated through the manager.

**INV-3: Backends must not implement policy.**
A backend reports capabilities, probes availability, opens devices when asked, and fires change events when the platform notifies it. It does not decide which device to use, when to swap, whether to retry, or how to handle failure. That is the manager's job.

**INV-4: The ring buffer is the only real-time synchronization boundary.**
The DSP feeder thread and the platform callback thread share no mutable state other than the `SpscAudioRingBuffer`. VarHandle acquire/release on head/tail indices. No locks, no CAS, no shared flags, no shared objects.

**INV-5: No cross-thread shared mutable state outside the ring.**
The `volatile State` field on the manager is written by the lifecycle/notification thread and read by the DSP worker. This is a one-way signal, not shared mutable state. The callback thread does not read it.

**INV-6: Failure behavior is deterministic.**
Every failure mode has an explicitly defined outcome. No silent failures, no undefined behavior, no "it depends on timing."

**INV-7: No allocation in the DSP worker hot path.**
`renderBlock()`, `ringBuffer.writeFrom()`, and all code on the DSP worker thread's steady-state loop must not allocate beyond bounded, pre-allocated buffers. Hot-swap execution (which runs on the DSP worker but is not the steady-state path) may allocate.

**INV-8: Only the first SWAP_PENDING transition is honored.**
When multiple device change notifications arrive concurrently, only the first `RUNNING → SWAP_PENDING` transition takes effect. All subsequent notifications received while in `SWAP_PENDING` or `SWAPPING` state are ignored. This prevents swap cascades.

**INV-9: Callback exception handling must not allocate or log.**
The `try/catch` in the callback path fills silence on exception. The catch block itself must not allocate objects, create strings, or call any logging framework. This is easy to violate accidentally and must be actively guarded.

---

# 3. Lifecycle State Machine

## 3.1 States

| State | Description | Audio flowing? |
|-------|-------------|----------------|
| `UNINITIALIZED` | Constructed, no backend discovered, no device open | No |
| `INITIALIZED` | Backend discovered, device opened, ring buffer created, DSP worker not yet started | No |
| `RUNNING` | DSP worker active, device started, callback firing, audio flowing | **Yes** |
| `SWAP_PENDING` | Hot-swap requested; will execute at top of next DSP worker iteration | **Yes** (draining) |
| `SWAPPING` | Actively performing hot-swap: old device closing, new device opening | No (brief silence) |
| `DEGRADED` | Swap completed but no devices available. DSP worker continues (renders silence). Recoverable — devices may reappear. | No (silence) |
| `FAULTED` | Non-recoverable error during swap. Must `shutdown()` and re-initialize. | No |
| `CLOSED` | All resources released. Terminal state. | No |

## 3.2 Allowed Transitions

```
UNINITIALIZED ──initialize()──→ INITIALIZED
INITIALIZED   ──start()──────→ RUNNING
RUNNING       ──device change─→ SWAP_PENDING
SWAP_PENDING  ──DSP worker───→ SWAPPING
SWAPPING      ──success──────→ RUNNING
SWAPPING      ──no devices───→ DEGRADED
SWAPPING      ──failure──────→ FAULTED
DEGRADED      ──device change─→ SWAP_PENDING
DEGRADED      ──stop()───────→ CLOSED
RUNNING       ──stop()───────→ CLOSED
SWAP_PENDING  ──stop()───────→ CLOSED
FAULTED       ──stop()───────→ CLOSED
FAULTED       ──shutdown()───→ CLOSED
RUNNING       ──shutdown()───→ CLOSED
INITIALIZED   ──shutdown()───→ CLOSED
DEGRADED      ──shutdown()───→ CLOSED
```

## 3.3 Illegal Transitions

Any transition not listed above is illegal and throws `IllegalStateException`:

- `UNINITIALIZED → RUNNING` (must initialize first)
- `RUNNING → INITIALIZED` (cannot re-initialize without shutdown)
- `CLOSED → *` (terminal, no re-entry)
- `FAULTED → RUNNING` (must shutdown and re-initialize)
- `SWAPPING → SWAP_PENDING` (cannot nest swaps)

## 3.4 Who Triggers Each Transition

| Transition | Triggered by | Thread |
|-----------|-------------|--------|
| → INITIALIZED | `initialize()` call | Engine lifecycle thread |
| → RUNNING | `start()` call, or swap success | Engine lifecycle thread, or DSP worker |
| → SWAP_PENDING | `onDeviceChange()` | Platform notification thread |
| → SWAPPING | DSP worker loop detects SWAP_PENDING | DSP worker thread |
| → FAULTED | `handleHotSwap()` failure | DSP worker thread |
| → CLOSED | `stop()` or `shutdown()` | Engine lifecycle thread |

---

# 4. Event Model

## 4.1 What Counts as a Device Change

| Event | Description | Triggers swap? |
|-------|-------------|----------------|
| `DefaultDeviceChanged` | System default output changed (headphones plugged in, USB DAC connected) | **Yes** — transitions to SWAP_PENDING |
| `DeviceRemoved` | A device was disconnected | **Yes** — if it is the currently active device |
| `DeviceAdded` | A new device appeared | **Only if DEGRADED** — device reappears after all devices were lost. Otherwise logged only. |

## 4.2 When Listeners Fire

Device change events are fired **by the backend** from whatever thread the platform uses for notifications:

| Backend | Notification thread |
|---------|-------------------|
| CoreAudio | AudioObjectPropertyListener callback thread (Phase 1E) |
| WASAPI | IMMNotificationClient COM thread (Phase 1E) |
| ALSA | No native notification. Future: udev/inotify poll thread |

The `DeviceChangeListener.onDeviceChange()` method is invoked **directly** on that platform thread. The implementation (AudioDeviceManager) must be safe for cross-thread invocation.

## 4.3 Notification Guarantees

- Notifications are **asynchronous** — the backend fires them whenever the platform signals
- Notifications are **not serialized** — two events may arrive on different threads (platform-dependent)
- The manager uses `volatile State` to absorb concurrent notifications safely — only the first `RUNNING → SWAP_PENDING` transition takes effect; subsequent events during SWAP_PENDING or SWAPPING are ignored
- No pre-swap notification to game code. The swap is transparent. Game code may observe a brief silence (~50-100ms) during swap.

## 4.4 Listener Delivery Policy

- **Duplicate notifications** for the same physical event (e.g., two DefaultDeviceChanged for one plug event) are tolerated and coalesced. Only the first SWAP_PENDING transition takes effect; duplicates are no-ops.
- **Delivery is best-effort.** If a platform notification is missed, the system continues with the current device. Manual recovery (shutdown + re-initialize) is always available.
- **Listener exceptions never affect device lifecycle.** The `onDeviceChange()` implementation wraps all logic in a try/catch. Exceptions are logged and swallowed. The platform notification thread must not be disrupted.
- **Periodic reprobe is NOT performed.** The system relies solely on backend notifications to exit DEGRADED. If notifications are unreliable (e.g., ALSA without udev), manual shutdown + re-initialize is the recovery path.

## 4.4 What the Manager Does NOT Do on Notification

- Does NOT open or close devices on the notification thread
- Does NOT allocate on the notification thread
- Does NOT block the notification thread
- Only sets `state = SWAP_PENDING` (a single volatile write)

---

# 5. Hot-Swap Sequence

The hot-swap is executed entirely on the DSP worker thread, at the top of the feeder loop, when the worker detects `state == SWAP_PENDING`.

## 5.1 Step-by-Step Sequence

```
1. DSP worker detects state == SWAP_PENDING
2. state = SWAPPING
3. Stop old device:    activeDevice.stop()
4. Close old device:   activeDevice.close()
5. Re-enumerate:       activeBackend.enumerateDevices()
6. Select new default: first device with isDefault(), or first device
7. Open new device:    activeBackend.openDevice(newDefault, format, callback)
8. Pre-fill ring:      write 1 block of silence
9. Start new device:   activeDevice.start()
10. state = RUNNING
```

## 5.2 Failure During Hot-Swap

If any step in the swap sequence throws:

```
1. Log error
2. state = FAULTED
3. DSP worker loop exits (state != RUNNING and != SWAP_PENDING)
4. Audio output stops
5. Engine must call shutdown() then re-initialize() to recover
```

## 5.3 No-Devices-After-Swap

If `enumerateDevices()` returns empty during hot-swap:

```
1. Log warning: "No devices after hot-swap — audio degraded"
2. state → DEGRADED
3. DSP worker continues rendering to ring (silence fills output)
4. No active device — callback is not firing
5. When a DeviceAdded or DefaultDeviceChanged event arrives,
   DEGRADED → SWAP_PENDING → normal swap sequence resumes
```

DEGRADED is recoverable without shutdown. This avoids the "zombie RUNNING with no device" problem — RUNNING always means audio is actually flowing to hardware.

## 5.4 DEGRADED Exit Rules

| Exit path | Trigger | Behavior |
|-----------|---------|----------|
| `DEGRADED → SWAP_PENDING` | `DeviceAdded` event | Backend fires event when device appears. Manager transitions to SWAP_PENDING. Normal swap sequence resumes. |
| `DEGRADED → SWAP_PENDING` | `DefaultDeviceChanged` event | System default changed (e.g., USB DAC connected and becomes default). Same path. |
| `DEGRADED → CLOSED` | `stop()` or `shutdown()` | Manual intervention. Clean shutdown. |
| `DEGRADED → DEGRADED` | No events | System remains degraded indefinitely. DSP worker continues rendering silence. No periodic reprobe — relies on backend notifications. |

What CANNOT exit DEGRADED:
- `start()` — illegal (DSP worker is already running)
- `initialize()` — illegal (already past UNINITIALIZED)
- Timeout — no automatic recovery without a device change event

## 5.4 Swap Timing Budget

The hot-swap should complete within **100ms**. Users expect a brief audio dropout when switching devices. This is normal behavior even in AAA engines.

| Phase | Expected duration |
|-------|------------------|
| Stop old device | < 10ms |
| Close old device | < 20ms |
| Enumerate devices | < 10ms |
| Open new device | < 50ms |
| Start new device | < 10ms |
| **Total** | **< 100ms** |

## 5.5 Ring Buffer Behavior During Swap

During the swap:
- The DSP worker is not calling `renderBlock()` (it is executing the swap)
- The old device's callback stops when `activeDevice.stop()` returns
- The ring drains (underruns are expected and normal)
- After the new device starts, the ring refills from the DSP worker

The ring buffer is **not reset** during swap. Stale blocks from before the swap may be consumed by the new device. This may produce a brief **phase discontinuity** (a soft glitch) but avoids the sharp click artifact that a zeroed-then-refilled ring would produce. Only 1-2 blocks are typically stale. This is the same lesson learned during CoreAudio proving: phase continuity matters more than buffer cleanliness.

---

# 6. Failure Policy

Every failure mode has an explicitly defined, deterministic outcome.

## 6.1 Device Open Failure (During Initialize)

```
Scenario: activeBackend.openDevice() throws during initialize()
Policy:   Fall back to NullAudioBackend automatically
Behavior: Log error. Create NullAudioBackend. Open null device. Continue.
State:    → INITIALIZED (with null backend)
Audio:    Silence (discarded)
Recovery: Re-initialize with a different format or after driver fix
```

## 6.2 Device Open Failure (During Hot-Swap)

```
Scenario: activeBackend.openDevice() throws during handleHotSwap()
Policy:   Enter FAULTED state
Behavior: Log error. DSP worker exits.
State:    → FAULTED
Audio:    None
Recovery: shutdown() → re-initialize()
```

## 6.3 Negotiated Format Mismatch

```
Scenario: Device negotiates a different sample rate than requested
Policy:   Accept the negotiated format. Log warning if rate differs.
Behavior: AudioDeviceHandle.negotiatedFormat() reflects actual rate.
State:    No change
Audio:    May have incorrect pitch if mixer doesn't resample.
Future:   Insert resampler if actualRate != requestedRate.
```

## 6.4 Exclusive Mode Failure (WASAPI)

```
Scenario: WASAPI exclusive mode Initialize() returns AUDCLNT_E_*
Policy:   Fall back to shared mode automatically. Retry with AUDCLNT_SHAREMODE_SHARED.
Behavior: Log warning. Open in shared mode. Report exclusiveMode=false in negotiatedFormat.
State:    No change
Audio:    Works, but with shared-mode latency
```

## 6.5 Device Disappearance During Playback

```
Scenario: Active device is removed (USB unplug, Bluetooth disconnect)
Policy:   Backend fires DeviceRemoved. Manager transitions to SWAP_PENDING.
Behavior: Hot-swap sequence executes on next DSP worker iteration.
State:    RUNNING → SWAP_PENDING → SWAPPING → RUNNING (or FAULTED)
Audio:    Brief silence during swap (~50-100ms)
```

## 6.6 Repeated Underruns

```
Scenario: >50 underruns in 5 seconds
Policy:   Log warning. Do NOT fault. Underruns are expected during load spikes and transitions.
Behavior: checkUnderrunHealth() fires every 256 blocks. Warning includes delta and total.
State:    No change
Audio:    Intermittent silence/glitches
Future:   Optional policy: increase ring size, or notify profiler overlay
```

## 6.7 Backend Unavailable at Startup

```
Scenario: No platform backend is available (e.g., no audio hardware)
Policy:   Fall back to NullAudioBackend
Behavior: Log warning. Audio output discarded silently.
State:    → INITIALIZED (with null backend)
Audio:    Silence
```

## 6.8 Render Thread Death (Any Backend)

```
Scenario: Render/write thread exits unexpectedly (COM init failure, native crash, uncaught exception)
Policy:   State → FAULTED. No "zombie RUNNING" state allowed.
Behavior: DSP worker detects thread death on next iteration. Logs error. state = FAULTED.
State:    → FAULTED
Audio:    None
Recovery: shutdown() → re-initialize()
```

A render thread that has died while state is RUNNING violates the lifecycle contract. The DSP worker must detect this and transition to FAULTED rather than continuing to render into a ring that will never be drained.

---

# 7. Backend Responsibilities

Each `AudioBackend` implementation MUST:

| Responsibility | Detail |
|---------------|--------|
| Probe correctly | `isAvailable()` must be fast, accurate, and not open devices |
| Not cache stale state | `enumerateDevices()` must reflect current hardware every call |
| Not implement policy | Never decide which device, when to swap, or how to retry |
| Report capabilities | Return accurate `BackendCapabilities` |
| Fire change events | When the platform notifies, fire `DeviceChangeEvent` to the registered listener |
| Own native resources | All native memory/handles scoped to `Arena` owned by the device handle |
| Clean up on close | `AudioDeviceHandle.close()` must release all native resources. Idempotent. |
| Never throw from callback | Callback exceptions → silence, never propagation |

Each `AudioBackend` implementation MUST NOT:

| Prohibited | Reason |
|-----------|--------|
| Open devices without being asked | Lifecycle authority belongs to the manager |
| Retry failed operations | Retry policy belongs to the manager |
| Log in the callback | No I/O on the real-time thread |
| Allocate in the callback | Zero-allocation contract |
| Block in the callback | Real-time thread must never block |
| Hold references to the manager | Backends are SPI providers, not manager collaborators |

---

# 8. Threading Model

## 8.1 Thread Inventory

| Thread | Owner | Purpose | Scheduling |
|--------|-------|---------|------------|
| Engine lifecycle | Game engine | `initialize()`, `start()`, `stop()`, `shutdown()` | User-controlled |
| DSP worker (`dynamis-dsp-worker`) | AudioDeviceManager | `renderBlock()` + ring write + hot-swap execution | Platform thread, daemon |
| Platform callback | OS audio subsystem | Ring buffer read → output segment fill | OS real-time (CoreAudio), or Java platform thread (ALSA write thread, WASAPI render thread) |
| Platform notification | OS audio subsystem | `DeviceChangeListener.onDeviceChange()` invocation | OS notification thread |

## 8.2 What Each Thread May Do

| Thread | May | Must Not |
|--------|-----|----------|
| Engine lifecycle | Open/close devices, start/stop, shutdown | Block on real-time thread, call renderBlock() |
| DSP worker | Call renderBlock(), write to ring, execute hot-swap, check underrun health | Allocate on hot path, block indefinitely, call manager control methods |
| Platform callback | Read from ring, fill output segment, increment callback counter | Allocate, log, synchronize, throw, do DSP, make policy decisions |
| Platform notification | Set `state = SWAP_PENDING` via volatile write | Open/close devices, allocate significantly, block |

## 8.3 Synchronization Points

| Shared state | Writer | Reader | Mechanism |
|-------------|--------|--------|-----------|
| Ring buffer data | DSP worker | Platform callback | VarHandle acquire/release on writePos/readPos |
| `state` field | Lifecycle thread, notification thread | DSP worker | volatile read/write |
| `callbackCount` | Platform callback | Any (telemetry) | volatile (relaxed, telemetry only) |
| `feederBlockCount`, timing | DSP worker | Any (telemetry) | volatile (relaxed, telemetry only) |

No locks. No CAS. No synchronized blocks. Anywhere.

---

# 9. Cross-Backend Guarantees

These guarantees hold regardless of which backend is active. This is what makes the multi-backend architecture portable.

**G-1: Same callback contract.**
Every backend invokes `AudioCallback.render(MemorySegment, int, int)` to request audio data. The callback implementation (ring buffer read) is identical across all backends.

**G-2: Same lifecycle semantics.**
`AudioDeviceHandle.start()` / `stop()` / `close()` behave identically regardless of backend. Start means callback begins. Stop means callback stops. Close releases all resources.

**G-3: Same failure behavior.**
Device open failure → NullAudioBackend fallback. Hot-swap failure → FAULTED. Underruns → logged, not faulted. These rules do not vary by platform.

**G-4: Same event model.**
`DeviceChangeEvent` subtypes are the same across all backends. The manager handles them identically regardless of source.

**G-5: Same format negotiation.**
All backends accept `AudioFormat` and return negotiated format via `AudioDeviceHandle.negotiatedFormat()`. The manager does not need to know which backend is active to read the result.

**G-6: Same ring buffer topology.**
`DSP worker → ring → callback → output` is the same for all backends. CoreAudio's callback is native; ALSA's is a Java write thread; WASAPI's is a Java event thread. The ring doesn't know or care.

---

# 10. Implementation Checklist (Per Backend)

When implementing a new backend, verify:

- [ ] `isAvailable()` returns false on non-target platforms
- [ ] `enumerateDevices()` returns at least one device when available
- [ ] `openDevice()` creates a fully configured handle in stopped state
- [ ] `AudioDeviceHandle.start()` begins callback invocation
- [ ] `AudioDeviceHandle.stop()` stops callback invocation (not terminal)
- [ ] `AudioDeviceHandle.close()` releases all native resources (idempotent)
- [ ] Callback never throws, never allocates, never logs, never blocks
- [ ] Multi-block callback fill works (hardware may request > blockSize frames)
- [ ] `BackendCapabilities` accurately reports features
- [ ] `META-INF/services` file registered for ServiceLoader
- [ ] `module-info.java` has `provides AudioBackend with ...`
- [ ] Structural tests pass on all platforms
- [ ] Device tests gated with `@EnabledOnOs` for target platform
- [ ] Proving run produces clean audible tone (on target hardware)
