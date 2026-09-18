# Rehearse Telemetry historical reads in local k3d

This overlay prepares a **synthetic-data-only** k3d rehearsal. It does not create
an ingress route for Telemetry. Port-forward its ClusterIP Service to loopback
only when running an authorized HTTP smoke test. The Clinical internal API stays
on its existing ClusterIP Service; do not route `/api/internal/*` publicly.

## Before deployment

1. Confirm the context is `k3d-neurovision-local` and Clinical's existing
   PostgreSQL PVC is Bound. Keep the verified Clinical V1 backup and V6
   migration evidence from the task tracker; do not recreate or reset its PVC.
2. Confirm `clinical-service-flyway-v6-20260918` completed successfully. This
   overlay does **not** rerun that Job or invoke a down migration.
3. Import the already-built local images into `neurovision-local` under the
   immutable tags recorded below. Do not replace `1.0.0-SNAPSHOT` or mutate a
   running workload while preparing the import.
4. Run the existing local Secret setup with `-SkipAws -SkipTls`, after reviewing
   the active context. It preserves existing database passwords. Never print
   Secret values or put them in Git.

## Deployment order after separate authorization

The commands below are a **runbook, not executed by preparing these files**.
Starting the application workloads may cause OIDC discovery to contact the
configured development Cognito issuer; obtain explicit authorization first.

```powershell
kubectl config current-context
# Stop before deployment if the pinned TimescaleDB image is not cached locally.
docker image inspect timescale/timescaledb:2.30.1-pg17 --format '{{.Id}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Pinned TimescaleDB image is not cached locally.' }
$timescaleRef = 'docker.io/timescale/timescaledb:2.30.1-pg17'
$nodeImages = @(docker exec k3d-neurovision-local-server-0 ctr -n k8s.io images ls -q)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect k3d node images.' }
if ($nodeImages -cnotcontains $timescaleRef) {
    k3d image import timescale/timescaledb:2.30.1-pg17 -c neurovision-local
    $nodeImages = @(docker exec k3d-neurovision-local-server-0 ctr -n k8s.io images ls -q)
    if ($LASTEXITCODE -ne 0 -or $nodeImages -cnotcontains $timescaleRef) {
        throw 'TimescaleDB image import is unverified; do not deploy.'
    }
}
k3d image import neurovision/clinical-service:k3d-v6-20260918-6075a290 -c neurovision-local
k3d image import neurovision/telemetry-service:k3d-historical-20260918-80a8ac10 -c neurovision-local

.\infra\kubernetes\config\configure-local-secrets.ps1 -SkipAws -SkipTls
kubectl apply -f .\infra\kubernetes\config\telemetry-configmap.yaml
kubectl apply -f .\infra\kubernetes\local\dependencies\telemetry-timescaledb.yaml
kubectl rollout status statefulset/telemetry-timescaledb -n neurovision --timeout=180s

# Patch the existing generated Clinical Deployment, not its PostgreSQL PVC.
kubectl patch deployment clinical-service -n neurovision --type=strategic `
  --patch-file .\infra\kubernetes\local\telemetry\clinical-deployment-image-patch.yaml
kubectl rollout status deployment/clinical-service -n neurovision --timeout=180s

kubectl apply -k .\infra\kubernetes\local\telemetry
kubectl rollout status deployment/telemetry-service -n neurovision --timeout=180s
```

The TimescaleDB image is the same pinned `timescale/timescaledb:2.30.1-pg17`
used by Compose and is imported from the local Docker cache with
`imagePullPolicy: Never`. A missing image fails closed instead of pulling from
Docker Hub. It gets a **separate empty** k3d PVC. Telemetry's first
startup applies Flyway V1–V3 to that new database; do not copy Compose data,
Kafka records, or its DLQ into k3d. These database and broker settings are
local development fixtures, not controls for real patient data.

On this Windows setup, `k3d image import` once printed a containerd
missing-digest error but still returned exit code 0 and claimed success. The
node-image inventory check above is mandatory. If it fails, stop; do not
fall back to a registry pull. The local-only recovery that worked was
`docker image save --platform linux/amd64`, copying that tar into the k3d node,
and `ctr -n k8s.io images import` inside the node. Confirm the exact image
reference in the node before continuing, then remove both temporary tar files.

Use the targeted `-f` applies above when extending an existing k3d cluster;
reapplying the entire dependency overlay could also touch its Kafka topic Job
or unrelated databases. The Kustomize entries support clean-cluster setup.

## Verification boundary

- Verify the Clinical Deployment uses the new image and that its preserved
  patient count remains four with Flyway V6.
- Verify TimescaleDB has exactly three successful Telemetry migrations, no
  failures, and zero inbox/sample rows before synthetic ingestion.
- Verify the Telemetry image identity, readiness, and Kafka consumer health.
- Only after a separately authorized PKCE sign-in, create one test Clinical
  session and send synthetic gaze/gameplay samples through the public APIs.
  Then query historical pages through a loopback port-forward. Record only
  response status, sample counts/kinds, cursor presence, and aggregate DB counts;
  never log access tokens, patient identifiers, coordinates, or raw responses.

Revert application images independently if needed. PostgreSQL/Timescale schema
rollback is not automatic; preserve the Clinical backup and both PVCs.
