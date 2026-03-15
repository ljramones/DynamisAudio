# DynamisAudio Architecture Boundary Ratification Review

Date: 2026-03-11

## 1. Intent and Scope

DynamisAudio should be the bounded subsystem for audio rendering/runtime execution: playback, mixing, voice lifecycle, and spatial audio evaluation from externally provided world/listener/emitter data.

DynamisAudio should own:
- audio playback/runtime control
- voice/channel lifecycle and budgeting
- mixing and DSP processing
- emitter/listener audio evaluation
- spatialization parameter application
- backend/device abstraction for audio output
- audio asset/runtime consumption
- audio event intake and scheduling within the audio domain

DynamisAudio should not own:
- gameplay event authority
- authoritative world transform ownership
- simulation execution authority
- world mutation authority
- world lifecycle orchestration authority
- input/UI authority

## 2. Repo Overview (Grounded)

Repository structure (`DynamisAudio/pom.xml`) is an 8-module Maven build:
- `dynamis-audio-api`: contracts/value types (`AcousticWorldSnapshot`, `AcousticEventQueue`, `AudioAsset`, `MixBusControl`, `EarlyReflectionSink`, etc.)
- `dynamis-audio-core`: emitter lifecycle/runtime coordination (`LogicalEmitter`, `VoiceManager`, `AcousticSnapshotManager`, `AcousticEventQueueImpl`)
- `dynamis-audio-dsp`: mixer graph and output runtime (`SoftwareMixer`, `AudioBus`, DSP nodes, `VoicePool`, device adapters)
- `dynamis-audio-simulation`: acoustic query/proxy/reverb estimation and physics/collision integration seams (`DynamisCollisionRayBackend`, `AcousticWorldProxy*`, collision bootstrap/factory)
- `dynamis-audio-designer`: authoring/runtime control surfaces (`SoundEventDef`, `EventSystem`, `RtpcRegistry`, `MixSnapshotManager`, `HotReloadManager`)
- `dynamis-audio-music`: currently module shell (no main Java implementation sources found)
- `dynamis-audio-procedural`: currently module shell (no main Java implementation sources found)
- `dynamis-audio-test-harness`: integration/unit test module

Public API and runtime/backend abstractions:
- API layer is explicit via `dynamis-audio-api` with seam interfaces used across modules.
- Device abstraction is explicit in `dynamis-audio-dsp.device` (`AudioDevice`, `AudioDeviceFactory`, `PanamaAudioDevice`, `NullAudioDevice`).
- World-facing abstraction uses read-only snapshot/query contracts (`AcousticWorldSnapshot`) and audio-local ring intake (`AcousticEventQueue`).

Notable entry points and implementation areas:
- `SoftwareMixer.renderBlock()` drives render-thread processing.
- `VoiceManager` governs virtual/physical voice budgeting.
- `EventSystem.trigger(...)` is the named sound-event authoring surface.
- `AcousticRayQueryBackendFactory` and `DynamisCollisionRayBackend` bridge to physics queries.
- `PhysicsPreferredCollisionWorldFactory` and `AudioSimulationCollisionWorldBootstrap` provide collision-world composition helpers.

## 3. Strict Ownership Statement

DynamisAudio should exclusively own:
- playback/mixing/runtime audio processing
- audio backend abstraction and device output control
- voice management and prioritization within audio budgets
- spatialization and acoustic effects processing from provided world/listener data
- audio asset playback integration (streaming/PCM/runtime buffering)
- audio-local scheduling/buffering (render block cadence, event ring drain)
- audio authoring runtime surfaces (RTPC/mix snapshots/hot-reload) scoped to audio behavior
- audio diagnostics/monitoring surfaces tied to audio runtime health

## 4. Explicit Non-Ownership

DynamisAudio must not own:
- world lifecycle orchestration
- authoritative world transform truth
- ECS ownership/lifecycle
- physics stepping authority
- collision substrate authority
- gameplay rule/state execution
- global event-system ownership/routing authority
- persistence/session authority
- rendering/GPU authority
- AI authority
- input authority

DynamisAudio must not become a hidden gameplay or simulation controller.

## 5. Dependency Rules

Allowed dependency patterns:
- content/asset definitions for audio assets and authoring data
- bounded event intake interfaces for audio-relevant events
- listener/emitter/world data views via read/query interfaces
- physics/collision query interfaces strictly for acoustic sampling
- backend/platform libraries for audio output devices

Forbidden dependency patterns:
- direct world orchestration ownership
- direct simulation mutation or transform commit authority
- direct physics/collision ownership beyond bounded query consumption
- direct gameplay execution ownership
- persistence/session ownership
- hidden dependency on unrelated rendering/runtime subsystems beyond explicit integration seams

Repo-grounded observations:
- Core audio behavior remains centered in `api/core/dsp` contracts.
- `dynamis-audio-simulation` has direct static dependencies on collision/physics APIs and includes collision-world assembly helpers (`PhysicsPreferredCollisionWorldFactory`, `AudioSimulationCollisionWorldBootstrap`), which increases boundary pressure with physics/collision ownership.
- No direct ECS/WorldEngine/SceneGraph module dependency was found in code imports.

## 6. Public vs Internal Boundary Assessment

Public/internal split is functional but broad.

Findings:
- JPMS exports one broad package per module (`exports org.dynamisengine.audio.*`) rather than narrowly exposing API-only packages.
- Concrete implementation classes are publicly consumable across layers (for example `org.dynamisengine.audio.core` and `org.dynamisengine.audio.dsp` exports include runtime implementation types).
- DSP module exports device implementation package directly (`org.dynamisengine.audio.dsp.device`), including concrete backends.

Assessment: architecture includes explicit seam interfaces, but implementation leakage remains moderate because module exports are broad and concrete runtime types are part of the external surface.

## 7. Authority Leakage or Overlap

Overall: no major gameplay/world mutation authority leak found, but there is notable overlap pressure in physics/collision composition responsibilities.

Observed clean boundaries:
- Audio world integration is primarily read/query oriented (`AcousticWorldSnapshot`, ray backends, event intake).
- No direct evidence of DynamisAudio mutating authoritative world state, owning ECS lifecycle, or controlling WorldEngine lifecycle.
- Event surfaces are audio-domain focused (named sound event triggering and audio parameter application), not a general engine event bus implementation.

Overlap/leakage pressures:
- Physics/Collision overlap:
  - `dynamis-audio-simulation` includes direct collision-world creation/composition helpers and runtime assembly-mode policy (`PhysicsPreferredCollisionWorldFactory`, `AudioSimulationCollisionWorldBootstrap`).
  - This goes beyond pure acoustic query consumption and approaches collision composition policy ownership.
- Event authority naming/scope pressure:
  - `dynamis-audio-designer.EventSystem` is a local audio event trigger/orchestration surface; if expanded carelessly, it could drift toward gameplay event ownership.

Distinction:
- Current implementation primarily consumes listener/emitter/world data to render audio consequences.
- Direct authoritative gameplay/simulation/world mutation ownership was not found.

## 8. Relationship Clarification

- WorldEngine:
  - DynamisAudio should consume world/listener/emitter snapshots from WorldEngine-managed execution flow.
  - DynamisAudio should render audio outcomes only.
  - DynamisAudio should not own world runtime lifecycle.

- ECS:
  - DynamisAudio should consume ECS-derived read models/adapters.
  - DynamisAudio should not own ECS state or mutation authority.

- SceneGraph:
  - DynamisAudio should consume positional/orientation data for listener and emitters.
  - DynamisAudio should not own scene hierarchy or transform truth.

- Physics:
  - DynamisAudio should consume bounded query services (raycasts/metadata) for acoustics.
  - DynamisAudio should not own simulation stepping or physics composition policy.

- Collision:
  - DynamisAudio should consume collision query results via interfaces/backends.
  - DynamisAudio should not own collision-world assembly policy long-term.

- Event:
  - DynamisAudio should intake audio-relevant events and emit audio-local diagnostics/notifications.
  - DynamisAudio should not become global event routing authority.

- Content:
  - DynamisAudio should consume authored assets/RTPC/mix/event definitions.
  - DynamisAudio should not own global content pipeline policy.

- UI/Input:
  - DynamisAudio may consume UI-triggered audio commands.
  - DynamisAudio should not own input/UI authority.

- Animus:
  - DynamisAudio should consume animation-driven cues/parameters where needed.
  - DynamisAudio should not own animation evaluation authority.

- DynamisAI:
  - DynamisAudio should consume AI-generated intent/events as inputs.
  - DynamisAudio should not own AI decision authority.

## 9. Ratification Result

**Boundary ratified with minor tightening recommended**.

Justification:
- The subsystem is strongly centered on audio runtime responsibilities (voice lifecycle, DSP/mixing, device abstraction, acoustic query consumption) and does not show direct authoritative world/gameplay mutation ownership.
- Tightening is recommended because simulation module code currently includes collision-world assembly/policy helpers and broad public exports that can blur subsystem authority lines if expanded.

## 10. Boundary Rules Going Forward

- DynamisAudio must consume listener/emitter/world state through bounded read/query interfaces only.
- DynamisAudio must not own authoritative transform, world, or simulation state.
- Audio-trigger execution must remain audio-domain behavior, not gameplay rule authority.
- Physics/collision integration must remain query-oriented; collision-world composition policy should remain owned by physics/collision subsystems.
- Backend/runtime implementation details should remain internal behind stable audio-facing APIs.
- DynamisAudio must not become a shadow event-routing or world orchestration layer.
- Public module exports should be narrowed toward contract-first surfaces over concrete implementation types.
