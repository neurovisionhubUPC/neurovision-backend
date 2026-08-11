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
