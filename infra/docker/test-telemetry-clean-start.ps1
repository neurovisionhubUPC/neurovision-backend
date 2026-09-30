[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'smoke-test-telemetry-clean-start.ps1'
& $scriptPath -Mode OfflineTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$e2ePath = Join-Path $PSScriptRoot 'smoke-test-telemetry-clean-start-e2e.ps1'
& $e2ePath -Mode OfflineTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
