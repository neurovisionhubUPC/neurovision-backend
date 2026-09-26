[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'smoke-test-telemetry-compose-recovery.ps1'
& $scriptPath -Mode OfflineTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
