# Provision local data dependencies

This local-only overlay runs one PostgreSQL instance per service and one
single-node Apache Kafka broker inside the k3d cluster. It is intentionally not
a production topology.

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
kubectl rollout status statefulset/kafka -n neurovision --timeout=300s
kubectl wait --for=condition=complete job/kafka-topics -n neurovision --timeout=180s
```

## Local topology

| Resource | Address | Storage |
|---|---|---|
| IAM PostgreSQL | `iam-postgresql:5432` | 1 GiB `local-path` PVC |
| Profile PostgreSQL | `profile-postgresql:5432` | 1 GiB `local-path` PVC |
| Clinical PostgreSQL | `clinical-postgresql:5432` | 1 GiB `local-path` PVC |
| Apache Kafka | `kafka:9092` | 2 GiB `local-path` PVC |

Kafka runs as a single combined KRaft broker/controller. Topic auto-creation is
disabled; the `kafka-topics` Job creates application, DLQ, and replay-audit
topics idempotently.

## Verify

```powershell
kubectl get statefulset,pod,pvc,service,job -n neurovision
kubectl logs job/kafka-topics -n neurovision
kubectl exec -n neurovision kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

Expected topics:

- `patient-account-invited.v1`
- `patient-account-invited.v1.dlq`
- `neurovision.dlq-replay-audit.v1`

Use the [DLQ operations runbook](../operations/README.md) to inspect and replay
records. A replay never deletes the original DLQ record because Kafka topics are
append-only logs.

## Scope boundary

These manifests prioritize a reproducible laptop environment. Production must
use managed or highly available data services, encrypted transport,
authentication, backups, monitoring, and an explicit disaster-recovery plan.
