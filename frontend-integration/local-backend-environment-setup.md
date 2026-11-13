# Start the local native backend

Use this guide to build and start IAM, Profile, Clinical, Telemetry, and Analytics before connecting a frontend. All commands run from the repository root, not from this directory. Use the base `compose.yaml` only; no image archive, prebuilt runner, proxy, test fixture, host Maven, or host Mandrel is required.

## Quick start

1. Check out a revision that includes `0e49f20`. Use Docker Desktop in Linux container mode on an amd64 host, with Compose and BuildKit enabled. The first build needs access to the image registries and Maven repositories for uncached inputs. See [native build requirements](../COMPOSE_NATIVE.md) for memory guidance.
2. Copy `.env.example` to `.env` if you do not already have one. Set local database passwords, the Analytics cursor signing key, and the team's development Cognito settings. Use only credentials authorized for your local workflow, if needed. Never commit `.env`. Start with fresh local volumes for a clean setup; if reusing a database with data, check migrations and back it up first.
3. Build and start the stack:

   ```powershell
   docker compose up --build
   ```

   Leave Compose running. Use a second terminal for readiness checks. Do not use the JVM or prebuilt-image override for this workflow.

## What Compose starts

Compose builds the five native application images and starts their containers alongside these dependencies, using separate images:

| Component | Local dependency |
| --- | --- |
| IAM, Profile, and Clinical | One PostgreSQL instance per service |
| Telemetry | TimescaleDB |
| Analytics | MongoDB |
| Messaging | Kafka broker and the one-shot `kafka-init` service, which creates the configured topics |

You do not need to install these dependencies on Windows. Compose creates named volumes on first startup and reuses them on later starts. A fresh stack does not copy another developer's data or create Cognito accounts.

## Verify readiness

Check `/q/health/ready` on each published API. Use the ports in your `.env` if they differ from these defaults.

| Service | Local API |
| --- | --- |
| IAM | `http://127.0.0.1:8081` |
| Profile | `http://127.0.0.1:8082` |
| Clinical | `http://127.0.0.1:8083` |
| Telemetry | `http://127.0.0.1:8084` |
| Analytics | `http://127.0.0.1:8085` |

Example: `curl.exe --noproxy "*" --fail --max-time 5 http://127.0.0.1:8083/q/health/ready`. All five checks must return HTTP 200 with JSON `status: UP`. If one does not, check `docker compose ps -a` before connecting the frontend. Use `docker compose down` to stop your local stack without deleting its named volumes.

## What was validated

The build at `0e49f20` passed from a clean checkout on Docker Desktop, without an image archive, preloaded application images, or precompiled runners. The test used separate ports and fresh volumes, and a local OIDC fixture instead of development Cognito. All five services returned HTTP 200 with `status: UP` from Windows. This verifies the native build, startup, and host readiness path, not the frontend, authenticated flows, or operation against the development Cognito tenant.
