# Run NeuroVision with Docker Compose

Use the root `compose.yaml` to run IAM, Profile, Clinical, Telemetry, three isolated PostgreSQL databases, TimescaleDB, and Kafka without Kubernetes. The existing Kubernetes manifests remain unchanged. This is a **development environment for synthetic data**, not a deployment for real patient data: local Kafka and its payload-bearing DLQ use PLAINTEXT without production access controls or retention.

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
