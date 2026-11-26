# Preserve pending Clinical activity before closing a session

**Audience:** Jair and the frontend integration assistant. Address POST-REP-01 by retaining the existing activity request until its outcome is known. Reuse the current lifecycle guards and [closure recovery contract](session-closure-recovery.md); do not add backend endpoints, domain states or durable storage for this correction.

## Verified baseline and boundaries

Source comparison: backend `b28373d`, frontend `025929c`, 2026-11-25. The frontend audit reproduces the lost last-event scenario with simulated responses, not the deployed Clinical server. No tests were executed to prepare this guide.

| Operation | Existing contract |
|---|---|
| Send activity | `POST /api/v1/me/therapy-sessions/{therapySessionId}/activity-events`, PATIENT token, existing `PatientActivityEventBody` from [contracts.ts](contracts.ts). |
| Read current progress | `GET /api/v1/me/therapy-sessions/{therapySessionId}/progress`, same authorized patient/session. |
| Close | Existing completion PUT and `Idempotency-Key`; follow [session-closure-recovery.md](session-closure-recovery.md), not the activity event identity. |

Clinical activity, Telemetry batches and Analytics trials are separate operations. An Analytics acknowledgement or Telemetry `202` cannot acknowledge `REPETITION_FINISHED` or award Clinical repetition credit.

## Minimum frontend correction

1. Before dispatch, retain the complete activity body with its account, session and lifecycle generation. Keep `schemaVersion`, `eventId`, `sequence`, `type`, and any `repetitionId` / `repetitionIndex` unchanged on retries. Never rebuild a timed-out event with a new identity.
2. Serialize activity delivery per session. Assign distinct events contiguous sequences from the existing protocol (first sequence is zero), but do not dispatch a later event past an unresolved predecessor or skip/reuse its sequence to hide a failure. Refresh authentication independently of the retained body.
3. A timeout, network error or `503` leaves the write outcome uncertain, not successful or necessarily rolled back. Keep the original event pending and expose recoverable synchronization failure. A resolved warning handler must not make pending activity disappear from the close gate.
4. Use the existing finite request deadlines and single-flight/lifecycle guards. Permit a bounded explicit retry with the same session and complete body; no endless background retry or parallel drains. Abort does not prove the remote write was cancelled.
5. On a valid success response, acknowledge only the matching in-flight event in its still-admitted lifecycle. Then read fresh progress for the original session before deciding whether its target is reached. A replay receipt is the original response, not necessarily today's latest progress.
6. Include unresolved Clinical activity in the normal completion gate, alongside existing delivery checks. Do not automatically convert an uncertain event or `targetReached=false` into `ABANDONED`. Explicit patient abandonment remains a separate action under the existing flow.

Use the existing pending-state mechanism where possible. This guide does not prescribe a new queue framework. A voluntary session change must not silently discard or reassign critical pending work; follow the documented retention limits. If blocking versus explicit loss acknowledgement is not yet selected, resolve that product decision rather than silently choosing a discard policy. In-memory retention does not recover events after a reload or process loss.

## Backend guarantees and recovery limits

| Observation | Meaning / required handling |
|---|---|
| Previously accepted event, identical session + `eventId` + body | Ownership is checked, then Clinical returns the original receipt without another progress increment or lease renewal, even after the former lease expires. |
| Same `eventId`, different body | Conflict. Do not mutate the body or generate a new event to bypass it. |
| Event never accepted | It still needs an owned ACTIVE session, valid lease, expected sequence and valid activity/repetition state. Retention alone does not guarantee eventual acceptance. |
| Successful acknowledgement after an activity gap | Does not necessarily mean a repetition was credited; inspect current progress as described below. |
| `409` | Stop automatic delivery progression and read the original session's progress. Do not infer a unique cause, renumber the retained event or fabricate completion. |
| `401` / `403` | Use existing authentication/access handling. No fallback to another account or session; unresolved context is not a successful write. |
| GET fails or returns another session | Remain unresolved. Never use another session's cached progress. |

**Two timing limits matter:** accepted activity renews the session lease to at least 120 seconds from server receipt; a GET or replay of an old receipt does not. Separately, a server-observed ACTIVE activity gap **greater than 15 seconds** can put progress into `RESUME_REQUIRED` and clear the open repetition. An event may then be acknowledged without increasing `completedRepetitions`. Do not invent backdated credit: follow the existing resume flow and treat an interrupted repetition as uncredited. Recovery is not a promise to preserve an unconfirmed repetition across an outage.

Clinical wraps activity persistence in a local transaction: session update, activity progress and receipt belong to that transaction. GET progress is authoritative for stored state but is not an event-status endpoint and does not prove which `eventId` committed. Replaying the exact retained event is the existing receipt-recovery mechanism; no new receipt lookup endpoint is required.

## Minimum evidence for Jair to return

Use the actual activity/close callbacks with synthetic data and controlled HTTP promises in the existing frontend runner. These are acceptance checks to perform, not passing results from this guide.

- [ ] Last `REPETITION_FINISHED` returns `503`: event remains pending, normal close is blocked, and no automatic `ABANDONED` request occurs.
- [ ] Server double commits but loses the response: exact retry confirms the same event once; no second logical repetition is credited.
- [ ] Server double never accepted the request: retry preserves the entire body and only advances delivery after acknowledgement; later events cannot overtake it.
- [ ] Never-settling request: finite UI recovery; pending event survives timeout; repeated retry clicks do not create concurrent delivery.
- [ ] Gap / expiry / conflict: no fabricated credit or new identity workaround. Acknowledgement with `RESUME_REQUIRED` does not count as a completed repetition.
- [ ] A → B and A → B → A, account change and late handlers: no cross-session replay, cache fallback, callback or acknowledgement; retain LIFE-POST-GATE protections.
- [ ] Telemetry/trial success cannot clear Clinical pending activity; an empty queue after a reload is not described as durable recovery.

Return the frontend commit, exact command, observed results and remaining limitations. Do not record credentials, real identifiers or raw clinical payloads. Backend HTTP/database and deployed recovery checks require separate authorization.

## Source anchors

- Backend: `PatientSessionProgressService.activity/progress/complete`, `TransactionalPatientSessionProgress`, `SessionActivityProgress.accept`.
- Frontend: `EyeTrackingGameCanvas.tsx` activity dispatch/error handling and the existing delivery/closure controller.
- Related: [lifecycle guards](session-closure-lifecycle-check.md) and [Telemetry queue lifecycle](../telemetry/queue-lifecycle-fixes.md).
