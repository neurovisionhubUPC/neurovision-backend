[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$smoke = Join-Path $PSScriptRoot 'smoke-test-analytics-k3d.ps1'
. $smoke

if ((Get-ExpectedSourceTotal) -ne 1) { throw 'The existing namespace baseline changed.' }
$Namespace = 'neurovision-analytics-smoke'
if ((Get-ExpectedSourceTotal) -ne 0) { throw 'The isolated namespace baseline is not empty.' }
$Namespace = 'neurovision'

function Response($Value) {
    return @{ Status = 200; Body = (ConvertTo-Json -InputObject $Value -Depth 12 -Compress) }
}

function Assert-Rejected([string] $Kind, $Value, [guid] $Session, [guid] $Stream, $Event) {
    $rejected = $false
    try { Assert-ViewContent $Kind (Response $Value) $Session $Stream $Event }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'A changed synthetic projection was accepted.' }
}

$event = Read-SyntheticFixture
$event.sequence = 0
$session = [guid] $event.therapySessionId
$stream = [guid] $event.streamId
$cells = @(0..99 | ForEach-Object { 0L })
$cells[82] = 1L

$heatmap = [ordered]@{
    contractVersion = 1; sourceSchemaVersion = 1
    therapySessionId = $session.ToString(); streamId = $stream.ToString()
    calculationVersion = 'baseline-v1'; revision = 1
    rows = 10; columns = 10; cellCounts = $cells; gazeSampleCount = 1
    coordinateSpace = 'normalized-0..1'; cellWeight = 'raw-gaze-sample-count'
    minimumSequence = 0; maximumSequence = 0; missingSequenceRanges = @()
}
$gazePlot = [ordered]@{
    contractVersion = 1; pointSemantics = 'OBSERVED_GAZE_SAMPLES'
    calculationVersion = 'baseline-v1'; projectionRevision = 1
    completeness = @{ sourceSchemaVersion = 1; observedGazePointCount = 1 }
    points = @(@{ sequence = 0; gazeSampleIndex = 0; effectiveTime = $event.capturedAt
        x = 0.2; y = 0.8; confidence = 0.9 })
    nextCursor = $null
}
$replay = [ordered]@{
    contractVersion = 1; source = 'admitted-telemetry-kafka-v1'
    therapySessionId = $session.ToString(); streamId = $stream.ToString()
    projectionRevision = 1; sourceSchemaVersion = 1
    gazeSamples = @{ samples = @(@{
        batchId = $event.batchId; sequence = 0; sourceArrayIndex = 0
        capturedAt = $event.capturedAt; offsetMs = 0
        x = 0.2; y = 0.8; confidence = 0.9
    }); nextCursor = $null }
    gameplaySamples = @{ samples = @(@{
        batchId = $event.batchId; sequence = 0; sourceArrayIndex = 0
        capturedAt = $event.capturedAt; offsetMs = 1
        playerX = 0.1; playerY = 0.2; targetX = 0.3; targetY = 0.4
        score = 12; speed = 1.5; progress = 0.6
    }); nextCursor = $null }
}

Assert-ViewContent 'heatmap' (Response $heatmap) $session $stream $event
Assert-ViewContent 'gaze-plot' (Response $gazePlot) $session $stream $event
Assert-ViewContent 'replay' (Response $replay) $session $stream $event

$heatmap.cellCounts[82] = 0L
$heatmap.cellCounts[83] = 1L
Assert-Rejected 'heatmap' $heatmap $session $stream $event
$gazePlot.points[0].x = 0.21
Assert-Rejected 'gaze-plot' $gazePlot $session $stream $event
$replay.gameplaySamples.samples[0].score = 99
Assert-Rejected 'replay' $replay $session $stream $event

$source = Get-Content -LiteralPath $smoke -Raw
$patientStart = $source.IndexOf("`$script:stage = 'patient identity'", [StringComparison]::Ordinal)
$doctorLogin = $source.IndexOf("Get-RoleToken 'DOCTOR'", $patientStart, [StringComparison]::Ordinal)
$doctorMatch = $source.IndexOf('if ($doctor.Subject -cne $fixture.DoctorSubject', $doctorLogin, [StringComparison]::Ordinal)
$doctorClinical = $source.IndexOf('Assert-Status (Invoke-LocalGet $clinicalUrl $doctor.Token) 200', $doctorMatch, [StringComparison]::Ordinal)
$baseline = $source.IndexOf('$baseline = Assert-EmptyPipeline', $doctorClinical, [StringComparison]::Ordinal)
$publish = $source.IndexOf('Publish-OneSyntheticEvent $fixture.Session', $baseline, [StringComparison]::Ordinal)
if ($patientStart -lt 0 -or $doctorLogin -le $patientStart -or $doctorMatch -le $doctorLogin -or
        $doctorClinical -le $doctorMatch -or $baseline -le $doctorClinical -or $publish -le $baseline) {
    throw 'The two-role prepublication guard is not ordered correctly.'
}

Write-Output 'OFFLINE_SMOKE_CHECK_OK: three fixture-matched views; three tampering controls; doctor authorization before publication; isolated zero baseline.'
