[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$paths = @(
    (Join-Path $PSScriptRoot 'smoke-test-telemetry-compose-capacity.ps1'),
    (Join-Path $PSScriptRoot 'telemetry-compose-capacity-pkce.ps1'),
    (Join-Path $PSScriptRoot 'telemetry-compose-capacity-recovery.ps1'),
    $MyInvocation.MyCommand.Path)
foreach ($path in $paths) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'A capacity script is missing.' }
    $tokens = $null
    $parseErrors = $null
    [void] [System.Management.Automation.Language.Parser]::ParseFile($path, [ref] $tokens, [ref] $parseErrors)
    if (@($parseErrors).Count -ne 0) { throw 'A capacity script has PowerShell parse errors.' }
}

$harnessPath = Join-Path $PSScriptRoot 'smoke-test-telemetry-compose-capacity.ps1'
$result = @(& $harnessPath 2>&1)
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch
        '(?m)^CAPACITY_OFFLINE_TESTS_PASSED: external-calls=0; files=0; marker=0; guard-cases=14; nonce=verified; timing=verified; preflight=snapshot-only; recovery=verified') {
    throw 'The capacity harness default offline contract tests did not pass.'
}
$explicit = @(& $harnessPath -Mode OfflineTest 2>&1)
if ($LASTEXITCODE -ne 0 -or ($explicit -join "`n") -notmatch
        '(?m)^CAPACITY_OFFLINE_TESTS_PASSED: external-calls=0; files=0; marker=0; guard-cases=14; nonce=verified; timing=verified; preflight=snapshot-only; recovery=verified') {
    throw 'The capacity harness explicit offline contract tests did not pass.'
}
Write-Output 'CAPACITY_TEST_RUNNER_PASSED: parser=0; default-offline=passed; explicit-offline=passed'
