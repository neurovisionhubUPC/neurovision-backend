param([switch] $OfflineTest)
$ErrorActionPreference = 'Stop'
$script:externalCalls = 0; $script:fileWrites = 0; $script:markerWrites = 0
$scriptPath = Join-Path $PSScriptRoot 'diagnose-telemetry-clinical-session-start.ps1'
foreach ($path in @($scriptPath, $PSCommandPath)) {
    $tokens = $null; $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count) { throw ('PowerShell parse failed: ' + [IO.Path]::GetFileName($path)) }
}
. $scriptPath -Mode OfflineTest
if (-not (Get-Command Invoke-ClinicalSessionAttempt -ErrorAction SilentlyContinue)) {
    throw 'Clinical session diagnostic behavior is not implemented.'
}
function Invoke-ClinicalDiagnosticHttp($Request) { $script:externalCalls++; throw 'HTTP boundary unexpectedly reached.' }
function Invoke-ClinicalNativeReadOnly([string] $Arguments,[string] $InputText='') { $script:externalCalls++; throw 'Native boundary unexpectedly reached.' }
function Write-ClinicalEncryptedCreateNew([string] $Path,[string] $Text) { $script:fileWrites++; throw 'Persistent storage boundary unexpectedly reached.' }
foreach ($boundary in @(
    @{Name='Invoke-ClinicalDiagnosticHttp'; Marker='HTTP boundary unexpectedly reached.'},
    @{Name='Invoke-ClinicalNativeReadOnly'; Marker='Native boundary unexpectedly reached.'},
    @{Name='Write-ClinicalEncryptedCreateNew'; Marker='Persistent storage boundary unexpectedly reached.'}
)) {
    if ((Get-Command $boundary.Name -CommandType Function).Definition -notlike ('*' + $boundary.Marker + '*')) {
        throw 'Offline boundary spy could not be installed.'
    }
}

function Assert-Equal($Actual, $Expected, [string] $Name) {
    if ($Actual -cne $Expected) { throw ('Offline assertion failed: ' + $Name) }
}
function Invoke-FakeCase([string] $Case, [object] $Response, [bool] $Reserve = $true,
        [object] $OutcomeSave = $true, [object] $Baseline = $script:validBaseline,
        [bool] $ThrowPost = $false) {
    $events = [System.Collections.Generic.List[string]]::new()
    $script:postCount = 0
    $result = Invoke-ClinicalSessionAttempt -PlanId '11111111-1111-4111-8111-111111111111' `
        -IdempotencyKey '22222222-2222-4222-8222-222222222222' -RecordState 'absent' -Baseline $Baseline `
        -ReserveIntent { $events.Add('reserve'); return $Reserve } `
        -SendRequest { $events.Add('post'); $script:postCount++; if ($ThrowPost) { throw 'synthetic transport timeout' }; return $Response } `
        -PersistOutcome { $events.Add('outcome'); return $OutcomeSave }
    $posts = $script:postCount
    return [pscustomobject]@{ Result=$result; Events=@($events); Posts=$posts }
}

$script:validBaseline=[pscustomobject]@{Active=2;Completed=1;Cancelled=0;Abandoned=0;FailedTechnical=0;Total=3;StoredKeys=3;RunKeyCount=0}
$valid = @{ StatusCode=201; Content='{"therapySessionId":"33333333-3333-4333-8333-333333333333","status":"ACTIVE","exerciseCode":"fixation","therapyPlanId":"11111111-1111-4111-8111-111111111111"}' }
$created = Invoke-FakeCase 'created' $valid
Assert-Equal $created.Result.Category 'server_reported_created' '201 category'
Assert-Equal $created.Posts 1 'exactly one POST'
Assert-Equal ($created.Events -join ',') 'reserve,post,outcome' 'durable intent before POST'
$replay = Invoke-FakeCase 'replay' @{StatusCode=200; Content=$valid.Content}
Assert-Equal $replay.Result.Category 'server_reported_replay' '200 category'
Assert-Equal $replay.Result.FieldChecks.status $true '200 requires valid response shape'
$upperExercise = Invoke-FakeCase 'noncanonical-exercise' @{StatusCode=201;Content=($valid.Content -replace '"exerciseCode":"fixation"','"exerciseCode":"FIXATION"')}
Assert-Equal $upperExercise.Result.Category 'clinical_session_start_shape_invalid' 'response exercise must use Clinical canonical code'
$canonicalExerciseReplay = Invoke-FakeCase 'canonical-exercise-replay' @{StatusCode=200;Content=$valid.Content}
Assert-Equal $canonicalExerciseReplay.Result.Category 'server_reported_replay' 'canonical Clinical exercise code is valid for replay'
$replayJsonBad = Invoke-FakeCase 'bad-replay-json' @{StatusCode=200;Content='not-json'}
Assert-Equal $replayJsonBad.Result.Category 'clinical_session_start_json_invalid' 'malformed 200 is invalid JSON'
$replayBad = Invoke-FakeCase 'bad-replay' @{StatusCode=200;Content='{}'}
Assert-Equal $replayBad.Result.Category 'clinical_session_start_shape_invalid' 'malformed 200 is not replay proof'
$replayMismatch = Invoke-FakeCase 'mismatched-replay' @{StatusCode=200;Content=($valid.Content -replace 'ACTIVE','FAILED')}
Assert-Equal $replayMismatch.Result.Category 'clinical_session_start_shape_invalid' 'mismatched 200 is not replay proof'
foreach ($fieldText in @(
    ($valid.Content -replace '33333333-3333-4333-8333-333333333333','bad-guid'),
    ($valid.Content -replace '"status":"ACTIVE",',''),
    ($valid.Content -replace '"exerciseCode":"fixation",',''),
    ($valid.Content -replace ',"therapyPlanId":"11111111-1111-4111-8111-111111111111"','')
)) {
    $r=Invoke-FakeCase 'bad-replay-field' @{StatusCode=200;Content=$fieldText}
    Assert-Equal $r.Result.Category 'clinical_session_start_shape_invalid' '200 field mismatch is not replay proof'
}
foreach ($status in @(401,403,409,500,503)) {
    $r = Invoke-FakeCase 'http' @{StatusCode=$status; Content='{}'}
    Assert-Equal $r.Result.HttpStatus $status 'bounded status'
    Assert-Equal $r.Posts 1 'one rejection request'
    if ($status -ge 500) { Assert-Equal $r.Result.Category 'clinical_session_start_ambiguous' '5xx is ambiguous' }
}
$invalidStatus = Invoke-FakeCase 'invalid-status' @{StatusCode=600;Content='{}'}
Assert-Equal $invalidStatus.Result.HttpStatus 0 'invalid status is not reported'
$invalidCases=@(
    @{Response=@{StatusCode=0;Content=''}; Category='clinical_session_start_ambiguous'},
    @{Response=$null; Category='clinical_session_start_ambiguous'},
    @{Response=@{StatusCode='not-a-status';Content='{}'}; Category='clinical_session_start_response_invalid'},
    @{Response=@{StatusCode=600;Content='{}'}; Category='clinical_session_start_response_invalid'},
    @{Response=@{StatusCode=201;Content='not-json'}; Category='clinical_session_start_json_invalid'},
    @{Response=@{StatusCode=201;Content='{}'}; Category='clinical_session_start_shape_invalid'},
    @{Response=@{StatusCode=201;Content=($valid.Content -replace 'ACTIVE','FAILED')}; Category='clinical_session_start_shape_invalid'},
    @{Response=@{StatusCode=201;Content=($valid.Content -replace 'fixation','saccade')}; Category='clinical_session_start_shape_invalid'},
    @{Response=@{StatusCode=201;Content=($valid.Content -replace '11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444')}; Category='clinical_session_start_shape_invalid'},
    @{Response=@{StatusCode=201;Content=($valid.Content -replace '33333333-3333-4333-8333-333333333333','bad-guid')}; Category='clinical_session_start_shape_invalid'},
    @{Response=@{StatusCode=201;Content=($valid.Content -replace '"status":"ACTIVE",','')}; Category='clinical_session_start_shape_invalid'},
    @{Response=@{StatusCode=201;Content=($valid.Content -replace '"exerciseCode":"fixation",','')}; Category='clinical_session_start_shape_invalid'},
    @{Response=@{StatusCode=201;Content=($valid.Content -replace ',"therapyPlanId":"11111111-1111-4111-8111-111111111111"','')}; Category='clinical_session_start_shape_invalid'}
)
foreach ($case in $invalidCases) {
    $r = Invoke-FakeCase 'invalid' $case.Response
    Assert-Equal $r.Result.Category $case.Category ('response category ' + $case.Category)
}
foreach ($state in @('existing','corrupt','unavailable')) {
    $r = Invoke-ClinicalSessionAttempt -PlanId '11111111-1111-4111-8111-111111111111' `
        -IdempotencyKey '22222222-2222-4222-8222-222222222222' -RecordState $state `
        -ReserveIntent { throw 'must not reserve' } -SendRequest { throw 'must not POST' } -PersistOutcome { return $true }
    Assert-Equal $r.PostAttempts 0 ('no POST for record state ' + $state)
}
$blocked = Invoke-FakeCase 'flush-fails' $valid $false
Assert-Equal $blocked.Posts 0 'no POST when durable intent fails'
$saved = Invoke-FakeCase 'outcome-fails' $valid $true $false
Assert-Equal $saved.Result.Category 'server_reported_created' 'primary response classification retained'
Assert-Equal $saved.Result.OutcomeSaved $false 'outcome persistence separately reported'
Assert-Equal $saved.Result.StorageCategory 'outcome_write_failed' 'storage failure category separate'
Assert-Equal $saved.Result.HttpStatus 201 'HTTP status retained after outcome persistence failure'
Assert-Equal $saved.Result.FieldChecks.session_id $true 'field checks retained after outcome persistence failure'
Assert-Equal $saved.Posts 1 'no retry after outcome persistence failure'
$throwPost = Invoke-FakeCase 'throwing-post' $null $true $true $script:validBaseline $true
Assert-Equal $throwPost.Result.PostAttempts 1 'request attempt recorded before callback'
Assert-Equal $throwPost.Posts 1 'throwing callback is not retried'
Assert-Equal $throwPost.Result.Category 'clinical_session_start_ambiguous' 'thrown request is ambiguous'
$nonzeroBaseline=$script:validBaseline | Select-Object *; $nonzeroBaseline.RunKeyCount=1
$keyBlocked=Invoke-FakeCase 'preexisting-key' $valid $true $true $nonzeroBaseline
Assert-Equal $keyBlocked.Posts 0 'nonzero run-key baseline prevents POST'
$invalidBaseline=$script:validBaseline | Select-Object *; $invalidBaseline.Total=100
$baselineBlocked=Invoke-FakeCase 'invalid-baseline' $valid $true $true $invalidBaseline
Assert-Equal $baselineBlocked.Posts 0 'inconsistent baseline prevents POST'
$afterReadFail=Invoke-FakeCase 'after-aggregate-read-fails' $valid $true @{Saved=$true;StorageCategory='outcome_saved';Baseline=$script:validBaseline;AfterReadAvailable=$false}
Assert-Equal $afterReadFail.Result.Category 'server_reported_created' 'after snapshot failure preserves response result'
Assert-Equal $afterReadFail.Result.OutcomeSaved $true 'outcome survives after snapshot failure'
Assert-Equal $afterReadFail.Result.AfterReadAvailable $false 'after aggregate failure is explicit'
$intent=New-ClinicalDiagnosticIntent -PlanId '11111111-1111-4111-8111-111111111111' `
    -IdempotencyKey '22222222-2222-4222-8222-222222222222' -Baseline $script:validBaseline
foreach ($key in @('version','project','origin','method','route','body','planId','idempotencyKey','baseline','state')) {
    if (-not $intent.Contains($key)) { throw ('Encrypted intent omits required field: ' + $key) }
}
Assert-Equal $intent.state 'IN_FLIGHT' 'intent state'
$aggregate=ConvertFrom-ClinicalAggregateOutput -Text 'CLINICAL_AGGREGATE_V1|1|1|0|0|0|0|1|0' -RunKey '22222222-2222-4222-8222-222222222222'
Assert-Equal $aggregate.Total 1 'aggregate total'
Assert-Equal $aggregate.RunKeyCount 0 'aggregate key count'
foreach ($badAggregate in @('', "CLINICAL_AGGREGATE_V1|1|1|0|0|0|0|0|0`nOTHER|0", 'CLINICAL_AGGREGATE_V1|x|1|0|0|0|0|0|0', 'CLINICAL_AGGREGATE_V1|1|1|0|0|0|0|1|2')) {
    try { ConvertFrom-ClinicalAggregateOutput -Text $badAggregate -RunKey '22222222-2222-4222-8222-222222222222' | Out-Null; throw 'Invalid aggregate was accepted.' }
    catch { if ($_.Exception.Message -eq 'Invalid aggregate was accepted.') { throw } }
}
foreach ($missingGate in @(
    { Assert-ClinicalDiagnosticGates -AuthorizeClinicalSession -AuthorizeExpiredSessionTransition -ConfirmSyntheticData },
    { Assert-ClinicalDiagnosticGates -AuthorizePatientPkce -AuthorizeExpiredSessionTransition -ConfirmSyntheticData },
    { Assert-ClinicalDiagnosticGates -AuthorizePatientPkce -AuthorizeClinicalSession -ConfirmSyntheticData },
    { Assert-ClinicalDiagnosticGates -AuthorizePatientPkce -AuthorizeClinicalSession -AuthorizeExpiredSessionTransition }
)) {
    try { & $missingGate; throw 'A missing authorization gate was accepted.' }
    catch { if ($_.Exception.Message -eq 'A missing authorization gate was accepted.') { throw } }
}
$script:entryComposeCalls=0
function Assert-ClinicalComposeScope([string] $Root) { $script:entryComposeCalls++; throw 'offline_compose_boundary' }
$completeGates=@{AuthorizePatientPkce=$true;AuthorizeClinicalSession=$true;AuthorizeExpiredSessionTransition=$true;ConfirmSyntheticData=$true}
try { Invoke-ClinicalDiagnosticEntryPoint @completeGates; throw 'Entry point did not reach safe Compose boundary.' }
catch { if ($_.Exception.Message -eq 'Entry point did not reach safe Compose boundary.') { throw }; if ($_.Exception.Message -ne 'offline_compose_boundary') { throw } }
Assert-Equal $script:entryComposeCalls 1 'all named entry gates reach first safe boundary'
foreach ($gateName in @('AuthorizePatientPkce','AuthorizeClinicalSession','AuthorizeExpiredSessionTransition','ConfirmSyntheticData')) {
    $missing=@{AuthorizePatientPkce=$true;AuthorizeClinicalSession=$true;AuthorizeExpiredSessionTransition=$true;ConfirmSyntheticData=$true}
    $missing[$gateName]=$false
    $before=$script:entryComposeCalls
    try { Invoke-ClinicalDiagnosticEntryPoint @missing; throw 'Entry point accepted a missing gate.' }
    catch { if ($_.Exception.Message -eq 'Entry point accepted a missing gate.') { throw }; if ($_.Exception.Message -notlike '*authorization switches are required*') { throw } }
    Assert-Equal $script:entryComposeCalls $before ('missing gate blocks Compose boundary: ' + $gateName)
}
try { [Reflection.Assembly]::Load('System.Security') | Out-Null } catch { }
try { [Reflection.Assembly]::Load('System.Security.Cryptography.ProtectedData') | Out-Null } catch { }
$dpapi = Protect-ClinicalDiagnosticRecord -PlainText 'offline synthetic record'
$dpapiStatus='unavailable'
if ($dpapi.Available) {
    Assert-Equal (Unprotect-ClinicalDiagnosticRecord $dpapi.CipherText) 'offline synthetic record' 'DPAPI memory roundtrip'
    $dpapiStatus='memory_roundtrip_passed'
}
Assert-Equal $script:externalCalls 0 'external calls'
Assert-Equal $script:fileWrites 0 'file writes'
Assert-Equal $script:markerWrites 0 'marker writes'
Write-Output ('OFFLINE_TEST_PASS: externalCalls={0}; fileWrites={1}; markerWrites={2}; dpapi={3}' -f $script:externalCalls,$script:fileWrites,$script:markerWrites,$dpapiStatus)
