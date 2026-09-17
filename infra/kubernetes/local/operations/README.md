# Operate the local Kafka dead-letter queue

Use the PowerShell runbook to inspect quarantined messages, replay one exact
record, and review the append-only replay audit trail. It is intentionally scoped
to the local `PatientAccountInvitedV1` flow.

## Quick path

```powershell
# Metadata only; payloads remain hidden.
.\infra\kubernetes\local\operations\kafka-dlq.ps1 -Action Inspect

# Inspect one record when its business payload is required for diagnosis.
.\infra\kubernetes\local\operations\kafka-dlq.ps1 `
  -Action Inspect -Partition 1 -Offset 0 -IncludePayload

# Replay requires an exact position, operator, reason, and confirmation.
.\infra\kubernetes\local\operations\kafka-dlq.ps1 `
  -Action Replay `
  -Partition 1 `
  -Offset 0 `
  -Operator $env:USERNAME `
  -Reason "Native reflection metadata was corrected"

# Read REQUESTED and COMPLETED/FAILED audit records.
.\infra\kubernetes\local\operations\kafka-dlq.ps1 -Action Audit
```

## Safety controls

| Control | Behavior |
| --- | --- |
| Exact selector | Replay requires one partition and offset. |
| Fixed routing | Only `patient-account-invited.v1.dlq` can replay to `patient-account-invited.v1`. |
| Human intent | Replay has high-impact PowerShell confirmation and requires an operator and reason. |
| Payload privacy | General inspection hides values unless `-IncludePayload` is supplied. |
| Audit trail | Every attempt emits `REQUESTED` before replay and `COMPLETED` or `FAILED` afterward. |
| Idempotency | Profile's inbox uses the original `eventId`, so repeated delivery cannot create another profile. |

Kafka records are immutable. Successful replay does not delete the source DLQ
record; the audit topic records what an operator did with it.

## Production boundary

This script uses `kubectl exec` and Kafka command-line tools from the local broker
Pod. Production should use authenticated operational tooling, restricted Kafka
ACLs, durable audit retention, monitoring, and a reviewed authorization process.

## Telemetry DLQ: exact-position local tool

Telemetry uses a **different** operator path. Its DLQ contains original gaze and
gameplay coordinates; never use the IAM/Profile script or a general Kafka console
to inspect it. The Java CLI reads one exact partition/offset as bytes and prints
only a fixed failure code, byte count, and Kafka positions. It never prints the
key or value. Build the local JVM artifact first:

```powershell
.\mvnw.cmd -o -B -ntp -pl telemetry-service package -DskipTests
```

For local Docker Compose Kafka exposed on `127.0.0.1:9092` (or the configured
`KAFKA_PORT`), inspect one known position and replay **only** an exhausted
persistence retry after the underlying fault has been resolved:

```powershell
.\infra\kubernetes\local\operations\telemetry-dlq.ps1 `
  -Action Inspect -Partition 0 -Offset 42

.\infra\kubernetes\local\operations\telemetry-dlq.ps1 `
  -Action Replay -Partition 0 -Offset 42 `
  -Operator 'approved.operator' -Reason RETRY_AFTER_RECOVERY `
  -Confirmation 'REPLAY telemetry-batch-accepted.v1.dlq:0:42' -WhatIf

# Remove -WhatIf only after checking the exact position and receiving approval.
```

`Replay` also prompts for PowerShell high-impact confirmation. Allowed reasons
are `RETRY_AFTER_RECOVERY`, `RETRY_AFTER_CAPACITY_RESTORED`, and
`RETRY_AFTER_OPERATOR_REVIEW`; free-text clinical detail cannot enter the audit.
`INVALID_EVENT` and `PERSISTENCE_REJECTED` are inspectable but deliberately
**not replayable unchanged**: they would normally return to the DLQ. Repair the
producer/contract or resolve the identity conflict instead of looping poison.

Each replay appends `REQUESTED` to `neurovision.dlq-replay-audit.v1` before
publishing to the fixed `telemetry-batch-accepted.v1` topic, then appends
`COMPLETED` or `FAILED`. A `FAILED` audit means publication was not confirmed;
the broker may still have appended after a timeout. If terminal audit fails,
only `REQUESTED` may be durable and the tool reports an **ambiguous outcome**.
Inspect the restricted audit/source offsets before another attempt. A repeat is
sample-safe because the Timescale inbox deduplicates `batchId`; it is not
audit-free and should not be used as a polling mechanism. The DLQ record is
never deleted.

The local k3d Kafka advertises `kafka.neurovision.svc.cluster.local:9092`.
A simple host-side `kubectl port-forward` does **not** make that advertised
address reachable by this CLI. Running the operator tool inside a restricted
cluster network and granting its ACLs belongs to Phase 10; do not claim the
Compose launcher is a working k3d/production runner or copy artifacts into a
Pod ad hoc. Production requires authenticated operator identity, a two-person
approval policy where appropriate, restricted read/write ACLs, encryption,
retention, and secured audit access before patient data is admitted.
