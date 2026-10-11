param(
    [ValidateSet('OfflineTest','AuthorizedRun')][string] $Mode = 'OfflineTest',
    [switch] $AuthorizePatientPkce,
    [switch] $AuthorizeClinicalSession,
    [switch] $AuthorizeExpiredSessionTransition,
    [switch] $ConfirmSyntheticData
)
$script:IsDotSourced = $MyInvocation.InvocationName -eq '.'

function Assert-ClinicalDiagnosticGates {
    param([switch] $AuthorizePatientPkce, [switch] $AuthorizeClinicalSession,
        [switch] $AuthorizeExpiredSessionTransition, [switch] $ConfirmSyntheticData)
    if (-not ($AuthorizePatientPkce -and $AuthorizeClinicalSession -and
            $AuthorizeExpiredSessionTransition -and $ConfirmSyntheticData)) {
        throw 'All four independent authorization switches are required.'
    }
}

function Get-ClinicalRecordState([string] $RecordPath) {
    if (-not (Test-Path -LiteralPath $RecordPath)) { return 'absent' }
    try {
        $info=Get-Item -LiteralPath $RecordPath -ErrorAction Stop
        if ($info.PSIsContainer -or $info.Length -lt 16 -or $info.Length -gt 65536 -or
                ($info.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return 'corrupt' }
        $bytes = [IO.File]::ReadAllBytes($RecordPath)
        $plain = [Security.Cryptography.ProtectedData]::Unprotect($bytes, $null,
            [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [Array]::Clear($plain, 0, $plain.Length)
        return 'existing'
    } catch { return 'corrupt' }
}

function Protect-ClinicalDiagnosticRecord([string] $PlainText) {
    try {
        Add-Type -AssemblyName System.Security -ErrorAction Stop
        $bytes = [Text.Encoding]::UTF8.GetBytes($PlainText)
        $cipher = [Security.Cryptography.ProtectedData]::Protect($bytes, $null,
            [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [Array]::Clear($bytes, 0, $bytes.Length)
        return [pscustomobject]@{ Available=$true; CipherText=[Convert]::ToBase64String($cipher) }
    } catch {
        return [pscustomobject]@{ Available=$false; CipherText=$null }
    }
}

function Unprotect-ClinicalDiagnosticRecord([string] $CipherText) {
    Add-Type -AssemblyName System.Security -ErrorAction Stop
    $cipher = [Convert]::FromBase64String($CipherText)
    $plain = [Security.Cryptography.ProtectedData]::Unprotect($cipher, $null,
        [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try { return [Text.Encoding]::UTF8.GetString($plain) }
    finally { [Array]::Clear($plain, 0, $plain.Length) }
}

function Write-ClinicalEncryptedCreateNew([string] $Path, [string] $Text) {
    $script:fileWrites++
    $protected = Protect-ClinicalDiagnosticRecord $Text
    if (-not $protected.Available) { return $false }
    $bytes = [Convert]::FromBase64String($protected.CipherText)
    $stream = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
        return $true
    } catch { return $false }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Complete-ClinicalAttemptRecord($Result, [scriptblock] $PersistOutcome) {
    try {
        $saved=& $PersistOutcome ([pscustomobject]@{
            Category=$Result.Category; HttpStatus=$Result.HttpStatus
            FieldChecks=$Result.FieldChecks; PostAttempts=$Result.PostAttempts
        })
    } catch { $saved=[pscustomobject]@{Saved=$false; StorageCategory='outcome_write_failed'} }
    if ($saved -is [System.Collections.IDictionary] -or $null -ne $saved.Saved) {
        if ($saved.Baseline) { $Result.Baseline=$saved.Baseline }
        if ($saved.After) { $Result.After=$saved.After }
        if ($null -ne $saved.AfterReadAvailable) { $Result.AfterReadAvailable=$saved.AfterReadAvailable }
        $Result.OutcomeSaved=[bool]$saved.Saved
        $Result.StorageCategory=if ($saved.StorageCategory) { $saved.StorageCategory } elseif ($saved.Saved) { 'outcome_saved' } else { 'outcome_write_failed' }
    } else {
        $Result.OutcomeSaved=[bool]$saved
        $Result.StorageCategory=if ($saved) { 'outcome_saved' } else { 'outcome_write_failed' }
    }
}

function Test-ClinicalAggregateSnapshot($Snapshot, [switch] $RequireZeroRunKey) {
    if ($null -eq $Snapshot) { return $false }
    $names=@('Active','Completed','Cancelled','Abandoned','FailedTechnical','Total','StoredKeys','RunKeyCount')
    $values=@{}
    foreach ($name in $names) { $number=0; if ($null -eq $Snapshot.$name -or -not [long]::TryParse([string]$Snapshot.$name,[ref]$number) -or $number -lt 0) { return $false }; $values[$name]=$number }
    $sum=$values.Active+$values.Completed+$values.Cancelled+$values.Abandoned+$values.FailedTechnical
    if ($values.Total -ne $sum -or $values.StoredKeys -gt $values.Total -or $values.RunKeyCount -gt 1) { return $false }
    if ($RequireZeroRunKey -and $values.RunKeyCount -ne 0) { return $false }
    return $true
}

function New-ClinicalDiagnosticIntent([string] $PlanId, [string] $IdempotencyKey, $Baseline) {
    $plan=[guid]::Empty; $key=[guid]::Empty
    if (-not [guid]::TryParse($PlanId,[ref]$plan) -or $plan -eq [guid]::Empty -or
            -not [guid]::TryParse($IdempotencyKey,[ref]$key) -or $key -eq [guid]::Empty -or
            -not (Test-ClinicalAggregateSnapshot $Baseline -RequireZeroRunKey)) { throw 'diagnostic_intent_invalid' }
    return [ordered]@{version=1;project='neurovision-rehab';origin='http://127.0.0.1:8083';method='POST';
        route='/api/v1/me/therapy-plans/{planId}/sessions';body='{"exerciseCode":"FIXATION"}';planId=$plan.ToString();
        idempotencyKey=$key.ToString();baseline=$Baseline;state='IN_FLIGHT'}
}

function ConvertFrom-ClinicalAggregateOutput([string] $Text, [string] $RunKey) {
    $parts=$Text.Trim().Split('|'); $nums=@(); $n=0
    if ($parts.Count -ne 9 -or $parts[0] -cne 'CLINICAL_AGGREGATE_V1') { throw 'clinical_aggregate_invalid' }
    for ($i=1;$i -lt $parts.Count;$i++) {
        if (-not [int]::TryParse($parts[$i],[ref]$n) -or $n -lt 0) { throw 'clinical_aggregate_invalid' }
        $nums += $n
    }
    $snapshot=[pscustomobject]@{Total=$nums[0];Active=$nums[1];Completed=$nums[2];Cancelled=$nums[3];Abandoned=$nums[4];FailedTechnical=$nums[5];StoredKeys=$nums[6];RunKeyCount=$nums[7]}
    if (-not (Test-ClinicalAggregateSnapshot $snapshot) -or $snapshot.RunKeyCount -gt 1) { throw 'clinical_aggregate_invalid' }
    return $snapshot
}

function Invoke-ClinicalSessionAttempt {
    param([string] $PlanId, [string] $IdempotencyKey, [ValidateSet('absent','existing','corrupt','unavailable')][string] $RecordState,
        $Baseline,
        [scriptblock] $ReserveIntent, [scriptblock] $SendRequest, [scriptblock] $PersistOutcome)
    $result = [ordered]@{ Category='clinical_session_start_unavailable'; HttpStatus=0; FieldChecks=@{}; PostAttempts=0; OutcomeSaved=$null; StorageCategory=$null }
    if ($RecordState -ne 'absent') { $result.Category = 'diagnostic_record_' + $RecordState; return [pscustomobject]$result }
    if (-not (Test-ClinicalAggregateSnapshot $Baseline -RequireZeroRunKey)) {
        $result.Category='clinical_session_start_baseline_invalid'; return [pscustomobject]$result
    }
    $planGuid=[guid]::Empty; $keyGuid=[guid]::Empty
    if (-not [guid]::TryParse($PlanId,[ref]$planGuid) -or $planGuid -eq [guid]::Empty -or
            -not [guid]::TryParse($IdempotencyKey,[ref]$keyGuid) -or $keyGuid -eq [guid]::Empty) {
        $result.Category='clinical_session_start_request_invalid'; return [pscustomobject]$result
    }
    $result.Baseline=$Baseline
    try { $reserved = & $ReserveIntent } catch { $reserved = $false }
    if (-not $reserved) { $result.Category='diagnostic_intent_persistence_failed'; return [pscustomobject]$result }
    $result.PostAttempts=1
    try { $response = & $SendRequest }
    catch { $result.Category='clinical_session_start_ambiguous'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result }
    $status = 0
    if ($null -eq $response) { $result.Category='clinical_session_start_ambiguous'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result }
    if (-not [int]::TryParse([string]$response.StatusCode, [ref]$status)) {
        $result.Category='clinical_session_start_response_invalid'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result
    }
    if ($status -eq 0) { $result.Category='clinical_session_start_ambiguous'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result }
    if ($status -lt 100 -or $status -gt 599) { $result.Category='clinical_session_start_response_invalid'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result }
    $result.HttpStatus = $status
    if ($status -ge 500) { $result.Category='clinical_session_start_ambiguous'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result }
    if ($status -notin @(200,201)) { $result.Category='clinical_session_start_http_rejected'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result }
    try { $body = ConvertFrom-Json -InputObject ([string]$response.Content) -ErrorAction Stop }
    catch { $result.Category='clinical_session_start_json_invalid'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result }
    $sid = [guid]::Empty
    $result.FieldChecks = [ordered]@{
        session_id = [guid]::TryParse([string]$body.therapySessionId, [ref]$sid) -and $sid -ne [guid]::Empty
        status = $body.status -ceq 'ACTIVE'
        exercise = $body.exerciseCode -ceq 'fixation'
        plan = $body.therapyPlanId -ceq $PlanId
    }
    if (@($result.FieldChecks.Values | Where-Object { -not $_ }).Count -gt 0) {
        $result.Category='clinical_session_start_shape_invalid'; Complete-ClinicalAttemptRecord $result $PersistOutcome; return [pscustomobject]$result
    }
    $result.Category=if ($status -eq 200) { 'server_reported_replay' } else { 'server_reported_created' }
    Complete-ClinicalAttemptRecord $result $PersistOutcome
    return [pscustomobject]$result
}

function Invoke-ClinicalDiagnosticRequest([string] $Method, [string] $Uri,
        [string] $AccessToken, [string] $Body = '', [string] $IdempotencyKey = '') {
    try {
        $headers = @{}
        if (-not [string]::IsNullOrWhiteSpace($AccessToken)) { $headers['Authorization'] = 'Bearer ' + $AccessToken }
        if ($IdempotencyKey) { $headers['Idempotency-Key'] = $IdempotencyKey }
        $args = @{ Method=$Method; Uri=$Uri; Headers=$headers; UseBasicParsing=$true; TimeoutSec=15; ErrorAction='Stop' }
        if ($Method -eq 'POST') { $args['ContentType']='application/json'; $args['Body']=$Body }
        $r = Invoke-ClinicalDiagnosticHttp $args
        return @{ StatusCode=[int]$r.StatusCode; Content=[string]$r.Content }
    } catch {
        $status = 0
        try { if ($null -ne $_.Exception.Response) { $status=[int]$_.Exception.Response.StatusCode } } catch { }
        return @{ StatusCode=$status; Content=$null }
    }
}

function Invoke-ClinicalDiagnosticHttp($Request) {
    $script:externalCalls++
    return Invoke-WebRequest @Request
}

function Invoke-ClinicalNativeReadOnly([string] $Arguments, [string] $InputText = '') {
    $script:externalCalls++
    $psi=New-Object Diagnostics.ProcessStartInfo
    $psi.FileName='docker'; $psi.Arguments=$Arguments; $psi.UseShellExecute=$false; $psi.CreateNoWindow=$true
    $psi.RedirectStandardInput=[bool]$InputText; $psi.RedirectStandardOutput=$true; $psi.RedirectStandardError=$true
    $p=New-Object Diagnostics.Process; $p.StartInfo=$psi
    try {
        if (-not $p.Start()) { throw 'native_unavailable' }
        $outTask=$p.StandardOutput.ReadToEndAsync(); $errTask=$p.StandardError.ReadToEndAsync()
        if ($InputText) { $p.StandardInput.WriteLine($InputText); $p.StandardInput.Close() }
        if (-not $p.WaitForExit(10000)) { try { $p.Kill() } catch { }; throw 'native_timeout' }
        $out=$outTask.Result; $null=$errTask.Result
        return [pscustomobject]@{ExitCode=$p.ExitCode;Output=$out}
    } finally { $p.Dispose() }
}

function Assert-ClinicalComposeScope([string] $Root) {
    $compose = Join-Path $Root 'compose.yaml'
    $text = [IO.File]::ReadAllText($compose)
    if ($text -notmatch '(?m)^name:\s*neurovision-rehab\s*$') { throw 'compose_scope_invalid' }
    $result=Invoke-ClinicalNativeReadOnly ('compose --project-name neurovision-rehab --file "' + $compose + '" ps --services --status running')
    $services=$result.Output -split "`r?`n"
    if ($result.ExitCode -ne 0 -or $services -notcontains 'clinical-service' -or $services -notcontains 'clinical-postgresql') {
        throw 'compose_services_unavailable'
    }
}

function Get-ClinicalPlanSessionAggregate([string] $Root, [string] $PlanId, [string] $RunKey) {
    $guid=[guid]::Empty
    if (-not [guid]::TryParse($PlanId,[ref]$guid) -or $guid -eq [guid]::Empty) { throw 'plan_id_invalid' }
    $keyGuid=[guid]::Empty
    if (-not [string]::IsNullOrWhiteSpace($RunKey) -and -not [guid]::TryParse($RunKey,[ref]$keyGuid)) { throw 'idempotency_key_invalid' }
    $compose=Join-Path $Root 'compose.yaml'
    $runKeySql=if ($keyGuid -eq [guid]::Empty) { '0' } else { "count(*) FILTER (WHERE idempotency_key = '$($keyGuid.ToString())')" }
    $sql="SELECT 'CLINICAL_AGGREGATE_V1', count(*), count(*) FILTER (WHERE status='ACTIVE'), count(*) FILTER (WHERE status='COMPLETED'), count(*) FILTER (WHERE status='CANCELLED'), count(*) FILTER (WHERE status='ABANDONED'), count(*) FILTER (WHERE status='FAILED_TECHNICAL'), count(*) FILTER (WHERE idempotency_key IS NOT NULL), $runKeySql FROM therapy_sessions WHERE therapy_plan_id = '$($guid.ToString())';"
    $result=Invoke-ClinicalNativeReadOnly ('compose --project-name neurovision-rehab --file "' + $compose + '" exec -T clinical-postgresql psql -v ON_ERROR_STOP=1 -U neurovision_clinical -d neurovision_clinical -t -A -F "|"') $sql
    if ($result.ExitCode -ne 0) { throw 'clinical_aggregate_unavailable' }
    return ConvertFrom-ClinicalAggregateOutput $result.Output $RunKey
}

function Read-ClinicalPlanId {
    $secure = Read-Host 'Enter the UUID of the existing synthetic FIXATION plan' -AsSecureString
    $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $value=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr); $secure.Dispose()
    }
    $id=[guid]::Empty
    if (-not [guid]::TryParse($value,[ref]$id) -or $id -eq [guid]::Empty) { throw 'plan_id_invalid' }
    return $id.ToString()
}

function Invoke-AuthorizedClinicalDiagnostic {
    param([switch] $AuthorizePatientPkce,[switch] $AuthorizeClinicalSession,
        [switch] $AuthorizeExpiredSessionTransition,[switch] $ConfirmSyntheticData)
    Assert-ClinicalDiagnosticGates -AuthorizePatientPkce:$AuthorizePatientPkce `
        -AuthorizeClinicalSession:$AuthorizeClinicalSession `
        -AuthorizeExpiredSessionTransition:$AuthorizeExpiredSessionTransition `
        -ConfirmSyntheticData:$ConfirmSyntheticData
    $root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    Assert-ClinicalComposeScope $root
    $clinical='http://127.0.0.1:8083'
    $ready=Invoke-ClinicalDiagnosticRequest 'GET' "$clinical/q/health/ready" ''
    if ($ready.StatusCode -ne 200) { throw 'clinical_readiness_unavailable' }
    $planId=Read-ClinicalPlanId
    $confirmation=Read-Host 'Confirm this is an existing approved synthetic active FIXATION plan (type YES)'
    if ($confirmation -cne 'YES') { throw 'manual_plan_confirmation_required' }
    $recordDir=Join-Path $env:LOCALAPPDATA 'NeuroVision\diagnostics\clinical-session-start'
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'diagnostic_storage_unavailable' }
    $intentPath=Join-Path $recordDir 'attempt.intent.dpapi'
    $outcomePath=Join-Path $recordDir 'attempt.outcome.dpapi'
    $intentState=Get-ClinicalRecordState $intentPath
    $outcomeState=Get-ClinicalRecordState $outcomePath
    if ($intentState -ne 'absent') { throw ('diagnostic_record_' + $intentState) }
    if ($outcomeState -ne 'absent') { throw ('diagnostic_outcome_record_' + $outcomeState) }
    $helper=Join-Path $PSScriptRoot 'telemetry-compose-capacity-pkce.ps1'
    . $helper
    $accessToken=Get-CapacityPatientAccessToken (Join-Path $root '.env')
    $plans=Invoke-ClinicalDiagnosticRequest 'GET' "$clinical/api/v1/me/therapy-plans" $accessToken
    if ($plans.StatusCode -ne 200) { throw 'clinical_plan_query_rejected' }
    try { $planList=@(ConvertFrom-Json -InputObject $plans.Content -ErrorAction Stop) } catch { throw 'clinical_plan_response_invalid' }
    $eligible=@($planList | Where-Object { $_.therapyPlanId -ceq $planId -and $_.status -ceq 'ACTIVE' -and $_.dailyExercisePrescriptions.FIXATION -ge 1 })
    if ($eligible.Count -ne 1) { throw 'eligible_synthetic_plan_not_confirmed' }
    $idempotencyKey=[guid]::NewGuid().ToString()
    $baseline=Get-ClinicalPlanSessionAggregate $root $planId $idempotencyKey
    $intent=ConvertTo-Json -InputObject (New-ClinicalDiagnosticIntent $planId $idempotencyKey $baseline) -Compress
    $post={ Invoke-ClinicalDiagnosticRequest 'POST' "$clinical/api/v1/me/therapy-plans/$planId/sessions" $accessToken '{"exerciseCode":"FIXATION"}' $idempotencyKey }
    $reserve={
        if (-not (Test-Path -LiteralPath $recordDir)) { [IO.Directory]::CreateDirectory($recordDir) | Out-Null }
        $directory=Get-Item -LiteralPath $recordDir -ErrorAction Stop
        if (-not $directory.PSIsContainer -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return $false }
        return Write-ClinicalEncryptedCreateNew $intentPath $intent
    }
    $save={ param($summary)
        $after=$null; $afterRead=$false
        try { $after=Get-ClinicalPlanSessionAggregate $root $planId $idempotencyKey; $afterRead=$true } catch { }
        $record=@{ Category=$summary.Category; HttpStatus=$summary.HttpStatus; FieldChecks=$summary.FieldChecks; PostAttempts=$summary.PostAttempts; Baseline=$baseline; After=$after; AfterReadAvailable=$afterRead }
        $saved=Write-ClinicalEncryptedCreateNew $outcomePath (ConvertTo-Json $record -Compress)
        return [pscustomobject]@{ Saved=$saved; StorageCategory=$(if ($saved) {'outcome_saved'} else {'outcome_write_failed'}); Baseline=$baseline; After=$after; AfterReadAvailable=$afterRead }
    }
    $attempt=Invoke-ClinicalSessionAttempt -PlanId $planId -IdempotencyKey $idempotencyKey -RecordState 'absent' -Baseline $baseline -ReserveIntent $reserve -SendRequest $post -PersistOutcome $save
    Write-Output ('CLINICAL_SESSION_DIAGNOSTIC: category={0}; httpStatus={1}; idFieldValid={2}; statusFieldValid={3}; exerciseFieldValid={4}; planFieldValid={5}; postAttempts={6}; outcomeSaved={7}; storageCategory={8}; afterSnapshotAvailable={9}; baselineActive={10}; afterActive={11}; baselineFailed={12}; afterFailed={13}; baselineAbandoned={14}; afterAbandoned={15}; baselineSessions={16}; afterSessions={17}; baselineStoredKeys={18}; afterStoredKeys={19}; baselineRunKeyCount={20}; afterRunKeyCount={21}' -f `
        $attempt.Category,$attempt.HttpStatus,$attempt.FieldChecks.session_id,$attempt.FieldChecks.status,$attempt.FieldChecks.exercise,$attempt.FieldChecks.plan,$attempt.PostAttempts,$attempt.OutcomeSaved,$attempt.StorageCategory,$attempt.AfterReadAvailable,$attempt.Baseline.Active,$attempt.After.Active,$attempt.Baseline.FailedTechnical,$attempt.After.FailedTechnical,$attempt.Baseline.Abandoned,$attempt.After.Abandoned,$attempt.Baseline.Total,$attempt.After.Total,$attempt.Baseline.StoredKeys,$attempt.After.StoredKeys,$attempt.Baseline.RunKeyCount,$attempt.After.RunKeyCount)
}

function Invoke-ClinicalDiagnosticEntryPoint {
    param([switch] $AuthorizePatientPkce,[switch] $AuthorizeClinicalSession,
        [switch] $AuthorizeExpiredSessionTransition,[switch] $ConfirmSyntheticData)
    Invoke-AuthorizedClinicalDiagnostic -AuthorizePatientPkce:$AuthorizePatientPkce `
        -AuthorizeClinicalSession:$AuthorizeClinicalSession `
        -AuthorizeExpiredSessionTransition:$AuthorizeExpiredSessionTransition `
        -ConfirmSyntheticData:$ConfirmSyntheticData
}

if (-not $script:IsDotSourced) {
    if ($Mode -eq 'OfflineTest') {
        $runner=Join-Path $PSScriptRoot 'test-telemetry-clinical-session-start.ps1'
        & $runner -OfflineTest
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        return
    }
    try {
        Invoke-ClinicalDiagnosticEntryPoint -AuthorizePatientPkce:$AuthorizePatientPkce `
            -AuthorizeClinicalSession:$AuthorizeClinicalSession `
            -AuthorizeExpiredSessionTransition:$AuthorizeExpiredSessionTransition `
            -ConfirmSyntheticData:$ConfirmSyntheticData
    } catch {
        $category='clinical_diagnostic_stopped'
        if ($_.Exception.Message -match '^(compose_scope_invalid|compose_unavailable|compose_timeout|compose_services_unavailable|clinical_readiness_unavailable|plan_id_invalid|idempotency_key_invalid|manual_plan_confirmation_required|diagnostic_storage_unavailable|diagnostic_record_[a-z]+|diagnostic_outcome_record_[a-z]+|clinical_aggregate_unavailable|clinical_aggregate_timeout|clinical_aggregate_invalid|clinical_plan_query_rejected|clinical_plan_response_invalid|eligible_synthetic_plan_not_confirmed)$') {
            $category=$_.Exception.Message
        }
        Write-Output ('CLINICAL_SESSION_DIAGNOSTIC: category={0}' -f $category)
        exit 1
    }
}
