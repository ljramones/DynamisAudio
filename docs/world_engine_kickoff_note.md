# Dynamis World Engine Kickoff Note

Purpose:
- Define the first engineering slice for standing up the game engine around existing Dynamis libraries.
- Keep scope narrow: bootstrap loop, subsystem seams, and first playable audio path.

## What Exists Today

Completed and validated in DynamisAudio:
- Full spatial audio pipeline through Phase 7.
- Geometry-driven acoustics, reflections, reverb bus, fingerprinting, voice chain, asset streaming/resampling.
- Stable seams that avoid dependency cycles.

Companion expectation:
- DynamisCollision provides physics world and ray query surfaces.

## Engine Responsibilities

The host engine must provide:
- Main loop ownership and deterministic update phases.
- Entity/component model for listener + emitters.
- Physics world lifetime and geometry iteration.
- Asset loading/decoding pipeline feeding audio assets.
- Event routing for portal/geometry/state changes.

## Initial Vertical Slice (Milestone 1)

Goal:
- Start engine, load one scene, play one 3D one-shot sound with physically coherent occlusion/reverb.

Required tasks:
1. Boot sequence and subsystem init order.
2. Physics scene load and `PhysicsSurfaceTagger` mapping.
3. Acoustic snapshot publish from physics mesh.
4. Listener transform propagation each frame.
5. Emitter trigger path bound to game event.
6. PCM asset decode and bind to promoted voice.
7. Audio callback driving `SoftwareMixer.renderBlock()`.

Exit criteria:
- Audible one-shot with positional change as listener moves.
- No render-path allocations.
- No exceptions across 5+ minutes continuous run.

## Milestone 2 (Room Transitions)

Goal:
- Two connected rooms with distinct fingerprints and smooth portal transition.

Tasks:
- Register rooms and materials.
- Build and register fingerprints.
- Drive `FingerprintDrivenReverbNode` from current/blended room state.
- Validate transition continuity while crossing a portal.

Exit criteria:
- Clear audible difference across rooms.
- No zipper artifacts during transition.

## Non-Negotiable Contracts

- Keep module dependency direction clean; use API seams, not cross-layer imports.
- Preserve proxy triangle ordering contract used by collision backend mapping.
- Route all bus inputs through `submitBlock()`; no direct buffer mutation.
- Keep VoiceManager capacity enforcement explicit and observable.

## Suggested Repo-Level TODO Order

1. Engine skeleton + subsystem lifecycle document.
2. Physics integration + mesh tagging adapter.
3. Audio subsystem adapter (construct + wire DynamisAudio).
4. Asset loader abstraction (decoded PCM source path first).
5. Scene format metadata for acoustic materials/rooms/portals.
6. Tooling hooks for runtime inspection (voice counts, room id, backend mode).

## Operational Notes for Future Sessions

- Use `dynamis-audio/README.md` + `docs/architecture_record.md` from DynamisAudio as source of truth.
- Treat phase markers in architecture record as historical closures; avoid re-opening resolved seams unless required by engine constraints.
- If behavior diverges in engine integration, document mismatch as explicit adapter code rather than mutating core library assumptions first.
