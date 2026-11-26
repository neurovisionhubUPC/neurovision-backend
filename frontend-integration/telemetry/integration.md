# Telemetry: send batches and read stored samples

Default local origin: `http://127.0.0.1:8084` (`TELEMETRY_PORT`); see the [local environment guide](../local-backend-environment-setup.md). Telemetry receives gaze and gameplay samples and serves bounded historical reads. It does not award Clinical progress or produce reports.

## Browser endpoints

Use `Authorization: Bearer <access-token>` on both routes and `Content-Type: application/json` only on POST. See [contracts.ts](contracts.ts).

| Method and path | Role | Path params | Query | JSON body | Success |
| --- | --- | --- | --- | --- | --- |
| `POST /api/v1/telemetry/batches` | PATIENT | None | None | `TelemetryBatchBody` | 202, `TelemetryBatchAcceptedResponse` |
| `GET /api/v1/telemetry/sessions/{therapySessionId}/samples` | PATIENT or DOCTOR | `therapySessionId` | `TelemetrySamplesQueryParams` | None | 200, `TelemetrySamplesPageResponse` |

### Send a batch

- Use the session UUID returned by Clinical. Send schema version 1, UUIDs for `batchId` and `streamId`, and a nonnegative integer `sequence`. Keep the stream ID stable for the same capture stream. Batch sequence is separate from Clinical activity-event sequence.
- Send `capturedAt` and `sentAt` as ISO instants, with capture at or before send. The server checks future clock skew. Each sample array has nonnegative integer offsets in milliseconds, in nondecreasing order within that array.
- Send both sample arrays; use `[]` for the unused kind, but not for both. The combined sample limit is deployment-configured. Coordinates, confidence, and gameplay progress are finite values in [0, 1]; score is a nonnegative integer, and speed is finite and nonnegative. Do not add extra JSON fields.
- **202 `BROKER_ACCEPTED` means Kafka acknowledged publication.** It does not confirm TimescaleDB persistence, Analytics projection, or session completion. Do not turn a 429 or an uncertain 503 into a successful upload, or assume the endpoint is an HTTP idempotency API.

For retry compatibility, preserve the complete queued batch, including its original `sentAt`; do not rebuild its timestamps or samples on another attempt. Follow the [small offline queue check](retry-queue-check.md) before changing the frontend or backend.

For the verified input-switch and concurrent-drain risks, follow the [minimum queue lifecycle corrections](queue-lifecycle-fixes.md). That handoff also separates pending delivery, permanent rejection and backend behavior after session closure.

Keep new mouse interaction out of `gazeSamples`; follow the [gaze/mouse separation handoff](gaze-mouse-separation.md). V1 has no numeric input-origin field, and gameplay coordinates do not automatically produce a cursor heatmap.

### Read historical samples

Provide a known `streamId` UUID and `from` / `to` ISO instants (prefer UTC with `Z`). The filter is on `sampleTime`: `from` inclusive, `to` exclusive, with `from < to` and a window of at most 15 minutes. `limit` is 1–200, default 100. This is a single-stream read, not a stream discovery endpoint.

Omit `cursor` on the first page. On later pages, use the returned `nextCursor` as-is with the same session, stream, and time bounds. Stop at null. Ordering is ascending by sample time, batch ID, sample kind, then index. An authorized empty read is 200 with `samples: []`; do not fill gaps or coerce fields for the other sample kind from null to zero.

## Errors

Handle HTTP status before decoding the controller's `{ code }` envelope. Framework rejections can have a different body.

| Status | Batch POST | Historical GET |
| --- | --- | --- |
| 400 | `INVALID_BATCH` for malformed JSON or a specific validation code | `INVALID_TELEMETRY_QUERY` for invalid stream, time window, limit, or cursor |
| 401 | `UNAUTHENTICATED` | `UNAUTHENTICATED` |
| 403 | `TELEMETRY_INGESTION_FORBIDDEN` | `TELEMETRY_READ_FORBIDDEN`, also used for a malformed session UUID |
| 429 | `TELEMETRY_INGESTION_RATE_LIMITED` | Not mapped by this controller |
| 503 | `TELEMETRY_INGESTION_UNAVAILABLE` | `TELEMETRY_READ_UNAVAILABLE`; not an empty read |

## Internal calls — not browser endpoints

Telemetry sends the caller's original access token to Clinical for authorization: `/api/internal/v1/therapy-sessions/{therapySessionId}/telemetry-authorization` for ingestion and `/api/internal/v1/therapy-sessions/{therapySessionId}/telemetry-read-authorization` for history. Each history page is authorized before storage access. Do not call these routes or connect to Kafka or TimescaleDB from the browser.

Sources: `TelemetryBatchController`, `TelemetrySamplesController`, their wire DTOs, `TelemetryBatchValidator`, `QueryHistoricalTelemetrySamplesService`, and `PgHistoricalTelemetryReadAdapter`. Contract cases: `TelemetryBatchValidatorTest`, `IngestTelemetryBatchCommandServiceTest`, `QueryHistoricalTelemetrySamplesServiceTest`, and `HistoricalTelemetryReadIT`.
