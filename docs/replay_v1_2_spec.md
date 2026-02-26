# Replay v1.2 Spec

## Status
- Version: `v1.2`
- Type: behavioural contract + implementation/test split
- Scope owner: replay pipeline (`packet`, `recorder`, `runner`, backend resolvers, replay tests)

## Goal
Formalize replay validation semantics and remove reflection-based stable body-ID lookup in the recorder.

## Deliverables

### 1. ValidationMode in packet

Add enum:
- `STRICT`
- `BEHAVIOURAL`

Add field on `ReproPacket`:
- `validationMode`

Defaulting rule:
- deterministic tuning => `STRICT`
- otherwise => `BEHAVIOURAL`

Packet compatibility:
- If absent in older packets, infer with the same defaulting rule.

### 2. Runner supports both modes

Runner must branch on `ReproPacket.validationMode`.

`STRICT` mode:
- checkpoint hash mismatch => fail immediately
- final hash mismatch => fail

`BEHAVIOURAL` mode:
- checkpoint/final hash mismatch => log warning, do not fail on hash alone
- enforce invariants at checkpoint/final:
  - sampled state contains no `NaN`/`Inf`
  - `minY > -10` (default threshold; configurable later)
  - body count unchanged unless packet explicitly allows drift
  - optional bounds (if present in packet config):
    - position bounds
    - velocity bounds

Behavioural failure policy:
- invariant violation => fail test/run
- hash mismatch only => pass with warning telemetry

### 3. Remove reflection from ID capture

In `dynamisphysics-test`, add test-visible interface:
- `StableBodyIdHandle { int bodyId(); }`

Implement on:
- `Ode4jBodyHandle`
- `JoltBodyHandle`

Recorder contract:
- use typed path (`instanceof StableBodyIdHandle`) for stable ID capture
- remove reflection fallback for body-ID extraction

### 4. Resolver cleanup

Keep backend resolvers for lookup by stable body ID.

Error behavior:
- return clear unsupported-handle errors for unresolved handle categories:
  - vehicle
  - character
  - ragdoll
- do not silently skip unsupported types

### 5. Tests split

Create:
- `ReplayStrictRoundTripTest`
  - ODE4J + Jolt deterministic (`threads=1`)
  - strict hash-match required
- `ReplayBehaviouralRoundTripTest`
  - Jolt PERF (`threads=8`)
  - behavioural assertions only

Gate:
- keep existing replay gate
- include both test suites

## Commit Split

### 1. `feat(replay): add validation modes and typed stable-id handle path`
- packet field + enum
- runner mode handling
- `StableBodyIdHandle` + handle implementations
- recorder reflection removal

### 2. `test(replay): split strict vs behavioural roundtrip suites`
- strict test class
- behavioural test class
- replay gate script update
- README replay semantics section

## Gates
- `./scripts/gate-replay.sh`
- `./scripts/gate-jolt.sh`
- optional: `./scripts/gate-long-determinism.sh`

## Acceptance Criteria
- Strict replay passes on ODE4J and Jolt deterministic mode.
- Behavioural replay passes on Jolt multithread PERF mode.
- Recorder no longer uses reflection for body IDs.
- Replay docs clearly define strict vs behavioural semantics.

## Implementation Notes
- Keep behavioural invariants deterministic and cheap; no allocation-heavy checks in hot loops.
- Emit mode + mismatch/invariant diagnostics in replay output for CI triage.
- Preserve reproducibility metadata in packet (seed/tuning/profile) to make strict failures actionable.
