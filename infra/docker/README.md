# Run NeuroVision with Docker Compose

Use the root `compose.yaml` to run IAM, Profile, Clinical, Telemetry, three isolated PostgreSQL databases, TimescaleDB, and Kafka without Kubernetes. The existing Kubernetes manifests remain unchanged. This is a **development environment for synthetic data**, not a deployment for real patient data: local Kafka and its payload-bearing DLQ use PLAINTEXT without production access controls or retention.

## Exploratory Telemetry capacity observation

The capacity harness is an intentionally small, sequential synthetic observation through PATIENT-authenticated Telemetry HTTP, Clinical authorization, Kafka, and TimescaleDB. It is not a benchmark, production-capacity result, multi-session test, clinical validation, SLA, or estimate of the vision algorithm's sampling rate. Synthetic sample offsets are fixture-shaped placeholders; the harness measures HTTP submission cadence separately.

Run the offline contract tests first; this is also the default mode and makes no service, OIDC, database, Kafka, file, or marker calls:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\test-telemetry-compose-capacity.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-telemetry-compose-capacity.ps1
```

An optional `-Mode Preflight` performs read-only loopback readiness, Compose ownership, database aggregate, Kafka-offset, and consumer-lag checks. It does not start or stop services. A real run requires fresh, separate user authorization for every side effect and bounded workload setting:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-telemetry-compose-capacity.ps1 `
  -Mode AuthorizedRun -AuthorizePatientPkce -AuthorizeClinicalSession `
  -AuthorizeTelemetryWrite -AuthorizeExpiredSessionTransition -ConfirmSyntheticData `
  -BatchCount 5 -SamplesPerBatch 10 -BatchIntervalMs 1000
```

The live flow requires exactly one existing active eligible PATIENT plan, then asks for confirmation before creating a Clinical test session. Clinical may expire an already-expired active session while admitting a new one, so that separate effect has its own mandatory authorization switch. The token stays in process memory; no password, token, code, plan/session/stream ID, payload, coordinate, timestamp, or DLQ body is emitted. Telemetry `202` is only broker acceptance; the harness separately waits for exact run-scoped Timescale counts and verifies accepted/consumer offset deltas, zero stable lag, and no DLQ movement. No session, batch, service, volume, or marker is cleaned up automatically. The one-shot marker in the system temporary directory is retained and blocks another live run until a human deliberately removes it. The bounded defaults are harness choices, not product requirements.

Reported HTTP ACK latency starts immediately before the POST, after request serialization. Persistence and consumer-confirmation durations are monotonic elapsed time since the final HTTP ACK; the preflight DLQ value is a single read-only snapshot, not a stability comparison.

## Quick path

Run these commands from the repository root.

1. Build the four Linux native executables with Mandrel and embed Swagger UI only for Compose:

   ```powershell
   .\mvnw.cmd -B -ntp -Pcompose -pl iam-service,profile-service,clinical-service,telemetry-service -am package "-Dnative" "-Dquarkus.native.container-build=true" "-DskipTests"
   ```

2. Generate `.env` with random database passwords, Cognito outputs, and one-hour credentials for the limited IAM role. This command accesses the configured AWS profiles; run it only with your own authorized AWS access. To validate Compose syntax without AWS access, use `.env.example` instead (see below).

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\configure-local-env.ps1
   ```

3. Build and start the Compose services:

   ```powershell
   docker compose up --build -d
   docker compose ps
   ```

4. Verify the native services:

   ```powershell
   curl.exe http://localhost:8081/q/health/ready
   curl.exe http://localhost:8082/q/health/ready
   curl.exe http://localhost:8083/q/health/ready
   curl.exe http://localhost:8084/q/health/ready
   ```

5. Open Swagger UI:

   - IAM: <http://localhost:8081/q/swagger-ui>
   - Profile: <http://localhost:8082/q/swagger-ui>
   - Clinical: <http://localhost:8083/q/swagger-ui>
   - Telemetry: <http://localhost:8084/q/swagger-ui>

## Prerequisites

| Requirement | Why it is needed |
|---|---|
| Docker Desktop or Docker Engine with Compose v2 | Runs the complete local topology and the Mandrel native builder. |
| JDK 21 | Maven and Quarkus augmentation still run on the host before Mandrel compiles inside Docker. |
| Available ports `8081`–`8084` and `9092` | Exposes IAM, Profile, Clinical, Telemetry, and Kafka to the host. |
| AWS CLI v2 and authorized project profiles | Required only to generate real Cognito IAM credentials and run invitation flows. |

Docker alone can run already-published or previously-built images. A fresh repository clone currently also needs JDK 21 because the service Dockerfiles package native executables produced by Maven; they do not compile source code themselves.

## First run from a fresh clone

```powershell
git clone <repository-url>
cd neurovision-backend

# Build native artifacts with the Compose-only documentation profile.
.\mvnw.cmd -B -ntp -Pcompose -pl iam-service,profile-service,clinical-service,telemetry-service -am package `
  "-Dnative" `
  "-Dquarkus.native.container-build=true" `
  "-DskipTests"

# Requires the configured neurovision-personal and neurovision-iam-dev AWS profiles.
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\infra\docker\configure-local-env.ps1

docker compose up --build -d
docker compose ps
```

After the first run, use `docker compose up -d`. Add `--build` only after rebuilding or changing an image.

For a deliberately offline native rebuild, ensure the Maven dependencies and Mandrel builder image are already cached. Maven `-o` alone does **not** prevent Quarkus from pulling its builder image: also pass `"-Dquarkus.native.builder-image.pull=never"`. Build the resulting service image with `docker build --pull=false --network=none`; a missing prerequisite then fails instead of being downloaded.

## Local endpoints

| Service | Host URL | Container address |
|---|---|---|
| IAM | `http://localhost:8081` | `iam-service:8080` |
| Profile | `http://localhost:8082` | `profile-service:8080` |
| Clinical | `http://localhost:8083` | `clinical-service:8080` |
| Telemetry | `http://localhost:8084` | `telemetry-service:8080` |
| TimescaleDB | Not host-exposed | `telemetry-timescaledb:5432` |
| Kafka | `localhost:9092` | `kafka:9092` |

The local Compose topology intentionally uses HTTP and does not read the Kubernetes TLS certificate. Cognito and OIDC discovery continue using their public HTTPS endpoints.

The machine running Compose does not need k3d. If k3d is also running, consider stopping it to reduce memory use; the Compose ports were chosen not to reuse the k3d gateway ports.

## OpenAPI and Swagger UI

| Service | Swagger UI | OpenAPI document |
|---|---|---|
| IAM | `http://localhost:8081/q/swagger-ui` | `http://localhost:8081/q/openapi` |
| Profile | `http://localhost:8082/q/swagger-ui` | `http://localhost:8082/q/openapi` |
| Clinical | `http://localhost:8083/q/swagger-ui` | `http://localhost:8083/q/openapi` |
| Telemetry | `http://localhost:8084/q/swagger-ui` | `http://localhost:8084/q/openapi` |

The Maven `compose` profile embeds Swagger UI at build time. Normal native builds that omit `-Pcompose` do not embed it, so Kubernetes artifacts remain unaffected.

The same profile enables Authorization Code with PKCE for Swagger UI. Choose the `authorization_code` entry, enter the public Cognito App Client ID, leave `client_secret` empty, and request `openid`, `email`, and `profile`. The `implicit` entry discovered from Cognito must not be used.

## Cognito smoke test

Point the existing smoke script directly at IAM:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\aws\cognito\smoke-test-patient-invitation.ps1 `
  -Operation InvitePatient `
  -PatientEmail "controlled-address@example.com" `
  -IamServiceBaseUrl "http://localhost:8081"
```

## Temporary AWS credentials

The IAM container receives temporary credentials for `neurovision-iam-dev`; they expire after one hour. Refresh and recreate IAM before another administrative Cognito operation:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\configure-local-env.ps1
docker compose up -d --force-recreate iam-service
```

The generated `.env` is ignored by Git. Never copy its contents into `compose.yaml`, Dockerfiles, or committed documentation.

`TELEMETRY_DB_PASSWORD` is generated once and reused on subsequent runs, like the other database passwords. The TimescaleDB image is pinned to `timescale/timescaledb:2.30.1-pg17`; its named volume is separate from the three PostgreSQL volumes. The Telemetry service receives development-only bounded request/Kafka limits in `compose.yaml`. They are **not** clinical limits or measured production capacity. Its JDBC connection runs Flyway V1–V3 at startup, while the reactive connection handles sample reads and writes. Kafka topic provisioning already includes the Telemetry event, its DLQ, and replay audit topic.

## Telemetry Phase 10: orthogonal Compose evidence (TCS-5/TCS-6/TCS-7)

**Current outcome (2026-09-29): Phase 10 is complete for its Compose objective using orthogonal evidence.** TCS-5 independently proved isolated clean startup, migration-path readiness without manual repair, Kafka initialization, dependency health, and Telemetry readiness from an empty Timescale volume; individual migration versions were not captured. In the base `neurovision-rehab` environment, separate synthetic evidence proved authenticated HTTP `202` → Kafka → Timescale ingestion, unchanged persisted aggregates across the bounded restart (`1/2/1/1/0`), authenticated paginated historical read after recovery, and the previously recorded Timescale outage/recovery. **The combined isolated TCS-6 flow was not completed and is explicitly non-gating/retired by the architectural decision.** Reusing base records would invalidate isolation; creating another identity solely for the combined smoke is not required. Never report TCS-6 as passed.

TCS-7 cleanup completed with the exact project-scoped command `docker compose --env-file .env -p telemetry-clean-start-20260926 -f infra/docker/telemetry-clean-start.compose.yaml down --volumes --remove-orphans` (exit 0). The isolated post-state was C0/N0/V0; all four named volumes were directly confirmed absent. Five anonymous Kafka/init mounts were proven exclusive to the isolated project before cleanup; their removal is inferred from successful Compose `down --volumes` semantics because their names were not retained, not independently checked by name. The base environment remained C10/N1/V5 and its recorded fingerprint was unchanged; the two-container k3d fingerprint was also unchanged. No raw volume names or patient identifiers are included.

The detail below is a retained operational/history record. Where an older paragraph says TCS-6 is pending or cleanup was not performed, that was the state at that time and is superseded by the current outcome above.

The disposable definition `telemetry-clean-start.compose.yaml` is standalone, not an overlay on `compose.yaml`. It fixes a distinct project, network, and four named volumes; it publishes only loopback ports `18081`, `18083`, `18084`, and `19092`. Its Timescale volume is `telemetry-clean-start-20260926-telemetry-data`; it never references `neurovision-rehab_telemetry-timescaledb-data`. The definition includes IAM and IAM PostgreSQL for the original doctor invitation event path, plus Clinical, Telemetry, Kafka/topic initialization, and their databases. TCS-5 runtime evidence was the historical six-container run. The revised eight-container model was subsequently started under bounded authorization and removed by TCS-7 cleanup; the combined authenticated TCS-6 flow was not completed. IAM requires repository-root `.env` configuration and short-lived AWS credentials. Keep resolved configuration private.

Run the default fake-only guard suite and validate the Compose model without starting services:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\test-telemetry-clean-start.ps1
docker compose --env-file .env -p telemetry-clean-start-20260926 -f .\infra\docker\telemetry-clean-start.compose.yaml config -q
```

The offline suite does not call Docker, HTTP, Cognito, or a database. `config -q` only validates the model. The clean-start harness refuses resource/model/port collisions before its one scoped clean-start `compose up`, requires explicit switches, disables builds and pulls, and has no cleanup operation.

The reported exact escalated TCS-5 `AuthorizedStart` exited 0 with six isolated containers, PostgreSQL, TimescaleDB, and Kafka healthy, `kafka-init` exit 0, and Telemetry readiness HTTP 200 from the empty TimescaleDB volume. This is historical TCS-5 runtime/readiness evidence; individual migration versions were not separately captured. The existing `neurovision-rehab` environment and Git/index remained unchanged. The stack was left running after TCS-5, but later read-only inspection found its six existing containers stopped with restart policy `no`; no stop operation was performed in that inspection. A later separately authorized `ResumeIsolatedCore` invocation returned nonzero at the old `up --wait` command because the included one-shot `kafka-init` correctly exited; read-only follow-up verified the intended six-service state, `kafka-init` exit 0, and Telemetry readiness HTTP 200. This is observed resume behavior, not a harness PASS or replacement for the historical TCS-5 clean-start evidence. Authenticated ingestion was not run, and cleanup was not performed. Anonymous metadata/JWKS access to the configured development issuer was permitted, but issuer access was not independently observed.

TCS-6 remains incomplete. An earlier escalated IAM-only start attempt stopped safely before creating resources because PowerShell 5.1 argument quoting made a network-inspection template command fail. A later separately authorized invocation exited 1 with `iam_start_guard_failed`, while parent-reported follow-up found IAM PostgreSQL healthy, IAM service running, and IAM readiness HTTP 200. This is partial observed runtime state, not a harness PASS; no doctor invitation, login, or API/data flow is claimed. Source inspection showed `iam_start` was not mapped to a specific diagnostic and remained set during readiness polling; an offline correction now maps operation and readiness failures separately, but was not runtime-retried. Later read-only diagnosis found the same PowerShell 5.1 native-argument transport issue in the existing-container Go-template listing. The clean-start harnesses now avoid Docker Go-template output entirely: resource presence uses template-free `--quiet` listings, while container/network/volume state and labels are parsed from JSON. Fake-only regressions cover exact counts, labels/states, empty/malformed JSON, and fixed diagnostics. This code correction was not validated by another IAM start. The old core has since reached the expected running/healthy state, but the prior resume harness returned nonzero; do not repeat it while these services are running. If the six core containers are stopped in a later authorized window, the corrected mode resumes only those existing containers using ordered `docker compose start` calls: first the three long-running dependencies, then it verifies dependency health and that `kafka-init` remains exited 0, then it starts Clinical and Telemetry. It uses no `up`, recreation, build, pull, IAM start, or cleanup. The corrected mode has not been run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-telemetry-clean-start-e2e.ps1 -Mode ResumeIsolatedCore -AuthorizeIsolatedCoreResume -ConfirmDisposableSyntheticEnvironment
```

Before any IAM startup, separately authorize starting only the isolated IAM database/service (they publish to the clean-start Kafka broker, not the existing project):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-telemetry-clean-start-e2e.ps1 -Mode StartIsolatedIam -AuthorizeIsolatedIamStart -ConfirmDisposableSyntheticEnvironment
```

The following original stage sequence is retained as historical design, not as a current Phase 10 action or acceptance step. Do not execute it solely to close Phase 10. It described user-operated `-Mode DoctorSetup` (browser-based DOCTOR PKCE, masked execution-time patient email, one IAM invitation/Cognito provisioning and Clinical plan/config writes), `-Mode PatientSession` (user-operated PATIENT PKCE and one ACTIVE session), `-Mode SubmitBatch` (one generated fixture batch only), and `-Mode Verify` (bounded batch-ID-scoped inbox/sample-kind counts only). Each stage refuses its prior side-effect marker so an ambiguous invitation, session, or ingestion result cannot be replayed automatically. The browser opens without writing the login URL to console; access tokens and patient email remain in memory only. A local temporary state file carries only pseudonymous internal identifiers and stage flags, never credentials, email, token, sample payload, or coordinates. Do not manually clear it to bypass a refusal; resolve the one-shot stage result first. The offline tests use fakes/pure guards only. This combined TCS-6 path was not completed and is retired/non-gating by architectural decision: reusing base records would invalidate isolation, and creating another identity solely for the combined smoke is not required. TCS-7 cleanup is complete as recorded above. Never use this definition for real patient data.

## Analytics Phase 13B: real Clinical authorization

The repository overlay `analytics-real-clinical.compose.yaml` and harness `smoke-test-analytics-real-clinical-compose.ps1` target only the isolated project `analytics-ca13b-realclinical-20260925`; the default Compose configuration is unchanged. The overlay exposes Clinical and Analytics only on loopback ports `18083` and `18085`, removes dependency host ports, uses the prebuilt Analytics **JVM** image, and attaches only the pre-existing, test-only `neurovision-rehab_clinical-postgresql-data` volume externally. Kafka and MongoDB use isolated project volumes. It requires an existing local `.env`, prebuilt `neurovision/analytics-service:compose-jvm` and Clinical images, and that Clinical volume containing sessions associated with the existing PATIENT and DOCTOR test accounts. It does not create users or seed Clinical data. Do not run it with another project name or real patient data.

From the repository root, validate the model and start only this isolated stack with pre-existing images. The default harness invocation is static preflight only: it makes no Cognito request, browser login, database query, Kafka publish, or API call.

```powershell
$project = 'analytics-ca13b-realclinical-20260925'
$overlay = 'infra/docker/analytics-real-clinical.compose.yaml'
docker compose -p $project -f compose.yaml -f $overlay config -q
docker compose -p $project -f compose.yaml -f $overlay up -d --no-build --pull never --wait --wait-timeout 120 analytics-service
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-analytics-real-clinical-compose.ps1
```

The overlay assumes the approved external Clinical test volume already exists; never create or replace it to make this smoke pass. Its effective Compose model was compared with the previously used ignored test overlay and matched. This is a local JVM Analytics smoke, **not** verification of an Analytics native image, production readiness, or Kubernetes behavior.

Only when the operator explicitly chooses the interactive test, use `-RunPkce` with an existing test account. The script opens Cognito hosted sign-in; do not pass a password or token on the command line. It requires Clinical and Analytics ready on their loopback ports. If the authenticated subject has no matching session in the approved volume, it stops without seeding one. On a match, it checks Clinical directly, publishes one synthetic V1 event to isolated Kafka, waits at most 20 seconds for an Analytics summary and revision, then expects a generic 403 for an unrelated session. Output contains only statuses, role, and pass/fail markers—not identifiers, tokens, coordinates, or response bodies.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-analytics-real-clinical-compose.ps1 -RunPkce -Role PATIENT
```

The event fixture is `analytics-service/src/test/resources/fixtures/telemetry/v1/accepted-event-wire.json`; the script substitutes fresh event, batch, stream, and correlation IDs in memory. An unsuccessful run may have published its one event, so inspect the isolated state before deliberately running it again. Do not use `docker compose logs` or `down -v` for this test.

To opt into the fail-closed check, add `-VerifyClinicalUnavailable` to an interactive `-RunPkce` invocation. Only after an authorized Analytics summary does the harness stop the isolated project's `clinical-service`, query that same summary with the same in-memory access token, and require HTTP 503. A `finally` block starts only that existing service and waits a bounded time for readiness, including when the assertion fails. It does not reset or delete volumes; allow for a brief Clinical outage in this isolated project only.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-analytics-real-clinical-compose.ps1 -RunPkce -Role PATIENT -VerifyClinicalUnavailable
```

Observed on 2026-09-25: independent PATIENT and DOCTOR PKCE smokes each returned Clinical 200, Analytics summary 200 with revision, and unrelated-session 403. A separate PATIENT PKCE outage smoke returned the same 200/403 results, then Analytics 503 while isolated Clinical was stopped; Clinical was restored and ready before teardown. Exactly three pinned synthetic V1 events were published, one per smoke. The full `analytics-service` Maven test run passed: 126 tests, zero failures, errors, or skips. After both services returned readiness 200, `docker compose stop` stopped only this isolated project; no service remains running and volumes were preserved.

When finished, stop this project without deleting volumes:

```powershell
docker compose -p $project -f compose.yaml -f $overlay stop
```

## Analytics Phase 13C: native image with real Clinical

The separate test-only overlay `analytics-native-real-clinical.compose.yaml` uses project
`analytics-ca13c-native-20260925`, loopback ports `18183` and `18185`, fresh Kafka/MongoDB
volumes, and the same approved external **test-only** Clinical volume. It does not seed
Clinical or alter the default Compose stack. The harness pins and verifies the local native
image ID and the running container image before interactive testing. A rebuilt image requires
reviewing that pin; a healthy JVM container is not native proof.

From the repository root, with cached Maven dependencies and builder/base images, build
offline and start only the isolated services:

```powershell
.\mvnw.cmd -o -B -ntp -pl analytics-service -am package "-Dnative" "-DskipTests" `
  "-Dquarkus.native.container-build=true" "-Dquarkus.native.builder-image.pull=never" `
  "-Dquarkus.native.container-runtime-options=--network=none"
docker build --pull=false --network=none -f analytics-service/src/main/docker/Dockerfile.native-micro `
  -t neurovision/analytics-service:compose-native analytics-service
$project = 'analytics-ca13c-native-20260925'
$overlay = 'infra/docker/analytics-native-real-clinical.compose.yaml'
docker compose -p $project -f compose.yaml -f $overlay config -q
docker compose -p $project -f compose.yaml -f $overlay up --no-build --pull never -d `
  clinical-postgresql analytics-mongodb kafka kafka-init clinical-service analytics-service
.\infra\docker\smoke-test-analytics-native-real-clinical-compose.ps1 -CheckNativeRuntime
```

The default harness invocation is local preflight. `-CheckNativeRuntime` checks readiness
and image identity without Cognito sign-in or event publishing. Only with explicit approval
for the development Cognito test accounts may an operator run `-RunPkce -Role DOCTOR` or
`-Role PATIENT`; the interactive login does not require sharing a password. On an eligible
Clinical session, the harness publishes one synthetic V1 event and checks Clinical 200,
Analytics summary 200 with revision, and an unrelated-session 403. A failed run may already
have published its event; inspect aggregate state before retrying. Do not replay DLQ data.
For a separately authorized outage probe, append `-VerifyClinicalUnavailable` to an
interactive PATIENT invocation. The harness checks Analytics 503 while only the isolated
Clinical service is stopped, then restores Clinical in `finally`.

Observed on 2026-09-25 with corrected native image
`sha256:4df5f6b03adb16b06539d8cca7dfd3a98724174257d054f4ce9f398908c44abf`:
the **DOCTOR** synthetic smoke passed those 200/200/403 checks. An earlier PATIENT attempt
stopped at Clinical session lookup before publishing; a later bounded PATIENT run with the
existing Compose test fixture passed Clinical 200, Analytics summary 200 with revision, and
unrelated-session 403 after exactly one fresh synthetic V1 event. An earlier native attempt
quarantined one synthetic event as `INVALID_EVENT`; it was not replayed. Aggregate final state
was accepted-topic end offset 3, DLQ end offset 1, MongoDB inbox count 2, and summary count 2.
A separate PATIENT outage smoke then returned Clinical 200, Analytics summary 200, unrelated
403, and Analytics 503 while Clinical was stopped. Independent readiness checks after the
harness restored Clinical returned 200 for both services. That smoke published one new
synthetic event: accepted-topic end offset 4, DLQ end offset still 1, MongoDB inbox and
summary counts 3/3. The old quarantined event was **not** replayed. Analytics JVM tests
passed 128/128. Kubernetes, production capacity, and real-patient privacy controls were
not verified by these smokes.

After verifying the project name and overlay, remove only this isolated project's
containers and network while retaining its named volumes and the external Clinical volume:

```powershell
docker compose -p $project -f compose.yaml -f $overlay down
```

Do **not** add `-v` or remove images. Keep the Phase 13B JVM project separate.

## Synthetic Telemetry verification

1. Check the Compose model without reading private `.env` values: `docker compose --env-file .env.example config --quiet`. Do not use the example file to run authenticated services; it contains placeholders.
2. After starting with your authorized local `.env`, verify `docker compose ps` and `http://localhost:8084/q/health/ready`. TimescaleDB and Kafka topic provisioning gate Telemetry startup; Clinical is started on the same network. Clinical authorization still fails closed if Clinical is unavailable.
3. In a **test-only patient account**, create or use an `ACTIVE` Clinical therapy session. Send a synthetic gaze/gameplay batch to `POST http://localhost:8084/api/v1/telemetry/batches` using its Cognito access token. A `202 BROKER_ACCEPTED` means Kafka acknowledged the event, **not** that TimescaleDB has committed it. Do not put the token in a committed script or terminal history. See the [batch contract](../../docs/plans/telemetry-phase-2-contract.md) and [Clinical authorization flow](../../docs/plans/telemetry-phase-3-http-batch-ingestion.md).
4. Wait for the consumer, then inspect only synthetic pseudonymous IDs and counts: `docker compose exec -T telemetry-timescaledb psql -U neurovision_telemetry -d neurovision_telemetry -c 'SELECT count(*) FROM telemetry_batch_inbox; SELECT count(*) FROM telemetry_samples;'`. Query `GET /api/v1/telemetry/sessions/{therapySessionId}/samples` with the test token and a bounded stream/time window to verify historical replay. See the [read contract](../../docs/plans/telemetry-phase-7-historical-replay-query.md). A real end-to-end proof requires those responses and matching rows; green health alone is insufficient.
5. Keep restart recovery separate from ingestion. The offline guards can be checked without Docker or service traffic:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\test-telemetry-compose-recovery.ps1
   ```

   After confirming that the selected local Telemetry volume contains only synthetic test data, run the read-only preflight. It verifies the fixed Compose project, volume ownership, required dependency state, and non-empty aggregate baseline; output contains counts only.

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-telemetry-compose-recovery.ps1 -Mode Preflight -ConfirmSyntheticData
   ```

   Only after explicitly authorizing a brief local database and Telemetry restart, run the existing bounded restart mode. It restarts only `telemetry-timescaledb` and `telemetry-service`, then requires their health/readiness and exact safe aggregate equality. This mode is unchanged by the separate outage check below.

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-telemetry-compose-recovery.ps1 -Mode AuthorizedRun -AuthorizeRestart -ConfirmSyntheticData
   ```

   The separate outage mode stops and starts **only** TimescaleDB; Telemetry is required to remain running. Use it only after a separate, explicit authorization for that specific local synthetic-data outage and only when the named volume is disposable synthetic-test state:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\smoke-test-telemetry-compose-recovery.ps1 -Mode OutageRun -AuthorizeDatabaseOutage -ConfirmSyntheticData -KeepTelemetryRunning
   ```

   The three confirmation switches are all required; this mode rejects the restart authorization switch. It verifies Telemetry is still running during the outage, starts only `telemetry-timescaledb`, waits for its health and then Telemetry readiness, and compares the same aggregate snapshot. Compose dependency declarations gate startup only, so no Java/Phase 9 readiness behavior is implied by this local harness. No ingestion, replay, database mutation, volume removal, `down`, or Telemetry restart occurs. Failures before a successful database stop report `restoration=not_required`; later failures make at most one scoped database start/health attempt and report `restoration=succeeded` or `restoration=failed`. On failure, inspect both local services before any retry; do not assume a failed restoration is safe to retry blindly. Neither mode proves an authenticated historical read. Never run `docker compose down -v` unless you intend to delete all local databases and Kafka data.

The service integration tests cover authenticated HTTP, Kafka, and Timescale behavior with synthetic fixtures independently of this deployed Compose smoke. A complete deployed-stack smoke still needs a controlled test Cognito identity and Clinical session; do not bypass authorization for convenience. For an existing V1 TimescaleDB volume, follow the [V2 preflight](../../docs/plans/telemetry-v2-migration-preflight.md) before starting a V2/V3-capable Telemetry image. A new empty volume has no historical V1 rows to preflight.

Your teammate must receive their own authorized AWS identity or role access. Do not share the generated `.env`, access keys, session token, or your personal AWS profile files.

## Daily commands

```powershell
# Start existing containers.
docker compose up -d

# Inspect status and logs.
docker compose ps
docker compose logs -f iam-service
docker compose logs -f profile-service
docker compose logs -f clinical-service
docker compose logs -f telemetry-service

# Rebuild images after recompiling source code.
docker compose build
docker compose up -d --force-recreate iam-service profile-service clinical-service telemetry-service
```

## Stop or reset

Preserve database and Kafka volumes:

```powershell
docker compose down
```

Delete all Compose data and start from an empty environment:

```powershell
docker compose down -v
```

The `-v` operation is destructive and does not affect k3d volumes.
