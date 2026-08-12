# Run NeuroVision with Docker Compose

Use the root `compose.yaml` to run IAM, Profile, Clinical, three isolated PostgreSQL databases, and Kafka without Kubernetes. The existing Kubernetes manifests remain unchanged.

## Quick path

Run these commands from the repository root.

1. Build the three Linux native executables with Mandrel and embed Swagger UI only for Compose:

   ```powershell
   .\mvnw.cmd -B -ntp -Pcompose -pl iam-service,profile-service,clinical-service -am package "-Dnative" "-Dquarkus.native.container-build=true" "-DskipTests"
   ```

2. Generate `.env` with random database passwords, Cognito outputs, and one-hour credentials for the limited IAM role:

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
   ```

5. Open Swagger UI:

   - IAM: <http://localhost:8081/q/swagger-ui>
   - Profile: <http://localhost:8082/q/swagger-ui>
   - Clinical: <http://localhost:8083/q/swagger-ui>

## Prerequisites

| Requirement | Why it is needed |
|---|---|
| Docker Desktop or Docker Engine with Compose v2 | Runs the complete local topology and the Mandrel native builder. |
| JDK 21 | Maven and Quarkus augmentation still run on the host before Mandrel compiles inside Docker. |
| Available ports `8081`, `8082`, `8083`, and `9092` | Exposes IAM, Profile, Clinical, and Kafka to the host. |
| AWS CLI v2 and authorized project profiles | Required only to generate real Cognito IAM credentials and run invitation flows. |

Docker alone can run already-published or previously-built images. A fresh repository clone currently also needs JDK 21 because the service Dockerfiles package native executables produced by Maven; they do not compile source code themselves.

## First run from a fresh clone

```powershell
git clone <repository-url>
cd neurovision-backend

# Build native artifacts with the Compose-only documentation profile.
.\mvnw.cmd -B -ntp -Pcompose -pl iam-service,profile-service,clinical-service -am package `
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

## Local endpoints

| Service | Host URL | Container address |
|---|---|---|
| IAM | `http://localhost:8081` | `iam-service:8080` |
| Profile | `http://localhost:8082` | `profile-service:8080` |
| Clinical | `http://localhost:8083` | `clinical-service:8080` |
| Kafka | `localhost:9092` | `kafka:9092` |

The local Compose topology intentionally uses HTTP and does not read the Kubernetes TLS certificate. Cognito and OIDC discovery continue using their public HTTPS endpoints.

The machine running Compose does not need k3d. If k3d is also running, consider stopping it to reduce memory use; the Compose ports were chosen not to reuse the k3d gateway ports.

## OpenAPI and Swagger UI

| Service | Swagger UI | OpenAPI document |
|---|---|---|
| IAM | `http://localhost:8081/q/swagger-ui` | `http://localhost:8081/q/openapi` |
| Profile | `http://localhost:8082/q/swagger-ui` | `http://localhost:8082/q/openapi` |
| Clinical | `http://localhost:8083/q/swagger-ui` | `http://localhost:8083/q/openapi` |

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

# Rebuild images after recompiling source code.
docker compose build
docker compose up -d --force-recreate iam-service profile-service clinical-service
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
