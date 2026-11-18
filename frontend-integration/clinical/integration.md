# Clinical: patient management, therapy planning, and session execution

Default local origin: `http://127.0.0.1:8083` (`CLINICAL_PORT`); see the [local environment guide](../local-backend-environment-setup.md). This guide covers the doctor's patient management and planning workflows and the patient's therapy execution workflow.

Start with the assigned-patient list. Use the returned `clinicalPatientId` to open the detail and context. This ID is not a Profile ID or a Cognito subject. When editing context, read its current version before sending a patch.

## Browser endpoints

All routes require a `DOCTOR` access token and scope data to an active assignment for the signed-in doctor. Send `Authorization: Bearer <access-token>` on every call and `Content-Type: application/json` on PATCH only. Types are in [contracts.ts](contracts.ts).

| Method and path | Path params | Query | JSON body | Success |
| --- | --- | --- | --- | --- |
| `GET /api/v1/clinical-patients` | None | `page`, `size` | None | 200, `AssignedPatientsResponse` |
| `GET /api/v1/clinical-patients/{clinicalPatientId}` | `clinicalPatientId` | None | None | 200, `AssignedPatientDetailResponse` |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/rehabilitation-context` | `clinicalPatientId` | None | None | 200, `RehabilitationContextResponse` |
| `PATCH /api/v1/clinical-patients/{clinicalPatientId}/rehabilitation-context` | `clinicalPatientId` | None | `RehabilitationContextPatchBody` | 200, `RehabilitationContextResponse` |

## List and detail

- `page` is a zero-based integer (default 0); `size` is an integer from 1 to 100 (default 20). The offset `page * size` must fit a signed 32-bit integer. The order is newest `createdAt` first, then `clinicalPatientId` ascending. An empty page returns `patients: []`; `total` counts all matching assignments' patients, not just the page. There is no name search, status filter, or custom sort.
- Use the canonical lowercase UUID from the list in detail and context paths. The patient's `status` is distinct from the assignment's status: an active assignment can include a patient with `PENDING_ASSESSMENT`.
- Clinical checks the assignment before resolving names through Profile. `AVAILABLE` includes both names; `MISSING` and `PENDING_COMPLETION` return null names. Handle those as display states, not lookup failures. An unavailable name lookup can produce 503. The browser does not call the internal name lookup.
- Detail adds `strokeDiagnosis` and `sequelae`. It does not include session history or reports.

## Edit rehabilitation context

This is clinician-entered context, not automated diagnosis. Send `expectedVersion` from the last read as a nonnegative integer, plus at least one editable field. Example: `{"expectedVersion":3,"affectedHemifield":"LEFT","observations":null}` updates the side, clears observations, and leaves other fields unchanged. Use the version returned by a successful patch for subsequent edits.

- Omitted fields stay unchanged; explicit null clears a field. For `sequelae`, null or an empty array clears it and the response returns `[]`. A supplied array replaces the whole list.
- `affectedHemifield` accepts exactly `LEFT` or `RIGHT`, or null. Text is trimmed; blank diagnosis or observations clear the field. Limits after trimming: 500 Unicode code points for `previousDiagnosis`, 1,000 total for `sequelae`, and 2,000 for `observations`. Sequelae entries must be nonblank and distinct after trimming (at most 1,000 entries).
- The PATCH accepts at most 65,536 UTF-8 bytes and rejects unknown or duplicate JSON keys. It does not change eligibility or patient status. Context reads and writes do not call Profile.

## Errors

Handle errors by HTTP status first. Controller-mapped errors have `{ "code": "..." }` and `Cache-Control: no-store`; framework-level authentication or parsing errors may have a different body.

| Status | When to handle it |
| --- | --- |
| 400 | Invalid query, path ID, or PATCH body. |
| 401 | Missing or invalid authentication. |
| 403 | Role or assignment denied. An unknown patient and an unassigned patient both return 403 for a valid ID. |
| 409 | PATCH version conflict. Re-read the context and reconcile the edit; do not blindly resend it. |
| 503 | A required read, write, or upstream lookup is unavailable. Do not display this as an empty result. |

Sources: `DoctorClinicalPatientController`, `AssignedPatientQueryService`, `JpaAssignedPatientReadAdapter`, `DoctorRehabilitationContextController`, and `RehabilitationContext`. Contract cases: `AssignedPatientControllerTest`, `AssignedPatientQueryServiceTest`, and `DoctorRehabilitationContextControllerTest`.

## Doctor workflow: plans, configurations, and revisions

Use the `clinicalPatientId` from the assigned-patient list, then keep the `therapyPlanId` returned by create or list. All routes below require the doctor's bearer access token and an active assignment. Existing plans are scoped to the author. POST uses `Content-Type: application/json`; GET has no body.

| Method and path | Path params | Query | JSON body | Success |
| --- | --- | --- | --- | --- |
| `POST /api/v1/clinical-patients/{clinicalPatientId}/therapy-plans` | Patient ID | None | `CreateTherapyPlanBody` | 201, `TherapyPlanResponse` |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-plans` | Patient ID | `page`, `size`, `status` | None | 200, `TherapyPlansResponse` |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-plans/{therapyPlanId}` | Patient and plan IDs | None | None | 200, `TherapyPlanDetailResponse` |
| `POST /api/v1/clinical-patients/{clinicalPatientId}/therapy-plans/{therapyPlanId}/configurations` | Patient and plan IDs | None | `AppendTherapeuticConfigurationBody` | 201, `TherapeuticConfigurationResponse` |
| `POST /api/v1/clinical-patients/{clinicalPatientId}/therapy-plans/{therapyPlanId}/revisions` | Patient and plan IDs | None | `ReviseTherapyPlanBody` | 201 new or 200 identical authorized replay, `TherapyPlanRevisionResponse` |

### Create and read plans

- Send positive integers for session limit, target duration, minimum valid duration, and minimum valid sample count. Maximum session duration must be at least the minimum valid duration. Durations here are in minutes. The optional hard safety limit is separate from the daily target. Prescribe at least one supported exercise code with a positive integer quantity.
- List defaults to `page=0` and `size=20`. Size is 1–100 and `page * size` must fit a signed 32-bit integer. Sort is plan UUID ascending. `status` filters the effective status by exact `ACTIVE`, `SUSPENDED`, or `CANCELLED`; omit it for all statuses. No text search. An empty page returns `plans: []`, with `total` still representing the matching count.
- Detail returns currently effective configurations for prescribed exercises, with their parameters. It is not a configuration history, revision list, or guarantee of patient admission.

### Append an exercise configuration

Send a prescribed `exerciseCode`, `effectiveFrom` as an ISO-8601 instant (use UTC with `Z`), and the schema version with its matching parameters. This creates an immutable version; the 201 response returns version metadata, not the parameters.

| Exercise code | Parameter `mode` |
| --- | --- |
| `visual-scanning` | `STANDARD_SCANNING`, `SYSTEMATIC_EXPLORATION`, `COMPENSATORY_SACCADE` |
| `visual-search` | `TARGET_SEARCH_WITH_DISTRACTORS` |
| `fixation` | `SUSTAINED_TARGET_FIXATION` |

Schema 1 uses the exercise fields directly inside `parameters`. Schema 2 uses `parameters: { parameters: exerciseFields, completionTarget: { criterion, target } }`. The target is a positive integer up to 2,147,483,647: seconds for `ACTIVE_TIME`, count for `REPETITIONS`. It measures technical completion, not clinical efficacy. The parameter objects reject missing or extra fields; all numeric exercise fields are integers except the search bias.

| Scope | Inclusive parameter limits |
| --- | --- |
| All exercises | `targetSizePx` 30–150; `difficultyLevel` 1–5 |
| Scanning | `minimumFixationMs` 300–2,000; `maximumStimulusDurationMs` 2,000–10,000; `eccentricityDegrees` 5–30; `repetitions` 5–50; `interStimulusIntervalMs` 500–3,000 |
| Search | `targetCount` 5–40; `distractorCount` 0–20; `minimumFixationMs` 300–2,000; `maximumSearchTimeMs` 3,000–20,000; `affectedHemifieldBias` 0.5–1.0 |
| Fixation | `minimumFixationMs` 300–3,000; `maximumFixationMs` 500–5,000 and at least the minimum; `repetitions` 5–30; `restBetweenRepetitionsMs` 500–3,000 |

### Revise an existing plan

Generate a canonical UUID `commandId` per intended change. On an uncertain outcome, retry the same command ID and same input; a different change under that ID conflicts. Omit unchanged fields; explicit null, unknown keys, and duplicate keys are rejected. A reason alone is not a change. Supplied limits and prescription quantities must be positive integers; a supplied prescription map is the new distribution, not a per-key patch.

Stopping accepts only `SUSPENDED` or `CANCELLED` and requires a reason of 1–500 characters after trimming. There is no resume-to-`ACTIVE` command. Stop-only revisions do not query Profile; create, configuration append, and scheduling revisions (including mixed stop and scheduling changes) require the existing active doctor Profile with valid CMP. Plan reads do not require that lookup.

Clinical chooses effective times. Stops, reductions, and new hard caps apply immediately; increases and exercise distribution changes apply at the next midnight in `America/Lima`. A command can return one or two revisions. Use each returned `effectiveFrom`; do not assume all changes are already effective. Stopping cancels an active session rather than completing it. There is no GET revisions endpoint in this contract.

### Plan-specific errors

Handle HTTP status first: 400 invalid input; 401 authentication; 403 denied access (including inaccessible plans or failed scheduling eligibility); 409 append or revision conflict; 503 unavailable dependency or read. Reads and revisions have controller-mapped `{ code }` errors; do not assume create, append, or framework rejections share that shape.

Sources: `DoctorTherapyPlanController`, `DoctorTherapyPlanReadController`, `DoctorTherapyPlanRevisionController`, their assemblers, and `TherapeuticExerciseParameters`. Contract cases: `DoctorSchedulingControllerBoundaryTest`, `DoctorTherapyPlanReadControllerTest`, `DoctorTherapyPlanRevisionControllerTest`, and `TherapyPlanRevisionRestAssemblerTest`.

## Patient workflow: execute a prescribed therapy

Use the patient's bearer access token. Clinical derives the patient from the authenticated identity; do not send a patient ID. List plans and check the current session before starting a new one. Run the exercise with the **frozen parameters returned for the session**, not a fresh copy of the plan's configuration.

### Browser endpoints

All routes below require `PATIENT` and have **no query parameters**. Send `Authorization: Bearer <access-token>` on each call and `Content-Type: application/json` only with a JSON body. Start and completion also require `Idempotency-Key` (1–128 characters after trimming).

| Method and path | Path params | JSON body | Success |
| --- | --- | --- | --- |
| `GET /api/v1/me/therapy-plans` | None | None | 200, `PatientTherapyPlanResponse[]` |
| `POST /api/v1/me/therapy-plans/{therapyPlanId}/sessions` | `therapyPlanId` | `StartPatientTherapySessionBody` | 201 new or 200 replay, `PatientTherapySessionResponse` |
| `GET /api/v1/me/therapy-sessions/current` | None | None | 200, `PatientCurrentSessionResponse` |
| `GET /api/v1/me/therapy-sessions/{therapySessionId}` | `therapySessionId` | None | 200, `PatientTherapySessionDetailResponse` |
| `POST /api/v1/me/therapy-sessions/{therapySessionId}/activity-events` | `therapySessionId` | `PatientActivityEventBody` | 200, `PatientSessionProgressResponse` |
| `GET /api/v1/me/therapy-sessions/{therapySessionId}/progress` | `therapySessionId` | None | 200, `PatientSessionProgressResponse` |
| `PUT /api/v1/me/therapy-sessions/{therapySessionId}/completion` | `therapySessionId` | `PatientSessionCompletionBody` | 200, `PatientSessionProgressResponse` |

### Choose, start, or recover a session

- Plans are an unpaginated array; no plans is `[]`. Daily availability is an observation for the `quotaDate` in `America/Lima`, not a reservation or a deadline for the user. A null slot count is not zero. `AVAILABLE` is not an admission guarantee; `BLOCKED` has a reason. `CHECK_AT_START` means the start flow must evaluate the expired session under its normal admission rules. Reading the list does not expire or renew sessions.
- Send only the selected `exerciseCode` in the start body. Reuse the same key and request if the start outcome is uncertain; do not start with a fresh key just to recover a lost response. Clinical checks admission at start and can return 409 even after an available observation.
- `/current` returns `NONE` with `session: null`, or `ACTIVE` or `EXPIRED` with the frozen session payload. An expired observation is not resumable and does not change the stored session status. Use `state`, not the nested `session.status`, to distinguish these cases.
- Detail reads one owned session with a current active assignment, including terminal sessions. It returns frozen parameters and limits; `technicalProgress: null` means the session has no prescribed technical goal, not that its counters are zero. The detail is not a replacement for the current-session or progress endpoints.

### Record activity and complete

The activity, progress, and completion endpoints apply to sessions started with schema 2 and a frozen completion target. Legacy sessions return 409 on these endpoints. Use the returned session UUID in canonical lowercase form.

1. Send `ACTIVITY_STARTED` at **sequence 0**. Send one event at a time, advancing with the acknowledged `nextSequence`. Each new event has its own UUID `eventId`; an identical replay returns the original acknowledgement without advancing or renewing again. If the outcome is unclear, replay exactly or read progress before deciding what to send next.
2. Send `HEARTBEAT` every 5 seconds while active. Clinical measures time from server receipt, not client timestamps. A gap greater than 15 seconds adds no time and discards an open repetition; if the returned state is `RESUME_REQUIRED`, send `RESUMED` before more activity. Use `PAUSED` and `RESUMED` for intentional pauses. A successful progress read adds no credit and does not renew the lease.
3. For repetitions, send `REPETITION_STARTED` and `REPETITION_FINISHED` with the same `repetitionId` and one-based `repetitionIndex`. Start only one repetition at a time, at index `completedRepetitions + 1`. Use a new repetition ID if a pause or gap discarded the open attempt. Do not send client counters or timestamps.
4. Send `{"action":"FINISHED"}` only when `targetReached` is true; successful finish returns `COMPLETED`. Send `{"action":"ABANDONED"}` to stop without meeting the target and preserve partial progress. Use the same completion key and action to confirm an uncertain result. New activity and completion require a live session; exact receipt replays can still confirm an earlier accepted operation.

Completion is local to Clinical. It does not prove gaze quality, full Telemetry persistence, or report readiness. Telemetry ingestion and Analytics results are separate contracts.

### Saving progress and recovering after an interruption

Clinical does not wait for the end of the exercise to save progress. Each accepted activity event saves the session accounting, progress state, and event acknowledgement in one transaction. Count a repetition only after its matching `REPETITION_FINISHED` is accepted. Data that exists only in browser memory is not guaranteed to survive a refresh or disconnect.

The session starts with a **120-second lease**. Each newly accepted activity event renews the lease from server receipt time by 120 seconds (without moving an existing later expiry backwards). An identical event replay does not renew it. Reading `/current`, detail, or `/progress` also does not renew it. A pause is not an indefinite reservation: without further accepted activity, the session expires.

To recover, read `/current` and, for a live schema-2 session, `/progress`. Reuse the returned frozen parameters, credited counters, and `nextSequence`; do not send `ACTIVITY_STARTED` again for an already started activity. Use `RESUMED` when paused or when a gap requires resumption. If the observation is `EXPIRED`, do not send new activity to that session; the normal start flow must recheck admission and handle the expired session. Expiry does not erase previously saved counters, but those counters are not transferred to a new session.

**Interrupted repetition:** already accepted completed repetitions remain credited. A pause, a gap greater than 15 seconds, or resumption discards the open repetition without counting it as completed. Start its replacement with a new `repetitionId` and index `completedRepetitions + 1`. Recovery is not a saved-game checkpoint: these endpoints do not expose an unfinished repetition's ID or persist the exact screen, target layout, or browser game state. For example, after 3 accepted completions out of 5, those 3 remain stored; a partially performed fourth repetition is not a fourth completion.

Telemetry batches and terminal attempt results are separate saves: a Telemetry 202 confirms broker acceptance, while Analytics receives a result when an attempt ends. Neither is an automatic checkpoint for an unfinished game. See [Telemetry](../telemetry/integration.md) and [Analytics](../analytics/integration.md).

### Patient-specific errors

Handle status first: 400 for malformed inputs where validated; 401 for authentication; 403 for role or access denial; 409 for start admission, event ordering, lease, unsupported legacy progress, target, or idempotency conflicts; 503 for unavailable reads or operations where mapped. The controller-mapped error codes are in `PatientTherapyApiErrorResponse`; do not assume start or framework rejections have that JSON shape. Never render a failed read as an empty plan list or `NONE`.

Sources: `PatientTherapyController`, `PatientCurrentSessionController`, `PatientTherapySessionDetailController`, `PatientSessionProgressController`, `SessionActivityRestAssembler`, and `PatientSessionProgressService`. Contract cases: the corresponding four controller test classes and `SessionActivityProgress` accounting rules.

## Doctor workflow: review session history

Select a patient from the assigned-patient list, then open a session using its `therapySessionId`. Both reads require `Authorization: Bearer <access-token>` with `DOCTOR` and without `PATIENT` or `ADMIN`. Clinical requires a current active assignment and returns only sessions owned by that doctor. Profile/CMP and current plan status do not gate these reads.

| Method and path | Path params | Query | JSON body | Success |
| --- | --- | --- | --- | --- |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions` | `clinicalPatientId` | `DoctorTherapySessionsQueryParams` | None | 200, `DoctorTherapySessionsResponse` |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}` | `clinicalPatientId`, `therapySessionId` | None | None | 200, `DoctorTherapySessionDetailResponse` |

Use canonical lowercase UUIDs from Clinical. No JSON body or `Content-Type` is needed.

### Filter and page through history

| Query parameter | Accepted values |
| --- | --- |
| `page` | Zero-based nonnegative integer, default 0. `page * size` must fit a signed 32-bit integer. |
| `size` | Integer 1–100, default 20. |
| `exerciseCode` | Exact `fixation`, `visual-scanning`, or `visual-search`. Use the exercise code, not the configuration mode. |
| `status` | Exact `ACTIVE`, `COMPLETED`, `CANCELLED`, `ABANDONED`, or `FAILED_TECHNICAL`. |
| `from`, `to` | ISO date-times with `Z` or an explicit offset; filter on `startedAt` with `from` inclusive and `to` exclusive. Either bound can be omitted; with both, `from < to`. |

Omit unused filters instead of sending empty strings. Filters combine with AND. Use `URLSearchParams` or equivalent so a positive UTC offset's `+` is encoded. Sorting is fixed: newest `startedAt` first, then `therapySessionId` descending. This is page/size pagination, not a cursor; reset to page 0 when filters change. An authorized empty or out-of-range page returns 200 with `sessions: []` and the filtered `total`. Later requests can see changes to the result set.

### Read frozen detail

List items return persisted status, timeline, active duration, quota date, and configuration references. Detail also returns `configurationParameters`, `sessionLimits`, and `technicalProgress`. Configuration values come from the session snapshot, not the latest plan. `endedAt` can be null; `activeDurationSeconds` is a persisted counter, not elapsed wall-clock time.

- Schema 1: `technicalProgress.availability` is `NOT_APPLICABLE`; criterion, target, counters and `targetReached` are all null. Do not display inferred zeros.
- Schema 2: availability is `AVAILABLE`; a session with no credited activity can have real zero counters. `ACTIVE_TIME` targets are in seconds, `REPETITIONS` targets are counts, and `activeMilliseconds` is always milliseconds.

These GETs do not change status, renew or expire a lease, or infer completion. They expose neither raw telemetry nor report readiness.

### History and detail errors

Both controllers map errors to `{ code }` with `Cache-Control: no-store`: 400 `SESSION_READ_INVALID_REQUEST` for invalid inputs; 401 `SESSION_READ_UNAUTHENTICATED`; 403 `SESSION_READ_FORBIDDEN` uniformly for missing or denied resources; 503 `SESSION_READ_UNAVAILABLE` for read or representation failures. Framework-level authentication can reject before this mapping. Never treat 403 or 503 as an empty page.

Sources: `DoctorTherapySessionReadController`, `DoctorTherapySessionQueryService`, `JpaDoctorTherapySessionReadRepository`, and `DoctorTherapySessionReadResponse`. Contract cases: `DoctorTherapySessionReadControllerTest`, `DoctorTherapySessionQueryServiceTest`, and `DoctorTherapySessionDetailTest`.

## Doctor workflow: preview, save, and download a report

Use the patient and session IDs from Clinical history. Each route requires a bearer access token with `DOCTOR`, without `PATIENT` or `ADMIN`, plus session authorship and a current active assignment. Use canonical lowercase UUIDs. None of these routes takes a JSON body; do not send `{}` or a JSON content type for snapshot creation.

| Method and full path | Query | Additional request header | Success |
| --- | --- | --- | --- |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-preview` | Optional metric selector | None | 200, `DoctorSessionReport` |
| `POST /api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-snapshots` | Optional metric selector | `Idempotency-Key` | 201 new, with `Location`; 200 replay. Both return `DoctorReportSnapshotResponse`. |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-snapshots` | `page`, `size` | None | 200, `DoctorReportSnapshotsResponse` |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-snapshots/{reportSnapshotId}` | None | None | 200, `DoctorReportSnapshotResponse` |
| `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-snapshots/{reportSnapshotId}/pdf` | None | None | 200, binary `application/pdf` |

### Preview and select metrics

Preview composes a technical report for a completed session without saving it. Clinical resolves identities through Profile and reads observed evidence through Analytics; the browser does not assemble the report from internal routes. A report can succeed with pending or missing identity details. Show `CONFIRMED_NO_EVIDENCE` as no analytical evidence, not as an error or proof that no telemetry was sent. A failed dependency or incomplete composition returns 503, not analytical absence. Observed streams and gaze plots are bounded views; `hasNext` does not mean this report endpoint accepts a cursor.

For preview or a new snapshot, omit all selectors for the base report or send **one** selector with the exact value `SYNTHETIC` or `PRODUCER_REPORTED`. Match it to the frozen session mode. Do not send empty or repeated values.

| Frozen mode | Query key | Optional JSON section |
| --- | --- | --- |
| `TARGET_SEARCH_WITH_DISTRACTORS` | `visualSearchDataOrigin` | `visualSearch` |
| `STANDARD_SCANNING` | `standardScanningDataOrigin` | `standardScanning` |
| `SUSTAINED_TARGET_FIXATION` | `sustainedFixationDataOrigin` | `sustainedFixation` |
| `COMPENSATORY_SACCADE` | `compensatorySaccadeDataOrigin` | `compensatorySaccade` |
| `SYSTEMATIC_EXPLORATION` | `systematicExplorationDataOrigin` | `systematicExploration` |

An omitted section is not a zero result. Preserve the reported origin and distinguish technical measurements from a diagnosis or clinical efficacy.

### Save and retrieve an immutable copy

- Snapshot creation composes again; it does not accept the preview JSON. Treat the POST response as the authoritative saved copy. Use a key of 1–128 characters after trimming. If the outcome is uncertain, reuse the same key and selection, including omission. An authorized identical replay returns the stored copy without re-enrichment. Changing the selection under that key returns 409; a new key intentionally creates another copy.
- List returns metadata only: `reportSnapshotId` and `createdAt` per item. Defaults: `page=0`, `size=20`; size 1–100, nonnegative page, and `page * size` within a signed 32-bit integer. Order: `createdAt` descending, then snapshot UUID descending. Authorized empty pages return `snapshots: []` and the total.
- Detail returns `{ reportSnapshotId, createdAt, report }`. Detail and PDF use only the saved copy, with no Profile/Analytics refresh or recalculation. Later data or profile edits do not change older copies.

### Download the PDF

Make an authenticated request with the saved snapshot ID. On success read the response as a blob, not JSON. The server sends `Content-Disposition: attachment; filename="session-report-<snapshot-id>.pdf"`. If the browser cannot read that header cross-origin, derive the same filename from the snapshot ID. The PDF can include the frozen heatmap, gaze plot, and selected metrics when present and renderable; it does not fetch missing data.

Check HTTP status before reading the blob. Controller errors are JSON `{ code }`, never partial PDFs. Preview uses `SESSION_REPORT_` and saved-copy routes use `SESSION_REPORT_SNAPSHOT_`. Handle 400 invalid input, 401 authentication, 403 denial, 404 authorized missing snapshot (detail/PDF), 409 selection conflict (create), and 503 unavailable composition, persistence, or rendering as applicable. Controller responses use `Cache-Control: no-store`; framework errors can have a different shape.

Sources: `DoctorSessionReportController`, `DoctorSessionReportSnapshotController`, and `SessionReportRestAssembler`. Contract cases: `DoctorSessionReportControllerTest`, `DoctorSessionReportSnapshotControllerTest`, `DoctorSessionReportSnapshotListControllerTest`, and `DoctorSessionReportPdfControllerTest`.
