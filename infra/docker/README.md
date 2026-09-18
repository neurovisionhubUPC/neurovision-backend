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

## Synthetic Telemetry verification

1. Check the Compose model without reading private `.env` values: `docker compose --env-file .env.example config --quiet`. Do not use the example file to run authenticated services; it contains placeholders.
2. After starting with your authorized local `.env`, verify `docker compose ps` and `http://localhost:8084/q/health/ready`. TimescaleDB and Kafka topic provisioning gate Telemetry startup; Clinical is started on the same network. Clinical authorization still fails closed if Clinical is unavailable.
3. In a **test-only patient account**, create or use an `ACTIVE` Clinical therapy session. Send a synthetic gaze/gameplay batch to `POST http://localhost:8084/api/v1/telemetry/batches` using its Cognito access token. A `202 BROKER_ACCEPTED` means Kafka acknowledged the event, **not** that TimescaleDB has committed it. Do not put the token in a committed script or terminal history. See the [batch contract](../../docs/plans/telemetry-phase-2-contract.md) and [Clinical authorization flow](../../docs/plans/telemetry-phase-3-http-batch-ingestion.md).
4. Wait for the consumer, then inspect only synthetic pseudonymous IDs and counts: `docker compose exec -T telemetry-timescaledb psql -U neurovision_telemetry -d neurovision_telemetry -c 'SELECT count(*) FROM telemetry_batch_inbox; SELECT count(*) FROM telemetry_samples;'`. Query `GET /api/v1/telemetry/sessions/{therapySessionId}/samples` with the test token and a bounded stream/time window to verify historical replay. See the [read contract](../../docs/plans/telemetry-phase-7-historical-replay-query.md). A real end-to-end proof requires those responses and matching rows; green health alone is insufficient.
5. Run `docker compose restart telemetry-service telemetry-timescaledb` and repeat the counts to confirm the named volume preserved synthetic rows. Never run `docker compose down -v` unless you intend to delete all local databases and Kafka data.

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
