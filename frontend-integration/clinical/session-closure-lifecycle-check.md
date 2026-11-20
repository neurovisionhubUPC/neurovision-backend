# Bind session closure to its original lifecycle

**Audience:** Jair and the frontend integration assistant. Correct LIFE-POST-GATE in the existing frontend closure flow. Preserve the current backend contracts, queue corrections and explicit incomplete-data policy. No new infrastructure or delivery framework is needed.

## Evidence and scope

Read-only source comparison on 2026-11-20: frontend `80342a7ac2737ac4bc88bb6ad8f6e31beb18ba86`, backend `2204ead8cbcd8701dba1ad8fe80287bc76437ef7`. The frontend audit reports a reproduction in both `eb1fe55` and `80342a7`; the backend team's review corroborated the source path but did not execute that reproduction.

Frontend source: `src/presentation/features/serious-games/components/EyeTrackingGameCanvas.tsx`, especially the closure continuation around lines 1080–1148. Paths and line numbers refer to that frontend baseline; locate the equivalent functions if HEAD changes.

The callback can pass the delivery gate for session A, await `getSessionProgress(A)`, then resume after the UI switches to session B. It uses A's captured session ID with the current mutable completion key and can write A's progress/FINISHED state into shared refs and callbacks now serving B. A mounted component is not necessarily the same session lifecycle.

**Out of scope:** backend changes, new HTTP fields, global idempotency-key reservations, queue redesign, durable browser storage, changes to gaze freshness, Docker/cloud execution or repeating PDF demonstrations. This document contains instructions and expected checks, not completed frontend fixes or passing test results.

## Minimum correction

1. At closure entry, capture one immutable operation context: session ID, that session's completion key, and lifecycle generation/controller identity. Reuse the existing session-delivery identity mechanism where possible; do not create another orchestration layer.
2. Keep the completion key stable for retries of that logical closure. Do not read a newly assigned `completionKeyRef.current` after an await or generate a new key to recover an uncertain completion.
3. After each relevant await, check that the captured context is still admitted by the active session lifecycle. This includes waiting for event delivery, the delivery gate, progress retrieval and completion response. A same-ID teardown/restart must also invalidate old work; session ID alone is insufficient.
4. Immediately before sending completion, verify admission and use only the captured session/key. If stale, stop that continuation: do not send completion and do not publish progress, errors, FINISHED state or callbacks into the replacement lifecycle.
5. After completion responds, verify admission again before changing shared refs/UI or invoking parent callbacks. Apply the same guard to `catch` and `finally` updates so an old failure cannot clear a new session's SAVING state or show an unrelated error.
6. Invalidate admission on session replacement and teardown. Preserve the existing single-flight behavior; a second close click while the same logical close is pending must not start a competing operation. Disabling navigation while saving may help UX but is not a substitute for continuation guards.

The identity check and dispatch should have no intervening asynchronous gap. Do not clear current-session queues as part of rejecting an old continuation.

## Requests already sent are different

Once a completion request has been dispatched, switching sessions or aborting a browser request cannot guarantee that Clinical did not commit it. Ignore a stale response for the new lifecycle, but do not describe this as server rollback or retry with a different key. If the original operation is later reconciled, retain its original session/key and use existing authorized reads/retry semantics; never automatically associate it with the replacement session.

## Backend behavior the test must not misrepresent

Actual route: **PUT `/api/v1/me/therapy-sessions/{therapySessionId}/completion`**, with the PATIENT token and `Idempotency-Key`.

| Situation | Existing contract |
|---|---|
| Session path and key | The path identifies the session. The key is scoped to that session's completion receipt, not a credential globally bound to a UI session. |
| First FINISHED | Clinical checks ownership, ACTIVE/unexpired state and the frozen technical progress target. It does not wait for Telemetry/Analytics delivery. |
| Existing completion, same key/action | Returns the original acknowledgement under the existing ownership checks. |
| Existing completion, changed key/action | Conflicts. |
| Old owned session with a fresh key | Can close if all first-completion conditions still hold. Do not expect automatic rejection solely because the frontend intended the key for another session. |

This is a frontend lifecycle defect, not a demonstrated authorization bypass. Backend guards do not replace frontend identity admission. See [integration.md](integration.md) for the full Clinical contract.

## Minimum offline regression set

Use the actual closure callback and lifecycle/controller path, the existing test runner and controlled API promises. A helper-only test that never runs the real continuation is insufficient.

| Controlled interleaving | Expected observation |
|---|---|
| A passes gate; progress(A) waits; switch to B; resolve progress(A) | No completion(A) dispatched by the stale continuation; B's progress/key/state/callbacks remain unchanged. |
| Switch or teardown while waiting for event delivery or the gate | Old operation does not proceed to later API stages or update the replacement UI. |
| Teardown and recreate the same session ID | Old generation is rejected even though the ID matches. |
| Completion(A) already dispatched; switch to B; settle its response or error | Request used A's captured key; response/catch/finally cannot overwrite B or claim rollback of A. |
| A remains current throughout | Normal completion still uses A's original key and applies its receipt once. Existing retry/uncertain-response behavior remains intact. |
| Repeated close click while A's closure is pending | One active logical closure; no competing keys or duplicate UI completion callbacks. |

Preserve the existing local regression coverage for queue identity, input switching, concurrent flushes, stable `sentAt`, pending synchronization and held gaze observations. Do not weaken those checks to fix this race.

## Return evidence, not inferred guarantees

Provide the frontend commit/date, affected functions, exact existing test command, observed results for each interleaving and any remaining limitation. Use synthetic identities only in memory; do not export tokens, patient identifiers, gaze samples or raw HTTP bodies.

**Finite closure:** stale continuations cannot initiate a later closure stage or mutate the replacement lifecycle, while the unchanged-session path and original-key retry remain correct. Backend Java, authenticated persistence, mounted-browser navigation and server effects of already-dispatched requests remain separate evidence boundaries, not results established by these offline checks.
