# Check that telemetry retries preserve the original batch

**Audience:** Jair and the frontend integration assistant. Verify the existing queue first; do not redesign it or change the backend. This is a small offline check using synthetic data and the frontend's existing test runner and HTTP mock.

## Why this matters

The backend offline characterization found that changing only `sentAt` preserves Telemetry's persistence fingerprint but changes Analytics' fingerprint, producing `BATCH_CONFLICT` there. An unchanged replay is classified as `DUPLICATE` by the tested Analytics admission handler. These checks did not run real databases or Kafka.

For compatibility with both consumers, **prepare the outbound batch once and reuse its complete payload on every retry**, including the original `sentAt`. A retry is another delivery attempt for that batch, not a new capture batch. See the [integration contract](integration.md) and [payload types](contracts.ts).

## Smallest useful inspection

Locate these three points in the existing frontend; report their file/function names:

1. Where capture samples become a queued `TelemetryBatchBody`.
2. Where the HTTP request body is built for `POST /api/v1/telemetry/batches`.
3. Where a failed or uncertain request remains pending and is retried.

Check whether the retry path generates another timestamp/UUID, increments the queued batch sequence, rebuilds samples, or reads a mutable capture buffer. Do not assume it is wrong solely because there is a `Date.now()` call nearby: identify whether that value actually changes the outbound batch.

### Payload invariants

| Preserve for the same queued batch | Required comparison |
|---|---|
| Identity | `schemaVersion`, `batchId`, `therapySessionId`, `streamId`, `sequence` unchanged |
| Time | `capturedAt` **and `sentAt`** unchanged |
| Samples | Both arrays, sample order, every `offsetMs`, and all sample values unchanged |
| Entire body | Deep equality of the JSON bodies; no extra retry/device/context fields added |

Set `sentAt` once when preparing the initial outbound payload. Do not replace it with the current time inside the retry loop. Local attempt counters/backoff metadata must not be added to the closed V1 body. Authentication headers are outside this payload comparison; never preserve an expired access token just to keep the body unchanged.

## Minimum offline check

Use the real existing queue/request path with an HTTP double, not a separate implementation of the retry behavior. No running backend, Cognito login, Docker, or cloud access is needed. Do not add a test framework if one is already available.

1. Create one valid synthetic batch and enqueue it using the existing frontend flow. Use only synthetic identifiers and samples; no real patient/session data.
2. Capture a **detached snapshot** of the first outgoing JSON body at the HTTP boundary. Capturing only a reference to its object is insufficient: a later mutation could change both observations and hide the defect.
3. Make the first request fail with a simulated network error/timeout. Keep the batch pending; an uncertain response is not proof that the server rejected it.
4. Advance the test clock and trigger one retry through the existing retry path. Capture a second detached body snapshot.
5. Assert deep equality of both bodies, including `sentAt`, and that this is still the same queued batch rather than a new identity. If the client supplies serialized JSON strings, parse them for comparison; property order alone is not a logical payload change.
6. Return a mocked `202 {"code":"BROKER_ACCEPTED"}` on that retry. Verify that the queue handles only that batch's broker acknowledgement according to its existing policy, not unrelated pending entries. Do not interpret it as durable storage or Analytics completion.

### Small regression set

| Case | Expected observation |
|---|---|
| Network error/timeout, then retry after the clock advances | Same complete body, especially the original `sentAt`; no premature successful-upload state |
| `503`, then retry | No assumption of rollback/success; when retried, the same batch body is reused |
| Capture continues or the source buffer changes while a batch is pending | The already queued batch and its retry remain unchanged; new capture data belongs to a separate batch |
| Mocked `202` after the retry | Only the corresponding batch is acknowledged; no claim that TimescaleDB/Analytics processed it |

Use the existing bounded retry policy; this guide does not authorize unlimited retries or a new queue implementation. Do not alter the payload to force a different outcome after a conflict. New batches, unlike retries, must have their own logical identity and sequence according to the existing stream rules.

## Keep evidence small and safe

Compare bodies in memory. Do not log/export access tokens, Authorization headers, patient/session identifiers, coordinates, complete payloads, or a raw HAR. A safe test result can state only:

```text
sameLogicalIdentity: true
sameCapturedAt: true
sameSentAt: true
sameSamplesAndOrder: true
sameCompleteBody: true
unconfirmedBatchRetained: true
onlyAcknowledgedBatchHandled: true
```

These are expected checks, **not results already obtained from the frontend**. Use the actual test assertions/results when reporting; do not print this block as a substitute for a test.

## Return to the backend team

Provide the frontend commit/date, queue and request-builder file/function names, the exact existing test command, executed test names/results, and whether any field changed on retry. If a check fails, identify the field and code location without sharing its value. If no suitable test runner/mock exists, report that limitation before adding tooling.

**Finite closure:** the existing frontend retry path demonstrably resends the same logical JSON payload after a failed attempt and a clock change. That closes this frontend queue check only; authenticated HTTP, real Kafka/storage, session closure, and clinical validity remain separate checks.

If a defect is found, report it first. This guide does not request backend changes, persisted-hash changes, endpoints, migrations, a new harness, or an automatic frontend refactor.
