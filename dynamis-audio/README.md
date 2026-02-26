# DynamisAudio

AAA-competitive spatial audio engine for the Dynamis World Engine.
Simulation-first, designer-controllable, physically coherent.
Built on JDK 25 with Loom virtual threads and Panama FFM.

---

## Module Map

```
dynamis-audio-api          ← interfaces, constants, value types (no dependencies)
    ↑
dynamis-audio-simulation   ← ray backends, reverb estimators, fingerprint builders
    ↑
dynamis-audio-core         ← emitter lifecycle, voice manager, snapshot manager
    ↑
dynamis-audio-dsp          ← mixer, bus graph, DSP nodes, voice chain
    ↑
dynamis-audio-designer     ← RTPC, mix snapshots, hot reload, fingerprint registry
    ↑
dynamis-audio-test-harness ← integration tests only (never shipped)
```

Dependency direction is strictly upward. No module may import from a module
above it in this list. API seam interfaces (`MixBusControl`,
`EarlyReflectionSink`, `VoiceCompletionListener`) break any potential
cross-layer cycles at the `api` boundary.

External dependencies:
- `io.vectrix:vectrix` — SIMD-optimised math (all modules)
- `io.dynamis:meshforge` — mesh processing (`simulation`, `provided`)
- `org.dynamisphysics:dynamis-physics-api` — BVH ray cast (`simulation`, `provided`)

---

## Real-Time / Zero-Allocation Contract

The DSP render thread — `SoftwareMixer.renderBlock()` and every method it
calls — must never allocate on the heap during steady-state operation.

**Enforced by CI:**
- `AllocationEnforcementTest` instruments the render path and fails if any
  allocation escapes after `prepare()`.

**Rules:**
- All buffers sized in `prepare(maxFrameCount, channels)`, never in
  `processInternal()`.
- `VarHandle` acquire/release for snapshot and parameter double-buffers.
- Pre-allocated ping-pong buffers in `VoiceNode` — two buffers, alternating
  per DSP stage.
- Loom virtual threads handle emitter lifecycle and ray queries off the
  render thread.
- `AcousticEventQueueImpl` is a lock-free ring buffer; drain is called once
  per `renderBlock()`.

**Permitted off render-thread:**
- `AcousticFingerprintBuilder.build()` — called at room entry.
- `AcousticWorldProxyBuilder.build()` / `buildFromPhysicsMesh()` — called
  on `GeometryDestroyedEvent`.
- `VoicePool` construction — called once at engine startup.
- `ResamplingAudioAsset` constructor — allocates intermediate buffer once
  at asset bind time.

---

## Build and Test

Requires JDK 25, Maven 3.9+.

### Full build and test

```bash
mvn -pl dynamis-audio-test-harness -am test
```

### Per-module compile gates (in dependency order)

```bash
mvn -pl dynamis-audio-api        -am compile
mvn -pl dynamis-audio-simulation -am compile
mvn -pl dynamis-audio-core       -am compile
mvn -pl dynamis-audio-dsp        -am compile
mvn -pl dynamis-audio-designer   -am compile
mvn -pl dynamis-audio-test-harness -am test
```

### Force brute-force ray backend (CI without physics world)

```bash
mvn -pl dynamis-audio-test-harness -am test \
    -Ddynamis.audio.raybackend=brute
```

### Phase test counts (authoritative at close of each phase)

| Phase | Count |
|-------|-------|
| 0     | 87    |
| 1     | 188   |
| 2     | 248   |
| 3     | 292   |
| 4     | 319   |
| 5     | 338   |
| 6     | 358   |
| 7     | 377   |

---

## Integration Handoff — Dynamis World Engine

The following wiring is required in the game engine repo before audio plays.

### 1. Engine startup

```java
// Construct in dependency order
AcousticSnapshotManager  acousticMgr  = new AcousticSnapshotManager();
AcousticEventQueueImpl   eventQueue   = new AcousticEventQueueImpl(256);
MixSnapshotManager       mixMgr       = new MixSnapshotManager();
AudioDevice              device       = AudioDeviceFactory.create(sampleRate, 2, blockSize);
SoftwareMixer            mixer        = new SoftwareMixer(acousticMgr, eventQueue, device, mixMgr);
VoiceManager             voiceMgr     = new VoiceManager(
                                            AcousticConstants.DEFAULT_PHYSICAL_BUDGET,
                                            AcousticConstants.DEFAULT_CRITICAL_BUDGET);

// Wire cross-references
mixer.setVoiceManager(voiceMgr);
voiceMgr.setVoicePoolCapacity(mixer.getVoicePool().capacity());

// Add SchroederReverbNode or FingerprintDrivenReverbNode to reverb bus
FingerprintDrivenReverbNode reverbNode = new FingerprintDrivenReverbNode("main-reverb");
mixer.getReverbBus().addEffect(reverbNode);
```

### 2. Geometry load

```java
// Build proxy from physics mesh — call once per level load or on GeometryDestroyedEvent
AcousticWorldProxy proxy = new AcousticWorldProxyBuilder()
    .buildFromPhysicsMesh(physicsWorld, (bodyId, triIdx,
                                         ax, ay, az,
                                         bx, by, bz,
                                         cx, cy, cz) -> {
        // Map physics material/layer to acoustic surface metadata
        // Return null to skip non-acoustic surfaces (triggers, sensors, etc.)
        return myGameAcousticTagger.tag(bodyId, triIdx,
                                        ax, ay, az, bx, by, bz, cx, cy, cz);
    });

// Publish to audio snapshot — game thread
AcousticWorldSnapshotImpl back = acousticMgr.acquireBackBuffer();
back.setRayQueryBackend(
    AcousticRayQueryBackendFactory.create(physicsWorld, proxy));
acousticMgr.publish();
```

**Triangle ordering contract:** `buildFromPhysicsMesh()` enumerates triangles
in physics iteration order. `DynamisCollisionRayBackend` uses
`RaycastResult.layer()` as the proxy triangle index. Both must use the same
ordering — do not sort or reindex triangles between build and backend
construction.

### 3. Per-frame main loop

```java
// Game thread: update listener position on all active emitters
for (LogicalEmitter emitter : voiceManager.emitters()) {
    emitter.setListenerPosition(listener.x, listener.y, listener.z);
}

// Game thread: post acoustic events (portal state changes, geometry updates)
eventQueue.post(new PortalStateChanged(portalId, newAperture));

// DSP thread (dedicated thread or engine audio callback):
mixer.renderBlock(); // drives everything — events, snapshots, voice chain
```

### 4. Playing a sound

```java
// Load asset (pre-converted to 48kHz — PHASE 7: resampling handles other rates)
PcmAudioAsset asset = new PcmAudioAsset(myDecoder.decodeToFloat(), 2);

// Trigger via EventSystem (handles emitter creation, RTPC, voice registration)
EventSystem events = new EventSystem(voiceManager);
events.registerEvent(new SoundEventDef.Builder("explosion")
    .gain(0.9f).looping(false).build());
LogicalEmitter emitter = events.trigger("explosion", x, y, z);

// Bind asset to the voice once promoted to PHYSICAL
// (VoiceManager promotion is automatic — asset binding is your responsibility)
VoiceNode voice = mixer.getVoicePool().voices()[emitter.voiceIndex()];
voice.setAsset(asset);
```

### 5. Checklist before first audio output

- [ ] `PhysicsSurfaceTagger` implemented and tested with your material ID scheme
- [ ] `AcousticRoom` implementations registered in snapshot for all rooms
      (provide `volumeMeters3()`, `surfaceAreaMeters2()`, `totalAbsorption(band)`)
- [ ] `AcousticFingerprintBuilder` called on room entry;
      fingerprints registered in `AcousticFingerprintRegistry`
- [ ] `FingerprintDrivenReverbNode.setFingerprint()` called when listener
      changes room
- [ ] `AcousticFingerprintRegistry.unregister(roomId)` called before proxy
      rebuild on `GeometryDestroyedEvent`
- [ ] `VoiceManager.setVoicePoolCapacity()` called after mixer construction
- [ ] Audio device opened before first `mixer.renderBlock()` call
- [ ] All `PcmAudioAsset` sources pre-decoded to 48kHz stereo float PCM
      (or rely on `ResamplingAudioAsset` wrapping for other rates)
- [ ] `generateStubTone()` is removed — confirm voices render silence
      without a bound asset, not a test tone

---

## Architecture Record

Full design decisions, rationale, and per-phase constraints are in:
```
docs/architecture_record.md
```

Read this before making any changes to the snapshot double-buffer pattern,
the DSP node lifecycle, the module dependency graph, or the voice promotion
model. Every non-obvious decision is documented there with the reasoning that
would otherwise require reconstructing from git history.
