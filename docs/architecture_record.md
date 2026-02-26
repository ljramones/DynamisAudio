# Architecture Record

## 2026-02-25: Mix Snapshot Dependency Inversion (`MixBusControl`)

Decision:
- `AudioBus` implements `MixBusControl`.
- `MixSnapshotManager` depends on `MixBusControl` (API interface), not `AudioBus` (DSP concrete type).

Rationale:
- Enforces clean module direction: `designer -> api <- dsp`.
- Removes direct `designer -> dsp` coupling and avoids cyclic dependency pressure.
- Establishes a reusable seam pattern for future cross-layer integrations (for example HRTF and simulation control surfaces).

Impact:
- `MixSnapshotManager` bus registry and update path operate on `MixBusControl`.
- `AudioBus` remains the runtime implementation used by `SoftwareMixer`.

## 2026-02-26: Early Reflection Dependency Inversion (`EarlyReflectionSink`)

Decision:
- `EarlyReflectionNode` implements `EarlyReflectionSink`.
- `LogicalEmitter` depends on `EarlyReflectionSink` (API interface), not `EarlyReflectionNode` (DSP concrete type).

Rationale:
- Preserves module direction: `core -> api <- dsp`.
- Avoids introducing `core -> dsp` coupling for reflection tap updates.
- Reuses the same seam pattern established by `MixBusControl`.

Impact:
- Virtual-thread reflection ray fan updates are published through an API seam.
- DSP reflection implementation can evolve independently without core-layer rewiring.

## 2026-02-26: AudioBus Dynamic Prepare on Runtime Graph Mutation

Decision:
- `AudioBus.addSource()` and `AudioBus.addEffect()` immediately call `prepare(...)`
  on newly added nodes when the bus is already prepared.

Rationale:
- Runtime graph mutation must be production-safe: adding an effect/source after
  initial `prepare()` must not leave the new node unprepared.
- Unprepared nodes on an active bus can silently output zeros, causing audible
  correctness failures during live graph updates.

Impact:
- Effects and source buses added at runtime are immediately usable.
- Test and production behavior now match for dynamic graph editing paths.

## 2026-02-26: Phase 4 Acoustic Fingerprinting

Decision:
- `AcousticFingerprint` is an API-layer immutable value type in `dynamis-audio-api`.
- `FingerprintBlender.MutableAcousticFingerprint` is the only mutable fingerprint type and is used only as blend scratch.
- `FingerprintBlender` uses logarithmic interpolation for room volume and surface area.
- `FingerprintDrivenReverbNode` smooths fingerprint-driven parameter changes with `SMOOTH_COEFF = 0.025f`.
- `SchroederReverbNode` is non-final to allow extension by `FingerprintDrivenReverbNode`.
- `dynamis-audio-dsp` now depends on `dynamis-audio-simulation`.
- `AcousticFingerprintRegistry` lookup priority is `override > computed`.

Rationale:
- `AcousticFingerprint` in `api` provides a compact cross-module descriptor with no simulation dependency leakage; constructor performs defensive array copies to enforce immutability.
- Mutable blend state is explicit at call sites: `MutableAcousticFingerprint` must not be stored behind an `AcousticFingerprint` reference.
- Perceptual interpolation for room scale uses ratios, not linear deltas:
  linear midpoint between `10m^3` and `1000m^3` is `505m^3`, while logarithmic midpoint is `100m^3` (geometric mean), which better matches perceived transition.
- Reverb parameter smoothing prevents zipper noise during room transitions; expected convergence lag is ~5ms.
- Hard scene cuts (for example teleport) require explicit `reset()` on `FingerprintDrivenReverbNode` to avoid residual smoothing tail.
- New dependency edge is acyclic: `dsp -> simulation -> api` and `simulation -> api` (no cycle introduced).
- Registry priority allows designer overrides without geometry/proxy rebuild work; `clearOverride()` restores computed fingerprints.

Constraints:
- `SchroederReverbNode.processInternal()` is not sealed; subclasses must call `super.processInternal()` after parameter automation to preserve signal processing.
- `AcousticFingerprintRegistry.unregister()` should be called before proxy rebuild on `GeometryDestroyedEvent`; keep this on Phase 5 event wiring checklist.

Impact:
- Fingerprint data can be shared safely across modules while blend-time mutability remains explicit and localized.
- Room transition automation is perceptually smoother and artifact-resistant by default.
- DSP layer can consume blended fingerprints directly with no reflection or dynamic type checks.

## 2026-02-26: Phase 5 Full Per-Voice Signal Chain Wiring

Decision:
- `VoiceNode` uses a two-buffer ping-pong processing pattern sized in `prepare(maxFrameCount, channels)`.
- Per-voice processing order is fixed: `EarlyReflectionNode -> EqNode -> GainNode -> ReverbSendNode`.
- Dry output is routed to the SFX path; reverb-send output is routed to the reverb bus path.
- `VoicePool` remains fixed-capacity and allocation-free on acquire/release.
- `AudioBus.submitBlock()` is the required signal injection point for source accumulation.
- Per-band MFP is documented as `MFP(band) = 4V / (S * (1 - scattering(band)))` with scattering clamped to `[0, 0.9999]`.
- `buildFromPhysicsMesh()` preserves triangle ordering by `(bodyId, triangleIndex)` in physics iteration order.
- Phase 5 keeps a mixer stub tone source for integration (`generateStubTone()` at `0.01f`).

Rationale:
- Ping-pong buffers keep `VoiceNode.renderBlock()` allocation-free while preserving explicit stage boundaries.
- Node ordering is acoustically constrained: reflections must be synthesized before occlusion EQ and gain/send stages; reordering breaks physical intent.
- Pool exhaustion behavior (`acquire() == null`) makes budget pressure explicit and prevents hidden allocations under load.
- Centralizing bus accumulation behind `submitBlock()` prevents unsafe direct writes to bus internals and keeps source mixing semantics consistent.
- Per-band MFP uses `AcousticRoom.dominantMaterialId()` as the Phase 5 lookup source; per-surface weighted scattering is deferred.
- Physics-to-proxy index stability is required because `DynamisCollisionRayBackend.layer()` is consumed as proxy triangle index.
- Stub tone keeps the voice chain integration testable before asset streaming lands.

Constraints:
- Future `VoiceNode` extensions must preserve the core stage order unless acoustic behavior changes are intentional and documented.
- `VoiceManager` must not promote more emitters than pool capacity; current enforcement is implicit via budget sizing.
  Phase 6 must add an explicit assertion for capacity invariants.
- `AudioBus` internal accumulation buffers are write-protected by contract; all sources must enter via `submitBlock()`.
- `buildFromPhysicsMesh()` ordering contract and `DynamisCollisionRayBackend.layer()` mapping must be updated together if physics enumeration semantics change.
- `SoftwareMixer.generateStubTone()` is temporary.
  `// PHASE 6: remove stub tone` remains on the Phase 6 checklist when real PCM streaming is wired.

Impact:
- Phase 5 connects the per-voice DSP path end-to-end with zero allocation in steady-state rendering.
- Voice routing semantics are now explicit and enforceable at architecture level (dry vs reverb path split).
- Future streaming integration has a defined replacement seam (`generateStubTone()`).

## 2026-02-26: Phase 6 Asset Streaming Pipeline

Decision:
- `AudioAsset` is the API-layer playback interface and defines a caller-owned buffer contract for `readFrames()`.
- `PcmAudioAsset` performs a defensive copy at construction; `StreamingAudioAsset` allocates a single staging `ByteBuffer` at construction (`DSP_BLOCK_SIZE * channels * 4` bytes).
- `VoiceNode.setAsset()` enforces `AcousticConstants.SAMPLE_RATE` (48kHz) at bind time.
- End-of-stream behavior is split by emitter mode:
  looping emitters call `asset.reset()` and continue;
  one-shot emitters render zeros and wait for budget-cycle demotion.
- `VoiceManager.setVoicePoolCapacity(int)` is the cross-module capacity seam (primitive, no `core -> dsp` type dependency).
- `StreamingAudioAsset.reset()` remains a deliberate no-op for non-seekable channels.
- Phase 6 removes stub-tone usage from the active voice path.

Rationale:
- `AudioAsset.readFrames()` must be zero-allocation on the render path; ownership of output buffers stays with `VoiceNode`.
- Bind-time sample-rate rejection is fail-fast and attributable. Render-time detection would allow at least one corrupted block to hit the device.
- Looping/one-shot EOS split keeps behavior deterministic while completion signaling is still deferred.
- Primitive capacity seam preserves module direction and avoids introducing dependency cycles.
- Non-seekable stream rewind is not generally possible; a no-op reset with explicit warning is safer than pretending loop support exists.

Constraints:
- `AudioAsset` implementations must not allocate in `readFrames()` after construction-time setup.
- 48kHz mismatch remains a hard error in `VoiceNode.setAsset()`.
  `// PHASE 7: runtime resampling` marks planned relaxation.
- One-shot EOS demotion is not immediate:
  up to one `evaluateBudget()` interval of silence can occur before demotion.
  Phase 7 should add an explicit completion callback to remove this tail gap.
- `VoiceManager` trims effective promotion pressure to available configured capacity and logs when candidates exceed capacity.
  This is an operational signal to adjust `AcousticConstants.DEFAULT_PHYSICAL_BUDGET`.
  Trim behavior is non-fatal: lower-priority candidates are deferred to later budget cycles.
- `StreamingAudioAsset` is currently unsuitable for `looping = true` emitters unless callers recreate it from a fresh channel at EOS.
  `// PHASE 7: SeekableByteChannel` marks the planned fix.
- `generateStubTone()` is no longer part of the active render source path and should be removed in cleanup to avoid integration ambiguity.

Impact:
- Real PCM assets now drive voice rendering with no steady-state render allocations.
- Sample-rate contract violations are caught early at binding boundaries.
- Capacity control between budget policy and available voice pool is explicit and cycle-safe.
