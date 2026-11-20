# Preserve pending delivery across input changes and concurrent flushes

**Audience:** Jair and the frontend integration assistant. Fix the existing queue lifecycle and drain coordination, not the backend or the delivery architecture. Use the existing frontend test runner and HTTP doubles. This guide is a handoff, not evidence that the corrections or tests below have been executed.

## Verified baseline and scope

Read-only comparison on 2026-11-20:

- Frontend: `eb1fe55703053d5ba338c46c4b50d77ff78670d0`.
- Backend: `8a2ae12309003fdb19032e550d4b79a114fc67b0`.
- Frontend sources below are relative to the frontend checkout, not this backend repository.

The source supports RG-02 (queue replacement) and RG-03 (concurrent Telemetry drains). RG-01's low-frequency acquisition failures were reported by the frontend audit, not independently executed in this comparison. Preserve its 20 held-observation protection cases; do not weaken freshness checks as a queue fix.

**Out of scope:** backend changes, new endpoints or payload fields, hash migrations, IndexedDB/service workers, durable delivery infrastructure, a new test framework, Docker, cloud access, or real patient data.

## 1. Keep queues alive for the same session — RG-02

Source: `src/presentation/features/serious-games/components/EyeTrackingGameCanvas.tsx`, initialization effect around lines 432–510. It constructs both queues and depends on `mouseFallbackEnabled`; switching input replaces the active references without transferring pending entries.

Minimum correction:

1. Separate input/freshness resets from queue ownership. Keep one existing Telemetry queue and one trial queue for the same session lifetime; switching webcam/mouse must not recreate or reset them.
2. Preserve queued bodies and in-flight requests unchanged. Apply the current input-origin convention only to newly formed results, never rewrite a queued result's `dataOrigin` or reuse its `trialId` for changed content.
3. Keep the existing stream rules. If input switching requires stream rotation, rotate using the existing queue operation without dropping already formed batches or resetting their identities.
4. Make session replacement/teardown explicit. Do not carry old-session entries into a different session or silently clear unresolved entries and report synchronization success. Durable recovery after reload remains outside this MVP correction.

Resetting the gaze guard when input changes can remain appropriate; it does not require replacing delivery queues.

## 2. Serialize Telemetry drains — RG-03

Source: `src/infrastructure/telemetry/telemetryBatchQueue.ts`, `flush()` around lines 357–374 and `drainLoop()` around 435–451. Multiple callers can await the same old drain, then start new drains without rechecking ownership. Multiple successful requests can also increment the broker counter for the same logical batch.

Minimum correction:

- Give the existing queue one serialized drain owner. Concurrent `flush()` callers must join or serialize behind it; after waiting, recheck ownership before starting another drain.
- Release ownership safely on success and failure. Keep the existing bounded retry/backoff policy; do not introduce unlimited retries.
- Handle ACKs by batch identity, not by removing whichever item happens to be first. Count each acknowledged logical batch once in the client counter.
- Preserve the complete body on every attempt, including `sentAt`, samples, order and sequence. Refresh authentication independently of the body.

`TrialAnalyticsQueue` already serializes delivery through `deliveryChain`; do not replace it with another concurrency mechanism. Repeated `submit()` calls can still create duplicate client entries: ensure the producer submits one logical trial once and uses the existing pending-entry retry path. Do not generate another `trialId` to hide an uncertain outcome.

Two HTTP `202` responses are two broker acknowledgements, not proof of two database records. Fixing client mutual exclusion does not establish exactly-once processing.

## 3. Make incomplete synchronization visible

Current `TrialAnalyticsQueue.hasUnresolved` includes PENDING/UNCERTAIN but excludes FAILED_PERMANENT. The normal completion gate checks that flag and pending Telemetry batches, not permanent failures. Telemetry can also drop new chunks when its capacity is exceeded.

Keep these distinctions visible to the user and in safe test assertions:

| Observation | Meaning / minimum handling |
|---|---|
| PENDING or UNCERTAIN | Not confirmed; retain the existing normal-close gate and retry path. |
| FAILED_PERMANENT | Explicitly rejected, not synchronized. Surface the failure; do not silently label all results saved or automatically retry unchanged permanent errors. |
| Telemetry overflow/permanent rejection | Data may be missing even if the current queue is empty. Do not label an empty queue as complete delivery. |
| Request never settles | No successful receipt exists. Do not manufacture success or automatically close because a local wait elapsed. |
| Telemetry `202 BROKER_ACCEPTED` | Kafka publication acknowledged, not TimescaleDB/Analytics persistence. |

**Product decision still pending:** whether ordinary Clinical completion may proceed with permanently rejected/dropped analytics, or requires an explicit incomplete-data acknowledgement. This guide does not silently select that policy or change backend completion rules. A bounded request deadline/cancellation policy also needs an explicit design; an aborted request can have an uncertain server outcome.

The current client is in-memory/best-effort, not a durable delivery guarantee. Do not describe `PENDING_SYNC` as proof that every sample is eventually stored.

## 4. Backend compatibility to preserve

| Contract | Current behavior |
|---|---|
| Telemetry retry | Keep the entire original body. Timescale persistence excludes `sentAt` from its hash; Analytics includes it. The frontend baseline now preserves it. |
| Trial identity | Stored identity is session + `trialId`; identical content confirms the original, changed content conflicts. Origin is content, not a separate identity. |
| Semantic results after closure | Fresh Clinical authorization still applies. Identical already-stored results can return ALREADY_STORED; missing results cannot be inserted after closure. |
| Telemetry after closure | New HTTP requests, including exact replays, require an ACTIVE, unexpired session and can be rejected after closure. |
| Origin | SYNTHETIC and PRODUCER_REPORTED are accepted. Mouse/mixed → SYNTHETIC is a compatible producer convention, not device attestation. Neither enum proves physical webcam use. |

Do not close first and assume unsaved results can be uploaded later. A lost response may hide a completed write; preserve its identity/body when confirming it.

### Nine Systematic Exploration bodies

The nine bodies in the frontend audit are **statically compatible**, conditional on frozen `minimumFixationMs=300` and `maximumStimulusDurationMs=2500`. Every visit uses `timeToVisitMs=300` and `confirmedDwellMs=300`, in the canonical prefix TOP_LEFT → TOP_RIGHT → BOTTOM_LEFT → BOTTOM_RIGHT.

| Outcome | Visit counts | Corresponding elapsedMs |
|---|---|---|
| COMPLETED | 4 | 1200 |
| TIMED_OUT | 0, 1, 2, 3 | 2500, 2800, 3100, 3400 |
| INTERRUPTED | 0, 1, 2, 3 | 230, 530, 830, 1130 |

Times are relative to each quadrant activation. Java subtracts the sum of visit times from elapsedMs: completion requires zero remainder; timeout requires one full quadrant deadline; interruption requires a remainder below that deadline. Use the actual frozen session configuration, not hard-coded fixture values. No Java or HTTP acceptance test of these bodies was executed in this review.

## Minimum regression evidence to return

Use the actual queue and lifecycle path with controlled HTTP promises, not a replacement implementation. These are requested checks, not existing passing results:

- [ ] Pending Telemetry and trial entries survive webcam → mouse → webcam in the same session, including an in-flight request; their bodies/identities remain unchanged.
- [ ] Several simultaneous `flush()` calls after one failed drain produce at most one in-flight send per logical batch, preserve unrelated entries and count each acknowledged batch once.
- [ ] A failed send remains retryable under the existing bounded policy; an ACK removes only its matching batch; a new batch arriving during a drain is not lost.
- [ ] The existing full-body retry checks, including stable `sentAt`, remain green. See [retry-queue-check.md](retry-queue-check.md).
- [ ] Permanent rejection and overflow remain visible when pending counts reach zero; the interface does not claim complete synchronization.
- [ ] Pending/uncertain requests do not permit normal completion; a never-settling request is not treated as successful. Confirm the separate incomplete-data policy before changing permanent-failure completion behavior.
- [ ] Different-session initialization does not send old entries under the new session; preserve existing freshness/held-observation protections.

Return the new frontend commit, changed file/functions, exact existing test command, observed results and remaining policy decisions. Do not include tokens, real identifiers, coordinates, raw request bodies or HAR exports. Backend contract execution and authenticated persistence tests require separate authorization; do not repeat prior demos to close these client corrections.
