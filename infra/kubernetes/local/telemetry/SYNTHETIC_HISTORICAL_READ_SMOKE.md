# Local k3d synthetic historical-read smoke

This bounded smoke was executed **once** in local k3d on 2026-09-18 with explicit authorization, synthetic data, and the development Cognito pool. The one-shot script now refuses to run against the populated baseline; do not reset data or retry it merely to repeat the smoke.

## Observed result

- Two user-operated PKCE sign-ins matched the sole active test doctor-patient assignment before any POST.
- Clinical returned `201` for one plan, one configuration, and one `ACTIVE` session. Telemetry returned `202` for one dual-kind batch.
- TimescaleDB persisted one inbox row and two samples (`GAZE=1`, `GAMEPLAY=1`); Kafka source offset total increased from zero to one and DLQ offsets stayed at zero.
- Patient historical reads returned two `200` pages with one sample each, a cursor only on page one, and both kinds. Anonymous read returned `401`; a random unknown session returned generic `403`.
- Independent aggregate readback confirmed one plan, one session, one inbox row, and two samples. Both owned loopback forwards were stopped. No identifiers, tokens, coordinates, or raw response bodies were printed.

The remainder of this page documents the one-shot procedure for audit, not an instruction to repeat it against the current k3d data.

## Read-only preflight

1. Confirm the context is `k3d-neurovision-local` and Clinical, Telemetry, TimescaleDB, and Kafka are Ready in `neurovision`.
2. Query only aggregate counts: Clinical patient statuses, active doctor-patient assignments, plans/sessions; Telemetry inbox/samples; Kafka source/DLQ offsets. Do not print subjects, Clinical patient IDs, coordinates, or token values.
3. Before the completed first run, the baseline was four test patients, one active assignment, zero plans/sessions/inbox/samples, and source/DLQ offsets at zero. That baseline has now changed. Stop on any future invocation; do not create a second plan or batch blindly.
4. Use loopback-only forwards, not a public route: `kubectl -n neurovision port-forward --address 127.0.0.1 service/clinical-service 18083:8080` and, separately, `kubectl -n neurovision port-forward --address 127.0.0.1 service/telemetry-service 18084:8080`. Health/readiness GETs and unauthenticated historical GETs are safe in preflight. The latter should return `401`.

With those two forwards already running, `smoke-test-telemetry-historical-read-k3d.ps1 -PreflightOnly` checks the exact empty-state baseline, Kafka offsets, local readiness, and anonymous `401`. This mode reads neither `.env` nor Cognito, opens no browser, and performs no POST. It is not a substitute for the authenticated run.

The four Clinical patients are `PENDING_ASSESSMENT`. Current plan creation and session admission require an active doctor-patient assignment, but do not independently require patient status `ACTIVE`; do not turn this implementation detail into a clinical policy. The test DOCTOR and PATIENT must match the **same sole active assignment**. Check subjects against that assignment in memory, without logging identifiers. Stop on a mismatch rather than changing an assignment or choosing another patient.

## Authorized synthetic run

Run this section only after the user explicitly authorizes browser-operated DOCTOR and PATIENT PKCE against `neurovision-dev-cognito` in `us-east-1` and the single local synthetic HTTP write sequence. Do not reuse earlier access tokens or authorization codes. The script or operator must never print the authorization URL, callback code, tokens, subjects, session IDs, response bodies, or coordinates.

The guarded harness is `smoke-test-telemetry-historical-read-k3d.ps1 -AuthorizedSyntheticRun`. It requires both preexisting loopback forwards and refuses to run if the exact empty baseline changed. **Do not invoke this mode until the separate authorization is recorded.** It does not manage port-forward processes or accept alternate service ports.

1. Sign in as the assigned test DOCTOR using PKCE and validate issuer, client, `token_use=access`, expiry, and the `DOCTOR` group. Sign in as the assigned test PATIENT the same way, requiring `PATIENT` and rejecting `DOCTOR`/`ADMIN`. Validate both subjects against the one active Clinical assignment before any POST.
2. Through the loopback Clinical forward, create exactly one plan prescribing the external code `fixation` with `POST /api/v1/clinical-patients/{clinicalPatientId}/therapy-plans` (`201`). Then append exactly one schema-v1 `fixation` configuration with `POST /api/v1/clinical-patients/{clinicalPatientId}/therapy-plans/{therapyPlanId}/configurations` (`201`), effective shortly in the future. Clinical uses `FIXATION` only as an internal enum name; the JSON requests, response codes, and prescription map keys use lowercase `fixation`. The request shapes and technical parameter bounds are in `docs/plans/clinical-doctor-therapy-plan-api.md`.
3. As the test PATIENT, read `GET /api/v1/me/therapy-plans` (`200`) and require precisely the just-created active `fixation` plan. Start one session using `POST /api/v1/me/therapy-plans/{therapyPlanId}/sessions` with a fresh `Idempotency-Key` (`201`). The Clinical session lease is two minutes; send the batch promptly. A retry with the same key must not create another session.
4. Through the loopback Telemetry forward, send exactly one new synthetic V1 dual-kind batch to `POST /api/v1/telemetry/batches` (`202`), based on `telemetry-service/src/test/resources/fixtures/telemetry/v1/dual-typed-batch.json`. Replace all generated IDs and capture/send timestamps in memory. Never send camera frames or real measurements. Do not call the existing Compose ingestion script: it creates another session and targets the Compose database.
5. Poll only aggregate TimescaleDB counts until one inbox row and two sample rows (`GAZE=1`, `GAMEPLAY=1`) appear. Confirm source Kafka offset increases by one and DLQ offsets remain unchanged. Stop on timeout or DLQ movement; do not replay or send a second batch.
6. Read `GET /api/v1/telemetry/sessions/{therapySessionId}/samples` as the PATIENT with the exact `streamId`, a bounded UTC window under 15 minutes, and `limit=1`. Expect page 1 `200`, one sample and a cursor; page 2 `200`, the other sample and no cursor. Assert distinct sample keys and both kinds in memory. Separately expect `401` without a token and generic `403` for a random unknown session with the patient token. Print only status codes, counts, kinds, and cursor presence.

Clinical's historical-read authorization is rechecked for every page. The session need not remain `ACTIVE` for a later authorized read, but its ownership/assignment must still permit access. Do not expose Clinical internal endpoints or add a public Telemetry route for this smoke.

## Stop and recovery

- Fail closed before writes if context, service health, image identity, baseline counts, Cognito pool, PKCE callback, role, or assignment differs from expectation.
- If the plan POST succeeds and a later step fails, preserve its generated IDs only in protected process memory for diagnosis; do not automatically retry with a new plan, session, or batch. Compare aggregate counts before resuming.
- Leave both PVCs, Kafka topics, and the Clinical backup intact. No volume deletion, DLQ replay, registry pull, Git staging, branch, commit, push, or PR is part of this run.
- Close the two port-forward processes after verification; never bind them to `0.0.0.0`.
