# Analytics: observed streams and exercise results

Default local origin: `http://127.0.0.1:8085` (`ANALYTICS_PORT`); see the [local environment guide](../local-backend-environment-setup.md). Use the Clinical session ID and the original Cognito access token as `Authorization: Bearer <access-token>`. Send `Content-Type: application/json` on POST only. See [contracts.ts](contracts.ts).

## Observed streams

All paths below start with `/api/v1/analytics/sessions/{therapySessionId}`. Each row is a GET without a request body.

| Suffix | Role | Query | 200 response |
| --- | --- | --- | --- |
| `/streams` | DOCTOR | `page`, `size` | `AnalyticsStreamsResponse` |
| `/streams/{streamId}/summary` | PATIENT or DOCTOR | None | `AnalyticsSummaryResponse` |
| `/streams/{streamId}/heatmap` | PATIENT or DOCTOR | None | `AnalyticsHeatmapResponse` |
| `/streams/{streamId}/gaze-plot` | PATIENT or DOCTOR | `pageSize`, `cursor` | `AnalyticsGazePlotResponse` |
| `/streams/{streamId}/replay` | PATIENT or DOCTOR | `pageSize`, `gazeCursor`, `gameplayCursor` | `AnalyticsReplayResponse` |

- Discovery sorts by stream ID ascending. `page` starts at 0, `size` defaults to 20 (1–50), and `page * size` may not exceed 1,000. Follow `hasNext`; an authorized empty page is 200 with `items: []`. Counts describe samples observed in the projection, not trials or completion.
- Use a known stream ID for a stream-specific read. A missing projection can be 404; it is not an authorization failure. The summary and heatmap are technical observations, not therapeutic outcomes. The heatmap counts samples, not time spent fixating.
- Gaze plot and replay default to 100 samples per page (1–500). Treat cursors as opaque and reuse them only for the same stream and endpoint; replay has one cursor per lane. Null means no continuation for that lane. Omission is a first-page request, not a signal to skip the lane. Respect `segmentBreakBefore` and reported gaps; do not interpolate missing observations or label observed points as physiological fixations.

Handle status before the controller's `{ code }` envelope: 400 for invalid paging/cursor inputs where mapped, 401 for authentication, 403 for denial, 404 for missing stream-specific evidence, 503 for failed dependencies or reads. Do not turn 503 into empty evidence. Framework rejections may have a different body.

Sources: stream controllers and REST assemblers, `AnalyticsSessionStreamsControllerTest`, `HeatmapProjectionControllerTest`, `GazePlotPageControllerTest`, and `QueryReplayPageServiceTest`.

## Exercise trial results

Use the frozen mode to choose the path. Expand each slug below into:

- **PATIENT**: `POST /api/v1/analytics/sessions/{therapySessionId}/{slug}/trial-results`. JSON body, no query or idempotency header. Returns `AnalyticsTrialReceipt`.
- **DOCTOR**: `GET /api/v1/analytics/sessions/{therapySessionId}/{slug}/summary`. No body. Requires `dataOrigin=SYNTHETIC` or `dataOrigin=PRODUCER_REPORTED` exactly once. Clinical checks authorship and active assignment.

| Frozen mode | Slug | POST body type | GET 200 response |
| --- | --- | --- | --- |
| `TARGET_SEARCH_WITH_DISTRACTORS` | `visual-search` | `VisualSearchTrialBody` | `VisualSearchTrialSummary` |
| `STANDARD_SCANNING` | `standard-scanning` | `StandardScanningTrialBody` | `StandardScanningTrialSummary` |
| `SUSTAINED_TARGET_FIXATION` | `sustained-fixation` | `SustainedFixationTrialBody` | `SustainedFixationTrialSummary` |
| `COMPENSATORY_SACCADE` | `compensatory-saccade` | `CompensatorySaccadeTrialBody` | `CompensatorySaccadeTrialSummary` |
| `SYSTEMATIC_EXPLORATION` | `systematic-exploration` | `SystematicExplorationTrialBody` | `SystematicExplorationTrialSummary` |

### Submit one terminal result

Send schema version 1 and an explicit origin. Label fixtures `SYNTHETIC`; do not mix them with producer-reported results. The server rejects unknown fields and bounds the JSON body to 8,192 UTF-8 bytes. Times are integer milliseconds. The producer reports the outcome; Analytics validates it against the session's frozen configuration. These results are separate from Telemetry samples and Clinical completion.

- Visual Search, Standard Scanning, and Compensatory Saccade require confirmed dwell on `SUCCESS` and explicit null on `TIMEOUT` or `INTERRUPTED`. Success must meet the frozen dwell minimum within the allowed duration.
- Sustained Fixation distinguishes cumulative time inside the target from longest continuous time. Longest continuous time cannot exceed cumulative time, which cannot exceed elapsed time.
- Systematic Exploration reports an ordered prefix of `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`, with dwell and time to visit per quadrant. All four visits are required for `COMPLETED`. Its timeout outcome is spelled `TIMED_OUT`.

201 `CREATED` means stored. 200 `ALREADY_STORED` confirms an identical result for that exercise, session, and trial ID, even after closure when still authorized. New results require a live session. Do not reuse a trial ID for a different outcome or origin. A 503 `WRITE_OUTCOME_UNKNOWN` is not proof that nothing was saved; to confirm, reuse the exact original result rather than creating another trial ID.

POST uses `{ status }`: 400 invalid result, 401 invalid token, 403 denied, 409 conflict, 413 oversized body, 415 unsupported media, and 503 unavailable or uncertain write. Handle HTTP status first in case a rejection comes from the framework.

### Read a summary

The summary uses stored results for the selected origin. No pagination or partial summary: more than 1,000 attempts for the selection returns 422. An authorized empty selection returns 200 with zero counts, null medians where applicable, and an empty traversal list for exploration. Null median means no eligible measurement, not zero milliseconds. Interrupted attempts are not timeouts. Summary errors use `{ code }`: 400 invalid request, 401 invalid token, 403 denied, 422 limit exceeded, or 503 unavailable.

Sources: the five `*TrialResultsController` and `*TrialSummaryController` implementations, their decoders and response records, and the corresponding controller tests (including origin selection, exact replay, empty results, and failure handling).

## Internal boundaries

Analytics delegates session authorization to Clinical under `/api/internal/v1/therapy-sessions/{therapySessionId}/...` with the original access token, including historical-read authorization and mode-specific trial result and summary authorization. The browser must not call these internal routes or connect to Kafka or MongoDB. Clinical owns report composition and PDF download; Analytics returns observations and summaries, not saved report copies.
