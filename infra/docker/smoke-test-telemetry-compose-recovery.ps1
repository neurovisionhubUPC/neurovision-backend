[CmdletBinding()]
param(
    [ValidateSet('Preflight', 'AuthorizedRun', 'OfflineTest')]
    [string] $Mode = 'Preflight',
    [switch] $AuthorizeRestart,
    [switch] $ConfirmSyntheticData
)

$ErrorActionPreference = 'Stop'
$projectName = 'neurovision-rehab'
$volumeKey = 'telemetry-timescaledb-data'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$composeFile = Join-Path $repositoryRoot 'compose.yaml'
$databaseService = 'telemetry-timescaledb'
$telemetryService = 'telemetry-service'
$script:recoveryStage = 'mode_guard'

function Get-FailureDiagnostic([string] $Stage, [string] $Message) {
    $safeStage = if ($Stage -in @(
        'mode_guard', 'compose_file', 'compose_config', 'compose_model', 'volume_labels',
        'dependencies', 'clinical_readiness', 'telemetry_readiness', 'baseline_snapshot',
        'database_restart', 'database_health', 'telemetry_restart', 'telemetry_health',
        'post_snapshot')) { $Stage } else { 'unknown' }
    $guard = switch -CaseSensitive ($Message) {
        'Preflight requires synthetic-data confirmation and forbids restart authorization.' { 'preflight_mode_invalid' }
        'AuthorizedRun requires explicit restart authorization and synthetic-data confirmation.' { 'authorized_mode_invalid' }
        'OfflineTest does not accept runtime authorization switches.' { 'offline_mode_invalid' }
        'The root Compose file is unavailable.' { 'compose_file_unavailable' }
        'Docker Compose inspection or scoped operation failed.' { 'compose_operation_failed' }
        'Docker Compose configuration could not be safely inspected.' { 'compose_config_invalid' }
        'Docker Compose runtime state could not be safely inspected.' { 'compose_state_invalid' }
        'The named Telemetry persistent volume is missing or unreadable.' { 'volume_unavailable' }
        'Persistent-volume ownership labels could not be safely checked.' { 'volume_labels_invalid' }
        'The selected Compose file is not the expected Telemetry project.' { 'compose_project_mismatch' }
        'Telemetry database service lacks its expected named volume or healthcheck.' { 'database_definition_invalid' }
        'Compose project or persistent-volume identity is not the expected local Telemetry target.' { 'recovery_target_mismatch' }
        'Persistent-volume ownership does not match the selected Compose project.' { 'volume_owner_mismatch' }
        'A required local Compose dependency is absent or ambiguous.' { 'dependency_missing_or_ambiguous' }
        'Kafka topic provisioning has not completed successfully.' { 'kafka_provisioning_incomplete' }
        'A required local Compose dependency is not running and healthy.' { 'dependency_not_healthy' }
        'The local Clinical readiness check failed.' { 'clinical_not_ready' }
        'The local Telemetry readiness check failed.' { 'telemetry_not_ready' }
        'Telemetry aggregate snapshot was not a single safe result.' { 'snapshot_shape_invalid' }
        'Telemetry aggregate snapshot has an unexpected shape.' { 'snapshot_shape_invalid' }
        'Telemetry aggregate snapshot contains invalid metadata.' { 'snapshot_metadata_invalid' }
        'Existing synthetic baseline is empty or violates Telemetry aggregate invariants.' { 'baseline_invalid' }
        'Persisted Telemetry aggregates changed across the restart.' { 'persisted_aggregates_changed' }
        'A restarted local Compose service did not become healthy within the bounded wait.' { 'database_health_timeout' }
        'Telemetry readiness did not recover within the bounded wait.' { 'telemetry_health_timeout' }
        default { 'operation_failed' }
    }
    return [pscustomobject]@{ Stage = $safeStage; Guard = $guard }
}

function Format-FailureDiagnostic([string] $Stage, [string] $Message) {
    $diagnostic = Get-FailureDiagnostic $Stage $Message
    return ("Telemetry Compose recovery stopped safely; stage={0}; guard={1}; no cleanup or ingestion was attempted." -f `
        $diagnostic.Stage, $diagnostic.Guard)
}

function Assert-RecoveryContext([string] $Project, [string] $VolumeKey, [string] $VolumeName) {
    if ($Project -cne 'neurovision-rehab' -or $VolumeKey -cne 'telemetry-timescaledb-data' -or
            $VolumeName -cne 'neurovision-rehab_telemetry-timescaledb-data') {
        throw 'Compose project or persistent-volume identity is not the expected local Telemetry target.'
    }
}

function Get-LabelValue([object] $Labels, [string] $Name) {
    if ($Labels -is [System.Collections.IDictionary]) { return $Labels[$Name] }
    if ($null -eq $Labels -or $null -eq $Labels.PSObject.Properties[$Name]) { return $null }
    return $Labels.PSObject.Properties[$Name].Value
}

function Assert-VolumeLabels([string] $Project, [string] $VolumeKey, [object] $Labels) {
    if ((Get-LabelValue $Labels 'com.docker.compose.project') -cne $Project -or
            (Get-LabelValue $Labels 'com.docker.compose.volume') -cne $VolumeKey) {
        throw 'Persistent-volume ownership does not match the selected Compose project.'
    }
}

function Assert-Snapshot([pscustomobject] $Snapshot) {
    if ($null -eq $Snapshot -or $Snapshot.InboxCount -le 0 -or $Snapshot.SampleCount -le 0 -or
            $Snapshot.GazeCount -lt 0 -or $Snapshot.GameplayCount -lt 0 -or
            ($Snapshot.GazeCount + $Snapshot.GameplayCount) -ne $Snapshot.SampleCount -or
            $Snapshot.DuplicateSampleKeys -ne 0) {
        throw 'Existing synthetic baseline is empty or violates Telemetry aggregate invariants.'
    }
}

function Assert-SameSnapshot([pscustomobject] $Before, [pscustomobject] $After) {
    Assert-Snapshot $Before
    Assert-Snapshot $After
    foreach ($field in @('InboxCount', 'SampleCount', 'GazeCount', 'GameplayCount', 'DuplicateSampleKeys')) {
        if ([long] $Before.$field -ne [long] $After.$field) {
            throw 'Persisted Telemetry aggregates changed across the restart.'
        }
    }
}

function Assert-OfflineCase([bool] $Condition, [string] $Name) {
    if (-not $Condition) { throw "Offline recovery test failed: $Name" }
}

function Assert-Throws([scriptblock] $Action, [string] $Name) {
    $failed = $false
    try { & $Action } catch { $failed = $true }
    Assert-OfflineCase $failed $Name
}

function Assert-ModeSelection([string] $SelectedMode, [bool] $Authorized, [bool] $SyntheticConfirmed) {
    if ($SelectedMode -eq 'Preflight' -and ($Authorized -or -not $SyntheticConfirmed)) {
        throw 'Preflight requires synthetic-data confirmation and forbids restart authorization.'
    }
    if ($SelectedMode -eq 'AuthorizedRun' -and (-not $Authorized -or -not $SyntheticConfirmed)) {
        throw 'AuthorizedRun requires explicit restart authorization and synthetic-data confirmation.'
    }
    if ($SelectedMode -eq 'OfflineTest' -and ($Authorized -or $SyntheticConfirmed)) {
        throw 'OfflineTest does not accept runtime authorization switches.'
    }
}

function Invoke-OfflineTests {
    $good = [pscustomobject]@{
        InboxCount = [long] 2
        SampleCount = [long] 4
        GazeCount = [long] 2
        GameplayCount = [long] 2
        DuplicateSampleKeys = [long] 0
    }
    Assert-RecoveryContext 'neurovision-rehab' 'telemetry-timescaledb-data' `
        'neurovision-rehab_telemetry-timescaledb-data'
    Assert-VolumeLabels 'neurovision-rehab' 'telemetry-timescaledb-data' @{
        'com.docker.compose.project' = 'neurovision-rehab'
        'com.docker.compose.volume' = 'telemetry-timescaledb-data'
    }
    Assert-Snapshot $good
    Assert-SameSnapshot $good ($good.PSObject.Copy())
    $singleLineSnapshot = Convert-DatabaseSnapshot '2|4|2|2|0'
    Assert-OfflineCase ($singleLineSnapshot.InboxCount -eq 2 -and $singleLineSnapshot.SampleCount -eq 4 -and
        $singleLineSnapshot.GazeCount -eq 2 -and $singleLineSnapshot.GameplayCount -eq 2 -and
        $singleLineSnapshot.DuplicateSampleKeys -eq 0) 'single-line-database-snapshot-parsed'
    Assert-Throws { Assert-RecoveryContext 'other-project' 'telemetry-timescaledb-data' `
            'other-project_telemetry-timescaledb-data' } 'wrong-project-refused'
    Assert-Throws { Assert-VolumeLabels 'neurovision-rehab' 'telemetry-timescaledb-data' @{
            'com.docker.compose.project' = 'other-project'
            'com.docker.compose.volume' = 'telemetry-timescaledb-data'
        } } 'wrong-volume-owner-refused'
    Assert-Throws { Assert-Snapshot ([pscustomobject]@{
            InboxCount = [long] 0; SampleCount = [long] 0; GazeCount = [long] 0
            GameplayCount = [long] 0; DuplicateSampleKeys = [long] 0
        }) } 'empty-baseline-refused'
    Assert-Throws { Assert-Snapshot ([pscustomobject]@{
            InboxCount = [long] 1; SampleCount = [long] 2; GazeCount = [long] 1
            GameplayCount = [long] 1; DuplicateSampleKeys = [long] 1
        }) } 'duplicate-baseline-refused'
    Assert-Throws { Assert-ModeSelection 'Preflight' $false $false } 'unconfirmed-preflight-refused'
    Assert-Throws { Assert-ModeSelection 'Preflight' $true $true } 'restart-switch-refused-in-preflight'
    Assert-Throws { Assert-ModeSelection 'AuthorizedRun' $false $true } 'missing-restart-authorization-refused'
    Assert-Throws { Assert-ModeSelection 'AuthorizedRun' $true $false } 'missing-synthetic-attestation-refused'
    Assert-Throws { Assert-ModeSelection 'OfflineTest' $false $true } 'offline-runtime-switch-refused'
    $nativeSuccess = Invoke-NativeCommand $env:ComSpec @('/d', '/c', 'echo NATIVE-STDOUT-CAPTURE-CANARY & echo NATIVE-STDERR-REDACTION-CANARY 1>&2')
    Assert-OfflineCase ($nativeSuccess.ExitCode -eq 0) 'native-stderr-zero-exit-captured'
    Assert-OfflineCase (($nativeSuccess.Output -join "`n") -match 'NATIVE-STDOUT-CAPTURE-CANARY') `
        'native-stdout-still-captured'
    Assert-OfflineCase (($nativeSuccess.Output -join "`n") -notmatch 'NATIVE-STDERR-REDACTION-CANARY') `
        'native-stderr-zero-exit-redacted'
    Assert-OfflineCase ($ErrorActionPreference -ceq 'Stop') 'native-stderr-zero-exit-restores-error-preference'
    $nativeFailure = Invoke-NativeCommand $env:ComSpec @('/d', '/c', 'echo NATIVE-STDERR-REDACTION-CANARY 1>&2 & exit /b 7')
    Assert-OfflineCase ($nativeFailure.ExitCode -eq 7) 'native-stderr-nonzero-exit-captured'
    Assert-OfflineCase (($nativeFailure.Output -join "`n") -notmatch 'NATIVE-STDERR-REDACTION-CANARY') `
        'native-stderr-nonzero-exit-redacted'
    Assert-OfflineCase ($ErrorActionPreference -ceq 'Stop') 'native-stderr-nonzero-exit-restores-error-preference'
    $diagnosticCases = @(
        @('mode_guard', 'Preflight requires synthetic-data confirmation and forbids restart authorization.', 'preflight_mode_invalid'),
        @('compose_file', 'The root Compose file is unavailable.', 'compose_file_unavailable'),
        @('compose_config', 'Docker Compose configuration could not be safely inspected.', 'compose_config_invalid'),
        @('compose_model', 'The selected Compose file is not the expected Telemetry project.', 'compose_project_mismatch'),
        @('volume_labels', 'Persistent-volume ownership does not match the selected Compose project.', 'volume_owner_mismatch'),
        @('dependencies', 'A required local Compose dependency is not running and healthy.', 'dependency_not_healthy'),
        @('clinical_readiness', 'The local Clinical readiness check failed.', 'clinical_not_ready'),
        @('telemetry_readiness', 'The local Telemetry readiness check failed.', 'telemetry_not_ready'),
        @('baseline_snapshot', 'Existing synthetic baseline is empty or violates Telemetry aggregate invariants.', 'baseline_invalid'),
        @('database_restart', 'Docker Compose inspection or scoped operation failed.', 'compose_operation_failed'),
        @('database_health', 'A restarted local Compose service did not become healthy within the bounded wait.', 'database_health_timeout'),
        @('telemetry_restart', 'Docker Compose inspection or scoped operation failed.', 'compose_operation_failed'),
        @('telemetry_health', 'Telemetry readiness did not recover within the bounded wait.', 'telemetry_health_timeout'),
        @('post_snapshot', 'Persisted Telemetry aggregates changed across the restart.', 'persisted_aggregates_changed'))
    foreach ($case in $diagnosticCases) {
        $diagnostic = Get-FailureDiagnostic $case[0] $case[1]
        Assert-OfflineCase ($diagnostic.Stage -ceq $case[0] -and $diagnostic.Guard -ceq $case[2]) `
            ("diagnostic-stage-{0}-guard-{1}" -f $case[0], $case[2])
    }
    $unknownStage = Get-FailureDiagnostic 'attacker-controlled-stage' 'unknown detail'
    Assert-OfflineCase ($unknownStage.Stage -ceq 'unknown' -and $unknownStage.Guard -ceq 'operation_failed') `
        'diagnostic-unknown-fallback'
    $secretText = 'injected secret: TOKEN-DO-NOT-PRINT'
    $safeLine = Format-FailureDiagnostic 'baseline_snapshot' $secretText
    Assert-OfflineCase ($safeLine -notlike "*$secretText*" -and $safeLine -match 'stage=baseline_snapshot; guard=operation_failed') `
        'diagnostic-does-not-disclose-exception'
    $healthyDatabase = [pscustomobject]@{
        Service = 'telemetry-timescaledb'; State = 'running'; Health = 'healthy'
    }
    $startingDatabase = [pscustomobject]@{
        Service = 'telemetry-timescaledb'; State = 'running'; Health = 'starting'
    }
    Assert-OfflineCase (Test-DatabaseReadyState @($healthyDatabase)) 'database-ready-state-accepted'
    Assert-OfflineCase (-not (Test-DatabaseReadyState @($startingDatabase))) `
        'database-not-healthy-state-rejected'
    $changed = [pscustomobject]@{
        InboxCount = [long] 2; SampleCount = [long] 5; GazeCount = [long] 3
        GameplayCount = [long] 2; DuplicateSampleKeys = [long] 0
    }
    Assert-Throws { Assert-SameSnapshot $good $changed } 'aggregate-change-detected'
    Write-Output 'OFFLINE_TESTS_PASSED: guard-refusals=9; database-readiness-predicate=2; aggregate-equality=1; aggregate-change-detected=1; diagnostic-stage-guards=14; safe-fallback=1; exception-redaction=1; native-stderr-exits=2; native-stderr-redaction=2; error-preference-restoration=2'
}

function Invoke-NativeCommand([string] $Executable, [string[]] $Arguments) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # PowerShell 5.1 promotes native stderr to an error record; keep it non-terminating
        # only for this call so stderr stays discarded and LASTEXITCODE can be captured.
        $ErrorActionPreference = 'Continue'
        $output = @(& $Executable @Arguments 2>$null)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{ Output = $output; ExitCode = $exitCode }
}

function Invoke-Compose([string[]] $Arguments) {
    Push-Location $repositoryRoot
    try {
        $result = Invoke-NativeCommand 'docker' (@('compose', '--project-directory', $repositoryRoot,
            '-f', $composeFile) + $Arguments)
    } finally { Pop-Location }
    if ($result.ExitCode -ne 0) { throw 'Docker Compose inspection or scoped operation failed.' }
    return $result.Output
}

function Get-ComposeConfig {
    $lines = Invoke-Compose @('config', '--format', 'json')
    try { return ($lines -join "`n") | ConvertFrom-Json } catch {
        throw 'Docker Compose configuration could not be safely inspected.'
    }
}

function Get-ComposeState {
    $lines = Invoke-Compose @('ps', '--all', '--format', 'json')
    $items = @()
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace([string] $line)) { continue }
        try { $items += ($line | ConvertFrom-Json) } catch {
            throw 'Docker Compose runtime state could not be safely inspected.'
        }
    }
    return $items
}

function Get-VolumeLabels([string] $VolumeName) {
    Push-Location $repositoryRoot
    try {
        $result = Invoke-NativeCommand 'docker' @('volume', 'inspect', '--format', '{{json .Labels}}', $VolumeName)
    } finally { Pop-Location }
    if ($result.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace([string] $result.Output)) {
        throw 'The named Telemetry persistent volume is missing or unreadable.'
    }
    try { return ($result.Output | ConvertFrom-Json) } catch {
        throw 'Persistent-volume ownership labels could not be safely checked.'
    }
}

function Assert-ComposeModel([pscustomobject] $Config) {
    if ($Config.name -cne $projectName -or $null -eq $Config.services -or
            $null -eq $Config.volumes) {
        throw 'The selected Compose file is not the expected Telemetry project.'
    }
    $database = $Config.services.$databaseService
    if ($null -eq $database -or @($database.volumes).Count -ne 1 -or
            $database.volumes[0].source -cne $volumeKey -or
            $database.volumes[0].target -cne '/var/lib/postgresql/data' -or
            $null -eq $database.healthcheck) {
        throw 'Telemetry database service lacks its expected named volume or healthcheck.'
    }
    $declared = $Config.volumes.$volumeKey
    $volumeName = [string] $declared.name
    Assert-RecoveryContext ([string] $Config.name) $volumeKey $volumeName
    return $volumeName
}

function Assert-DependenciesReady([object[]] $State) {
    $required = @('kafka', 'clinical-service', $databaseService, $telemetryService, 'kafka-init')
    foreach ($service in $required) {
        $serviceMatches = @($State | Where-Object { $_.Service -ceq $service })
        if ($serviceMatches.Count -ne 1) { throw 'A required local Compose dependency is absent or ambiguous.' }
        $item = $serviceMatches[0]
        if ($service -eq 'kafka-init') {
            if ($item.State -cne 'exited' -or [string] $item.ExitCode -cne '0') {
                throw 'Kafka topic provisioning has not completed successfully.'
            }
        } elseif ($item.State -cne 'running' -or
                ($service -in @('kafka', $databaseService) -and $item.Health -cne 'healthy')) {
            throw 'A required local Compose dependency is not running and healthy.'
        }
    }
}

function Test-DatabaseReadyState([object[]] $State) {
    $serviceMatches = @($State | Where-Object { $_.Service -ceq $databaseService })
    return ($serviceMatches.Count -eq 1 -and $serviceMatches[0].State -ceq 'running' -and
        $serviceMatches[0].Health -ceq 'healthy')
}

function Assert-LocalReady([string] $Name, [int] $Port) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get `
            -Uri ("http://localhost:{0}/q/health/ready" -f $Port) -TimeoutSec 5 -ErrorAction Stop
        if ([int] $response.StatusCode -ne 200) { throw 'Not ready.' }
    } catch { throw ("The local {0} readiness check failed." -f $Name) }
}

function Convert-DatabaseSnapshot([object] $Output) {
    $lines = @($Output)
    if ($lines.Count -ne 1) { throw 'Telemetry aggregate snapshot was not a single safe result.' }
    $fields = ([string] $lines[0]).Trim().Split('|')
    if ($fields.Count -ne 5) { throw 'Telemetry aggregate snapshot has an unexpected shape.' }
    $numbers = @()
    foreach ($field in $fields) {
        $parsed = [long] 0
        if (-not [long]::TryParse($field, [ref] $parsed) -or $parsed -lt 0) {
            throw 'Telemetry aggregate snapshot contains invalid metadata.'
        }
        $numbers += $parsed
    }
    return [pscustomobject]@{
        InboxCount = $numbers[0]; SampleCount = $numbers[1]; GazeCount = $numbers[2]
        GameplayCount = $numbers[3]; DuplicateSampleKeys = $numbers[4]
    }
}

function Get-DatabaseSnapshot {
    $sql = @'
SELECT (SELECT count(*) FROM telemetry_batch_inbox),
       count(*),
       count(*) FILTER (WHERE sample_kind = 'GAZE'),
       count(*) FILTER (WHERE sample_kind = 'GAMEPLAY'),
       count(*) - count(DISTINCT (sample_time, batch_id, sample_kind, sample_index))
FROM telemetry_samples;
'@
    $lines = @(Invoke-Compose @('exec', '-T', $databaseService, 'psql', '-X', '-q', '-A', '-t', '-F', '|',
        '-v', 'ON_ERROR_STOP=1', '-U', 'neurovision_telemetry', '-d', 'neurovision_telemetry', '-c', $sql)
    )
    return Convert-DatabaseSnapshot $lines
}

function Wait-ForDatabaseReady([int] $TimeoutSeconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $state = Get-ComposeState
        if (Test-DatabaseReadyState $state) { return }
        Start-Sleep -Seconds 2
    }
    throw 'A restarted local Compose service did not become healthy within the bounded wait.'
}

function Wait-ForTelemetryReady([int] $TimeoutSeconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $state = Get-ComposeState
        $item = @($state | Where-Object { $_.Service -ceq $telemetryService })
        if ($item.Count -eq 1 -and $item[0].State -ceq 'running') {
            try {
                $response = Invoke-WebRequest -UseBasicParsing -Method Get `
                    -Uri 'http://localhost:8084/q/health/ready' -TimeoutSec 5 -ErrorAction Stop
                if ([int] $response.StatusCode -eq 200) { return }
            } catch { }
        }
        Start-Sleep -Seconds 2
    }
    throw 'Telemetry readiness did not recover within the bounded wait.'
}

function Invoke-Recovery([bool] $RunRestart) {
    $script:recoveryStage = 'compose_file'
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        throw 'The root Compose file is unavailable.'
    }
    $script:recoveryStage = 'compose_config'
    $config = Get-ComposeConfig
    $script:recoveryStage = 'compose_model'
    $volumeName = Assert-ComposeModel $config
    $script:recoveryStage = 'volume_labels'
    Assert-VolumeLabels $projectName $volumeKey (Get-VolumeLabels $volumeName)
    $script:recoveryStage = 'dependencies'
    Assert-DependenciesReady (Get-ComposeState)
    $script:recoveryStage = 'clinical_readiness'
    Assert-LocalReady 'Clinical' 8083
    $script:recoveryStage = 'telemetry_readiness'
    Assert-LocalReady 'Telemetry' 8084
    $script:recoveryStage = 'baseline_snapshot'
    $before = Get-DatabaseSnapshot
    Assert-Snapshot $before
    Write-Output ('PREFLIGHT_PASSED: project={0}; volume=verified; synthetic-data=operator-confirmed; inboxRows={1}; samples={2}; gazeRows={3}; gameplayRows={4}; duplicateSampleKeys={5}' -f `
        $projectName, $before.InboxCount, $before.SampleCount, $before.GazeCount,
        $before.GameplayCount, $before.DuplicateSampleKeys)
    if (-not $RunRestart) { return }

    $script:recoveryStage = 'database_restart'
    [void] (Invoke-Compose @('restart', $databaseService))
    $script:recoveryStage = 'database_health'
    Wait-ForDatabaseReady
    $script:recoveryStage = 'telemetry_restart'
    [void] (Invoke-Compose @('restart', $telemetryService))
    $script:recoveryStage = 'telemetry_health'
    Wait-ForTelemetryReady
    $script:recoveryStage = 'post_snapshot'
    $after = Get-DatabaseSnapshot
    Assert-SameSnapshot $before $after
    Write-Output ('RECOVERY_PASSED: database=healthy; telemetry=healthy; inboxRows={0}; samples={1}; gazeRows={2}; gameplayRows={3}; duplicateSampleKeys={4}; historicalRead=not-checked' -f `
        $after.InboxCount, $after.SampleCount, $after.GazeCount, $after.GameplayCount, $after.DuplicateSampleKeys)
}

try {
    if ($Mode -eq 'OfflineTest') {
        Assert-ModeSelection $Mode ([bool] $AuthorizeRestart) ([bool] $ConfirmSyntheticData)
        Invoke-OfflineTests
        exit 0
    }
    if ($Mode -eq 'Preflight') {
        Assert-ModeSelection $Mode ([bool] $AuthorizeRestart) ([bool] $ConfirmSyntheticData)
        Invoke-Recovery $false
        exit 0
    }
    Assert-ModeSelection $Mode ([bool] $AuthorizeRestart) ([bool] $ConfirmSyntheticData)
    Invoke-Recovery $true
} catch {
    Write-Error -Message (Format-FailureDiagnostic $script:recoveryStage $_.Exception.Message) -ErrorAction Continue
    exit 1
}
