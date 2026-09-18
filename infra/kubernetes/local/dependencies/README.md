# Provision local data dependencies

This local-only overlay runs separate PostgreSQL databases for IAM, Profile,
and Clinical, one TimescaleDB database for Telemetry, and one single-node
Apache Kafka broker inside k3d. It is intentionally not a production topology.

## Quick path

1. Create the namespace, public configuration, and local Secrets.
2. Apply this dependency overlay.
3. Wait for the databases, broker, and topic provisioning Job.

```powershell
kubectl apply -k .\infra\kubernetes\base
kubectl apply -k .\infra\kubernetes\config
.\infra\kubernetes\config\configure-local-secrets.ps1
kubectl apply -k .\infra\kubernetes\local\dependencies

kubectl rollout status statefulset/iam-postgresql -n neurovision --timeout=180s
kubectl rollout status statefulset/profile-postgresql -n neurovision --timeout=180s
kubectl rollout status statefulset/clinical-postgresql -n neurovision --timeout=180s
kubectl rollout status statefulset/telemetry-timescaledb -n neurovision --timeout=180s
kubectl rollout status statefulset/kafka -n neurovision --timeout=300s
kubectl wait --for=condition=complete job/kafka-topics -n neurovision --timeout=180s
```

## Local topology

| Resource | Address | Storage |
|---|---|---|
| IAM PostgreSQL | `iam-postgresql:5432` | 1 GiB `local-path` PVC |
| Profile PostgreSQL | `profile-postgresql:5432` | 1 GiB `local-path` PVC |
| Clinical PostgreSQL | `clinical-postgresql:5432` | 1 GiB `local-path` PVC |
| Telemetry TimescaleDB | `telemetry-timescaledb:5432` | 1 GiB `local-path` PVC |
| Apache Kafka | `kafka:9092` | 2 GiB `local-path` PVC |

Kafka runs as a single combined KRaft broker/controller. Topic auto-creation is
disabled; the `kafka-topics` Job creates application, DLQ, and replay-audit
topics idempotently.

TimescaleDB uses the pinned `timescale/timescaledb:2.30.1-pg17` image from
Compose but a separate k3d volume. Telemetry, not this dependency overlay,
owns Flyway V1–V3 migration at its first startup. Existing k3d Clinical data
is not copied or reset by adding this new database.

## Verify

```powershell
kubectl get statefulset,pod,pvc,service,job -n neurovision
kubectl logs job/kafka-topics -n neurovision
kubectl exec -n neurovision kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

Expected topics:

- `patient-account-invited.v1`
- `patient-account-invited.v1.dlq`
- `telemetry-batch-accepted.v1`
- `telemetry-batch-accepted.v1.dlq`
- `neurovision.dlq-replay-audit.v1`

Use the [DLQ operations runbook](../operations/README.md) to inspect and replay
IAM/Profile records only. The Telemetry DLQ retains sensitive pseudonymous
coordinates and is not supported by that tool. A separate metadata-only,
exact-position Telemetry operator path is documented in the same runbook for
host-reachable local Kafka; k3d execution wiring remains a Phase 10 deployment
task. A replay never deletes the original DLQ record because Kafka topics are
append-only logs.

## Scope boundary

These manifests prioritize a reproducible laptop environment. Production must
use managed or highly available data services, encrypted transport,
authentication, backups, monitoring, and an explicit disaster-recovery plan.
