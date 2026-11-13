# Run the native backend from a clean checkout

On a Linux amd64 Docker engine (including Docker Desktop on Windows with Linux containers), use Docker Compose with BuildKit. Set up the repository's local environment variables using `.env.example` as a reference. Keep local credentials out of Git.

From the repository root:

```sh
docker compose up --build
```

Use only the base Compose file for this flow. No image archive, imported application image, precompiled runner, host Maven, or host Mandrel is required. The first build needs access to the image registries and Maven repositories for uncached inputs. Give Docker enough memory for a native build with a 5 GiB Java heap plus compiler and container overhead. The shared Maven cache serializes native compilation steps and is not copied into the runtime images.

Compose uses a shared multi-stage Dockerfile: the UBI 9 Mandrel stage compiles each selected module and the final UBI 9 micro stage runs its executable as UID 1001. The Clinical image also contains the Source Sans 3 license. The per-service packaging-only Dockerfiles remain available for workflows that already have Linux runners.

On an existing project, startup can run database migrations. Check the migration state and back up any local data before replacing services. For acceptance from scratch, use a clean checkout, a fresh Docker environment without the application images, and fresh local volumes. Confirm all five services return HTTP 200 with `status: UP` from `/q/health/ready` on their configured ports (defaults 8081–8085). This is a build and startup check, not a PDF regression test.
