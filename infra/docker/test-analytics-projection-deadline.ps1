[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scripts = @(
    'smoke-test-analytics-real-clinical-compose.ps1',
    'smoke-test-analytics-native-real-clinical-compose.ps1'
)

function Invoke-LocalGet([string] $url, [string] $token, [int] $timeoutSeconds = 10) {
    $script:observedTimeouts += $timeoutSeconds
    if ($null -ne $script:mockResponse) { return $script:mockResponse }
    Start-Sleep -Milliseconds $script:mockDelayMilliseconds
    return @{ Status = $script:mockStatus; Body = $null }
}

foreach ($name in $scripts) {
    $path = Join-Path $PSScriptRoot $name
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref] $tokens, [ref] $errors)
    if ($errors.Count -ne 0) { throw "$name contains PowerShell syntax errors." }

    $get = @($ast.FindAll({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Invoke-LocalGet'
    }, $true))
    $loops = @($ast.FindAll({ param($node)
        $node -is [System.Management.Automation.Language.WhileStatementAst] -and
        $node.Extent.Text -match 'projectionWait.ElapsedMilliseconds'
    }, $true))
    if ($get.Count -ne 1 -or $loops.Count -ne 1) { throw "$name has an unexpected projection polling structure." }
    if ($get[0].Extent.Text -notmatch '-TimeoutSec \$timeoutSeconds') {
        throw "$name does not pass the remaining request budget to Invoke-WebRequest."
    }

    $loopText = $loops[0].Extent.Text
    if ($loopText -notmatch '20000 - \$projectionWait.ElapsedMilliseconds' -or
            $loopText -notmatch 'Start-Sleep -Milliseconds') {
        throw "$name does not enforce the 20-second projection deadline."
    }

    # Scale the real loop's deadline down to keep this local test fast; no service is called.
    $summaryUrl = 'http://127.0.0.1/not-called'
    $identity = @{ Token = 'synthetic-test-token' }
    $verified = $false
    $script:observedTimeouts = @()
    $script:mockResponse = $null
    $script:mockDelayMilliseconds = 850
    $script:mockStatus = 404
    $projectionWait = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Expression ($loopText.Replace('20000', '1200'))

    if ($verified -or $script:observedTimeouts.Count -ne 1 -or
            $script:observedTimeouts[0] -ne 1 -or $projectionWait.ElapsedMilliseconds -gt 2000) {
        throw "$name did not cap the request and sleep by the remaining deadline."
    }

    $session = [guid]::NewGuid()
    $stream = [guid]::NewGuid()
    $script:mockResponse = @{ Status = 200; Body = (@{
        schemaVersion = 1; sourceSchemaVersion = 1; calculationVersion = 'baseline-v1'; revision = 1
        therapySessionId = $session.ToString(); streamId = $stream.ToString()
    } | ConvertTo-Json -Compress) }
    $script:observedTimeouts = @()
    $verified = $false
    $projectionWait = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Expression ($loopText.Replace('20000', '1200'))
    if (-not $verified -or $script:observedTimeouts.Count -ne 1 -or
            $script:observedTimeouts[0] -ne 1) {
        throw "$name did not accept a valid projected summary before the deadline."
    }

    $script:mockResponse = $null
    $script:mockDelayMilliseconds = 1250
    $script:mockStatus = 0
    $script:observedTimeouts = @()
    $verified = $false
    $projectionWait = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Expression ($loopText.Replace('20000', '1200'))
    if ($verified -or $script:observedTimeouts.Count -ne 1 -or
            $script:observedTimeouts[0] -ne 1) {
        throw "$name did not stop after a request timeout at the deadline."
    }
    Write-Output "PROJECTION_DEADLINE_OK $name"
}
