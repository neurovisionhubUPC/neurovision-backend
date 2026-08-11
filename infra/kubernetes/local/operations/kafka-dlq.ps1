<#
.SYNOPSIS
Inspects, replays, and audits records from the local NeuroVision Kafka DLQ.

.DESCRIPTION
Replay is restricted to the PatientAccountInvited V1 DLQ and its original topic.
Every attempt writes REQUESTED and terminal audit records to Kafka. Payloads are
hidden during inspection unless IncludePayload is explicitly selected.
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = "High")]
param(
    [Parameter(Mandatory)]
    [ValidateSet("Inspect", "Replay", "Audit")]
    [string] $Action,

    [ValidateRange(0, 2147483647)]
    [int] $Partition,

    [ValidateRange(0, [long]::MaxValue)]
    [long] $Offset,

    [ValidateRange(1, 100)]
    [int] $MaxMessages = 20,

    [string] $Operator = $env:USERNAME,

    [string] $Reason,

    [switch] $IncludePayload
)

$ErrorActionPreference = "Stop"
$Namespace = "neurovision"
$KafkaPod = "kafka-0"
$BootstrapServer = "kafka:9092"
$DlqTopic = "patient-account-invited.v1.dlq"
$OriginalTopic = "patient-account-invited.v1"
$AuditTopic = "neurovision.dlq-replay-audit.v1"
$HasPartition = $PSBoundParameters.ContainsKey("Partition")
$HasOffset = $PSBoundParameters.ContainsKey("Offset")

function Assert-KafkaAvailable {
    & kubectl get pod $KafkaPod -n $Namespace | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Kafka Pod '$KafkaPod' is not available in namespace '$Namespace'."
    }
}

function Assert-ExactRecordSelector {
    if (-not $HasPartition -or -not $HasOffset) {
        throw "Replay requires both -Partition and -Offset."
    }
}

function Get-ExactDlqRecord {
    param(
        [Parameter(Mandatory)] [int] $RecordPartition,
        [Parameter(Mandatory)] [long] $RecordOffset
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& kubectl exec -n $Namespace $KafkaPod -c kafka -- `
            /opt/kafka/bin/kafka-console-consumer.sh `
            --bootstrap-server $BootstrapServer `
            --topic $DlqTopic `
            --partition $RecordPartition `
            --offset $RecordOffset `
            --max-messages 1 `
            --timeout-ms 5000 `
            --formatter-property print.key=true `
            --formatter-property print.value=true `
            --formatter-property "key.separator=`t" 2>$null)
        $consumerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($consumerExitCode -ne 0 -or $lines.Count -ne 1) {
        throw "Expected exactly one DLQ record at partition $RecordPartition offset $RecordOffset."
    }

    $parts = $lines[0] -split "`t", 2
    if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0]) -or
        [string]::IsNullOrWhiteSpace($parts[1])) {
        throw "The selected DLQ record does not contain a replayable key and value."
    }

    try {
        $event = $parts[1] | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "The selected DLQ value is not valid JSON: $($_.Exception.Message)"
    }

    foreach ($requiredProperty in @("eventId", "invitationId", "patientSubject", "occurredAt")) {
        if ([string]::IsNullOrWhiteSpace([string] $event.$requiredProperty)) {
            throw "The selected DLQ event is missing '$requiredProperty'."
        }
    }

    return [pscustomobject]@{
        Key = $parts[0]
        Value = $parts[1]
        Event = $event
    }
}

function Send-KafkaRecord {
    param(
        [Parameter(Mandatory)] [string] $Topic,
        [Parameter(Mandatory)] [string] $Key,
        [Parameter(Mandatory)] [string] $Value
    )

    "$Key`t$Value" | & kubectl exec -i -n $Namespace $KafkaPod -c kafka -- `
        /opt/kafka/bin/kafka-console-producer.sh `
        --bootstrap-server $BootstrapServer `
        --topic $Topic `
        --sync `
        --reader-property parse.key=true `
        --reader-property "key.separator=`t"

    if ($LASTEXITCODE -ne 0) {
        throw "Kafka rejected the record for topic '$Topic'."
    }
}

function Write-ReplayAudit {
    param(
        [Parameter(Mandatory)] [guid] $ReplayId,
        [Parameter(Mandatory)] [string] $Status,
        [Parameter(Mandatory)] [string] $EventKey,
        [string] $Failure
    )

    $audit = [ordered]@{
        replayId = $ReplayId.ToString()
        status = $Status
        operator = $Operator.Trim()
        reason = $Reason.Trim()
        sourceTopic = $DlqTopic
        sourcePartition = $Partition
        sourceOffset = $Offset
        targetTopic = $OriginalTopic
        eventKey = $EventKey
        occurredAt = [DateTimeOffset]::UtcNow.ToString("O")
    }
    if (-not [string]::IsNullOrWhiteSpace($Failure)) {
        $audit.failure = $Failure
    }

    Send-KafkaRecord -Topic $AuditTopic -Key $ReplayId.ToString() `
        -Value ($audit | ConvertTo-Json -Compress)
}

function Show-DlqRecords {
    $arguments = @(
        "exec", "-n", $Namespace, $KafkaPod, "-c", "kafka", "--",
        "/opt/kafka/bin/kafka-console-consumer.sh",
        "--bootstrap-server", $BootstrapServer,
        "--topic", $DlqTopic,
        "--max-messages", $MaxMessages,
        "--timeout-ms", "5000",
        "--formatter-property", "print.timestamp=true",
        "--formatter-property", "print.partition=true",
        "--formatter-property", "print.offset=true",
        "--formatter-property", "print.key=true",
        "--formatter-property", "print.value=$($IncludePayload.IsPresent.ToString().ToLowerInvariant())"
    )

    if ($HasPartition) {
        $arguments += @("--partition", $Partition)
        if ($HasOffset) {
            $arguments += @("--offset", $Offset)
        } else {
            $arguments += @("--offset", "earliest")
        }
    } else {
        $arguments += "--from-beginning"
    }

    & kubectl @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "DLQ inspection failed or no records matched before the timeout."
    }
}

function Show-ReplayAudit {
    & kubectl exec -n $Namespace $KafkaPod -c kafka -- `
        /opt/kafka/bin/kafka-console-consumer.sh `
        --bootstrap-server $BootstrapServer `
        --topic $AuditTopic `
        --from-beginning `
        --max-messages $MaxMessages `
        --timeout-ms 5000 `
        --formatter-property print.key=true `
        --formatter-property print.value=true `
        --formatter-property "key.separator=`t"

    if ($LASTEXITCODE -ne 0) {
        throw "Audit inspection failed or no audit records matched before the timeout."
    }
}

Assert-KafkaAvailable

switch ($Action) {
    "Inspect" {
        Show-DlqRecords
    }
    "Audit" {
        Show-ReplayAudit
    }
    "Replay" {
        Assert-ExactRecordSelector
        if ([string]::IsNullOrWhiteSpace($Operator)) {
            throw "Replay requires a non-empty -Operator value."
        }
        if ([string]::IsNullOrWhiteSpace($Reason)) {
            throw "Replay requires a non-empty -Reason value."
        }

        $record = Get-ExactDlqRecord -RecordPartition $Partition -RecordOffset $Offset
        $description = "$DlqTopic partition $Partition offset $Offset to $OriginalTopic"
        if (-not $PSCmdlet.ShouldProcess($description, "Replay Kafka record")) {
            return
        }

        $replayId = [guid]::NewGuid()
        Write-ReplayAudit -ReplayId $replayId -Status "REQUESTED" -EventKey $record.Key
        try {
            Send-KafkaRecord -Topic $OriginalTopic -Key $record.Key -Value $record.Value
        } catch {
            $replayFailure = $_
            try {
                Write-ReplayAudit -ReplayId $replayId -Status "FAILED" `
                    -EventKey $record.Key -Failure $replayFailure.Exception.Message
            } catch {
                Write-Warning "Replay failed and its FAILED audit record could not be written."
            }
            throw $replayFailure
        }

        try {
            Write-ReplayAudit -ReplayId $replayId -Status "COMPLETED" -EventKey $record.Key
        } catch {
            throw "Replay $replayId reached '$OriginalTopic', but its COMPLETED audit record could not be written. Verify the consumer outcome before retrying. $($_.Exception.Message)"
        }
        Write-Output "Replay $replayId completed for $description."
    }
}
