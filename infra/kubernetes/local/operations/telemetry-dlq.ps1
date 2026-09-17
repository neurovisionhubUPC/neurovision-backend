<#
.SYNOPSIS
Inspect or replay one exact Telemetry DLQ record without displaying its payload.

.DESCRIPTION
Local-only launcher for the packaged Telemetry operator CLI. It never performs
topic scans or accepts an arbitrary source/target topic. The operator must
provide an exact position and, for replay, a fixed reason code and confirmation.
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Inspect', 'Replay')]
    [string] $Action,

    [Parameter(Mandatory)]
    [ValidateRange(0, 2147483647)]
    [int] $Partition,

    [Parameter(Mandatory)]
    [ValidateRange(0, [long]::MaxValue)]
    [long] $Offset,

    [string] $BootstrapServer = '127.0.0.1:9092',

    [ValidatePattern('^[A-Za-z0-9._@-]{3,64}$')]
    [string] $Operator,

    [ValidateSet('RETRY_AFTER_RECOVERY', 'RETRY_AFTER_CAPACITY_RESTORED', 'RETRY_AFTER_OPERATOR_REVIEW')]
    [string] $Reason,

    [string] $Confirmation
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$app = Join-Path $root 'telemetry-service\target\quarkus-app'
if (-not (Test-Path (Join-Path $app 'app\telemetry-service-1.0.0-SNAPSHOT.jar'))) {
    throw 'Package telemetry-service locally before using the Telemetry DLQ tool.'
}
$classpath = (Join-Path $app 'app\*') + ';' +
    (Join-Path $app 'lib\main\*') + ';' + (Join-Path $app 'lib\boot\*')
$arguments = @(
    '-Djava.util.logging.manager=org.jboss.logmanager.LogManager',
    '-cp', $classpath,
    'com.neurovision.rehab.telemetry.interfaces.operations.TelemetryDlqCli',
    '--action', $Action.ToLowerInvariant(),
    '--bootstrap', $BootstrapServer,
    '--partition', [string] $Partition,
    '--offset', [string] $Offset
)

if ($Action -eq 'Replay') {
    if ([string]::IsNullOrWhiteSpace($Operator) -or [string]::IsNullOrWhiteSpace($Reason)) {
        throw 'Replay requires -Operator and -Reason.'
    }
    $expected = "REPLAY telemetry-batch-accepted.v1.dlq:$Partition`:$Offset"
    if ($Confirmation -cne $expected) {
        throw "Type the exact confirmation: $expected"
    }
    if (-not $PSCmdlet.ShouldProcess("Telemetry DLQ partition $Partition offset $Offset", 'Replay once')) {
        return
    }
    $arguments += @('--operator', $Operator, '--reason', $Reason, '--confirm', $Confirmation)
}

& java @arguments
if ($LASTEXITCODE -ne 0) {
    throw 'Telemetry DLQ operation failed; no payload was printed by the tool.'
}
