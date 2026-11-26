# Recover an uncertain session closure without changing its identity

**Audience:** Jair and the frontend integration assistant. Use the existing Clinical endpoints and lifecycle guards to recover from a missing response. Do not create another closing operation or borrow another session's cached progress. This is an MVP integration contract, not a backend change or evidence of an implemented frontend fix.

## Scope and existing routes

Backend source checked at `6721ba2` on 2026-11-21. The supplied frontend audit summary references `490aaed`; its complete report was not available at the expected local path during this review. Backend offline closure checks previously passed 17/17; they do not prove browser behavior or deployed HTTP/database recovery.

| Operation | Existing contract |
|---|---|
| Read progress | `GET /api/v1/me/therapy-sessions/{therapySessionId}/progress`; response includes `therapySessionId`, `status`, `targetReached` and technical progress. |
| Complete | `PUT /api/v1/me/therapy-sessions/{therapySessionId}/completion`; `Idempotency-Key`, `Content-Type: application/json`, body `{"action":"FINISHED"}` or `{"action":"ABANDONED"}`. |
| Authentication | Use the existing PATIENT access token. A refreshed token may change; the logical operation's session, key and action must not. |

Reuse [integration.md](integration.md) and the [LIFE-POST-GATE guard instructions](session-closure-lifecycle-check.md). No new endpoints, queue framework, storage layer, automatic retry loop or prescription changes are required.

For an uncertain activity POST before closure, use [pending activity recovery](pending-activity-recovery.md). Activity `eventId` recovery and completion `Idempotency-Key` recovery are different operations; neither acknowledges the other.

## 1. Bind progress and operation identity — LIFE-CACHE-01

- Capture patient/account context, session ID, completion key, action and lifecycle generation together before the asynchronous close flow starts. Reuse the existing identity/controller mechanism.
- Invalidate cached progress on session replacement, teardown and account change. Every returned/cached progress object must match the captured `therapySessionId` and belong to the admitted lifecycle before it can drive a decision or callback.
- Check admission after each relevant await and before dispatching completion or updating shared refs/UI, including error/finally paths. A → B → A must reject A's old generation even when the session ID matches again.
- If progress(B) fails or carries another session ID, do not use progress(A) as fallback. Do not initiate a first FINISHED request from that cache; show that progress could not be verified and offer an explicit retry of the read.
- Matching IDs alone do not establish freshness or authorization. Backend always applies its own ownership, target and validity checks.

## 2. Bound the wait, not the server transaction — LIFE-WAIT-01

Use one documented, finite frontend deadline for each Clinical read/write attempt and the existing request client where possible. Select the concrete duration in the frontend implementation; this contract does not invent a backend timeout or change its configuration.

When the deadline expires, leave the indefinitely waiting SAVING presentation and show a recoverable failure/uncertain state. Do not report success, clear pending data or start another session automatically. A forced analytics close does not bypass this rule.

| What was waiting | Meaning |
|---|---|
| Progress GET, before completion dispatch | Progress is unavailable; this flow has not sent completion yet. No fallback from another session. |
| Completion PUT after dispatch, network failure or missing response | Closure outcome is unknown. Clinical might have committed it. Retain the original operation identity. |
| Completion PUT returns `503` | Not a successful acknowledgement; do not assume rollback. Preserve identity for reconciliation. |

`Promise.race` may bound the local wait but does not stop an underlying continuation. Abort, navigation and disposal do not prove the remote write was cancelled. Keep the existing single-flight/attempt guards: late responses and old catch/finally handlers must not overwrite a replacement lifecycle or a newer recovery decision. A still-current late receipt may be handled once only after those checks.

## 3. Explicit recovery with the same key

1. On the user's recovery action, query progress for the **original session** with current authorized credentials. Bound this read too; if it fails, remain unresolved rather than looping or declaring completion.
2. Validate response identity and lifecycle admission. Show the server-observed status, separately from whether this particular closure request has been acknowledged.
3. If the original completion key/action are retained and reconciliation requires resubmission, permit one bounded explicit resend using the exact same session, key and body. Never replace the key or switch FINISHED to ABANDONED as an automatic recovery strategy.
4. Process the outcome below. Repeated clicks join or are blocked by the active recovery attempt; they do not launch parallel user operations. An earlier timed-out request may still be executing remotely: do not claim zero overlap or exactly-once delivery.

| Recovery outcome | Required handling |
|---|---|
| Completion acknowledgement with matching session | Confirm the operation once, only for its still-admitted lifecycle. Replaying an existing matching receipt does not create another closure. |
| Progress shows COMPLETED/ABANDONED | Confirms observed server state, not which key/action caused it. Exact-key replay can confirm the original receipt; do not manufacture a receipt from the GET. |
| Progress shows ACTIVE | Does not prove the earlier request failed or that its lease is still valid. GET progress does not renew or persist expiration. A resend may succeed or conflict under current backend checks. |
| `409` from completion | Do not generate a new key or repeatedly retry. Refresh the original session's state and surface the conflict. Causes include expiry, unmet target, terminal state or another stored key/action; do not infer one cause from the generic status alone. |
| `401` / `403` | Use existing authentication/access handling. No cross-account fallback; do not delete uncertain operation context merely because access is unavailable. |
| Another timeout/network failure/`503` | Keep outcome unresolved and offer later explicit recovery. No fabricated success or automatic endless retry. |

Backend semantics remain unchanged: first closure requires an owned ACTIVE, unexpired session; FINISHED additionally requires its stored technical target. An existing completion with the same key/action returns the original acknowledgement under ownership checks, even after the former expiry. Different key/action conflicts. The key is scoped to the path session, not globally reserved for a UI session.

## 4. In-memory limits — no durable recovery claim

Retain the operation context while the existing frontend lifecycle can recover it. If a reload loses the original key/action, do not silently generate a replacement and call it an idempotent retry. Use existing authorized session reads to establish observed state and disclose that the original operation context is unavailable.

For this MVP, pending critical work may block a voluntary session change, or require an explicit acknowledgement of possible loss under the existing product policy. Neither approach recovers data after a page/process loss. Do not silently reassign pending work to another patient/session.

Clinical completion does not guarantee analytics delivery. New Telemetry HTTP submissions require an active session. After closure, Analytics may confirm an identical already-stored trial but cannot insert a missing trial. Preserve the [queue lifecycle and delivery limits](../telemetry/queue-lifecycle-fixes.md); this guide does not add durable storage or change forced/permanent-loss closure policy.

## Minimum frontend evidence to return

Use synthetic data, existing tests and controlled API promises through the actual close/recovery callbacks:

- [ ] B's progress read fails while A is cached: no completion(B) or callback uses A; a mismatched progress response is rejected too.
- [ ] A → B and A → B → A: late progress, completion, errors and finally handlers cannot mutate the replacement lifecycle.
- [ ] Progress never resolves: bounded UI recovery, no completion sent and no stale-cache fallback.
- [ ] Completion commits in the HTTP double but its response is lost: read and same-key/action resend return the original receipt without another logical closure.
- [ ] Completion was not stored before timeout: recovery can submit that same identity if still eligible; expiry/conflict does not trigger a new-key workaround.
- [ ] Repeated recovery clicks and late original responses apply at most one UI acknowledgement; a recovery GET failure stays unresolved.
- [ ] Reload/account/session changes do not fabricate an original key, claim durable queue recovery or reassign work; existing LIFE-POST-GATE/RG-02/RG-03 protections remain covered.

Report the frontend commit, exact test command, observed cases/results and remaining limitations. Do not log tokens, real patient/session identifiers or raw clinical payloads. The checklist is expected evidence, not a claim that these tests ran or that backend HTTP/durable recovery was validated.
