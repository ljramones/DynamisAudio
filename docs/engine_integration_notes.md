# DynamisAudio Engine Integration Notes (Future Session Brief)

Purpose:
- Fast context handoff for integrating DynamisAudio into a host engine without re-deriving contracts from code history.
- This document is Codex-ready: it names seams, invariants, and first validation steps.

## Integration Contract Summary

Host engine must provide:
- `PhysicsSurfaceTagger` implementation for `AcousticWorldProxyBuilder.buildFromPhysicsMesh(...)`.
- `AcousticRoom` implementations with valid:
  - `volumeMeters3()`
  - `surfaceAreaMeters2()`
  - `totalAbsorption(int band)`
  - `dominantMaterialId()`
- Per-frame listener updates for active emitters.
- Asset pipeline that yields decoded PCM float data (preferably 48kHz stereo).

DynamisAudio provides:
- Voice lifecycle and budgeting (`VoiceManager`).
- Per-voice DSP chain (`VoiceNode`) and pool (`VoicePool`).
- Reverb bus and fingerprint-driven reverb automation.
- Ray backend factory with CI fallback (`dynamis.audio.raybackend=brute`).

## Required Startup Wiring

1. Construct managers in dependency order:
- `AcousticSnapshotManager`
- `AcousticEventQueueImpl`
- `MixSnapshotManager`
- `AudioDevice`
- `SoftwareMixer`
- `VoiceManager`

2. Wire seams:
- `mixer.setVoiceManager(voiceMgr)`
- `voiceMgr.setVoicePoolCapacity(mixer.getVoicePool().capacity())`

3. Reverb path:
- Add `FingerprintDrivenReverbNode` (or `SchroederReverbNode`) to `mixer.getReverbBus()`.

## Geometry and Ray Backend

On level load (and on geometry rebuild):
1. Build proxy via `buildFromPhysicsMesh(physicsWorld, tagger)`.
2. Create backend via `AcousticRayQueryBackendFactory.create(physicsWorld, proxy)`.
3. Publish backend to snapshot back-buffer, then `publish()`.

Critical ordering contract:
- Proxy triangle ordering must match physics iteration ordering.
- `DynamisCollisionRayBackend` mapping assumes physics ray hit layer/index matches proxy triangle index.

## Frame Loop Responsibilities

Game thread:
- Update listener position on active emitters.
- Post portal/geometry events to `AcousticEventQueueImpl`.

Audio/DSP thread:
- Call `mixer.renderBlock()` every block.

## Asset Pipeline Assumptions

Current behavior:
- `VoiceNode.setAsset()` accepts any sample rate.
- Non-48k assets are transparently wrapped in `ResamplingAudioAsset` (linear resampler).
- `PcmAudioAsset` is ideal for short SFX and looping one-shots.
- `StreamingAudioAsset` supports reset only when backing channel is `SeekableByteChannel`.

Completion behavior:
- One-shot EOS sets `completionPending` and is drained in `SoftwareMixer` to demote promptly.

## Real-Time Invariants (Do Not Break)

- No allocations on steady-state render path (`renderBlock()` and downstream processing).
- Pre-allocate buffers in `prepare(...)`; never allocate in `processInternal()`.
- Keep stage order in `VoiceNode`:
  - `EarlyReflectionNode -> EqNode -> GainNode -> ReverbSendNode`
- Submit signal to buses via `AudioBus.submitBlock()` only.

## First Integration Validation Checklist

- [ ] `mvn -pl dynamis-audio-test-harness -am test` is green in library repo.
- [ ] First engine boot reaches `mixer.renderBlock()` with no exceptions.
- [ ] Geometry publish path installs backend and returns non-miss hits in a known test scene.
- [ ] Physical voices can be promoted and render non-zero dry output with bound assets.
- [ ] Reverb bus receives send signal and reverb effect output is finite.
- [ ] Portal/fingerprint transitions update reverb parameters without zipper noise.

## Troubleshooting Anchors

If no audible output:
- Verify asset bound to promoted voice.
- Verify voice pool capacity and promotion counts.
- Verify no silent attenuation from EQ/gain/send values.

If wrong acoustics:
- Verify `PhysicsSurfaceTagger` metadata mapping.
- Verify room registration and `surfaceAreaMeters2()` correctness.
- Verify proxy rebuild and snapshot publish timing after geometry changes.

References:
- `dynamis-audio/README.md` (module map + integration checklist)
- `docs/architecture_record.md` (seams, invariants, phase decisions)
