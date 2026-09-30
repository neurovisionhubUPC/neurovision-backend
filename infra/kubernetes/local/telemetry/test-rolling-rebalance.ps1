[CmdletBinding()]
param(
    [ValidateSet('OfflineTest', 'Preflight', 'AuthorizedRollout', 'AuthorizedCanary')]
    [string] $Mode = 'OfflineTest',
    [switch] $AuthorizeRollingRestart,
    [switch] $AuthorizeSyntheticCanary,
    [switch] $ConfirmSyntheticData,
    [string] $ExpectedImage,
    [long] $ExpectedGeneration = -1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:LiveCalls = 0
$script:PostPodPollRequestTimeout = '5s'
$script:PostPodPollAttempts = 12
$script:PostPodPollIntervalSeconds = 5
# Approximate upper bound: 12 bounded 5-second reads plus 11 five-second waits; scheduling/API overhead may add a small amount.
$script:PostPodPollApproxMaxSeconds = 115
$script:SafeFailureCodes = @{
    'context-read' = 'command_failed'; 'context-validation' = 'context_mismatch'
    'workload-read' = 'command_failed'; 'workload-json' = 'parse_failed'; 'namespace-workload' = 'target_mismatch'
    'deployment-model' = 'model_mismatch'; 'image-baseline' = 'image_mismatch'; 'generation-baseline' = 'baseline_mismatch'
    'pod-read' = 'command_failed'; 'pod-json' = 'parse_failed'; 'pod-readiness' = 'readiness_mismatch'
    'kafka-read' = 'command_failed'; 'kafka-json' = 'parse_failed'; 'kafka-image' = 'image_mismatch'; 'kafka-readiness' = 'readiness_mismatch'
    'timescale-read' = 'command_failed'; 'timescale-json' = 'parse_failed'; 'timescale-image' = 'image_mismatch'; 'timescale-readiness' = 'readiness_mismatch'
    'consumer-group-read' = 'command_failed'; 'consumer-group-parse' = 'parse_or_scope_mismatch'
    'consumer-group-stability' = 'stability_mismatch'
    'post-deployment-read' = 'command_failed'; 'post-deployment-json' = 'parse_failed'; 'post-deployment-state' = 'state_mismatch'
    'post-pod-read' = 'command_failed'; 'post-pod-json' = 'parse_failed'; 'post-pod-timeout-empty' = 'pods_absent'
    'post-pod-timeout-multiple' = 'pods_not_converged'; 'post-pod-timeout-state' = 'pod_not_running'
    'post-pod-timeout-unready' = 'pod_not_ready'; 'post-pod-timeout-uid' = 'replacement_uid_unchanged'
    'post-consumer-group-read' = 'command_failed'; 'post-consumer-group-parse' = 'parse_or_scope_mismatch'
    'post-consumer-group-stability' = 'stability_or_rebalance_mismatch'
    'rollout-restart' = 'command_failed'; 'rollout-status' = 'status_failed'
    'rollout-authorization' = 'consent_required'; 'canary-authorization' = 'consent_required'; 'canary-unavailable' = 'safe_path_unavailable'
}

function Assert-Check([bool] $Condition, [string] $Name) {
    if (-not $Condition) { throw "Offline assertion failed: $Name" }
}

function Get-ConsumerGroupCommand {
    return @('kubectl', '-n', 'neurovision', 'exec', 'kafka-0', '-c', 'kafka', '--',
        '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'kafka:9092',
        '--describe', '--group', 'telemetry-timescale-v1')
}

function Get-RolloutRestartCommand {
    return @('kubectl', '-n', 'neurovision', 'rollout', 'restart', 'deployment/telemetry-service')
}

function Get-PostPodReadCommand {
    return @('-n', 'neurovision', 'get', 'pods', '-l', 'app.kubernetes.io/name=telemetry-service',
        "--request-timeout=$($script:PostPodPollRequestTimeout)", '-o', 'json')
}

function Assert-RolloutConsent {
    if (-not $AuthorizeRollingRestart -or -not $ConfirmSyntheticData) {
        Stop-Safely 'rollout-authorization'
    }
    if ([string]::IsNullOrWhiteSpace($ExpectedImage) -or $ExpectedGeneration -lt 1) {
        Stop-Safely 'generation-baseline'
    }
}

function Assert-CanaryConsent {
    Assert-RolloutConsent
    if (-not $AuthorizeSyntheticCanary) {
        Stop-Safely 'canary-authorization'
    }
}

function Test-OneShotState([string] $Path) {
    if (Test-Path -LiteralPath $Path) { throw 'One-shot attempt already exists; ambiguous retries are refused.' }
}

function Write-OneShotState([string] $Path) {
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    New-Item -ItemType File -Path $Path -ErrorAction Stop | Out-Null
    Set-Content -LiteralPath $Path -Value 'attempted' -NoNewline
}

function Get-RolloutStatePath {
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'Local one-shot state location is unavailable.' }
    return Join-Path $env:LOCALAPPDATA 'NeuroVision\TelemetryRollingRebalance\attempted.flag'
}

function Compare-Aggregates($Before, $After, [long] $ExpectedSamples) {
    foreach ($snapshot in @($Before, $After)) {
        foreach ($field in @('inbox', 'samples', 'duplicates', 'dlq')) {
            if ($null -eq $snapshot.$field -or [long]$snapshot.$field -lt 0) {
                throw 'Aggregate snapshot is incomplete.'
            }
        }
    }
    $sampleDelta = [long]$After.samples - [long]$Before.samples
    $inboxDelta = [long]$After.inbox - [long]$Before.inbox
    $duplicateDelta = [long]$After.duplicates - [long]$Before.duplicates
    $dlqDelta = [long]$After.dlq - [long]$Before.dlq
    return [pscustomobject]@{
        Passed = ($inboxDelta -eq 1 -and $sampleDelta -eq $ExpectedSamples -and
            $duplicateDelta -eq 0 -and $dlqDelta -eq 0)
        InboxDelta = $inboxDelta; SampleDelta = $sampleDelta
        DuplicateDelta = $duplicateDelta; DlqDelta = $dlqDelta
    }
}

function Format-SafeDiagnostic([string] $Text) {
    $safe = $Text -replace '(?i)bearer\s+\S+', 'Bearer [redacted]'
    $safe = $safe -replace '(?i)(payload|token|patient|session|batch|stream|coordinates?)(?:[_-][a-z]+)*\s*[:=]\s*[^\s,;]+', '$1=[redacted]'
    $safe = $safe -replace '\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b', '[id-redacted]'
    $safe = $safe -replace '\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z\b', '[time-redacted]'
    return $safe
}

function Get-SafeDiagnosticMessage([string] $Stage) {
    if (-not $script:SafeFailureCodes.ContainsKey($Stage)) {
        return 'Telemetry preflight failed safely [stage=unknown; code=operation_failed].'
    }
    return "Telemetry preflight failed safely [stage=$Stage; code=$($script:SafeFailureCodes[$Stage])]."
}

function Stop-Safely([string] $Stage) {
    throw (Get-SafeDiagnosticMessage $Stage)
}

function Resolve-SafeErrorMessage([string] $Candidate) {
    foreach ($stage in $script:SafeFailureCodes.Keys) {
        $expected = Get-SafeDiagnosticMessage $stage
        if ($Candidate -ceq $expected) { return $expected }
    }
    return Get-SafeDiagnosticMessage 'unknown'
}

function Invoke-SafeStep([string] $Stage, [scriptblock] $Action) {
    try { return (& $Action) }
    catch { Stop-Safely $Stage }
}

function Invoke-Live([string] $Stage, [string] $File, [string[]] $Arguments) {
    $script:LiveCalls++
    try {
        $output = & $File @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { Stop-Safely $Stage }
        return $output
    } catch { Stop-Safely $Stage }
}

function Wait-ForReplacementPod([scriptblock] $ReadPods, [scriptblock] $Wait, [int] $MaxAttempts = 12) {
    $lastFailureStage = 'post-pod-timeout-empty'
    for ($attempt = 0; $attempt -lt $MaxAttempts; $attempt++) {
        $snapshot = & $ReadPods
        $pods = @($snapshot.items)
        if ($pods.Count -eq 0) {
            $lastFailureStage = 'post-pod-timeout-empty'
        } elseif ($pods.Count -gt 1) {
            $lastFailureStage = 'post-pod-timeout-multiple'
        } else {
            $pod = $pods[0]
            if ([string]::IsNullOrWhiteSpace([string]$pod.metadata.uid)) {
                $lastFailureStage = 'post-pod-timeout-uid'
            } elseif ($pod.status.phase -cne 'Running') {
                $lastFailureStage = 'post-pod-timeout-state'
            } elseif (@($pod.status.containerStatuses).Count -lt 1 -or
                $pod.status.containerStatuses[0].ready -ne $true) {
                $lastFailureStage = 'post-pod-timeout-unready'
            } elseif ([string]$pod.metadata.uid -ceq [string]$script:BaselinePodUid) {
                $lastFailureStage = 'post-pod-timeout-uid'
            } else {
                return $pod
            }
        }
        if ($attempt -lt ($MaxAttempts - 1)) { & $Wait }
    }
    Stop-Safely $lastFailureStage
}

function Test-OfflineContract {
    foreach ($stage in $script:SafeFailureCodes.Keys) {
        $message = Get-SafeDiagnosticMessage $stage
        Assert-Check ($message -match [regex]::Escape("[stage=$stage; code=$($script:SafeFailureCodes[$stage])]") -and
            $message -notmatch 'Bearer|session|token|payload|host') "fixed diagnostic mapping: $stage"
    }
    $safeFailure = $null
    try { Invoke-SafeStep 'pod-readiness' { throw 'Bearer secret session=00000000-0000-0000-0000-000000000001 payload={"x":0.2} host=private' } }
    catch { $safeFailure = $_.Exception.Message }
    Assert-Check ($safeFailure -ceq 'Telemetry preflight failed safely [stage=pod-readiness; code=readiness_mismatch].') 'stage failure is fixed and redacted'
    $unknownFailure = Resolve-SafeErrorMessage 'session=private token=secret-session'
    Assert-Check ($unknownFailure -ceq 'Telemetry preflight failed safely [stage=unknown; code=operation_failed].' -and
        $unknownFailure -notmatch 'secret|token|session') 'unknown stage uses generic safe fallback'
    $script:BaselinePodUid = 'baseline-uid'
    $oldPod = [pscustomobject]@{metadata=[pscustomobject]@{uid='baseline-uid'};status=[pscustomobject]@{phase='Running';containerStatuses=@([pscustomobject]@{ready=$true})}}
    $newPod = [pscustomobject]@{metadata=[pscustomobject]@{uid='replacement-uid'};status=[pscustomobject]@{phase='Running';containerStatuses=@([pscustomobject]@{ready=$true})}}
    $transition = [pscustomobject]@{Index=0;Frames=@(
        [pscustomobject]@{items=@($oldPod,$newPod)},
        [pscustomobject]@{items=@($newPod)}
    )}
    $converged = Wait-ForReplacementPod { $frame = $transition.Frames[$transition.Index]; $transition.Index++; $frame } { } 3
    Assert-Check ($transition.Index -eq 2 -and $converged.metadata.uid -ceq 'replacement-uid') 'transient two-pod set converges to one ready replacement'
    $pollCases = @(
        @{Name='persistent multiple'; Stage='post-pod-timeout-multiple'; Frames=@([pscustomobject]@{items=@($oldPod,$newPod)})},
        @{Name='persistent empty'; Stage='post-pod-timeout-empty'; Frames=@([pscustomobject]@{items=@()})},
        @{Name='not ready'; Stage='post-pod-timeout-unready'; Frames=@([pscustomobject]@{items=@([pscustomobject]@{metadata=[pscustomobject]@{uid='replacement-secret'};status=[pscustomobject]@{phase='Running';containerStatuses=@([pscustomobject]@{ready=$false})}})})},
        @{Name='uid unchanged'; Stage='post-pod-timeout-uid'; Frames=@([pscustomobject]@{items=@($oldPod)})}
    )
    foreach ($case in $pollCases) {
        $state = [pscustomobject]@{Index=0;Frames=$case.Frames}
        $failure = $null
        try { Wait-ForReplacementPod { $frame = $state.Frames[0]; $state.Index++; $frame } { } 2 | Out-Null }
        catch { $failure = $_.Exception.Message }
        $expected = Get-SafeDiagnosticMessage $case.Stage
        Assert-Check ($failure -ceq $expected -and $failure -notmatch 'replacement-secret|baseline-uid|replacement-uid') "bounded pod poll fails closed safely: $($case.Name)"
    }
    $group = Get-ConsumerGroupCommand
    Assert-Check (($group -join ' ') -ceq 'kubectl -n neurovision exec kafka-0 -c kafka -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group telemetry-timescale-v1') 'consumer group command shape'
    $restart = Get-RolloutRestartCommand
    Assert-Check (($restart -join ' ') -ceq 'kubectl -n neurovision rollout restart deployment/telemetry-service') 'scoped rollout command'
    $podRead = Get-PostPodReadCommand
    Assert-Check (($podRead -join ' ') -ceq '-n neurovision get pods -l app.kubernetes.io/name=telemetry-service --request-timeout=5s -o json' -and
        $script:PostPodPollApproxMaxSeconds -eq 115) 'pod polling command timeout and approximate global bound'
    $syntheticPodJson = '{"items":[{"metadata":{"uid":"offline-a"}},{"metadata":{"uid":"offline-b"}}]}' | ConvertFrom-Json
    $normalizedSyntheticPods = @($syntheticPodJson.items)
    Assert-Check ($normalizedSyntheticPods.Count -eq 2 -and $normalizedSyntheticPods[0].metadata.uid -ceq 'offline-a') 'PowerShell JSON items normalize to a stable array'
    Assert-Check ((Compare-Aggregates ([pscustomobject]@{inbox=4;samples=10;duplicates=0;dlq=0}) ([pscustomobject]@{inbox=5;samples=12;duplicates=0;dlq=0}) 2).Passed) 'expected canary aggregate delta'
    Assert-Check (-not (Compare-Aggregates ([pscustomobject]@{inbox=4;samples=10;duplicates=0;dlq=0}) ([pscustomobject]@{inbox=5;samples=14;duplicates=1;dlq=0}) 2).Passed) 'duplicate or extra samples refused'
    $oneShot = Join-Path ([System.IO.Path]::GetTempPath()) ('telemetry-rebalance-test-' + [guid]::NewGuid().ToString('N'))
    try {
        Test-OneShotState $oneShot
        Write-OneShotState $oneShot
        $refused = $false
        try { Test-OneShotState $oneShot } catch { $refused = $true }
        Assert-Check $refused 'one-shot marker refuses retry'
        $writeRefused = $false
        try { Write-OneShotState $oneShot } catch { $writeRefused = $true }
        Assert-Check $writeRefused 'one-shot marker cannot be overwritten'
    } finally {
        Remove-Item -LiteralPath $oneShot -Force -ErrorAction SilentlyContinue
    }
    $deployment = [pscustomobject]@{
        metadata = [pscustomobject]@{name='telemetry-service';namespace='neurovision';generation=7}
        status = [pscustomobject]@{observedGeneration=7;availableReplicas=1}
        spec = [pscustomobject]@{
            replicas=1; strategy=[pscustomobject]@{type='RollingUpdate';rollingUpdate=[pscustomobject]@{maxSurge=1;maxUnavailable=0}}
            template=[pscustomobject]@{spec=[pscustomobject]@{containers=@([pscustomobject]@{name='telemetry-service';image='neurovision/telemetry-service:test'})}}
        }
    }
    $script:ExpectedImage = 'neurovision/telemetry-service:test'; $script:ExpectedGeneration = 7
    Assert-Target $deployment
    $deployment.metadata.name = 'other-service'
    $targetRefused = $false
    try { Assert-Target $deployment } catch { $targetRefused = $true }
    Assert-Check $targetRefused 'wrong rollout target refused'
    $dependency = [pscustomobject]@{
        metadata=[pscustomobject]@{name='kafka';namespace='neurovision'}
        spec=[pscustomobject]@{replicas=1;template=[pscustomobject]@{spec=[pscustomobject]@{containers=@([pscustomobject]@{image='apache/kafka:4.3.1'})}}}
        status=[pscustomobject]@{readyReplicas=1}
    }
    Assert-Dependency $dependency 'kafka' 'apache/kafka:4.3.1'
    $dependencyRefused = $false
    try { Assert-Dependency $dependency 'kafka' 'apache/kafka:unexpected' } catch { $dependencyRefused = $true }
    Assert-Check $dependencyRefused 'dependency image mismatch refused'
    $header = 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID'
    $emptyOffsets = Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 - 0 - member-empty host-empty client-empty"
    Assert-Check ($emptyOffsets.CommittedOffsetTotal -eq 0 -and $emptyOffsets.LogEndOffsetTotal -eq 0 -and $emptyOffsets.Lag -eq 0 -and $emptyOffsets.Members -eq 1) 'unset current and lag accepted only for empty log'
    $unsetCurrent = Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 - 0 0 member-current host-current client-current"
    Assert-Check ($unsetCurrent.CommittedOffsetTotal -eq 0 -and $unsetCurrent.Lag -eq 0) 'unset current accepted with empty log and zero lag'
    $unsetLag = Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 0 0 - member-lag host-lag client-lag"
    Assert-Check ($unsetLag.CommittedOffsetTotal -eq 0 -and $unsetLag.Lag -eq 0) 'unset lag accepted with empty log and zero current'
    $invalidOffsets = @(
        @('-', '1', '1'),
        @('0', '1', '-'),
        @('invalid', '0', '0'),
        @('0', '0', 'invalid'),
        @('0', '-', '0')
    )
    foreach ($case in $invalidOffsets) {
        $rawFailure = $null
        $row = "telemetry-timescale-v1 telemetry-batch-accepted.v1 0 $($case[0]) $($case[1]) $($case[2]) member-secret host-secret client-secret"
        try { Get-ConsumerGroupAggregate "$header`n$row" | Out-Null } catch { $rawFailure = $_.Exception.Message }
        Assert-Check ($rawFailure -ceq 'Consumer group offsets are unavailable.' -and
            $rawFailure -notmatch 'member-secret|host-secret|client-secret|\b1\b') 'invalid unset offset refusal has safe diagnostic'
    }
    $before = Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 2 2 0 member-a host-a client-a"
    $sameMember = Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 2 2 0 member-a host-a client-a"
    $newMember = Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 2 2 0 member-b host-b client-b"
    Assert-Check ($before.Partitions -eq 1 -and $before.Members -eq 1 -and $before.Lag -eq 0) 'safe consumer aggregate extraction'
    Assert-Check ($before.MemberFingerprint -match '^[0-9a-f]{64}$' -and $before.MemberFingerprint -notmatch 'member-a') 'raw member identity is fingerprinted'
    Assert-Check (-not (Test-StableConsumerSnapshots @($sameMember, $sameMember, $sameMember) 0 $before.MemberFingerprint)) 'unchanged membership rejected'
    Assert-Check (Test-StableConsumerSnapshots @($newMember, $newMember, $newMember) 0 $before.MemberFingerprint) 'same-count changed member transition accepted'
    $noMembers = Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 2 2 0 - - -"
    Assert-Check (-not (Test-StableConsumerSnapshots @($noMembers, $noMembers, $noMembers))) 'zero-member stability rejected'
    $lagged = Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 2 2 1 member-a host-a client-a"
    Assert-Check (-not (Test-StableConsumerSnapshots @($lagged, $lagged, $lagged) 0 $before.MemberFingerprint)) 'lag above bound rejected'
    $mixedTopicRefused = $false
    try { Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 2 2 0 member-a host-a client-a`ntelemetry-timescale-v1 unrelated.topic 1 2 2 0 member-a host-a client-a" | Out-Null } catch { $mixedTopicRefused = $true }
    Assert-Check $mixedTopicRefused 'mixed-topic rows rejected'
    $wrongTopicRefused = $false
    try { Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 unrelated.topic 0 2 2 0 member-a host-a client-a" | Out-Null } catch { $wrongTopicRefused = $true }
    Assert-Check $wrongTopicRefused 'wrong-topic row rejected'
    $wrongGroupRefused = $false
    try { Get-ConsumerGroupAggregate "$header`nunrelated-group telemetry-batch-accepted.v1 0 2 2 0 member-a host-a client-a" | Out-Null } catch { $wrongGroupRefused = $true }
    Assert-Check $wrongGroupRefused 'wrong-group row rejected'
    $duplicatePartitionRefused = $false
    try { Get-ConsumerGroupAggregate "$header`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 2 2 0 member-a host-a client-a`ntelemetry-timescale-v1 telemetry-batch-accepted.v1 0 2 2 0 member-b host-b client-b" | Out-Null } catch { $duplicatePartitionRefused = $true }
    Assert-Check $duplicatePartitionRefused 'ambiguous duplicate partition rejected'
    $nonMonotonic = @($newMember, $newMember, $newMember)
    $nonMonotonic[1] = [pscustomobject]@{Partitions=1;Members=1;MemberFingerprint=$newMember.MemberFingerprint;Lag=0;CommittedOffsetTotal=1;LogEndOffsetTotal=2}
    Assert-Check (-not (Test-StableConsumerSnapshots $nonMonotonic 0 $before.MemberFingerprint)) 'non-monotonic offsets rejected'
    $script:ExpectedImage = $null; $script:ExpectedGeneration = -1
    $guardRefused = $false
    try { Assert-RolloutConsent } catch { $guardRefused = $true }
    Assert-Check $guardRefused 'rollout without consent refused'
    $canaryRefused = $false
    try { Assert-CanaryConsent } catch { $canaryRefused = $true }
    Assert-Check $canaryRefused 'canary without both mutation consents refused'
    $safe = Format-SafeDiagnostic 'session_id=00000000-0000-0000-0000-000000000001 token=secret payload={"x":0.2} at 2026-09-29T12:00:00Z'
    Assert-Check ($safe -notmatch '00000000|secret|2026-09-29|0\.2') 'diagnostic redaction'
    Assert-Check ($script:LiveCalls -eq 0) 'offline path has zero external calls'
    Assert-Check ($Mode -ceq 'OfflineTest') 'default mode remains offline'
    'OfflineTest PASS: guard and aggregate contracts passed; external calls=0.'
}

function Assert-Target($Deployment) {
    Invoke-SafeStep 'namespace-workload' {
        if ($Deployment.metadata.name -cne 'telemetry-service' -or $Deployment.metadata.namespace -cne 'neurovision') { throw 'mismatch' }
    }
    Invoke-SafeStep 'deployment-model' {
        $spec = $Deployment.spec
        if ([long]$spec.replicas -ne 1 -or $spec.strategy.type -cne 'RollingUpdate' -or
            [string]$spec.strategy.rollingUpdate.maxSurge -cne '1' -or
            [string]$spec.strategy.rollingUpdate.maxUnavailable -cne '0' -or
            [long]$Deployment.status.observedGeneration -lt [long]$Deployment.metadata.generation -or
            [long]$Deployment.status.availableReplicas -ne 1 -or
            $spec.template.spec.containers.Count -ne 1 -or
            $spec.template.spec.containers[0].name -cne 'telemetry-service') { throw 'model mismatch' }
    }
    Invoke-SafeStep 'image-baseline' { if ($Deployment.spec.template.spec.containers[0].image -cne $ExpectedImage) { throw 'image mismatch' } }
    Invoke-SafeStep 'generation-baseline' { if ([long]$Deployment.metadata.generation -ne $ExpectedGeneration) { throw 'generation mismatch' } }
}

function Assert-Dependency($StatefulSet, [string] $Name, [string] $Image) {
    $imageStage = if ($Name -ceq 'kafka') { 'kafka-image' } else { 'timescale-image' }
    $readinessStage = if ($Name -ceq 'kafka') { 'kafka-readiness' } else { 'timescale-readiness' }
    Invoke-SafeStep 'namespace-workload' { if ($StatefulSet.metadata.name -cne $Name -or $StatefulSet.metadata.namespace -cne 'neurovision') { throw 'mismatch' } }
    Invoke-SafeStep $imageStage {
        if ($StatefulSet.spec.template.spec.containers.Count -ne 1 -or
            $StatefulSet.spec.template.spec.containers[0].image -cne $Image) { throw 'image mismatch' }
    }
    Invoke-SafeStep $readinessStage {
        if ([long]$StatefulSet.spec.replicas -ne 1 -or [long]$StatefulSet.status.readyReplicas -ne 1) { throw 'readiness mismatch' }
    }
}

function Get-ConsumerGroupAggregate([string] $Description) {
    $lines = @($Description -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $headers = @($lines | Where-Object { $_ -match '^GROUP\s+TOPIC\s+' })
    $expectedHeader = 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID'
    if ($headers.Count -ne 1 -or (($headers[0] -split '\s+') -join ' ') -cne $expectedHeader) {
        throw 'Consumer group output header is ambiguous.'
    }
    $rows = @($lines | Where-Object { $_ -cne $headers[0] })
    if ($rows.Count -eq 0) { throw 'Consumer group baseline is unavailable.' }
    $members = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $assignments = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $partitions = [System.Collections.Generic.HashSet[int]]::new()
    $lag = 0L; $committed = 0L; $end = 0L
    foreach ($row in $rows) {
        $columns = @($row -split '\s+')
        if ($columns.Count -lt 6) { throw 'Consumer group aggregate is malformed.' }
        if ($columns[0] -cne 'telemetry-timescale-v1' -or $columns[1] -cne 'telemetry-batch-accepted.v1') {
            throw 'Consumer group rows include an unexpected group or topic.'
        }
        $partition = 0
        if (-not [int]::TryParse($columns[2], [ref]$partition) -or $partition -lt 0 -or -not $partitions.Add($partition)) {
            throw 'Consumer group partition rows are ambiguous.'
        }
        $current = 0L; $last = 0L; $partitionLag = 0L
        if (-not [long]::TryParse($columns[4], [ref]$last) -or $last -lt 0) {
            throw 'Consumer group offsets are unavailable.'
        }
        if ($columns[3] -ceq '-') {
            if ($last -ne 0) { throw 'Consumer group offsets are unavailable.' }
            $current = 0
        } elseif (-not [long]::TryParse($columns[3], [ref]$current) -or $current -lt 0 -or $current -gt $last) {
            throw 'Consumer group offsets are unavailable.'
        }
        if ($columns[5] -ceq '-') {
            if ($last -ne 0) { throw 'Consumer group offsets are unavailable.' }
            $partitionLag = 0
        } elseif (-not [long]::TryParse($columns[5], [ref]$partitionLag) -or $partitionLag -lt 0) {
            throw 'Consumer group offsets are unavailable.'
        }
        if ($last -eq 0 -and $partitionLag -ne 0) { throw 'Consumer group offsets are unavailable.' }
        if ($columns.Count -lt 9) { throw 'Consumer group member identity is unavailable.' }
        if ($columns[6] -ne '-') {
            [void]$members.Add($columns[6])
            [void]$assignments.Add(($columns[6] + '|' + $columns[1] + '|' + $columns[2]))
        }
        $lag += $partitionLag; $committed += $current; $end += $last
    }
    $canonical = (@($assignments | Sort-Object) -join "`n")
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($canonical)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $fingerprint = [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    return [pscustomobject]@{ Partitions = $partitions.Count; Members = $members.Count; MemberFingerprint = $fingerprint;
        Lag = $lag; CommittedOffsetTotal = $committed; LogEndOffsetTotal = $end }
}

function Test-StableConsumerSnapshots($Snapshots, [long] $MaximumLag = 0, [string] $BeforeFingerprint = '') {
    if ($Snapshots.Count -lt 3) { return $false }
    $first = $Snapshots[0]
    if ($first.Members -lt 1 -or $first.Lag -lt 0 -or $first.Lag -gt $MaximumLag) { return $false }
    if ($BeforeFingerprint -and $first.MemberFingerprint -ceq $BeforeFingerprint) { return $false }
    foreach ($snapshot in $Snapshots | Select-Object -Skip 1) {
        if ($snapshot.Members -lt 1 -or $snapshot.Lag -lt 0 -or $snapshot.Lag -gt $MaximumLag -or
            $snapshot.Partitions -ne $first.Partitions -or $snapshot.Members -ne $first.Members -or
            $snapshot.MemberFingerprint -cne $first.MemberFingerprint -or
            $snapshot.CommittedOffsetTotal -lt $first.CommittedOffsetTotal -or
            $snapshot.LogEndOffsetTotal -lt $first.LogEndOffsetTotal -or
            $snapshot.Lag -ne $first.Lag -or $snapshot.CommittedOffsetTotal -ne $first.CommittedOffsetTotal -or
            $snapshot.LogEndOffsetTotal -ne $first.LogEndOffsetTotal) { return $false }
    }
    return $true
}

function Get-PostRolloutSnapshot {
    $deploymentText = Invoke-Live 'post-deployment-read' 'kubectl' @('-n', 'neurovision', 'get', 'deployment', 'telemetry-service', '-o', 'json') | Out-String
    $deployment = Invoke-SafeStep 'post-deployment-json' { $deploymentText | ConvertFrom-Json }
    Invoke-SafeStep 'post-deployment-state' {
        if ($deployment.metadata.name -cne 'telemetry-service' -or $deployment.metadata.namespace -cne 'neurovision' -or
            [long]$deployment.metadata.generation -le $ExpectedGeneration -or
            [long]$deployment.status.observedGeneration -lt [long]$deployment.metadata.generation -or
            [long]$deployment.status.availableReplicas -ne 1 -or [long]$deployment.spec.replicas -ne 1 -or
            $deployment.spec.strategy.type -cne 'RollingUpdate' -or
            [string]$deployment.spec.strategy.rollingUpdate.maxSurge -cne '1' -or
            [string]$deployment.spec.strategy.rollingUpdate.maxUnavailable -cne '0' -or
            $deployment.spec.template.spec.containers[0].image -cne $ExpectedImage) { throw 'state mismatch' }
    }
    $replacementPod = Wait-ForReplacementPod {
        $podText = Invoke-Live 'post-pod-read' 'kubectl' (Get-PostPodReadCommand) | Out-String
        Invoke-SafeStep 'post-pod-json' { $podText | ConvertFrom-Json }
    } { Start-Sleep -Seconds $script:PostPodPollIntervalSeconds } $script:PostPodPollAttempts
    $stable = @()
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        if ($attempt -gt 0) { Start-Sleep -Seconds 5 }
        $description = Invoke-Live 'post-consumer-group-read' 'kubectl' (Get-ConsumerGroupCommand | Select-Object -Skip 1) | Out-String
        $stable += Invoke-SafeStep 'post-consumer-group-parse' { Get-ConsumerGroupAggregate $description }
    }
    Invoke-SafeStep 'post-consumer-group-stability' {
        if (-not (Test-StableConsumerSnapshots $stable 0 $script:BaselineGroup.MemberFingerprint) -or
            $stable[0].CommittedOffsetTotal -lt $script:BaselineGroup.CommittedOffsetTotal -or
            $stable[0].LogEndOffsetTotal -lt $script:BaselineGroup.LogEndOffsetTotal) { throw 'unstable or unchanged member' }
    }
    return [pscustomobject]@{
        Generation = [long]$deployment.metadata.generation; Strategy = 'RollingUpdate'; ReadyPods = 1
        PodReplaced = ($replacementPod.metadata.uid -cne $script:BaselinePodUid); GroupPartitions = $stable[0].Partitions; GroupMembers = $stable[0].Members
        CommittedOffsetTotal = $stable[0].CommittedOffsetTotal; LogEndOffsetTotal = $stable[0].LogEndOffsetTotal
        GroupLag = $stable[0].Lag; GroupStable = $true
    }
}

function Invoke-Preflight {
    $context = (Invoke-Live 'context-read' 'kubectl' @('config', 'current-context') | Out-String).Trim()
    Invoke-SafeStep 'context-validation' { if ($context -cne 'k3d-neurovision-local') { throw 'mismatch' } }
    Invoke-SafeStep 'image-baseline' { if ([string]::IsNullOrWhiteSpace($ExpectedImage)) { throw 'missing image' } }
    Invoke-SafeStep 'generation-baseline' { if ($ExpectedGeneration -lt 1) { throw 'missing generation' } }

    $json = Invoke-Live 'workload-read' 'kubectl' @('-n', 'neurovision', 'get', 'deployment', 'telemetry-service', '-o', 'json') | Out-String
    $deployment = Invoke-SafeStep 'workload-json' { $json | ConvertFrom-Json }
    Assert-Target $deployment

    $podText = Invoke-Live 'pod-read' 'kubectl' @('-n', 'neurovision', 'get', 'pods', '-l', 'app.kubernetes.io/name=telemetry-service', '-o', 'json') | Out-String
    $pods = Invoke-SafeStep 'pod-json' { $podText | ConvertFrom-Json }
    Invoke-SafeStep 'pod-readiness' {
        if ($pods.items.Count -ne 1 -or $pods.items[0].status.phase -cne 'Running' -or
            $pods.items[0].status.containerStatuses[0].ready -ne $true) { throw 'readiness' }
    }

    $kafkaText = Invoke-Live 'kafka-read' 'kubectl' @('-n', 'neurovision', 'get', 'statefulset', 'kafka', '-o', 'json') | Out-String
    $kafkaJson = Invoke-SafeStep 'kafka-json' { $kafkaText | ConvertFrom-Json }
    Assert-Dependency $kafkaJson 'kafka' 'apache/kafka:4.3.1'

    $databaseText = Invoke-Live 'timescale-read' 'kubectl' @('-n', 'neurovision', 'get', 'statefulset', 'telemetry-timescaledb', '-o', 'json') | Out-String
    $databaseJson = Invoke-SafeStep 'timescale-json' { $databaseText | ConvertFrom-Json }
    Assert-Dependency $databaseJson 'telemetry-timescaledb' 'timescale/timescaledb:2.30.1-pg17'

    $groupSnapshots = @()
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        if ($attempt -gt 0) { Start-Sleep -Seconds 5 }
        $group = Invoke-Live 'consumer-group-read' 'kubectl' (Get-ConsumerGroupCommand | Select-Object -Skip 1) | Out-String
        $groupSnapshots += Invoke-SafeStep 'consumer-group-parse' { Get-ConsumerGroupAggregate $group }
    }
    Invoke-SafeStep 'consumer-group-stability' { if (-not (Test-StableConsumerSnapshots $groupSnapshots)) { throw 'unstable' } }
    $groupAggregate = $groupSnapshots[0]
    $script:BaselineGroup = $groupAggregate
    $script:BaselinePodUid = $pods.items[0].metadata.uid
    [pscustomobject]@{ Context = 'k3d-neurovision-local'; Namespace = 'neurovision'; Workload = 'telemetry-service';
        Generation = [long]$deployment.metadata.generation; Strategy = 'RollingUpdate'; Replicas = 1; ReadyPods = 1;
        MaxSurge = 1; MaxUnavailable = 0; KafkaReady = $true; DatabaseReady = $true;
        ConsumerGroup = 'telemetry-timescale-v1'; GroupPartitions = $groupAggregate.Partitions;
        GroupMembers = $groupAggregate.Members; CommittedOffsetTotal = $groupAggregate.CommittedOffsetTotal;
        LogEndOffsetTotal = $groupAggregate.LogEndOffsetTotal; GroupLag = $groupAggregate.Lag }
}

try {
switch ($Mode) {
    'OfflineTest' { Test-OfflineContract }
    'Preflight' { Invoke-Preflight }
    'AuthorizedRollout' {
        Assert-RolloutConsent
        $before = Invoke-Preflight
        $statePath = Get-RolloutStatePath
        Test-OneShotState $statePath
        Write-OneShotState $statePath
        Invoke-Live 'rollout-restart' 'kubectl' (Get-RolloutRestartCommand | Select-Object -Skip 1) | Out-Null
        Invoke-Live 'rollout-status' 'kubectl' @('-n', 'neurovision', 'rollout', 'status', 'deployment/telemetry-service', '--timeout=180s') | Out-Null
        $after = Get-PostRolloutSnapshot
        [pscustomobject]@{ Before = $before; After = $after }
    }
    'AuthorizedCanary' {
        Assert-CanaryConsent
        Stop-Safely 'canary-unavailable'
    }
}
} catch {
    $safeMessage = Resolve-SafeErrorMessage $_.Exception.Message
    [Console]::Error.WriteLine($safeMessage)
    exit 1
}
