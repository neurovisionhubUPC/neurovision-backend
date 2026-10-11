[CmdletBinding()]
param(
    [ValidateSet('OfflineTest', 'Preflight', 'AuthorizedRun', 'RecoveryAuthorizedRun')]
    [string] $Mode = 'OfflineTest',
    [ValidateRange(1, 20)] [int] $BatchCount = 5,
    [ValidateRange(2, 20)] [int] $SamplesPerBatch = 10,
    [ValidateRange(250, 5000)] [int] $BatchIntervalMs = 1000,
    [string] $ClinicalBaseUrl = 'http://localhost:8083',
    [string] $TelemetryBaseUrl = 'http://localhost:8084',
    [switch] $AuthorizePatientPkce,
    [switch] $AuthorizeClinicalSession,
    [switch] $AuthorizeTelemetryWrite,
    [switch] $AuthorizeExpiredSessionTransition,
    [switch] $ConfirmSyntheticData,
    [switch] $AuthorizeRecovery
)

$ErrorActionPreference = 'Stop'
$script:repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script:composeFile = Join-Path $script:repositoryRoot 'compose.yaml'
$script:envPath = Join-Path $script:repositoryRoot '.env'
$script:pkceHelper = Join-Path $PSScriptRoot 'telemetry-compose-capacity-pkce.ps1'
$script:markerPath = Join-Path ([IO.Path]::GetTempPath()) 'neurovision-telemetry-compose-capacity.once'
$script:stage = 'mode_guard'
$script:externalCalls = 0
$script:fileWrites = 0
$script:markerCreated = $false
$script:batchLimit = 20
$script:sampleLimit = 20
$script:requestTimeoutSeconds = 15
$script:persistenceTimeoutSeconds = 30
$script:nativeTimeoutMilliseconds = 15000
$script:projectName = 'neurovision-rehab'
$script:dbService = 'telemetry-timescaledb'
$script:kafkaService = 'kafka'
$script:clinicalService = 'clinical-service'
$script:telemetryService = 'telemetry-service'
$script:kafkaInitService = 'kafka-init'
$script:sourceTopic = 'telemetry-batch-accepted.v1'
$script:dlqTopic = 'telemetry-batch-accepted.v1.dlq'
$script:consumerGroup = 'telemetry-timescale-v1'
$script:recoveryState = $null
$script:recoveryOutcomeAttempted = $false
. (Join-Path $PSScriptRoot 'telemetry-compose-capacity-recovery.ps1')

function Get-Diagnostic([string] $Stage, [string] $Message) {
    $safeStage = if ($Stage -in @('mode_guard', 'configuration', 'compose_model', 'compose_state',
            'readiness', 'baseline', 'pkce_login', 'plan_lookup', 'session_start', 'marker', 'recovery_ledger', 'recovery_outcome',
            'telemetry_ingest', 'persistence_wait', 'kafka_verify')) { $Stage } else { 'unknown' }
    $httpStatus = $null
    if ($safeStage -eq 'plan_lookup' -and $Message -match '^Clinical plan lookup HTTP rejected: ([1-5][0-9]{2})\.$') {
        $guard = 'plan_lookup_http_rejected'
        $httpStatus = [int] $Matches[1]
    } elseif ($safeStage -eq 'session_start' -and $Message -match '^Clinical session start HTTP rejected: ([1-5][0-9]{2})\.$') {
        $guard = 'clinical_session_start_http_rejected'
        $httpStatus = [int] $Matches[1]
    } else { $guard = switch -CaseSensitive ($Message) {
        'OfflineTest does not accept authorization switches.' { 'offline_mode_invalid' }
        'Preflight does not accept live authorization switches.' { 'preflight_mode_invalid' }
        'AuthorizedRun requires all explicit synthetic-use gates.' { 'authorization_missing' }
        'RecoveryAuthorizedRun requires all six explicit gates.' { 'recovery_authorization_missing' }
        'AuthorizedRun does not accept recovery authorization.' { 'recovery_mode_invalid' }
        'Recovery mode accepts only the approved bounded workload.' { 'recovery_workload_invalid' }
        'The recovery marker is missing, invalid, or changed.' { 'recovery_marker_invalid' }
        'Recovery ledger is unavailable or already contains an attempt.' { 'recovery_ledger_blocked' }
        'Recovery outcome could not be durably stored.' { 'recovery_outcome_persistence_failed' }
        'Runtime URLs must be plain HTTP loopback origins.' { 'loopback_origin_invalid' }
        'Compose configuration does not match the local Telemetry target.' { 'compose_target_invalid' }
        'Required local Compose dependencies are absent, ambiguous, or unhealthy.' { 'compose_services_not_ready' }
        'Compose or local inspection command failed.' { 'local_inspection_failed' }
        'Aggregate query returned an invalid result.' { 'aggregate_result_invalid' }
        'Kafka offset or consumer lag state is unavailable or invalid.' { 'kafka_state_invalid' }
        'The one-shot marker already exists or could not be created.' { 'one_shot_marker_unavailable' }
        'Patient does not have exactly one eligible active synthetic plan.' { 'eligible_plan_ambiguous' }
        'Clinical plan lookup transport is unavailable.' { 'plan_lookup_unavailable' }
        'Clinical plan lookup response status is invalid.' { 'plan_lookup_response_invalid' }
        'Clinical plan lookup response JSON is invalid.' { 'plan_lookup_json_invalid' }
        'Clinical plan lookup response shape is invalid.' { 'plan_lookup_shape_invalid' }
        'Clinical session start transport is unavailable.' { 'clinical_session_start_unavailable' }
        'Clinical session start response status is invalid.' { 'clinical_session_start_response_invalid' }
        'Clinical session start response JSON is invalid.' { 'clinical_session_start_json_invalid' }
        'Clinical session start response shape is invalid.' { 'clinical_session_start_shape_invalid' }
        'Clinical session start was rejected or ambiguous.' { 'clinical_session_start_failed' }
        'Telemetry did not accept every configured batch.' { 'telemetry_acceptance_failed' }
        'Persisted aggregates did not reach the exact expected counts.' { 'persistence_incomplete' }
        'Kafka source, lag, or DLQ deltas did not match the isolated run.' { 'kafka_delta_mismatch' }
        'The bounded operation timed out.' { 'bounded_timeout' }
        default { 'operation_failed' }
    } }
    return [pscustomobject]@{ Stage = $safeStage; Guard = $guard; HttpStatus = $httpStatus }
}

function Format-Diagnostic([string] $Stage, [string] $Message) {
    $diagnostic = Get-Diagnostic $Stage $Message
    if ($null -ne $diagnostic.HttpStatus -and $diagnostic.HttpStatus -ge 100 -and $diagnostic.HttpStatus -le 599) {
        return ('CAPACITY_RUN_STOPPED: stage={0}; guard={1}; http_status={2}; no service cleanup was attempted.' -f `
            $diagnostic.Stage, $diagnostic.Guard, $diagnostic.HttpStatus)
    }
    return ('CAPACITY_RUN_STOPPED: stage={0}; guard={1}; no service cleanup was attempted.' -f `
        $diagnostic.Stage, $diagnostic.Guard)
}

function Assert-ModeSelection([string] $SelectedMode, [bool] $Pkce, [bool] $Session,
        [bool] $Write, [bool] $Expired, [bool] $Synthetic, [bool] $Recovery = $false) {
    $anyGate = $Pkce -or $Session -or $Write -or $Expired -or $Synthetic -or $Recovery
    if ($SelectedMode -eq 'OfflineTest' -and $anyGate) {
        throw 'OfflineTest does not accept authorization switches.'
    }
    if ($SelectedMode -eq 'Preflight' -and $anyGate) {
        throw 'Preflight does not accept live authorization switches.'
    }
    if ($SelectedMode -eq 'AuthorizedRun' -and $Recovery) {
        throw 'AuthorizedRun does not accept recovery authorization.'
    }
    if ($SelectedMode -eq 'AuthorizedRun' -and -not ($Pkce -and $Session -and $Write -and $Expired -and $Synthetic)) {
        throw 'AuthorizedRun requires all explicit synthetic-use gates.'
    }
    if ($SelectedMode -eq 'RecoveryAuthorizedRun' -and
            -not ($Pkce -and $Session -and $Write -and $Expired -and $Synthetic -and $Recovery)) {
        throw 'RecoveryAuthorizedRun requires all six explicit gates.'
    }
}

function Assert-RecoveryWorkload([int] $Count, [int] $Samples, [int] $Interval) {
    if ($Count -ne 5 -or $Samples -ne 10 -or $Interval -ne 1000) {
        throw 'Recovery mode accepts only the approved bounded workload.'
    }
    Assert-Workload $Count $Samples $Interval
}

function Assert-Workload([int] $Count, [int] $Samples, [int] $Interval) {
    if ($Count -lt 1 -or $Count -gt $script:batchLimit -or
            $Samples -lt 2 -or $Samples -gt $script:sampleLimit -or
            $Interval -lt 250 -or $Interval -gt 5000 -or
            [long] $Count * $Interval -gt 120000) {
        throw 'The configured workload exceeds a conservative bound.'
    }
}

function Assert-LoopbackOrigin([string] $Value) {
    $uri = $null
    if (-not [uri]::TryCreate($Value, [UriKind]::Absolute, [ref] $uri) -or
            $uri.Scheme -cne 'http' -or $uri.Host -cnotin @('localhost', '127.0.0.1', '::1') -or
            -not [string]::IsNullOrEmpty($uri.UserInfo) -or $uri.AbsolutePath -ne '/' -or
            -not [string]::IsNullOrEmpty($uri.Query) -or -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw 'Runtime URLs must be plain HTTP loopback origins.'
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

function Get-ComposeDefinitionLines([object] $Source) {
    if ($Source -is [string]) {
        if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
            throw 'Compose configuration does not match the local Telemetry target.'
        }
        return @(Get-Content -LiteralPath $Source)
    }
    return @($Source)
}

function Assert-ComposeProject([object] $Source) {
    try { $lines = Get-ComposeDefinitionLines $Source } catch {
        throw 'Compose configuration does not match the local Telemetry target.'
    }
    if (@($lines | Where-Object { $_ -cmatch '^name:\s*neurovision-rehab\s*$' }).Count -ne 1 -or
            @($lines | Where-Object { $_ -cmatch '^\s{2}telemetry-timescaledb-data:\s*$' }).Count -ne 1 -or
            @($lines | Where-Object { $_ -cmatch '^\s{6}- telemetry-timescaledb-data:/var/lib/postgresql/data\s*$' }).Count -ne 1) {
        throw 'Compose configuration does not match the local Telemetry target.'
    }
    $required = @($script:kafkaService, $script:kafkaInitService, $script:dbService,
        $script:clinicalService, $script:telemetryService)
    foreach ($service in $required) {
        $pattern = '^\s{2}' + [regex]::Escape($service) + ':\s*$'
        if (@($lines | Where-Object { $_ -cmatch $pattern }).Count -ne 1) {
            throw 'Compose configuration does not match the local Telemetry target.'
        }
    }
}

function Assert-ServiceCapacityBounds([object] $Source, [int] $Samples, [int] $Interval) {
    try { $lines = Get-ComposeDefinitionLines $Source } catch {
        throw 'Compose configuration does not match the local Telemetry target.'
    }
    $maxSamplesLine = @($lines | Where-Object { $_ -cmatch '^\s{6}TELEMETRY_MAX_SAMPLES:\s*[0-9]+\s*$' })
    $maxRateLine = @($lines | Where-Object { $_ -cmatch '^\s{6}TELEMETRY_MAX_BATCHES_PER_SECOND:\s*[0-9]+\s*$' })
    $maxSamples = 0; $maxRate = 0
    if ($maxSamplesLine.Count -ne 1 -or $maxRateLine.Count -ne 1 -or
            -not [int]::TryParse(($maxSamplesLine[0] -replace '^\s{6}TELEMETRY_MAX_SAMPLES:\s*', ''), [ref] $maxSamples) -or
            -not [int]::TryParse(($maxRateLine[0] -replace '^\s{6}TELEMETRY_MAX_BATCHES_PER_SECOND:\s*', ''), [ref] $maxRate) -or
            $maxSamples -lt $Samples -or $maxRate -lt [Math]::Ceiling(1000.0 / $Interval)) {
        throw 'Compose configuration does not match the local Telemetry target.'
    }
}

function Assert-VolumeOwnership {
    $volumeName = 'neurovision-rehab_telemetry-timescaledb-data'
    try {
        $lines = @(Invoke-BoundedNative 'docker' @('volume', 'inspect', '--format', '{{json .Labels}}', $volumeName))
        if ($lines.Count -ne 1) { throw 'bad' }
        $labels = $lines[0] | ConvertFrom-Json
        if ($labels.'com.docker.compose.project' -cne $script:projectName -or
                $labels.'com.docker.compose.volume' -cne 'telemetry-timescaledb-data') { throw 'bad' }
    } catch { throw 'Compose configuration does not match the local Telemetry target.' }
}

function Assert-Dependencies([object[]] $State) {
    foreach ($service in @($script:kafkaService, $script:kafkaInitService, $script:dbService,
            $script:clinicalService, $script:telemetryService)) {
        $matches = @($State | Where-Object { $_.Service -ceq $service })
        if ($matches.Count -ne 1) { throw 'Required local Compose dependencies are absent, ambiguous, or unhealthy.' }
        $item = $matches[0]
        if ($service -eq $script:kafkaInitService) {
            if ($item.State -cne 'exited' -or [string] $item.ExitCode -cne '0') {
                throw 'Required local Compose dependencies are absent, ambiguous, or unhealthy.'
            }
        } elseif ($item.State -cne 'running' -or
                ($service -in @($script:kafkaService, $script:dbService) -and $item.Health -cne 'healthy')) {
            throw 'Required local Compose dependencies are absent, ambiguous, or unhealthy.'
        }
    }
}

function Assert-ComposeArgumentsAllowed([string[]] $Arguments) {
    if ($Arguments.Count -lt 1 -or $Arguments[0] -cnotin @('ps', 'exec')) {
        throw 'Compose or local inspection command failed.'
    }
    if ($Arguments[0] -eq 'exec') {
        if ($Arguments.Count -lt 4 -or $Arguments[2] -cnotin @($script:dbService, $script:kafkaService)) {
            throw 'Compose or local inspection command failed.'
        }
        if (($Arguments[2] -eq $script:dbService -and $Arguments[3] -cne 'psql') -or
                ($Arguments[2] -eq $script:kafkaService -and $Arguments[3] -cnotin @(
                    '/opt/kafka/bin/kafka-get-offsets.sh', '/opt/kafka/bin/kafka-consumer-groups.sh'))) {
            throw 'Compose or local inspection command failed.'
        }
    }
}

function Quote-WindowsArgument([string] $Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

function Invoke-BoundedNative([string] $Executable, [string[]] $Arguments,
        [string] $InputText = '', [int] $TimeoutMs = 15000) {
    $script:externalCalls++
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Executable
    $info.Arguments = (($Arguments | ForEach-Object { Quote-WindowsArgument ([string] $_) }) -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Compose or local inspection command failed.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not [string]::IsNullOrEmpty($InputText)) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutMs)) {
            try { $process.Kill() } catch { }
            throw 'The bounded operation timed out.'
        }
        $null = $stderr.Result
        $output = $stdout.Result
        if ($output.Length -gt 65536 -or $process.ExitCode -ne 0) {
            throw 'Compose or local inspection command failed.'
        }
        return @($output -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    } catch {
        if ($_.Exception.Message -in @('The bounded operation timed out.',
                'Compose or local inspection command failed.')) { throw }
        throw 'Compose or local inspection command failed.'
    } finally { $process.Dispose() }
}

function Invoke-Compose([string[]] $Arguments, [string] $InputText = '') {
    Assert-ComposeArgumentsAllowed $Arguments
    Push-Location $script:repositoryRoot
    try {
        $args = @('compose', '--project-directory', $script:repositoryRoot, '-f', $script:composeFile,
            '-p', $script:projectName) + $Arguments
        return Invoke-BoundedNative 'docker' $args $InputText
    } finally { Pop-Location }
}

function Get-ComposeState {
    try {
        $lines = Invoke-Compose @('ps', '--all', '--format', 'json')
        $items = @()
        foreach ($line in $lines) { $items += ($line | ConvertFrom-Json) }
        return $items
    } catch { throw 'Required local Compose dependencies are absent, ambiguous, or unhealthy.' }
}

function Assert-Ready([string] $Name, [string] $Origin) {
    $script:externalCalls++
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri "$Origin/q/health/ready" `
            -TimeoutSec 5 -ErrorAction Stop
        if ([int] $response.StatusCode -ne 200) { throw 'Not ready.' }
    } catch { throw ('{0} readiness is unavailable.' -f $Name) }
}

function Invoke-LocalRequest([string] $Method, [string] $Uri, [string] $Token,
        [string] $Body = '', [hashtable] $Headers = @{}) {
    $script:externalCalls++
    $requestHeaders = @{ Authorization = "Bearer $Token"; Accept = 'application/json' }
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = $Headers[$key] }
    try {
        $args = @{ UseBasicParsing = $true; Method = $Method; Uri = $Uri; Headers = $requestHeaders
            TimeoutSec = $script:requestTimeoutSeconds; ErrorAction = 'Stop' }
        if ($Method -eq 'POST') { $args.ContentType = 'application/json'; $args.Body = $Body }
        $response = Invoke-WebRequest @args
        return @{ Status = [int] $response.StatusCode; Content = [string] $response.Content }
    } catch {
        $status = 0
        if ($null -ne $_.Exception.Response) { $status = [int] $_.Exception.Response.StatusCode }
        return @{ Status = $status; Content = '' }
    }
}

function Invoke-LocalGet([string] $Uri, [string] $Token = '') {
    $script:externalCalls++
    $headers = @{}
    if (-not [string]::IsNullOrEmpty($Token)) { $headers.Authorization = "Bearer $Token" }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $Uri -Headers $headers `
            -TimeoutSec $script:requestTimeoutSeconds -ErrorAction Stop
        return @{ Status = [int] $response.StatusCode; Content = [string] $response.Content }
    } catch {
        $status = 0
        if ($null -ne $_.Exception.Response) { $status = [int] $_.Exception.Response.StatusCode }
        return @{ Status = $status; Content = '' }
    }
}

function Get-DbSnapshot([string[]] $BatchIds = @()) {
    if ($BatchIds.Count -gt 0) {
        foreach ($id in $BatchIds) {
            $parsed = [guid]::Empty
            if (-not [guid]::TryParse($id, [ref] $parsed) -or $parsed -eq [guid]::Empty) {
                throw 'Aggregate query returned an invalid result.'
            }
        }
        $literalList = ($BatchIds | ForEach-Object { "'$_'::uuid" }) -join ','
        $sql = @"
WITH ids AS (SELECT unnest(ARRAY[$literalList]::uuid[]) AS batch_id),
inbox AS (SELECT i.batch_id FROM telemetry_batch_inbox i JOIN ids USING (batch_id)),
samples AS (SELECT s.batch_id,s.sample_kind,s.sample_index FROM telemetry_samples s JOIN ids USING (batch_id))
SELECT (SELECT count(DISTINCT batch_id) FROM inbox), count(*),
       count(*) FILTER (WHERE sample_kind='GAZE'), count(*) FILTER (WHERE sample_kind='GAMEPLAY'),
       count(*)-count(DISTINCT (batch_id,sample_kind,sample_index)) FROM samples;
"@
    } else {
        $sql = @'
SELECT (SELECT count(*) FROM telemetry_batch_inbox), count(*),
       count(*) FILTER (WHERE sample_kind='GAZE'), count(*) FILTER (WHERE sample_kind='GAMEPLAY'),
       count(*)-count(DISTINCT (batch_id,sample_kind,sample_index)) FROM telemetry_samples;
'@
    }
    try {
        $output = Invoke-Compose @('exec', '-T', $script:dbService, 'psql', '-X', '-q', '-A', '-t', '-F', '|',
            '-v', 'ON_ERROR_STOP=1', '-U', 'neurovision_telemetry', '-d', 'neurovision_telemetry', '-f', '-') $sql
        return ConvertFrom-AggregateOutput $output
    } catch { throw 'Aggregate query returned an invalid result.' }
}

function ConvertFrom-AggregateOutput([string[]] $Output) {
    if ($Output.Count -ne 1) { throw 'bad' }
    $fields = ([string] $Output[0]).Trim().Split('|')
    if ($fields.Count -ne 5) { throw 'bad' }
    $numbers = @()
    foreach ($field in $fields) {
        $value = [long] 0
        if (-not [long]::TryParse($field, [ref] $value) -or $value -lt 0) { throw 'bad' }
        $numbers += $value
    }
    return [pscustomobject]@{ Inbox = $numbers[0]; Samples = $numbers[1]; Gaze = $numbers[2]; Gameplay = $numbers[3]; DuplicateKeys = $numbers[4] }
}

function Get-KafkaOffsets([string] $Topic) {
    try {
        $output = Invoke-Compose @('exec', '-T', $script:kafkaService,
            '/opt/kafka/bin/kafka-get-offsets.sh', '--bootstrap-server', 'kafka:9092',
            '--topic', $Topic, '--time', '-1')
        return ConvertFrom-KafkaOffsetOutput $Topic $output
    } catch { throw 'Kafka offset or consumer lag state is unavailable or invalid.' }
}

function ConvertFrom-KafkaOffsetOutput([string] $Topic, [string[]] $Output) {
    $result = @{}
    foreach ($line in $Output) {
        $parts = ([string] $line).Trim().Split(':')
        $partition = 0; $offset = [long] 0
        if ($parts.Count -ne 3 -or $parts[0] -cne $Topic -or
                -not [int]::TryParse($parts[1], [ref] $partition) -or
                -not [long]::TryParse($parts[2], [ref] $offset) -or $offset -lt 0 -or
                $result.ContainsKey($partition)) { throw 'bad' }
        $result[$partition] = $offset
    }
    if ($result.Count -ne 3 -or -not $result.ContainsKey(0) -or
            -not $result.ContainsKey(1) -or -not $result.ContainsKey(2)) { throw 'bad' }
    return $result
}

function ConvertFrom-ConsumerGroupState([string[]] $Output) {
    $rows = @()
    foreach ($line in $Output) {
        $parts = @(([string] $line).Trim() -split '\s+')
        if ($parts.Count -ge 6 -and $parts[0] -ceq $script:consumerGroup -and $parts[1] -ceq $script:sourceTopic) {
            $partition = 0; $committed = [long] 0; $lag = [long] 0
            if (-not [int]::TryParse($parts[2], [ref] $partition) -or
                    -not [long]::TryParse($parts[3], [ref] $committed) -or $committed -lt 0 -or
                    -not [long]::TryParse($parts[5], [ref] $lag) -or $lag -lt 0) { throw 'bad' }
            $rows += [pscustomobject]@{ Partition = $partition; Committed = $committed; Lag = $lag }
        }
    }
    if ($rows.Count -ne 3 -or @($rows.Partition | Sort-Object -Unique).Count -ne 3) { throw 'bad' }
    $offsets = @{}
    foreach ($row in $rows) { $offsets[[int] $row.Partition] = [long] $row.Committed }
    return [pscustomobject]@{ Offsets = $offsets; Lag = [long] (($rows | Measure-Object -Property Lag -Sum).Sum) }
}

function Get-ConsumerState {
    try {
        $output = Invoke-Compose @('exec', '-T', $script:kafkaService,
            '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'kafka:9092',
            '--group', $script:consumerGroup, '--describe')
        return ConvertFrom-ConsumerGroupState $output
    } catch { throw 'Kafka offset or consumer lag state is unavailable or invalid.' }
}

function Get-KafkaSnapshot {
    $consumer = Get-ConsumerState
    return [pscustomobject]@{
        Source = Get-KafkaOffsets $script:sourceTopic
        Dlq = Get-KafkaOffsets $script:dlqTopic
        Consumer = $consumer.Offsets
        Lag = $consumer.Lag
    }
}

function Assert-KafkaStable([object] $Snapshot) {
    if ($Snapshot.Lag -ne 0) { throw 'Kafka offset or consumer lag state is unavailable or invalid.' }
}

function Format-PreflightKafkaSummary([object] $Snapshot) {
    return ('lag={0}; dlq_offset_partitions_observed={1}; kafka_snapshot=single_read_only_snapshot' -f `
        $Snapshot.Lag, @($Snapshot.Dlq.Keys).Count)
}

function Get-CapacityElapsedMilliseconds([long] $StartTimestamp, [long] $EndTimestamp) {
    if ($EndTimestamp -lt $StartTimestamp) { throw 'Monotonic duration calculation failed.' }
    return [long] (($EndTimestamp - $StartTimestamp) * 1000.0 / [Diagnostics.Stopwatch]::Frequency)
}

function Assert-SameKafka([object] $Before, [object] $After, [int] $ExpectedSourceDelta) {
    $sourceDelta = [long] 0; $consumerDelta = [long] 0
    foreach ($partition in 0..2) {
        $sourceDelta += [long] $After.Source[$partition] - [long] $Before.Source[$partition]
        $consumerDelta += [long] $After.Consumer[$partition] - [long] $Before.Consumer[$partition]
        if ($After.Source[$partition] -lt $Before.Source[$partition] -or
                $After.Consumer[$partition] -lt $Before.Consumer[$partition] -or
                $After.Dlq[$partition] -ne $Before.Dlq[$partition]) {
            throw 'Kafka source, lag, or DLQ deltas did not match the isolated run.'
        }
    }
    if ($sourceDelta -ne $ExpectedSourceDelta -or $consumerDelta -ne $ExpectedSourceDelta -or $After.Lag -ne 0) {
        throw 'Kafka source, lag, or DLQ deltas did not match the isolated run.'
    }
}

function Wait-KafkaCompletion([object] $Before, [int] $ExpectedRecords) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $previousComplete = $null
    do {
        $after = Get-KafkaSnapshot
        $sourceDelta = [long] 0; $consumerDelta = [long] 0
        foreach ($partition in 0..2) {
            $sourcePart = [long] $after.Source[$partition] - [long] $Before.Source[$partition]
            $consumerPart = [long] $after.Consumer[$partition] - [long] $Before.Consumer[$partition]
            if ($sourcePart -lt 0 -or $sourcePart -gt $ExpectedRecords -or $consumerPart -lt 0 -or
                    $after.Dlq[$partition] -ne $Before.Dlq[$partition]) {
                throw 'Kafka source, lag, or DLQ deltas did not match the isolated run.'
            }
            $sourceDelta += $sourcePart
            $consumerDelta += $consumerPart
        }
        if ($sourceDelta -gt $ExpectedRecords -or $consumerDelta -gt $ExpectedRecords) {
            throw 'Kafka source, lag, or DLQ deltas did not match the isolated run.'
        }
        if ($sourceDelta -eq $ExpectedRecords -and $consumerDelta -eq $ExpectedRecords -and $after.Lag -eq 0) {
            if ($null -ne $previousComplete) {
                Assert-SameKafka $previousComplete $after 0
                Assert-SameKafka $Before $after $ExpectedRecords
                return $after
            }
            $previousComplete = $after
        }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt 15)
    throw 'The bounded operation timed out.'
}

function Wait-Persisted([string[]] $BatchIds, [int] $ExpectedBatches, [int] $ExpectedSamples,
        [int] $ExpectedGaze, [int] $ExpectedGameplay) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $snapshot = Get-DbSnapshot $BatchIds
        if ($snapshot.Inbox -gt $ExpectedBatches -or $snapshot.Samples -gt $ExpectedSamples -or
                $snapshot.Gaze -gt $ExpectedGaze -or $snapshot.Gameplay -gt $ExpectedGameplay -or
                $snapshot.DuplicateKeys -ne 0) {
            throw 'Persisted aggregates did not reach the exact expected counts.'
        }
        if ($snapshot.Inbox -eq $ExpectedBatches -and $snapshot.Samples -eq $ExpectedSamples -and
                $snapshot.Gaze -eq $ExpectedGaze -and $snapshot.Gameplay -eq $ExpectedGameplay) { return $snapshot }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $script:persistenceTimeoutSeconds)
    throw 'The bounded operation timed out.'
}

function New-OneShotMarker {
    $script:externalCalls++
    $script:fileWrites++
    Claim-OneShotMarker {
        $stream = [IO.File]::Open($script:markerPath, [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            $bytes = [Text.Encoding]::ASCII.GetBytes('consumed')
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
            $script:markerCreated = $true
            return $true
        } finally { $stream.Dispose() }
    }
}

function New-CapacityRecoveryIntent([string] $MarkerHash, [guid] $PlanId,
        [string] $IdempotencyKey, [guid] $StreamId, [string[]] $BatchIds,
        [object] $Baseline, [object] $RunBaseline) {
    if ($MarkerHash -notmatch '^[0-9a-f]{64}$' -or $PlanId -eq [guid]::Empty -or
            $StreamId -eq [guid]::Empty -or $BatchIds.Count -ne 5) {
        throw 'Recovery intent is invalid.'
    }
    $key=[guid]::Empty
    if (-not [guid]::TryParse($IdempotencyKey,[ref]$key) -or $key -eq [guid]::Empty) {
        throw 'Recovery intent is invalid.'
    }
    return [ordered]@{
        schemaVersion=1; mode='RecoveryAuthorizedRun'; state='IN_FLIGHT'
        targetProject=$script:projectName; clinicalOrigin=$ClinicalBaseUrl; telemetryOrigin=$TelemetryBaseUrl
        legacyMarkerSha256=$MarkerHash; priorAttemptStage='session_start'
        priorAttemptOutcome='uncertain; this is a new attempt, not a replay'
        planId=$PlanId.ToString(); clinicalIdempotencyKey=$key.ToString()
        clinicalRequestBody='{"exerciseCode":"FIXATION"}'; streamId=$StreamId.ToString()
        batchIds=@($BatchIds); batchCount=5; samplesPerBatch=10; batchIntervalMs=1000
        baseline=(ConvertTo-SafeSummary $Baseline); runBaseline=(ConvertTo-SafeSummary $RunBaseline)
    }
}

function New-CapacityRecoveryOutcome($State, [string] $RunState, [string] $Stage,
        [string] $Guard, $HttpStatus = $null) {
    $status=0
    if ($null -ne $HttpStatus -and [int]::TryParse([string]$HttpStatus,[ref]$status) -and $status -ge 100 -and $status -le 599) { }
    else { $status=$null }
    return [ordered]@{
        schemaVersion=1; state=$RunState; stage=$Stage; guard=$Guard; httpStatus=$status
        clinicalPostAttempts=[int]$(if($State.ClinicalPostAttempted){1}else{0})
        clinicalSessionAccepted=[bool]$State.ClinicalSessionAccepted
        telemetryPostAttempts=[int]$State.TelemetryPostAttempts
        telemetryAcceptedBatches=[int]$State.TelemetryAcceptedBatches
        batchCount=5; samplesPerBatch=10; batchIntervalMs=1000
        sessionId=$State.SessionId; streamId=$State.StreamId; batchIds=@($State.BatchIds)
    }
}

function Assert-MarkerAbsent([bool] $Exists) {
    if ($Exists) { throw 'The one-shot marker already exists or could not be created.' }
}

function Claim-OneShotMarker([scriptblock] $CreateExclusive) {
    try {
        if (-not (& $CreateExclusive)) { throw 'not-created' }
    } catch { throw 'The one-shot marker already exists or could not be created.' }
}

function ConvertTo-SafeSummary([object] $Snapshot) {
    return [pscustomobject]@{ Inbox = $Snapshot.Inbox; Samples = $Snapshot.Samples
        Gaze = $Snapshot.Gaze; Gameplay = $Snapshot.Gameplay; DuplicateKeys = $Snapshot.DuplicateKeys }
}

function New-SyntheticBatch([object] $Fixture, [guid] $SessionId, [guid] $StreamId,
        [long] $Sequence, [int] $Samples, [DateTimeOffset] $CapturedAt) {
    $gazeCount = [int] [Math]::Ceiling($Samples / 2.0)
    $gameCount = $Samples - $gazeCount
    $gazeTemplate = $Fixture.gazeSamples[0]
    $gameTemplate = $Fixture.gameplaySamples[0]
    $gaze = @(); $game = @()
    for ($i = 0; $i -lt $gazeCount; $i++) {
        $item = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $gazeTemplate -Depth 8 -Compress)
        $item.offsetMs = [long] ($i * 10)
        $gaze += $item
    }
    for ($i = 0; $i -lt $gameCount; $i++) {
        $item = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $gameTemplate -Depth 8 -Compress)
        $item.offsetMs = [long] ($i * 10 + 5)
        $game += $item
    }
    return [pscustomobject]@{
        schemaVersion = 1; batchId = [guid]::NewGuid().ToString()
        therapySessionId = $SessionId.ToString(); streamId = $StreamId.ToString()
        sequence = $Sequence; capturedAt = $CapturedAt.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        sentAt = ''; gazeSamples = $gaze; gameplaySamples = $game
    }
}

function Invoke-OfflineTests {
    . $script:pkceHelper
    $script:externalCalls = 0; $script:fileWrites = 0; $script:markerCreated = $false
    Assert-ModeSelection 'OfflineTest' $false $false $false $false $false
    Assert-Workload 5 10 1000
    Assert-LoopbackOrigin 'http://localhost:8083' | Out-Null
    $validCompose = @(
        'name: neurovision-rehab', 'services:', '  kafka:', '  kafka-init:',
        '  telemetry-timescaledb:', '  clinical-service:', '  telemetry-service:',
        '      TELEMETRY_MAX_SAMPLES: 1000', '      TELEMETRY_MAX_BATCHES_PER_SECOND: 20',
        '      - telemetry-timescaledb-data:/var/lib/postgresql/data',
        'volumes:', '  telemetry-timescaledb-data:')
    Assert-ComposeProject $validCompose
    Assert-ServiceCapacityBounds $validCompose 10 1000
    $invalidCompose = @($validCompose | Where-Object { $_ -notmatch 'TELEMETRY_MAX_SAMPLES' }) +
        '      TELEMETRY_MAX_SAMPLES: 2'
    Assert-Dependencies @(
        [pscustomobject]@{ Service='kafka'; State='running'; Health='healthy' },
        [pscustomobject]@{ Service='kafka-init'; State='exited'; ExitCode=0 },
        [pscustomobject]@{ Service='telemetry-timescaledb'; State='running'; Health='healthy' },
        [pscustomobject]@{ Service='clinical-service'; State='running' },
        [pscustomobject]@{ Service='telemetry-service'; State='running' })
    $nativeFunction = (Get-Item Function:\Invoke-BoundedNative).ScriptBlock
    try {
        Set-Item Function:\Invoke-BoundedNative -Force -Value {
            param([string] $Executable, [string[]] $Arguments, [string] $InputText = '', [int] $TimeoutMs = 15000)
            return '{"com.docker.compose.project":"neurovision-rehab","com.docker.compose.volume":"telemetry-timescaledb-data"}'
        }
        Assert-VolumeOwnership
    } finally { Set-Item Function:\Invoke-BoundedNative -Force -Value $nativeFunction }
    if ($script:externalCalls -ne 0) { throw 'Offline volume-label validation called an external process.' }
    $localGetFunction = (Get-Item Function:\Invoke-LocalGet).ScriptBlock
    try {
        Set-Item Function:\Invoke-LocalGet -Force -Value {
            param([string] $Uri, [string] $Token = '')
            return $script:fakePlanResponse
        }
        $expectedPlan = '00000000-0000-4000-8000-000000000001'
        $validPlanJson = '[{"therapyPlanId":"' + $expectedPlan + '","status":"ACTIVE","dailyExercisePrescriptions":{"FIXATION":1}}]'
        $planObjectJson = $validPlanJson.Substring(1).TrimEnd(']')
        $script:fakePlanResponse = @{ Status=200; Content=$validPlanJson }
        if ((Get-EligiblePlan 'http://localhost:8083' 'synthetic-fake-token').ToString() -cne $expectedPlan) {
            throw 'Synthetic plan lookup success fixture was not accepted.'
        }
        $planFailureCases = @(
            [pscustomobject]@{ Response=@{Status=0;Content='private transport detail'}; Expected='guard=plan_lookup_unavailable;' },
            [pscustomobject]@{ Response=@{Status=401;Content='private unauthorized body'}; Expected='guard=plan_lookup_http_rejected; http_status=401;' },
            [pscustomobject]@{ Response=@{Status=403;Content='private forbidden body'}; Expected='guard=plan_lookup_http_rejected; http_status=403;' },
            [pscustomobject]@{ Response=@{Status=503;Content='private unavailable body'}; Expected='guard=plan_lookup_http_rejected; http_status=503;' },
            [pscustomobject]@{ Response=@{Status=600;Content='private status body'}; Expected='guard=plan_lookup_response_invalid;' },
            [pscustomobject]@{ Response=@{Status=200;Content='[{' }; Expected='guard=plan_lookup_json_invalid;' },
            [pscustomobject]@{ Response=@{Status=200;Content='{}' }; Expected='guard=plan_lookup_shape_invalid;' },
            [pscustomobject]@{ Response=@{Status=200;Content='[]' }; Expected='guard=eligible_plan_ambiguous;' },
            [pscustomobject]@{ Response=@{Status=200;Content='[' + $planObjectJson + ',' + $planObjectJson + ']' }; Expected='guard=eligible_plan_ambiguous;' })
        foreach ($case in $planFailureCases) {
            $script:fakePlanResponse = $case.Response
            $caught = $false
            try { [void] (Get-EligiblePlan 'http://localhost:8083' 'synthetic-fake-token') }
            catch {
                $diagnostic = Format-Diagnostic 'plan_lookup' $_.Exception.Message
                if ($diagnostic -notmatch [regex]::Escape($case.Expected) -or
                        $diagnostic -match 'private|synthetic-fake-token|00000000|unauthorized|forbidden|unavailable body|transport detail') {
                    throw 'Plan lookup diagnostic classification or redaction failed.'
                }
                $caught = $true
            }
            if (-not $caught) { throw 'A failing synthetic plan lookup fixture was accepted.' }
        }
    } finally { Set-Item Function:\Invoke-LocalGet -Force -Value $localGetFunction }
    if ($script:externalCalls -ne 0) { throw 'Offline plan lookup tests called an external process.' }
    $localRequestFunction = (Get-Item Function:\Invoke-LocalRequest).ScriptBlock
    try {
        Set-Item Function:\Invoke-LocalRequest -Force -Value {
            param([string] $Method, [string] $Uri, [string] $Token, [string] $Body = '', [hashtable] $Headers = @{})
            $script:fakeSessionPostCount++
            $script:fakeSessionRequest=[pscustomobject]@{Method=$Method;Headers=$Headers;Body=$Body;Token=$Token}
            if ($script:throwFakeSessionPost) { throw 'synthetic transport failure' }
            return $script:fakeSessionResponse
        }
        $script:fakeSessionPostCount=0; $script:fakeSessionRequest=$null; $script:throwFakeSessionPost=$false
        $testPlanId = [guid] '00000000-0000-4000-8000-000000000002'
        $testSessionId = '00000000-0000-4000-8000-000000000003'
        $validSessionJson = '{"therapySessionId":"' + $testSessionId + '","status":"ACTIVE","exerciseCode":"fixation","therapyPlanId":"' + $testPlanId.ToString() + '"}'
        $script:fakeSessionResponse = @{ Status=201; Content=$validSessionJson }
        if ((Start-SyntheticSession 'http://localhost:8083' 'synthetic-fake-token' $testPlanId).ToString() -cne $testSessionId) {
            throw 'Synthetic session start success fixture was not accepted.'
        }
        $script:fakeSessionPostCount=0
        $persistedKey='00000000-0000-4000-8000-000000000009'
        if ((Start-SyntheticSession 'http://localhost:8083' 'synthetic-fake-token' $testPlanId $persistedKey).ToString() -cne $testSessionId -or
                $script:fakeSessionPostCount -ne 1 -or $script:fakeSessionRequest.Headers['Idempotency-Key'] -cne $persistedKey -or
                $script:fakeSessionRequest.Body -cne '{"exerciseCode":"FIXATION"}') {
            throw 'Recovery session start did not use its persisted key in exactly one request.'
        }
        $script:fakeSessionPostCount=0; $script:throwFakeSessionPost=$true
        try { Start-SyntheticSession 'http://localhost:8083' 'synthetic-fake-token' $testPlanId $persistedKey | Out-Null }
        catch { }
        $script:throwFakeSessionPost=$false
        if ($script:fakeSessionPostCount -ne 1) { throw 'An ambiguous Clinical POST was retried.' }
        $sessionFailureCases = @(
            [pscustomobject]@{ Response=@{Status=0;Content='private transport detail'}; Expected='guard=clinical_session_start_unavailable;' },
            [pscustomobject]@{ Response=@{Status=401;Content='private unauthorized body'}; Expected='guard=clinical_session_start_http_rejected; http_status=401;' },
            [pscustomobject]@{ Response=@{Status=403;Content='private forbidden body'}; Expected='guard=clinical_session_start_http_rejected; http_status=403;' },
            [pscustomobject]@{ Response=@{Status=503;Content='private unavailable body'}; Expected='guard=clinical_session_start_http_rejected; http_status=503;' },
            [pscustomobject]@{ Response=@{Status=600;Content='private status body'}; Expected='guard=clinical_session_start_response_invalid;' },
            [pscustomobject]@{ Response=@{Status='invalid';Content='private status body'}; Expected='guard=clinical_session_start_response_invalid;' },
            [pscustomobject]@{ Response=@{Status=201;Content='{' }; Expected='guard=clinical_session_start_json_invalid;' },
            [pscustomobject]@{ Response=@{Status=201;Content='{}' }; Expected='guard=clinical_session_start_shape_invalid;' },
            [pscustomobject]@{ Response=@{Status=201;Content=($validSessionJson -replace '"fixation"','"saccade"')}; Expected='guard=clinical_session_start_shape_invalid;' },
            [pscustomobject]@{ Response=@{Status=201;Content=($validSessionJson -replace '"fixation"','"FIXATION"')}; Expected='guard=clinical_session_start_shape_invalid;' })
        foreach ($case in $sessionFailureCases) {
            $script:fakeSessionResponse = $case.Response
            $caught = $false
            try { [void] (Start-SyntheticSession 'http://localhost:8083' 'synthetic-fake-token' $testPlanId) }
            catch {
                $diagnostic = Format-Diagnostic 'session_start' $_.Exception.Message
                if ($diagnostic -notmatch [regex]::Escape($case.Expected) -or
                        $diagnostic -match 'private|synthetic-fake-token|00000000|unauthorized|forbidden|unavailable body|transport detail') {
                    throw 'Clinical session-start diagnostic classification or redaction failed.'
                }
                $caught = $true
            }
            if (-not $caught) { throw 'A failing synthetic session-start fixture was accepted.' }
        }
    } finally { Set-Item Function:\Invoke-LocalRequest -Force -Value $localRequestFunction }
    if ($script:externalCalls -ne 0) { throw 'Offline session-start tests called an external process.' }
    $before = [pscustomobject]@{ Inbox=0L; Samples=0L; Gaze=0L; Gameplay=0L; DuplicateKeys=0L }
    $after = [pscustomobject]@{ Inbox=5L; Samples=50L; Gaze=25L; Gameplay=25L; DuplicateKeys=0L }
    $sourceBefore = @{ 0=10L; 1=20L; 2=30L }; $sourceAfter = @{ 0=15L; 1=20L; 2=30L }
    $dlq = @{ 0=0L; 1=2L; 2=1L }
    $consumerBefore = @{ 0=8L; 1=20L; 2=30L }; $consumerAfter = @{ 0=13L; 1=20L; 2=30L }
    Assert-SameKafka ([pscustomobject]@{ Source=$sourceBefore; Consumer=$consumerBefore; Dlq=$dlq; Lag=0L }) `
        ([pscustomobject]@{ Source=$sourceAfter; Consumer=$consumerAfter; Dlq=$dlq; Lag=0L }) 5
    foreach ($case in @(
        { Assert-Workload 21 10 1000 },
        { Assert-Workload 5 1 1000 },
        { Assert-Workload 5 10 100 },
        { Assert-LoopbackOrigin 'http://remote.invalid' },
        { Assert-LoopbackOrigin 'https://localhost:8083' },
        { Assert-ServiceCapacityBounds $invalidCompose 10 1000 },
        { Assert-ModeSelection 'AuthorizedRun' $false $true $true $true $true },
        { Assert-ModeSelection 'AuthorizedRun' $true $false $true $true $true },
        { Assert-ModeSelection 'AuthorizedRun' $true $true $false $true $true },
        { Assert-ModeSelection 'AuthorizedRun' $true $true $true $false $true },
        { Assert-ModeSelection 'AuthorizedRun' $true $true $true $true $false },
        { Assert-ModeSelection 'OfflineTest' $true $false $false $false $false },
        { Assert-MarkerAbsent $true },
        { Assert-Dependencies @() },
        { Assert-ComposeArgumentsAllowed @('up', '-d') },
        { Assert-ComposeArgumentsAllowed @('exec', '-T', 'clinical-service', 'sh') },
        { Assert-SameKafka ([pscustomobject]@{Source=$sourceBefore;Consumer=$consumerBefore;Dlq=$dlq;Lag=0L}) `
            ([pscustomobject]@{Source=@{0=16L;1=20L;2=30L};Consumer=$consumerAfter;Dlq=$dlq;Lag=0L}) 5 },
        { Assert-SameKafka ([pscustomobject]@{Source=$sourceBefore;Consumer=$consumerBefore;Dlq=$dlq;Lag=0L}) `
            ([pscustomobject]@{Source=$sourceAfter;Consumer=$consumerAfter;Dlq=@{0=0L;1=3L;2=1L};Lag=0L}) 5 },
        { Assert-SameKafka ([pscustomobject]@{Source=$sourceBefore;Consumer=$consumerBefore;Dlq=$dlq;Lag=0L}) `
            ([pscustomobject]@{Source=$sourceAfter;Consumer=$consumerAfter;Dlq=$dlq;Lag=1L}) 5 },
        { Assert-SameKafka ([pscustomobject]@{Source=$sourceBefore;Consumer=$consumerBefore;Dlq=$dlq;Lag=0L}) `
            ([pscustomobject]@{Source=$sourceAfter;Consumer=@{0=12L;1=20L;2=30L};Dlq=$dlq;Lag=0L}) 5 })) {
        $failed = $false
        try { & $case } catch { $failed = $true }
        if (-not $failed) { throw 'An offline fail-closed guard did not reject invalid input.' }
    }
    $recoveryGateRejected = $false
    try { Invoke-CapacityMode -SelectedMode 'RecoveryAuthorizedRun' -Pkce $true -Session $true -Write $true -Expired $true -Synthetic $true -Recovery $false }
    catch { if ($_.Exception.Message -notmatch 'six explicit gates') { throw }; $recoveryGateRejected = $true }
    if (-not $recoveryGateRejected) { throw 'Recovery mode was not separately gated before side effects.' }
    if ($script:externalCalls -ne 0 -or $script:fileWrites -ne 0 -or $script:markerCreated) {
        throw 'Recovery authorization gate attempted an external or persistent operation.'
    }
    $recoveryGateCases = @(
        [pscustomobject]@{ Name='PatientPkce'; Pkce=$false; Session=$true; Write=$true; Expired=$true; Synthetic=$true; Recovery=$true },
        [pscustomobject]@{ Name='ClinicalSession'; Pkce=$true; Session=$false; Write=$true; Expired=$true; Synthetic=$true; Recovery=$true },
        [pscustomobject]@{ Name='TelemetryWrite'; Pkce=$true; Session=$true; Write=$false; Expired=$true; Synthetic=$true; Recovery=$true },
        [pscustomobject]@{ Name='ExpiredTransition'; Pkce=$true; Session=$true; Write=$true; Expired=$false; Synthetic=$true; Recovery=$true },
        [pscustomobject]@{ Name='SyntheticData'; Pkce=$true; Session=$true; Write=$true; Expired=$true; Synthetic=$false; Recovery=$true })
    foreach ($gateCase in $recoveryGateCases) {
        $rejected = $false
        try {
            Invoke-CapacityMode -SelectedMode 'RecoveryAuthorizedRun' -Pkce $gateCase.Pkce `
                -Session $gateCase.Session -Write $gateCase.Write -Expired $gateCase.Expired `
                -Synthetic $gateCase.Synthetic -Recovery $gateCase.Recovery
        } catch { if ($_.Exception.Message -notmatch 'six explicit gates') { throw }; $rejected = $true }
        if (-not $rejected -or $script:externalCalls -ne 0 -or $script:fileWrites -ne 0 -or $script:markerCreated) {
            throw ('Recovery mode did not fail closed for the {0} authorization gate.' -f $gateCase.Name)
        }
    }

    $recoveryMockNames = @('Assert-ComposeProject','Assert-ServiceCapacityBounds','Assert-VolumeOwnership',
        'Get-ComposeState','Assert-Dependencies','Assert-Ready','Get-DbSnapshot','Get-KafkaSnapshot','Get-EligiblePlan','Read-Host',
        'Get-CapacityRecoveryDirectory','Get-CapacityRecoveryMarkerState','Get-CapacityRecoveryLedgerState',
        'Reserve-CapacityRecoveryIntent','Save-CapacityRecoveryOutcome','Invoke-LocalGet','Invoke-LocalRequest')
    $recoveryOriginals = @{}
    $recoveryLocalOriginals = @{}
    foreach ($name in $recoveryMockNames) {
        $localPath="Function:\$name"
        $recoveryOriginals[$name] = [pscustomobject]@{
            Exists=(Test-Path ("Function:\script:{0}" -f $name))
            Body=$(if (Test-Path ("Function:\script:{0}" -f $name)) { (Get-Item ("Function:\script:{0}" -f $name)).ScriptBlock } else { $null })
        }
        $recoveryLocalOriginals[$name] = [pscustomobject]@{
            Exists=(Test-Path $localPath)
            Body=$(if (Test-Path $localPath) { (Get-Item $localPath).ScriptBlock } else { $null })
        }
    }
    $savedPkceHelper = $script:pkceHelper
    $script:recoveryEvents=[System.Collections.Generic.List[string]]::new()
    $script:recoveryMarkerState=[pscustomobject]@{Valid=$true;Hash='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'}
    $script:recoveryCoordinatorRequests=0; $script:recoveryCoordinatorSessionPosts=0
    $script:recoveryCoordinatorTelemetryPosts=0; $script:recoveryCoordinatorIntent=$null
    $script:recoveryCoordinatorOutcome=$null
    function Set-CapacityOfflineMock([string] $Name, [scriptblock] $Body) {
        New-Item ("Function:\script:{0}" -f $Name) -Force -Value $Body | Out-Null
    }
    try {
        Set-CapacityOfflineMock 'Assert-ComposeProject' { param($InputValue) }
        Set-CapacityOfflineMock 'Assert-ServiceCapacityBounds' { param($InputValue,$Samples,$Interval) }
        Set-CapacityOfflineMock 'Assert-VolumeOwnership' { }
        Set-CapacityOfflineMock 'Get-ComposeState' { return @() }
        Set-CapacityOfflineMock 'Assert-Dependencies' { param($InputValue) }
        Set-CapacityOfflineMock 'Assert-Ready' { param($Name,$Origin) }
        Set-CapacityOfflineMock 'Get-DbSnapshot' {
            param([string[]] $BatchIds=@())
            return [pscustomobject]@{Inbox=0L;Samples=0L;Gaze=0L;Gameplay=0L;DuplicateKeys=0L}
        }
        Set-CapacityOfflineMock 'Get-KafkaSnapshot' {
            return [pscustomobject]@{Source=@{0=0L;1=0L;2=0L};Consumer=@{0=0L;1=0L;2=0L};Dlq=@{0=0L;1=0L;2=0L};Lag=0L}
        }
        Set-CapacityOfflineMock 'Get-EligiblePlan' { param($Clinical,$Token); return [guid]'00000000-0000-4000-8000-000000000011' }
        Set-CapacityOfflineMock 'Read-Host' { param($Prompt); return 'yes' }
        Set-CapacityOfflineMock 'Get-CapacityRecoveryDirectory' { return 'offline-ledger-only' }
        Set-CapacityOfflineMock 'Get-CapacityRecoveryMarkerState' {
            $script:recoveryEvents.Add('marker-check'); return $script:recoveryMarkerState
        }
        Set-CapacityOfflineMock 'Get-CapacityRecoveryLedgerState' {
            $script:recoveryEvents.Add('ledger-check'); return 'absent'
        }
        Set-CapacityOfflineMock 'Reserve-CapacityRecoveryIntent' {
            param($Directory,$Text)
            $script:recoveryEvents.Add('intent-reserved')
            $script:recoveryCoordinatorIntent=ConvertFrom-Json -InputObject $Text
            return $true
        }
        Set-CapacityOfflineMock 'Save-CapacityRecoveryOutcome' {
            param($Directory,$Text)
            $script:recoveryEvents.Add('outcome-saved')
            $script:recoveryCoordinatorOutcome=ConvertFrom-Json -InputObject $Text
            return $true
        }
        Set-CapacityOfflineMock 'Invoke-LocalRequest' {
            param([string] $Method,[string] $Uri,[string] $Token,[string] $Body='',[hashtable] $Headers=@{})
            $script:recoveryCoordinatorRequests++
            if ($Uri -match '/sessions$') {
                $script:recoveryCoordinatorSessionPosts++
                $script:recoveryEvents.Add('clinical-post')
                return @{Status=201;Content='{"therapySessionId":"00000000-0000-4000-8000-000000000012","status":"ACTIVE","exerciseCode":"fixation","therapyPlanId":"00000000-0000-4000-8000-000000000011"}'}
            }
            $script:recoveryCoordinatorTelemetryPosts++
            $script:recoveryEvents.Add('telemetry-post')
            return @{Status=503;Content='private synthetic body'}
        }
        $script:pkceHelper = { function Get-CapacityPatientAccessToken([string] $EnvPath) { return 'synthetic-offline-token' } }
        $coordinatorStopped=$false
        try {
            Invoke-CapacityMode -SelectedMode 'RecoveryAuthorizedRun' -Pkce $true -Session $true `
                -Write $true -Expired $true -Synthetic $true -Recovery $true
        } catch { $coordinatorStopped=$true }
        if (-not $coordinatorStopped -or $script:recoveryCoordinatorSessionPosts -ne 1 -or
                $script:recoveryCoordinatorTelemetryPosts -ne 1 -or $script:recoveryCoordinatorRequests -ne 2 -or
                $null -eq $script:recoveryCoordinatorOutcome -or $script:recoveryCoordinatorOutcome.state -cne 'STOPPED' -or
                $script:recoveryCoordinatorOutcome.stage -cne 'telemetry_ingest' -or
                $script:recoveryCoordinatorOutcome.guard -cne 'telemetry_acceptance_failed' -or
                $script:recoveryCoordinatorOutcome.clinicalPostAttempts -ne 1 -or
                -not $script:recoveryCoordinatorOutcome.clinicalSessionAccepted -or
                $script:recoveryCoordinatorOutcome.telemetryPostAttempts -ne 1 -or
                $script:recoveryCoordinatorOutcome.telemetryAcceptedBatches -ne 0 -or
                ($script:recoveryEvents -join ',') -cne 'marker-check,marker-check,ledger-check,marker-check,intent-reserved,marker-check,clinical-post,marker-check,telemetry-post,outcome-saved' -or
                $script:recoveryCoordinatorIntent.state -cne 'IN_FLIGHT' -or
                [string]::IsNullOrWhiteSpace([string]$script:recoveryCoordinatorIntent.clinicalIdempotencyKey) -or
                (($script:recoveryCoordinatorIntent | ConvertTo-Json -Depth 8 -Compress) -match '(?i)access.?token|bearer') -or
                $script:markerCreated -or
                $script:fileWrites -ne 0 -or $script:externalCalls -ne 0 -or
                $script:recoveryMarkerState.Hash -cne 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb') {
            throw 'The recovery coordinator did not durably capture a partial Telemetry failure without retry or marker mutation.'
        }
    } finally {
        $script:pkceHelper=$savedPkceHelper
        foreach ($name in $recoveryMockNames) {
            if ($recoveryOriginals[$name].Exists) { Set-Item ("Function:\script:{0}" -f $name) -Force -Value $recoveryOriginals[$name].Body }
            else { Remove-Item ("Function:\script:{0}" -f $name) -ErrorAction SilentlyContinue }
            if ($recoveryLocalOriginals[$name].Exists) { Set-Item ("Function:\{0}" -f $name) -Force -Value $recoveryLocalOriginals[$name].Body }
            else { Remove-Item ("Function:\{0}" -f $name) -ErrorAction SilentlyContinue }
        }
    }

    $originalAuthorizedRun=(Get-Item Function:\Invoke-AuthorizedRun).ScriptBlock
    $script:recoveryRoute=$null
    try {
        Set-Item Function:\Invoke-AuthorizedRun -Force -Value {
            param([switch] $RecoveryMode)
            $script:recoveryRoute=[bool]$RecoveryMode
            throw 'offline_recovery_entry_boundary'
        }
        try {
            Invoke-CapacityMode -SelectedMode 'RecoveryAuthorizedRun' -Pkce $true -Session $true `
                -Write $true -Expired $true -Synthetic $true -Recovery $true
        } catch { if ($_.Exception.Message -ne 'offline_recovery_entry_boundary') { throw } }
    } finally { Set-Item Function:\Invoke-AuthorizedRun -Force -Value $originalAuthorizedRun }
    if (-not $script:recoveryRoute -or $script:externalCalls -ne 0 -or $script:fileWrites -ne 0) {
        throw 'Recovery mode did not reach the expected mocked entry boundary safely.'
    }

    $fakeHash='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    $script:recoveryMarkerState=[pscustomobject]@{Valid=$true;Hash=$fakeHash}
    $script:recoveryLedgerState='absent'; $script:reserveCount=0
    $script:recoveryEvents=[System.Collections.Generic.List[string]]::new()
    $markerProbe={ $script:recoveryEvents.Add('marker'); return $script:recoveryMarkerState }
    $ledgerProbe={ $script:recoveryEvents.Add('ledger'); return $script:recoveryLedgerState }
    $fakeIntent=[ordered]@{schemaVersion=1;mode='RecoveryAuthorizedRun';state='IN_FLIGHT';planId='test-plan';clinicalIdempotencyKey='test-key';batchIds=@('b1','b2','b3','b4','b5')}
    $reserve={ param($value); $script:reserveCount++; $script:recoveryEvents.Add('reserve'); $script:capturedRecoveryIntent=$value; return $true }
    $initialized=Initialize-CapacityRecoveryAttempt $fakeIntent $markerProbe $ledgerProbe $reserve
    if (-not $initialized.Reserved -or $initialized.MarkerHash -cne $fakeHash -or
            ($script:recoveryEvents -join ',') -cne 'marker,ledger,marker,reserve' -or
            $script:capturedRecoveryIntent.clinicalIdempotencyKey -cne 'test-key') {
        throw 'Recovery intent was not reserved after two matching marker checks.'
    }
    foreach ($stateName in @('existing','corrupt','inflight')) {
        $script:recoveryLedgerState=$stateName; $script:reserveCount=0
        $blocked=$false
        try { Initialize-CapacityRecoveryAttempt $fakeIntent $markerProbe $ledgerProbe $reserve | Out-Null } catch { $blocked=$true }
        if (-not $blocked -or $script:reserveCount -ne 0) { throw 'Existing recovery state did not block a new attempt.' }
    }
    $script:recoveryLedgerState='absent'; $script:reserveCount=0
    $script:recoveryMarkerState=[pscustomobject]@{Valid=$false;Hash=$null}
    $blockedMarker=$false
    try { Initialize-CapacityRecoveryAttempt $fakeIntent $markerProbe $ledgerProbe $reserve | Out-Null } catch { $blockedMarker=$true }
    if (-not $blockedMarker -or $script:reserveCount -ne 0) { throw 'Missing recovery marker did not block reservation.' }
    $script:probeCount=0
    $changingMarker={ $script:probeCount++; return [pscustomobject]@{Valid=$true;Hash=$(if($script:probeCount -eq 1){$fakeHash}else{'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'})} }
    $changedMarkerBlocked=$false
    try { Initialize-CapacityRecoveryAttempt $fakeIntent $changingMarker $ledgerProbe $reserve | Out-Null } catch { $changedMarkerBlocked=$true }
    if (-not $changedMarkerBlocked -or $script:reserveCount -ne 0) { throw 'Changed marker did not block reservation.' }
    $script:recoveryMarkerState=[pscustomobject]@{Valid=$true;Hash=$fakeHash}
    $reserveFails={ param($value); $script:reserveCount++; return $false }
    $reservationBlocked=$false
    try { Initialize-CapacityRecoveryAttempt $fakeIntent $markerProbe $ledgerProbe $reserveFails | Out-Null } catch { $reservationBlocked=$true }
    $postCalls=0
    if (-not $reservationBlocked -or $postCalls -ne 0) { throw 'Failed intent persistence did not prevent Clinical POST.' }
    $markerSequence=0
    $guardedMarker={ [pscustomobject]@{Valid=$true;Hash=$fakeHash} }
    $guarded=Invoke-CapacityRecoveryGuardedMutation $fakeHash $guardedMarker { 'one-post' }
    $blockedMutation=$false
    try { Invoke-CapacityRecoveryGuardedMutation $fakeHash { [pscustomobject]@{Valid=$false;Hash=$null} } { $postCalls++; 'bad' } | Out-Null } catch { $blockedMutation=$true }
    if ($guarded -cne 'one-post' -or -not $blockedMutation -or $postCalls -ne 0) { throw 'Recovery marker mutation guard failed.' }
    $exclusive=$false
    $createExclusive={ if($script:exclusive){return $false}; $script:exclusive=$true; return $true }
    $script:exclusive=$false
    if (-not (Claim-CapacityRecoveryIntent $createExclusive) -or (Claim-CapacityRecoveryIntent $createExclusive)) {
        throw 'Atomic recovery intent claim did not admit only one winner.'
    }
    $recoveryIntent=New-CapacityRecoveryIntent $fakeHash ([guid]'00000000-0000-4000-8000-000000000001') `
        '00000000-0000-4000-8000-000000000002' ([guid]'00000000-0000-4000-8000-000000000003') `
        @('00000000-0000-4000-8000-000000000004','00000000-0000-4000-8000-000000000005',
          '00000000-0000-4000-8000-000000000006','00000000-0000-4000-8000-000000000007',
          '00000000-0000-4000-8000-000000000008') `
        ([pscustomobject]@{Inbox=0;Samples=0;Gaze=0;Gameplay=0;DuplicateKeys=0}) `
        ([pscustomobject]@{Inbox=0;Samples=0;Gaze=0;Gameplay=0;DuplicateKeys=0})
    if ($recoveryIntent.clinicalIdempotencyKey -ne '00000000-0000-4000-8000-000000000002' -or
            $recoveryIntent.batchIds.Count -ne 5 -or $recoveryIntent.state -cne 'IN_FLIGHT' -or
            $recoveryIntent.Contains('accessToken')) { throw 'Recovery intent omitted its bounded identity binding or contained a credential.' }
    $outcomeFailureRejected=$false
    try { Save-CapacityRecoveryOutcomeOrThrow { return $false } ([ordered]@{state='COMPLETED'}) | Out-Null }
    catch { $outcomeFailureRejected=$_.Exception.Message -eq 'Recovery outcome could not be durably stored.' }
    if (-not $outcomeFailureRejected) { throw 'Outcome persistence failure was allowed to report success.' }
    $savedLocalAppData=$env:LOCALAPPDATA
    $script:recoveryLedgerFakeMode='safe'
    try {
        $env:LOCALAPPDATA='C:\offline-synthetic-local'
        $script:recoveryLedgerFakeDirectory=Get-CapacityRecoveryDirectory
        $readLedgerWithFakeFileSystem={
            function Test-Path {
                param([string] $LiteralPath)
                return ($LiteralPath -notmatch 'attempt\.(intent|outcome)\.dpapi$')
            }
            function Get-Item {
                param([string] $LiteralPath,[string] $ErrorAction)
                if ($script:recoveryLedgerFakeMode -eq 'file') {
                    return [pscustomobject]@{ PSIsContainer=$false; Attributes=[IO.FileAttributes]::Normal; Length=0 }
                }
                $attributes=[IO.FileAttributes]::Directory
                if ($script:recoveryLedgerFakeMode -eq 'reparse') {
                    $attributes=$attributes -bor [IO.FileAttributes]::ReparsePoint
                }
                return [pscustomobject]@{ PSIsContainer=$true; Attributes=$attributes; Length=0 }
            }
            Get-CapacityRecoveryLedgerState -Directory $script:recoveryLedgerFakeDirectory
        }
        if ((& $readLedgerWithFakeFileSystem) -cne 'absent') {
            throw 'Safe existing ledger ancestors with absent leaf files were not classified absent.'
        }
        foreach ($unsafeKind in @('file','reparse')) {
            $script:recoveryLedgerFakeMode=$unsafeKind
            if ((& $readLedgerWithFakeFileSystem) -cne 'corrupt') {
                throw 'A non-directory or reparse ledger ancestor was not classified corrupt.'
            }
        }
        $script:recoveryLedgerGuardCases=3
    } finally {
        $env:LOCALAPPDATA=$savedLocalAppData
        Remove-Variable recoveryLedgerFakeMode,recoveryLedgerFakeDirectory,recoveryLedgerGuardCases -Scope Script -ErrorAction SilentlyContinue
    }
    $diagnostic = Format-Diagnostic 'pkce_login' 'SECRET-CANARY'
    if ($diagnostic -notmatch '^CAPACITY_RUN_STOPPED: stage=pkce_login; guard=operation_failed;' -or
            $diagnostic -match 'SECRET-CANARY') { throw 'Safe diagnostic redaction failed.' }
    $summary = ConvertTo-SafeSummary $after
    if ($summary.PSObject.Properties.Name -contains 'SessionId' -or
            $summary.PSObject.Properties.Name -contains 'BatchId') { throw 'Safe summary includes identity fields.' }
    Assert-ComposeArgumentsAllowed @('ps', '--all', '--format', 'json')
    Assert-ComposeArgumentsAllowed @('exec', '-T', 'telemetry-timescaledb', 'psql')
    Assert-ComposeArgumentsAllowed @('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-get-offsets.sh')
    $groupState = ConvertFrom-ConsumerGroupState @(
        'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID',
        'telemetry-timescale-v1 telemetry-batch-accepted.v1 0 13 13 0 - - -',
        'telemetry-timescale-v1 telemetry-batch-accepted.v1 1 20 20 0 - - -',
        'telemetry-timescale-v1 telemetry-batch-accepted.v1 2 30 30 0 - - -')
    if ($groupState.Lag -ne 0 -or $groupState.Offsets[0] -ne 13) { throw 'Kafka group state parsing failed.' }
    $parsedOffsets = ConvertFrom-KafkaOffsetOutput $script:sourceTopic @(
        'telemetry-batch-accepted.v1:0:13', 'telemetry-batch-accepted.v1:1:20', 'telemetry-batch-accepted.v1:2:30')
    $parsedAggregate = ConvertFrom-AggregateOutput @('5|50|25|25|0')
    if ($parsedOffsets[0] -ne 13 -or $parsedAggregate.Inbox -ne 5 -or $parsedAggregate.DuplicateKeys -ne 0) {
        throw 'Aggregate or Kafka output parsing failed.'
    }
    $auth = New-CapacityAuthorizationParameters 'client-canary' 'http://localhost:5173/auth/callback' 'state-canary' 'challenge-canary' 'nonce-canary'
    if ($auth.nonce -cne 'nonce-canary') { throw 'OIDC authorization nonce was not included.' }
    $idTokenClaims = [pscustomobject]@{ iss='issuer-canary'; aud='client-canary'; exp=([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+60); nonce='nonce-canary' }
    Assert-CapacityIdTokenNonce $idTokenClaims 'issuer-canary' 'client-canary' 'nonce-canary'
    foreach ($claims in @(
        [pscustomobject]@{ iss='issuer-canary'; aud='client-canary'; exp=([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+60); nonce='wrong' },
        [pscustomobject]@{ iss='issuer-canary'; aud='other-client'; exp=([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+60); nonce='nonce-canary' },
        [pscustomobject]@{ iss='issuer-canary'; aud='client-canary'; exp=1; nonce='nonce-canary' })) {
        $rejected = $false
        try { Assert-CapacityIdTokenNonce $claims 'issuer-canary' 'client-canary' 'nonce-canary' } catch { $rejected = $true }
        if (-not $rejected) { throw 'Invalid ID-token nonce or claims were accepted.' }
    }
    $halfSecondTicks = [long] ([Diagnostics.Stopwatch]::Frequency / 2)
    if ((Get-CapacityElapsedMilliseconds 1000L (1000L + $halfSecondTicks)) -ne 500) { throw 'Monotonic duration calculation failed.' }
    $preflightSummary = Format-PreflightKafkaSummary ([pscustomobject]@{ Lag=0L; Dlq=@{0=1L;1=2L} })
    if ($preflightSummary -match 'stable' -or $preflightSummary -notmatch 'single_read_only_snapshot') {
        throw 'Preflight overclaimed Kafka or DLQ stability.'
    }
    $script:fakeMarker = $false
    Claim-OneShotMarker { if ($script:fakeMarker) { return $false }; $script:fakeMarker = $true; return $true }
    $markerReplayRejected = $false
    try { Claim-OneShotMarker { if ($script:fakeMarker) { return $false }; $script:fakeMarker = $true; return $true } }
    catch { $markerReplayRejected = $true }
    if (-not $script:fakeMarker -or -not $markerReplayRejected) { throw 'One-shot marker replay guard failed.' }
    if ($script:externalCalls -ne 0 -or $script:fileWrites -ne 0 -or $script:markerCreated) {
        throw 'Offline tests attempted external work or marker creation.'
    }
    Write-Output 'CAPACITY_OFFLINE_TESTS_PASSED: external-calls=0; files=0; marker=0; guard-cases=14; nonce=verified; timing=verified; preflight=snapshot-only; recovery=verified'
}

function Invoke-Preflight {
    if (-not (Test-Path -LiteralPath $script:composeFile -PathType Leaf)) {
        throw 'Compose configuration does not match the local Telemetry target.'
    }
    Assert-LoopbackOrigin $ClinicalBaseUrl | Out-Null
    Assert-LoopbackOrigin $TelemetryBaseUrl | Out-Null
    Assert-ComposeProject $script:composeFile
    Assert-ServiceCapacityBounds $script:composeFile $SamplesPerBatch $BatchIntervalMs
    Assert-VolumeOwnership
    Assert-Dependencies (Get-ComposeState)
    Assert-Ready 'Clinical' $ClinicalBaseUrl
    Assert-Ready 'Telemetry' $TelemetryBaseUrl
    $db = Get-DbSnapshot
    if ($db.Gaze + $db.Gameplay -ne $db.Samples -or $db.DuplicateKeys -ne 0) {
        throw 'Aggregate query returned an invalid result.'
    }
    $kafka = Get-KafkaSnapshot
    Assert-KafkaStable $kafka
    $kafkaSummary = Format-PreflightKafkaSummary $kafka
    Write-Output ('CAPACITY_PREFLIGHT_PASSED: project=verified; clinical=200; telemetry=200; inbox={0}; samples={1}; gaze={2}; gameplay={3}; duplicates={4}; {5}' -f `
        $db.Inbox, $db.Samples, $db.Gaze, $db.Gameplay, $db.DuplicateKeys, $kafkaSummary)
}

function Get-EligiblePlan([string] $Clinical, [string] $Token) {
    $response = Invoke-LocalGet "$Clinical/api/v1/me/therapy-plans" $Token
    if ($null -eq $response) { throw 'Clinical plan lookup response status is invalid.' }
    $status = 0
    if ($null -eq $response.Status -or -not [int]::TryParse([string] $response.Status, [ref] $status)) {
        throw 'Clinical plan lookup response status is invalid.'
    }
    if ($status -eq 0) { throw 'Clinical plan lookup transport is unavailable.' }
    if ($status -lt 100 -or $status -gt 599) { throw 'Clinical plan lookup response status is invalid.' }
    if ($status -ne 200) { throw "Clinical plan lookup HTTP rejected: $status." }
    $content = [string] $response.Content
    if (-not $content.TrimStart().StartsWith('[')) { throw 'Clinical plan lookup response shape is invalid.' }
    try { $decoded = ConvertFrom-Json -InputObject $content } catch { throw 'Clinical plan lookup response JSON is invalid.' }
    if ($null -eq $decoded) {
        if ($content.Trim() -ceq '[]') { $plans = @() }
        else { throw 'Clinical plan lookup response shape is invalid.' }
    } elseif ($decoded -is [array]) { $plans = $decoded }
    else { throw 'Clinical plan lookup response shape is invalid.' }
    foreach ($plan in $plans) {
        if ($null -eq $plan -or $plan -is [string] -or $plan -is [valuetype]) {
            throw 'Clinical plan lookup response shape is invalid.'
        }
        $properties = @($plan.PSObject.Properties.Name)
        if ($properties -notcontains 'therapyPlanId' -or $properties -notcontains 'status' -or
                $properties -notcontains 'dailyExercisePrescriptions' -or
                [string]::IsNullOrWhiteSpace([string] $plan.status) -or
                $null -eq $plan.dailyExercisePrescriptions -or
                $null -eq $plan.dailyExercisePrescriptions.PSObject) {
            throw 'Clinical plan lookup response shape is invalid.'
        }
        $planId = [guid]::Empty
        if (-not [guid]::TryParse([string] $plan.therapyPlanId, [ref] $planId) -or $planId -eq [guid]::Empty) {
            throw 'Clinical plan lookup response shape is invalid.'
        }
    }
    $eligible = @($plans | Where-Object {
        $_.status -eq 'ACTIVE' -and $_.dailyExercisePrescriptions.FIXATION -ge 1
    })
    if ($eligible.Count -ne 1) { throw 'Patient does not have exactly one eligible active synthetic plan.' }
    return [guid] ([string] $eligible[0].therapyPlanId)
}

function Start-SyntheticSession([string] $Clinical, [string] $Token, [guid] $PlanId, [string] $IdempotencyKey = '') {
    $keyGuid=[guid]::Empty
    if ([string]::IsNullOrWhiteSpace($IdempotencyKey)) { $IdempotencyKey=[guid]::NewGuid().ToString() }
    if (-not [guid]::TryParse($IdempotencyKey,[ref]$keyGuid) -or $keyGuid -eq [guid]::Empty) {
        throw 'Clinical session start request is invalid.'
    }
    $key = $keyGuid.ToString()
    $response = Invoke-LocalRequest 'POST' "$Clinical/api/v1/me/therapy-plans/$PlanId/sessions" `
        $Token '{"exerciseCode":"FIXATION"}' @{ 'Idempotency-Key' = $key }
    if ($null -eq $response) { throw 'Clinical session start response status is invalid.' }
    $status = 0
    if ($null -eq $response.Status -or -not [int]::TryParse([string] $response.Status, [ref] $status)) {
        throw 'Clinical session start response status is invalid.'
    }
    if ($status -eq 0) { throw 'Clinical session start transport is unavailable.' }
    if ($status -lt 100 -or $status -gt 599) { throw 'Clinical session start response status is invalid.' }
    if ($status -ne 201) { throw "Clinical session start HTTP rejected: $status." }
    try { $session = ConvertFrom-Json -InputObject ([string] $response.Content) }
    catch { throw 'Clinical session start response JSON is invalid.' }
    if ($null -eq $session -or $session -is [array] -or $session -is [string] -or $session -is [valuetype]) {
        throw 'Clinical session start response shape is invalid.'
    }
    $properties = @($session.PSObject.Properties.Name)
    if ($properties -notcontains 'therapySessionId' -or $properties -notcontains 'status' -or
            $properties -notcontains 'exerciseCode' -or $properties -notcontains 'therapyPlanId') {
        throw 'Clinical session start response shape is invalid.'
    }
    $id = [guid]::Empty
    if (-not [guid]::TryParse([string] $session.therapySessionId, [ref] $id) -or
            $id -eq [guid]::Empty -or $session.status -cne 'ACTIVE' -or
            $session.exerciseCode -cne 'fixation' -or $session.therapyPlanId -cne $PlanId.ToString()) {
        throw 'Clinical session start response shape is invalid.'
    }
    return $id
}

function Invoke-AuthorizedRun([switch] $RecoveryMode) {
    if ($RecoveryMode) { Assert-RecoveryWorkload $BatchCount $SamplesPerBatch $BatchIntervalMs }
    else { Assert-Workload $BatchCount $SamplesPerBatch $BatchIntervalMs }
    $clinical = Assert-LoopbackOrigin $ClinicalBaseUrl
    $telemetry = Assert-LoopbackOrigin $TelemetryBaseUrl
    if (-not (Test-Path -LiteralPath $script:composeFile -PathType Leaf)) {
        throw 'Compose configuration does not match the local Telemetry target.'
    }
    Assert-ComposeProject $script:composeFile
    Assert-ServiceCapacityBounds $script:composeFile $SamplesPerBatch $BatchIntervalMs
    Assert-VolumeOwnership
    Assert-Dependencies (Get-ComposeState)
    Assert-Ready 'Clinical' $clinical
    Assert-Ready 'Telemetry' $telemetry
    $dbBase = Get-DbSnapshot
    if ($dbBase.Gaze + $dbBase.Gameplay -ne $dbBase.Samples -or $dbBase.DuplicateKeys -ne 0) {
        throw 'Aggregate query returned an invalid result.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $script:repositoryRoot 'telemetry-service/src/test/resources/fixtures/telemetry/v1/dual-typed-batch.json') -PathType Leaf)) {
        throw 'The synthetic V1 fixture is unavailable.'
    }
    $fixture = Get-Content -LiteralPath (Join-Path $script:repositoryRoot 'telemetry-service/src/test/resources/fixtures/telemetry/v1/dual-typed-batch.json') -Raw | ConvertFrom-Json
    $batchIds = @(); for ($i=0; $i -lt $BatchCount; $i++) { $batchIds += [guid]::NewGuid().ToString() }
    $runBaseline = Get-DbSnapshot $batchIds
    if ($runBaseline.Inbox -ne 0 -or $runBaseline.Samples -ne 0) {
        throw 'Aggregate query returned an invalid result.'
    }

    $recoveryMarkerHash=$null
    if ($RecoveryMode) {
        $script:stage='recovery_ledger'
        $initialMarker=Get-CapacityRecoveryMarkerState $script:markerPath
        if (-not $initialMarker.Valid) { throw 'The recovery marker is missing, invalid, or changed.' }
        $recoveryMarkerHash=$initialMarker.Hash
    } else { Assert-MarkerAbsent (Test-Path -LiteralPath $script:markerPath -PathType Leaf) }
    $script:stage = 'pkce_login'
    . $script:pkceHelper
    $token = Get-CapacityPatientAccessToken $script:envPath
    $script:stage = 'plan_lookup'
    $plan = Get-EligiblePlan $clinical $token
    Write-Host 'ELIGIBLE_SYNTHETIC_PLAN: count=1; exercise=FIXATION'
    $confirmation = Read-Host 'Confirm this existing synthetic active plan for one new test session (yes/no)'
    if ($confirmation -cne 'yes') { throw 'Plan selection was not confirmed.' }

    $recoveryKey=$null; $streamId=[guid]::Empty
    if ($RecoveryMode) {
        $script:stage='recovery_ledger'
        $recoveryKey=[guid]::NewGuid().ToString()
        $streamId=[guid]::NewGuid()
        $recordDirectory=Get-CapacityRecoveryDirectory
        $intent=New-CapacityRecoveryIntent $recoveryMarkerHash $plan $recoveryKey $streamId $batchIds $dbBase $runBaseline
        $intentText=ConvertTo-Json -InputObject $intent -Depth 8 -Compress
        $reserve={ param($value) Reserve-CapacityRecoveryIntent $recordDirectory $intentText }
        $recovery=Initialize-CapacityRecoveryAttempt $intent `
            { Get-CapacityRecoveryMarkerState $script:markerPath } `
            { Get-CapacityRecoveryLedgerState $recordDirectory } $reserve
        $script:recoveryState=[pscustomobject]@{
            Reserved=[bool]$recovery.Reserved; Directory=$recordDirectory; MarkerHash=$recovery.MarkerHash
            PlanId=$plan.ToString(); IdempotencyKey=$recoveryKey; StreamId=$streamId.ToString()
            BatchIds=@($batchIds); ClinicalPostAttempted=$false; ClinicalSessionAccepted=$false
            SessionId=$null; TelemetryPostAttempts=0; TelemetryAcceptedBatches=0
        }
        $script:recoveryOutcomeAttempted=$false
    } else {
        $script:stage = 'marker'
        New-OneShotMarker
    }
    $script:stage = 'session_start'
    if ($RecoveryMode) {
        $startSession={
            $script:recoveryState.ClinicalPostAttempted=$true
            Start-SyntheticSession $clinical $token $plan $recoveryKey
        }
        $sessionId=Invoke-CapacityRecoveryGuardedMutation $recoveryMarkerHash `
            { Get-CapacityRecoveryMarkerState $script:markerPath } $startSession
        $script:recoveryState.ClinicalSessionAccepted=$true
        $script:recoveryState.SessionId=$sessionId.ToString()
    } else {
        $sessionId = Start-SyntheticSession $clinical $token $plan
        $streamId = [guid]::NewGuid()
    }
    $kafkaBefore = Get-KafkaSnapshot
    Assert-KafkaStable $kafkaBefore
    $start = [Diagnostics.Stopwatch]::StartNew()
    $ackMs = @()
    $lastAckTimestamp = [long] 0
    for ($sequence = 0; $sequence -lt $BatchCount; $sequence++) {
        $due = [long] $sequence * $BatchIntervalMs
        $remaining = $due - $start.ElapsedMilliseconds
        if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int] $remaining) }
        if ($start.ElapsedMilliseconds -gt 120000) { throw 'The bounded operation timed out.' }
        $capturedAt = [DateTimeOffset]::UtcNow
        $batch = New-SyntheticBatch $fixture $sessionId $streamId $sequence $SamplesPerBatch $capturedAt
        $batch.batchId = $batchIds[$sequence]
        $batch.sentAt = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        $body = ConvertTo-Json -InputObject $batch -Depth 16 -Compress
        $script:stage = 'telemetry_ingest'
        $sentAt = [Diagnostics.Stopwatch]::GetTimestamp()
        if ($RecoveryMode) {
            $sendBatch={
                $script:recoveryState.TelemetryPostAttempts++
                Invoke-LocalRequest 'POST' "$telemetry/api/v1/telemetry/batches" $token $body
            }
            $response=Invoke-CapacityRecoveryGuardedMutation $recoveryMarkerHash `
                { Get-CapacityRecoveryMarkerState $script:markerPath } $sendBatch
        } else { $response = Invoke-LocalRequest 'POST' "$telemetry/api/v1/telemetry/batches" $token $body }
        if ($response.Status -ne 202) { throw 'Telemetry did not accept every configured batch.' }
        if ($RecoveryMode) { $script:recoveryState.TelemetryAcceptedBatches++ }
        $lastAckTimestamp = [Diagnostics.Stopwatch]::GetTimestamp()
        $ackMs += Get-CapacityElapsedMilliseconds $sentAt $lastAckTimestamp
        if ($start.ElapsedMilliseconds -gt 120000) { throw 'The bounded operation timed out.' }
    }
    $start.Stop()
    $script:stage = 'persistence_wait'
    $gazeExpected = [int] [Math]::Ceiling($SamplesPerBatch / 2.0) * $BatchCount
    $gameExpected = ($SamplesPerBatch - [int] [Math]::Ceiling($SamplesPerBatch / 2.0)) * $BatchCount
    $persisted = Wait-Persisted $batchIds $BatchCount ($BatchCount * $SamplesPerBatch) $gazeExpected $gameExpected
    $persistConfirmMs = Get-CapacityElapsedMilliseconds $lastAckTimestamp ([Diagnostics.Stopwatch]::GetTimestamp())
    $script:stage = 'kafka_verify'
    $kafkaAfter = Wait-KafkaCompletion $kafkaBefore $BatchCount
    $consumerConfirmMs = Get-CapacityElapsedMilliseconds $lastAckTimestamp ([Diagnostics.Stopwatch]::GetTimestamp())
    $avgAck = [Math]::Round((($ackMs | Measure-Object -Average).Average), 1)
    $maxAck = [Math]::Round((($ackMs | Measure-Object -Maximum).Maximum), 1)
    $achieved = if ($start.Elapsed.TotalSeconds -gt 0) { [Math]::Round($BatchCount / $start.Elapsed.TotalSeconds, 3) } else { 0 }
    $sampleRate = if ($start.Elapsed.TotalSeconds -gt 0) {
        [Math]::Round(($BatchCount * $SamplesPerBatch) / $start.Elapsed.TotalSeconds, 3)
    } else { 0 }
    if ($RecoveryMode) {
        $script:stage='recovery_outcome'
        $outcome=New-CapacityRecoveryOutcome $script:recoveryState 'COMPLETED' 'complete' 'run_completed'
        $script:recoveryOutcomeAttempted=$true
        $save={ param($record) Save-CapacityRecoveryOutcome $script:recoveryState.Directory (ConvertTo-Json -InputObject $record -Depth 8 -Compress) }
        Save-CapacityRecoveryOutcomeOrThrow $save $outcome | Out-Null
    }
    $token = $null; $body = $null; $fixture = $null; $sessionId = [guid]::Empty; $streamId = [guid]::Empty
    Write-Output ('CAPACITY_RUN_PASSED: batches={0}; samples={1}; gaze={2}; gameplay={3}; scheduled_interval_ms={4}; achieved_batches_per_second={5}; achieved_payload_samples_per_second={6}; mean_http_ack_ms={7}; max_http_ack_ms={8}; persist_confirm_after_last_ack_ms={9}; consumer_confirm_after_last_ack_ms={10}; source_records={0}; consumer_delta={0}; dlq_delta=0; final_lag={11}; duplicate_sample_keys={12}' -f `
        $BatchCount, ($BatchCount * $SamplesPerBatch), $persisted.Gaze, $persisted.Gameplay,
        $BatchIntervalMs, $achieved, $sampleRate, $avgAck, $maxAck, $persistConfirmMs,
        $consumerConfirmMs, $kafkaAfter.Lag, $persisted.DuplicateKeys)
}

function Invoke-CapacityMode([string] $SelectedMode, [bool] $Pkce, [bool] $Session,
        [bool] $Write, [bool] $Expired, [bool] $Synthetic, [bool] $Recovery) {
    Assert-ModeSelection $SelectedMode $Pkce $Session $Write $Expired $Synthetic $Recovery
    if ($SelectedMode -eq 'OfflineTest') { Invoke-OfflineTests; exit 0 }
    if ($SelectedMode -eq 'Preflight') { Invoke-Preflight; exit 0 }
    if ($SelectedMode -eq 'RecoveryAuthorizedRun') { Invoke-CapacityRun -RecoveryMode; return }
    Invoke-CapacityRun
}

function Invoke-CapacityRun([switch] $RecoveryMode) {
    try {
        if ($RecoveryMode) { Invoke-AuthorizedRun -RecoveryMode }
        else { Invoke-AuthorizedRun }
    } catch {
        $script:recoveryOutcomeSaved=$null
        if ($null -ne $script:recoveryState -and $script:recoveryState.Reserved -and -not $script:recoveryOutcomeAttempted) {
            $diagnostic=Get-Diagnostic $script:stage $_.Exception.Message
            $outcome=New-CapacityRecoveryOutcome $script:recoveryState 'STOPPED' $diagnostic.Stage $diagnostic.Guard $diagnostic.HttpStatus
            $script:recoveryOutcomeAttempted=$true
            try {
                $script:recoveryOutcomeSaved=Save-CapacityRecoveryOutcome $script:recoveryState.Directory `
                    (ConvertTo-Json -InputObject $outcome -Depth 8 -Compress)
            } catch { $script:recoveryOutcomeSaved=$false }
        }
        throw
    }
}

try {
    Invoke-CapacityMode -SelectedMode $Mode -Pkce ([bool]$AuthorizePatientPkce) `
        -Session ([bool]$AuthorizeClinicalSession) -Write ([bool]$AuthorizeTelemetryWrite) `
        -Expired ([bool]$AuthorizeExpiredSessionTransition) -Synthetic ([bool]$ConfirmSyntheticData) `
        -Recovery ([bool]$AuthorizeRecovery)
} catch {
    $line = Format-Diagnostic $script:stage $_.Exception.Message
    if ($null -ne $script:recoveryOutcomeSaved -and -not $script:recoveryOutcomeSaved) { $line += ' recovery_outcome_saved=false;' }
    Write-Error -Message $line -ErrorAction Continue
    exit 1
}
