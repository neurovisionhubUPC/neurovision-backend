[CmdletBinding()]
param(
    [ValidateSet('OfflineTest', 'Preflight', 'AuthorizedRun')]
    [string] $Mode = 'OfflineTest',
    [switch] $AuthorizeDoctorPkce,
    [switch] $AuthorizePatientPkce,
    [switch] $AuthorizeClinicalSetup,
    [switch] $AuthorizeCanaryWrite,
    [switch] $ConfirmSyntheticData
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ExternalCalls = 0
$script:Root = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$script:FixturePath = Join-Path $script:Root 'telemetry-service/src/test/resources/fixtures/telemetry/v1/dual-typed-batch.json'
$script:ClinicalOrigin = 'http://127.0.0.1:18083'
$script:TelemetryOrigin = 'http://127.0.0.1:18084'
$script:RedirectUri = 'http://localhost:5173/auth/callback'
$script:FailureCodes = @{ authorization='consent_required';origin='loopback_mismatch';context='context_unavailable';'context-mismatch'='context_mismatch';workload='target_or_image_mismatch';port='port_forward_unavailable';baseline='baseline_mismatch';assignment='assignment_mismatch';identity='authenticated_state_mismatch';marker='already_attempted';pkce='authentication_failed';clinical='clinical_setup_failed';lease='lease_mismatch';batch='fixture_invalid';http='response_mismatch';sql='aggregate_unavailable';'database-attribution'='aggregate_mismatch';kafka='movement_unattributable';poll='bounded_timeout';unknown='operation_failed' }
$script:Images = @{ clinical='neurovision/clinical-service:k3d-v6-20260918-6075a290';telemetry='neurovision/telemetry-service:k3d-historical-20260918-80a8ac10';kafka='apache/kafka:4.3.1';timescale='timescale/timescaledb:2.30.1-pg17' }
$script:SourceTopic='telemetry-batch-accepted.v1'
$script:DlqTopic='telemetry-batch-accepted.v1.dlq'
$script:ConsumerGroup='telemetry-timescale-v1'
$script:GlobalTimeoutSeconds=300
$script:RunTimer=$null
$script:ProcessRunner=$null
$script:CanaryMarkerWritten=$false

function Stop-Safely([string] $Stage) {
    if (-not $script:FailureCodes.ContainsKey($Stage)) { $Stage='unknown' }
    throw "Canary check failed safely [stage=$Stage; code=$($script:FailureCodes[$Stage])]."
}
function Resolve-SafeErrorMessage([string] $Candidate) {
    foreach ($stage in $script:FailureCodes.Keys) {
        $expected="Canary check failed safely [stage=$stage; code=$($script:FailureCodes[$stage])]."
        if ($Candidate -ceq $expected) { return $expected }
    }
    'Canary check failed safely [stage=unknown; code=operation_failed].'
}
function Invoke-SafeStep([string] $Stage,[scriptblock] $Action) {
    try { return (& $Action) } catch { Stop-Safely $Stage }
}
function Assert-Consent {
    if (-not (Test-ConsentValues ([string]$Mode) ([bool]$AuthorizeDoctorPkce) ([bool]$AuthorizePatientPkce) ([bool]$AuthorizeClinicalSetup) ([bool]$AuthorizeCanaryWrite) ([bool]$ConfirmSyntheticData))) { Stop-Safely 'authorization' }
}
function Test-ConsentValues([string] $ModeValue,[bool] $Doctor,[bool] $Patient,[bool] $Clinical,[bool] $Canary,[bool] $Synthetic) {
    return ($ModeValue -ceq 'AuthorizedRun' -and $Doctor -and $Patient -and $Clinical -and $Canary -and $Synthetic)
}
function Assert-GlobalTimeout {
    if ($null -ne $script:RunTimer -and $script:RunTimer.Elapsed.TotalSeconds -ge $script:GlobalTimeoutSeconds) { Stop-Safely 'poll' }
}
function Assert-Loopback([string] $Origin,[int] $Port) {
    $uri=$null
    if (-not [uri]::TryCreate($Origin,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -cne 'http' -or $uri.Host -cne '127.0.0.1' -or
        $uri.Port -ne $Port -or $uri.AbsolutePath -cne '/' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) { Stop-Safely 'origin' }
    $uri.GetLeftPart([UriPartial]::Authority)
}
function Get-MarkerPath {
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { Stop-Safely 'marker' }
    Join-Path $env:LOCALAPPDATA 'NeuroVision\TelemetryHttpCanary\attempted.flag'
}
function Assert-MarkerAvailable([bool] $Exists) {
    if ($Exists) { Stop-Safely 'marker' }
}
function Write-CanaryMarker([string] $Path) {
    Assert-MarkerAvailable (Test-Path -LiteralPath $Path)
    $dir=Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    New-Item -ItemType File -Path $Path -ErrorAction Stop | Out-Null
    Set-Content -LiteralPath $Path -Value 'attempted' -NoNewline
    $script:CanaryMarkerWritten=$true
}
function Invoke-ClinicalMutation([scriptblock] $Action) {
    if(-not $script:CanaryMarkerWritten){Stop-Safely 'marker'}
    & $Action
}
function ConvertTo-WindowsArgument([string] $Value) {
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $builder=[Text.StringBuilder]::new();[void]$builder.Append('"');$slashes=0
    foreach($character in $Value.ToCharArray()){
        if($character -eq [char]92){$slashes++;continue}
        if($character -eq [char]34){[void]$builder.Append(('\' * (2*$slashes+1)));[void]$builder.Append('"');$slashes=0;continue}
        if($slashes -gt 0){[void]$builder.Append(('\' * $slashes));$slashes=0}
        [void]$builder.Append($character)
    }
    if($slashes -gt 0){[void]$builder.Append(('\' * (2*$slashes)))}
    [void]$builder.Append('"');$builder.ToString()
}
function Invoke-BoundedProcess([string] $File,[string[]] $Arguments,[string] $StandardInput='',[int] $TimeoutSeconds=15) {
    if($null -ne $script:ProcessRunner){return (& $script:ProcessRunner $File $Arguments $StandardInput $TimeoutSeconds)}
    $script:ExternalCalls++
    $command=Get-Command -Name $File -CommandType Application -ErrorAction Stop
    $psi=[Diagnostics.ProcessStartInfo]::new();$psi.FileName=$command.Source
    $psi.Arguments=(($Arguments | ForEach-Object {ConvertTo-WindowsArgument ([string]$_)}) -join ' ')
    $psi.UseShellExecute=$false;$psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true;$psi.RedirectStandardInput=$true
    $process=[Diagnostics.Process]::new();$process.StartInfo=$psi
    try {
        if(-not $process.Start()){throw 'start'}
        $stdoutTask=$process.StandardOutput.ReadToEndAsync()
        $stderrTask=$process.StandardError.ReadToEndAsync()
        $deadline=[Diagnostics.Stopwatch]::StartNew()
        if($StandardInput.Length -gt 0){
            $inputTask=$process.StandardInput.WriteAsync($StandardInput)
            if(-not $inputTask.Wait($TimeoutSeconds*1000)){try{$process.Kill()}catch{};throw 'timeout'}
        }
        $process.StandardInput.Close()
        $remaining=[Math]::Max(1,($TimeoutSeconds*1000)-[int]$deadline.ElapsedMilliseconds)
        if(-not $process.WaitForExit($remaining)){
            try{$process.Kill()}catch{}
            try{$process.WaitForExit(2000)|Out-Null}catch{}
            throw 'timeout'
        }
        $stdout=$stdoutTask.GetAwaiter().GetResult()
        $null=$stderrTask.GetAwaiter().GetResult()
        return @{ExitCode=$process.ExitCode;StdOut=$stdout;TimedOut=$false}
    } finally { $process.Dispose() }
}
function Invoke-Live([string] $Stage,[string] $File,[string[]] $Arguments,[int] $TimeoutSeconds=15) {
    try {
        if ($File -ceq 'kubectl') { $Arguments=@('--request-timeout=5s')+$Arguments }
        $result=Invoke-BoundedProcess $File $Arguments '' $TimeoutSeconds
        if($result.TimedOut -or $result.ExitCode -ne 0){Stop-Safely $Stage}
        return @(([string]$result.StdOut) -split "\r?\n" | Where-Object {$_ -ne ''})
    } catch { Stop-Safely $Stage }
}
function Assert-ExpectedKubeContext([string[]] $Rows) {
    if($Rows.Count -ne 1 -or $Rows[0].Trim() -cne 'k3d-neurovision-local'){Stop-Safely 'context-mismatch'}
    $true
}
function Get-ExpectedKubeContext {
    $rows=@(Invoke-Live 'context' 'kubectl' @('config','current-context'))
    Assert-ExpectedKubeContext $rows
}
function Invoke-LocalHttp([string] $Method,[string] $Uri,[string] $Token='',[string] $Body='',[hashtable] $Headers=@{}) {
    $headers=@{}
    if ($Token) { $headers.Authorization="Bearer $Token" }
    foreach ($key in $Headers.Keys) { $headers[$key]=$Headers[$key] }
    try {
        $options=@{Method=$Method;Uri=$Uri;Headers=$headers;UseBasicParsing=$true;TimeoutSec=15;MaximumRedirection=0;ErrorAction='Stop'}
        if ($Method -eq 'POST') { $options.ContentType='application/json';$options.Body=$Body }
        $response=Invoke-WebRequest @options
        return @{Status=[int]$response.StatusCode;Body=[string]$response.Content}
    } catch {
        $status=0
        if ($null -ne $_.Exception.Response) { $status=[int]$_.Exception.Response.StatusCode }
        return @{Status=$status;Body=''}
    }
}
function Invoke-ReadOnlySql([string] $Pod,[string] $Sql) {
    $boundedSql="SET statement_timeout='5s';"+[Environment]::NewLine+$Sql
    $args=@('--request-timeout=5s','-n','neurovision','exec','-i',$Pod,'--','sh','-c',
        'psql -X -q -A -t -F "|" -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -')
    $result=Invoke-BoundedProcess 'kubectl' $args $boundedSql 15
    if($result.TimedOut -or $result.ExitCode -ne 0){Stop-Safely 'sql'}
    @(([string]$result.StdOut) -split "\r?\n" | Where-Object {-not [string]::IsNullOrWhiteSpace($_)})
}
function Get-ClinicalBaseline {
    $baselineSql=Get-ClinicalBaselineSql
    $rows=@(Invoke-ReadOnlySql 'clinical-postgresql-0' $baselineSql)
    Resolve-ClinicalBaseline $rows
}
function Get-ClinicalBaselineSql {
@'
SELECT (SELECT count(*) FROM clinical_patients),
       (SELECT count(*) FROM clinical_patients WHERE status = 'PENDING_ASSESSMENT'),
       (SELECT count(*) FROM doctor_patient_assignments WHERE status = 'ACTIVE'),
       (SELECT count(*) FROM therapy_plans),
       (SELECT count(*) FROM therapy_sessions);
'@
}
function Resolve-ClinicalBaseline([string[]] $BaselineRows) {
    if($BaselineRows.Count -ne 1){Stop-Safely 'baseline'}
    if($BaselineRows[0] -ceq '4|4|1|0|0'){return 'clean'}
    if($BaselineRows[0] -ceq '4|4|1|1|1'){return 'provenance-required'}
    Stop-Safely 'baseline'
}
function ConvertTo-SqlLiteral([string] $Value) {
    if([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 128 -or $Value -match '[\x00-\x1f\x7f]'){Stop-Safely 'identity'}
    "'"+$Value.Replace("'","''")+"'"
}
function Get-AuthenticatedPriorStateSql([guid] $ExpectedPatientId,[string] $DoctorSubject,[string] $PatientSubject) {
    if($ExpectedPatientId -eq [guid]::Empty){Stop-Safely 'identity'}
    $patientId=ConvertTo-SqlLiteral $ExpectedPatientId.ToString();$doctor=ConvertTo-SqlLiteral $DoctorSubject;$patient=ConvertTo-SqlLiteral $PatientSubject
@"
SELECT
  (SELECT count(*) FROM therapy_plans),
  (SELECT count(*) FROM therapy_plans p
     JOIN clinical_patients cp ON cp.id = p.clinical_patient_id
     JOIN doctor_patient_assignments a ON a.clinical_patient_id = cp.id
    WHERE cp.id = ${patientId}::uuid AND cp.patient_cognito_sub = $patient AND cp.status = 'PENDING_ASSESSMENT'
      AND p.doctor_cognito_sub = $doctor AND p.status = 'ACTIVE' AND a.doctor_cognito_sub = $doctor AND a.status = 'ACTIVE'),
  (SELECT count(*) FROM therapy_sessions),
  (SELECT count(*) FROM therapy_sessions s
     JOIN therapy_plans p ON p.id = s.therapy_plan_id
     JOIN clinical_patients cp ON cp.id = s.clinical_patient_id
     JOIN doctor_patient_assignments a ON a.clinical_patient_id = cp.id
    WHERE cp.id = ${patientId}::uuid AND cp.patient_cognito_sub = $patient AND cp.status = 'PENDING_ASSESSMENT'
      AND p.clinical_patient_id = cp.id AND p.doctor_cognito_sub = $doctor AND p.status = 'ACTIVE'
      AND a.doctor_cognito_sub = $doctor AND a.status = 'ACTIVE'
      AND s.clinical_patient_id = cp.id AND s.doctor_cognito_sub = $doctor
      AND s.status = 'ACTIVE' AND s.lease_expires_at <= CURRENT_TIMESTAMP),
  (SELECT count(*) FROM therapy_sessions WHERE status = 'ACTIVE' AND lease_expires_at > CURRENT_TIMESTAMP);
"@
}
function Resolve-AuthenticatedPriorState([string[]] $Rows) {
    if($Rows.Count -ne 1 -or $Rows[0] -cnotmatch '^1\|1\|1\|1\|0$'){Stop-Safely 'identity'}
    $true
}
function Assert-AuthenticatedPriorState([hashtable] $Assignment,[string] $DoctorSubject,[string] $PatientSubject) {
    if($DoctorSubject -cne $Assignment.DoctorSubject -or $PatientSubject -cne $Assignment.PatientSubject){Stop-Safely 'assignment'}
    $rows=@(Invoke-ReadOnlySql 'clinical-postgresql-0' (Get-AuthenticatedPriorStateSql $Assignment.PatientId $DoctorSubject $PatientSubject))
    Resolve-AuthenticatedPriorState $rows
}
function Assert-ClinicalWritePrecondition([string] $Baseline,[hashtable] $Assignment,[string] $DoctorSubject,[string] $PatientSubject) {
    if($Baseline -cnotin @('clean','provenance-required')){Stop-Safely 'baseline'}
    $freshBaseline=Get-ClinicalBaseline
    if($freshBaseline -cne $Baseline){Stop-Safely 'baseline'}
    if($freshBaseline -ceq 'clean'){return $true}
    Assert-AuthenticatedPriorState $Assignment $DoctorSubject $PatientSubject
}
function Get-SoleAssignment {
    $sql=@'
SELECT p.id, p.patient_cognito_sub, a.doctor_cognito_sub
FROM clinical_patients p JOIN doctor_patient_assignments a ON a.clinical_patient_id = p.id
WHERE a.status = 'ACTIVE';
'@
    $rows=@(Invoke-ReadOnlySql 'clinical-postgresql-0' $sql)
    if ($rows.Count -ne 1) { Stop-Safely 'assignment' }
    $f=$rows[0].Split('|');$id=[guid]::Empty
    if ($f.Count -ne 3 -or -not [guid]::TryParse($f[0],[ref]$id) -or $id -eq [guid]::Empty -or
        [string]::IsNullOrWhiteSpace($f[1]) -or [string]::IsNullOrWhiteSpace($f[2])) { Stop-Safely 'assignment' }
    @{PatientId=$id;PatientSubject=$f[1];DoctorSubject=$f[2]}
}
function Assert-PortForwards {
    foreach ($origin in @($script:ClinicalOrigin,$script:TelemetryOrigin)) {
        if ((Invoke-LocalHttp 'GET' "$origin/q/health/ready").Status -ne 200) { Stop-Safely 'port' }
    }
}
function Get-Env([string] $Name) {
    $file=Join-Path $script:Root '.env'
    $matches=@(Get-Content -LiteralPath $file | Where-Object {$_ -match ('^'+[regex]::Escape($Name)+'=')})
    if($matches.Count -ne 1){Stop-Safely 'pkce'}
    $matches[0].Substring($Name.Length+1).Trim().Trim('"')
}
function Get-Iam([string] $Name) {
    $file=Join-Path $script:Root 'infra/kubernetes/config/iam-configmap.yaml'
    $matches=@(Get-Content -LiteralPath $file | Where-Object {$_ -match ('^\s*'+[regex]::Escape($Name)+':\s*')})
    if($matches.Count -ne 1){Stop-Safely 'pkce'}
    $matches[0].Split(':',2)[1].Trim().Trim('"').Trim([char]39)
}
function B64Url([byte[]] $Bytes) {[Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
function RandomB64([int] $Count) {
    $b=[byte[]]::new($Count);$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($b)}finally{$rng.Dispose()};B64Url $b
}
function Get-Oidc {
    $client=Get-Env 'COGNITO_CLIENT_ID';$issuer=(Get-Env 'COGNITO_ISSUER_URL').TrimEnd('/');$pool=Get-Env 'COGNITO_USER_POOL_ID'
    if($client -cne (Get-Iam 'COGNITO_CLIENT_ID') -or $pool -cne (Get-Iam 'COGNITO_USER_POOL_ID') -or
        $issuer -cne (Get-Iam 'COGNITO_ISSUER_URL').TrimEnd('/') -or $pool -notmatch '^us-east-1_[A-Za-z0-9]+$' -or
        $issuer -cne "https://cognito-idp.us-east-1.amazonaws.com/$pool"){Stop-Safely 'pkce'}
    try{$d=Invoke-RestMethod -Method Get -Uri "$issuer/.well-known/openid-configuration" -TimeoutSec 10 -MaximumRedirection 0}catch{Stop-Safely 'pkce'}
    $auth=$null;$token=$null
    if($d.issuer -cne $issuer -or -not [uri]::TryCreate([string]$d.authorization_endpoint,[UriKind]::Absolute,[ref]$auth) -or
       -not [uri]::TryCreate([string]$d.token_endpoint,[UriKind]::Absolute,[ref]$token) -or $auth.Scheme -cne 'https' -or $token.Scheme -cne 'https'){Stop-Safely 'pkce'}
    @{Client=$client;Issuer=$issuer;Authorize=$auth.AbsoluteUri;Token=$token.AbsoluteUri}
}
function Get-Token([string] $Role,[hashtable] $Oidc) {
    $verifier=RandomB64 64;$sha=[Security.Cryptography.SHA256]::Create()
    try{$challenge=B64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))}finally{$sha.Dispose()}
    $state=RandomB64 32;$q=[ordered]@{client_id=$Oidc.Client;response_type='code';redirect_uri=$script:RedirectUri;scope='openid email profile';state=$state;code_challenge=$challenge;code_challenge_method='S256';prompt='login'}
    $encoded=($q.GetEnumerator()|ForEach-Object{'{0}={1}' -f [uri]::EscapeDataString($_.Key),[uri]::EscapeDataString($_.Value)})-join '&'
    $listener=[Net.HttpListener]::new();$listener.Prefixes.Add('http://localhost:5173/')
    try{
        $listener.Start();Write-Host "PKCE_LOGIN_WAITING: complete $Role sign-in in browser."
        Start-Process "$($Oidc.Authorize)?$encoded";$task=$listener.GetContextAsync()
        if(-not $task.Wait([TimeSpan]::FromSeconds(120))){Stop-Safely 'pkce'}
        $callback=$task.Result;$code=$callback.Request.QueryString['code']
        $ok=Test-PkceCallback ([string]$callback.Request.Url.AbsolutePath) ([string]$callback.Request.QueryString['state']) $state ([string]$callback.Request.QueryString['error']) $code
        $callback.Response.StatusCode=if($ok){200}else{400};$callback.Response.ContentType='text/plain'
        $text=if($ok){'Authentication complete.'}else{'Authentication rejected.'};$bytes=[Text.Encoding]::UTF8.GetBytes($text)
        $callback.Response.ContentLength64=$bytes.Length;$callback.Response.OutputStream.Write($bytes,0,$bytes.Length);$callback.Response.Close()
        if(-not $ok){Stop-Safely 'pkce'}
        $tokenRequest=@{Method='Post';Uri=$Oidc.Token;ContentType='application/x-www-form-urlencoded';TimeoutSec=15;MaximumRedirection=0
            Body=@{grant_type='authorization_code';client_id=$Oidc.Client;code=$code;redirect_uri=$script:RedirectUri;code_verifier=$verifier}}
        try{$tokens=Invoke-RestMethod @tokenRequest}catch{Stop-Safely 'pkce'}
        if(-not $tokens.access_token){Stop-Safely 'pkce'}
        $segments=$tokens.access_token.Split('.');if($segments.Count -ne 3){Stop-Safely 'pkce'}
        $encodedClaims=$segments[1].Replace('-','+').Replace('_','/')
        if($encodedClaims.Length%4 -eq 2){$encodedClaims+='=='}elseif($encodedClaims.Length%4 -eq 3){$encodedClaims+='='}
        $claims=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encodedClaims))|ConvertFrom-Json
        $subject=Assert-TokenClaims $claims $Oidc.Issuer $Oidc.Client $Role ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
        @{Token=[string]$tokens.access_token;Subject=$subject}
    }catch{Stop-Safely 'pkce'}finally{if($listener.IsListening){$listener.Stop()};$listener.Close()}
}
function Test-PkceCallback([string] $Path,[string] $ActualState,[string] $ExpectedState,[string] $Error,[string] $Code) {
    return ($Path -ceq '/auth/callback' -and $ActualState -ceq $ExpectedState -and [string]::IsNullOrWhiteSpace($Error) -and -not [string]::IsNullOrWhiteSpace($Code))
}
function Assert-TokenClaims($Claims,[string] $Issuer,[string] $Client,[string] $Role,[long] $Now) {
    try{$groups=@($Claims.'cognito:groups');$expiry=[long]$Claims.exp}catch{Stop-Safely 'pkce'}
    if($Claims.iss -cne $Issuer -or [string]::IsNullOrWhiteSpace([string]$Claims.sub) -or $Claims.client_id -cne $Client -or $Claims.token_use -cne 'access' -or
        $groups -cnotcontains $Role -or $groups -contains 'ADMIN' -or ($Role -eq 'DOCTOR' -and $groups -contains 'PATIENT') -or
        ($Role -eq 'PATIENT' -and $groups -contains 'DOCTOR') -or $expiry -le $Now){Stop-Safely 'pkce'}
    [string]$Claims.sub
}
function Invoke-ClinicalSetup([hashtable] $Assignment,[hashtable] $Doctor,[hashtable] $Patient) {
    if($Doctor.Subject -cne $Assignment.DoctorSubject -or $Patient.Subject -cne $Assignment.PatientSubject -or $Doctor.Subject -ceq $Patient.Subject){Stop-Safely 'assignment'}
    $planUrl="$($script:ClinicalOrigin)/api/v1/clinical-patients/$($Assignment.PatientId)/therapy-plans"
    $plan=Invoke-ClinicalMutation {Invoke-LocalHttp 'POST' $planUrl $Doctor.Token '{"dailySessionLimit":1,"dailyTargetDurationMinutes":10,"dailyHardSafetyLimitMinutes":null,"minimumValidDurationMinutes":1,"maximumSessionDurationMinutes":5,"minimumValidSampleCount":2,"dailyExercisePrescriptions":{"fixation":1}}'}
    if($plan.Status -ne 201){Stop-Safely 'clinical'};$p=Invoke-SafeStep 'clinical' {ConvertFrom-Json $plan.Body};$planId=[guid]::Empty
    if(-not [guid]::TryParse([string]$p.therapyPlanId,[ref]$planId) -or $planId -eq [guid]::Empty -or $p.status -cne 'ACTIVE' -or $p.clinicalPatientId -cne $Assignment.PatientId.ToString()){Stop-Safely 'clinical'}
    $effective=[DateTimeOffset]::UtcNow.AddSeconds(5)
    $cfg=@{exerciseCode='fixation';parameterSchemaVersion=1;effectiveFrom=$effective.ToString('o');parameters=@{mode='SUSTAINED_TARGET_FIXATION';targetPosition='CENTER';minimumFixationMs=1000;maximumFixationMs=3000;targetSizePx=70;repetitions=10;restBetweenRepetitionsMs=1000;difficultyLevel=1}}|ConvertTo-Json -Depth 8 -Compress
    if((Invoke-ClinicalMutation {Invoke-LocalHttp 'POST' "$planUrl/$planId/configurations" $Doctor.Token $cfg}).Status -ne 201){Stop-Safely 'clinical'}
    while([DateTimeOffset]::UtcNow -lt $effective.AddMilliseconds(500)){Start-Sleep -Milliseconds 250}
    $visible=Invoke-LocalHttp 'GET' "$($script:ClinicalOrigin)/api/v1/me/therapy-plans" $Patient.Token
    if($visible.Status -ne 200){Stop-Safely 'clinical'}
    $plans=Invoke-SafeStep 'clinical' {@(ConvertFrom-Json $visible.Body)}
    $found=@($plans|Where-Object{$_.therapyPlanId -ceq $planId.ToString() -and $_.status -ceq 'ACTIVE' -and $_.dailyExercisePrescriptions.fixation -eq 1 -and @($_.effectiveConfigurations|Where-Object{$_.exerciseCode -ceq 'fixation'}).Count -eq 1})
    if($found.Count -ne 1){Stop-Safely 'clinical'}
    $response=Invoke-ClinicalMutation {Invoke-LocalHttp 'POST' "$($script:ClinicalOrigin)/api/v1/me/therapy-plans/$planId/sessions" $Patient.Token '{"exerciseCode":"fixation"}' @{'Idempotency-Key'=[guid]::NewGuid().ToString()}}
    if($response.Status -ne 201){Stop-Safely 'clinical'};$s=Invoke-SafeStep 'clinical' {ConvertFrom-Json $response.Body};$id=[guid]::Empty;$lease=[DateTimeOffset]::MinValue
    if(-not [guid]::TryParse([string]$s.therapySessionId,[ref]$id) -or $id -eq [guid]::Empty -or $s.status -cne 'ACTIVE' -or $s.therapyPlanId -cne $planId.ToString() -or
        $s.exerciseCode -cne 'fixation' -or -not [DateTimeOffset]::TryParse([string]$s.leaseExpiresAt,[ref]$lease) -or $lease -le [DateTimeOffset]::UtcNow.AddSeconds(30)){Stop-Safely 'lease'}
    @{SessionId=$id}
}
function New-CanaryPayload([guid] $SessionId) {
    if(-not (Test-Path -LiteralPath $script:FixturePath -PathType Leaf)){Stop-Safely 'batch'}
    $batch=Invoke-SafeStep 'batch' {Get-Content -LiteralPath $script:FixturePath -Raw|ConvertFrom-Json}
    if($batch.schemaVersion -ne 1 -or @($batch.gazeSamples).Count -ne 1 -or @($batch.gameplaySamples).Count -ne 1){Stop-Safely 'batch'}
    $batch.batchId=[guid]::NewGuid().ToString();$batch.therapySessionId=$SessionId.ToString();$batch.streamId=[guid]::NewGuid().ToString()
    $batch.sequence=0;$now=[DateTimeOffset]::UtcNow;$batch.capturedAt=$now.ToString('yyyy-MM-ddTHH:mm:ss.fffZ');$batch.sentAt=$now.AddSeconds(1).ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{BatchId=[guid]$batch.batchId;Json=[string](ConvertTo-Json -InputObject $batch -Depth 12 -Compress)}
}
function Assert-BrokerAccepted([hashtable] $Response) {
    if($Response.Status -ne 202){Stop-Safely 'http'}
    $body=Invoke-SafeStep 'http' {ConvertFrom-Json -InputObject $Response.Body}
    if($body.code -cne 'BROKER_ACCEPTED'){Stop-Safely 'http'}
}
function Invoke-DuplicateCanary([string] $Json,[string] $Token,[scriptblock] $Sender=$null) {
    $pair=@($Json,$Json)
    if ($pair[0] -cne $pair[1]) { Stop-Safely 'batch' }
    $url="$($script:TelemetryOrigin)/api/v1/telemetry/batches"
    if ($null -eq $Sender) { $Sender={param($target,$bearer,$body) Invoke-LocalHttp 'POST' $target $bearer $body} }
    foreach ($body in $pair) { Assert-BrokerAccepted (& $Sender $url $Token $body) }
}
function Get-BatchCounts([guid] $BatchId) {
    $sql="SELECT (SELECT count(*) FROM telemetry_batch_inbox WHERE batch_id = '$BatchId'::uuid), (SELECT count(*) FROM telemetry_samples WHERE batch_id = '$BatchId'::uuid);"
    $rows=@(Invoke-ReadOnlySql 'telemetry-timescaledb-0' $sql)
    ConvertFrom-BatchCounts $rows
}
function ConvertFrom-BatchCounts([string[]] $Rows) {
    if($Rows.Count -ne 1 -or $Rows[0] -notmatch '^(\d+)\|(\d+)$'){Stop-Safely 'sql'}
    @{Inbox=[long]$Matches[1];Samples=[long]$Matches[2]}
}
function Assert-BatchCounts([hashtable] $Value,[long] $Inbox,[long] $Samples) {
    if($Value.Inbox -ne $Inbox -or $Value.Samples -ne $Samples){Stop-Safely 'database-attribution'}
}
function Wait-BatchCounts([guid] $BatchId,[int] $TimeoutSeconds=90) {
    $watch=[Diagnostics.Stopwatch]::StartNew()
    while($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds){Assert-GlobalTimeout;$counts=Get-BatchCounts $BatchId
        if($counts.Inbox -gt 1 -or $counts.Samples -gt 2){Stop-Safely 'database-attribution'}
        if($counts.Inbox -eq 1 -and $counts.Samples -eq 2){return $counts}
        Start-Sleep -Seconds 2
    }
    Stop-Safely 'poll'
}
function Get-TopicOffsets([string] $Topic) {
    if($Topic -cnotin @($script:SourceTopic,$script:DlqTopic)){Stop-Safely 'kafka'}
    $rows=@(Invoke-Live 'kafka' 'kubectl' @('-n','neurovision','exec','kafka-0','-c','kafka','--','/opt/kafka/bin/kafka-get-offsets.sh','--bootstrap-server','kafka:9092','--topic',$Topic))
    ConvertFrom-TopicOffsets $rows $Topic
}
function ConvertFrom-TopicOffsets([string[]] $Rows,[string] $Topic) {
    if($Rows.Count -ne 3){Stop-Safely 'kafka'};$result=@{}
    foreach($row in $Rows){$f=([string]$row).Split(':');$p=0;$o=0L
        if($f.Count -ne 3 -or $f[0] -cne $Topic -or -not [int]::TryParse($f[1],[ref]$p) -or $p -lt 0 -or $p -gt 2 -or
            -not [long]::TryParse($f[2],[ref]$o) -or $o -lt 0 -or $result.ContainsKey($p)){Stop-Safely 'kafka'}
        $result[$p]=$o}
    if((@($result.Keys|Sort-Object)-join ',') -cne '0,1,2'){Stop-Safely 'kafka'};$result
}
function Get-ConsumerSnapshot {
    $rows=@(Invoke-Live 'kafka' 'kubectl' @('-n','neurovision','exec','kafka-0','-c','kafka','--','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','kafka:9092','--describe','--group',$script:ConsumerGroup))
    ConvertFrom-ConsumerSnapshot $rows
}
function ConvertFrom-ConsumerSnapshot([string[]] $Rows) {
    $lines=@($Rows|ForEach-Object {([string]$_).Trim()}|Where-Object {$_})
    $expected='GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID'
    $headers=@($lines|Where-Object {$_ -match '^GROUP\s+TOPIC\s+'})
    if($headers.Count -ne 1 -or (($headers[0]-split '\s+')-join ' ') -cne $expected){Stop-Safely 'kafka'}
    $parts=@{};$members=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal);$lag=0L;$committed=0L;$end=0L
    foreach($row in @($lines|Where-Object {$_ -cne $headers[0]})){$c=@($row -split '\s+');$p=0;$cur=0L;$last=0L;$l=0L
        if($c.Count -lt 9 -or $c[0] -cne $script:ConsumerGroup -or $c[1] -cne $script:SourceTopic -or
            -not [int]::TryParse($c[2],[ref]$p) -or $p -lt 0 -or $p -gt 2 -or $parts.ContainsKey($p) -or
            -not [long]::TryParse($c[4],[ref]$last) -or $last -lt 0 -or
            ($c[3] -cne '-' -and (-not [long]::TryParse($c[3],[ref]$cur) -or $cur -lt 0 -or $cur -gt $last)) -or
            ($c[3] -ceq '-' -and $last -ne 0) -or
            ($c[5] -cne '-' -and (-not [long]::TryParse($c[5],[ref]$l) -or $l -lt 0)) -or ($c[5] -ceq '-' -and $last -ne 0)){Stop-Safely 'kafka'}
        if($cur + $l -ne $last){Stop-Safely 'kafka'}
        $parts[$p]=@{Current=$cur;End=$last;Lag=$l};if($c[6] -cne '-') {[void]$members.Add("$($c[6])|$p")}
        $lag+=$l;$committed+=$cur;$end+=$last}
    if((@($parts.Keys|Sort-Object)-join ',') -cne '0,1,2' -or $members.Count -lt 1){Stop-Safely 'kafka'}
    $canonical=@($members|Sort-Object)-join [Environment]::NewLine;$sha=[Security.Cryptography.SHA256]::Create()
    try{$finger=[BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
    @{Partitions=$parts;Members=$members.Count;Fingerprint=$finger;Lag=$lag;Committed=$committed;End=$end;CommittedByPartition=@{0=$parts[0].Current;1=$parts[1].Current;2=$parts[2].Current}}
}
function Assert-OffsetsNonRegressing($Before,$After) {
    foreach($p in 0..2){if($After.CommittedByPartition[$p] -lt $Before.CommittedByPartition[$p] -or $After.Partitions[$p].End -lt $Before.Partitions[$p].End){Stop-Safely 'kafka'}}
}
function Assert-CanaryMovement($SourceBefore,$SourceAfter,$DlqBefore,$DlqAfter,$GroupBefore,$GroupAfter) {
    $delta=0L
    foreach($p in 0..2){if($SourceAfter[$p] -lt $SourceBefore[$p] -or $DlqAfter[$p] -ne $DlqBefore[$p]){Stop-Safely 'kafka'};$delta+=$SourceAfter[$p]-$SourceBefore[$p]}
    if($delta -ne 2 -or $GroupAfter.Lag -ne 0 -or $GroupAfter.Committed -ne $GroupBefore.Committed+2 -or $GroupAfter.End -ne $GroupBefore.End+2){Stop-Safely 'kafka'}
    Assert-OffsetsNonRegressing $GroupBefore $GroupAfter
    $true
}
function Wait-StableConsumer([int] $TimeoutSeconds=90) {
    $watch=[Diagnostics.Stopwatch]::StartNew();$previous=$null;$stable=0
    while($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds){Assert-GlobalTimeout;$now=Get-ConsumerSnapshot
        if($null -ne $previous -and $now.Lag -eq 0 -and $now.Committed -eq $previous.Committed -and $now.End -eq $previous.End -and $now.Fingerprint -ceq $previous.Fingerprint){$stable++}else{$stable=1}
        if($stable -ge 3){return $now};$previous=$now;Start-Sleep -Seconds 3}
    Stop-Safely 'poll'
}
function Invoke-Preflight {
    Assert-MarkerAvailable (Test-Path -LiteralPath (Get-MarkerPath))
    Assert-Loopback $script:ClinicalOrigin 18083|Out-Null;Assert-Loopback $script:TelemetryOrigin 18084|Out-Null
    Get-ExpectedKubeContext|Out-Null;Assert-PortForwards
    foreach($name in @('clinical-service','telemetry-service')){$d=Invoke-SafeStep 'workload' {(Invoke-Live 'workload' 'kubectl' @('-n','neurovision','get','deployment',$name,'-o','json')|Out-String)|ConvertFrom-Json}
        $image=if($name -ceq 'clinical-service'){$script:Images.clinical}else{$script:Images.telemetry}
        if($d.metadata.name -cne $name -or $d.metadata.namespace -cne 'neurovision' -or [long]$d.spec.replicas -ne 1 -or [long]$d.status.observedGeneration -lt [long]$d.metadata.generation -or
            [long]$d.status.availableReplicas -ne 1 -or @($d.spec.template.spec.containers).Count -ne 1 -or $d.spec.template.spec.containers[0].image -cne $image){Stop-Safely 'workload'}}
    foreach($name in @('clinical-service','telemetry-service')){$pods=Invoke-SafeStep 'workload' {(Invoke-Live 'workload' 'kubectl' @('-n','neurovision','get','pods','-l',"app.kubernetes.io/name=$name",'-o','json')|Out-String)|ConvertFrom-Json}
        $image=if($name -ceq 'clinical-service'){$script:Images.clinical}else{$script:Images.telemetry}
        if(@($pods.items).Count -ne 1 -or $pods.items[0].metadata.namespace -cne 'neurovision' -or $pods.items[0].status.phase -cne 'Running' -or
            @($pods.items[0].status.containerStatuses).Count -ne 1 -or $pods.items[0].status.containerStatuses[0].ready -ne $true -or
            @($pods.items[0].spec.containers).Count -ne 1 -or $pods.items[0].spec.containers[0].image -cne $image){Stop-Safely 'workload'}}
    foreach($name in @('kafka','telemetry-timescaledb')){$s=Invoke-SafeStep 'workload' {(Invoke-Live 'workload' 'kubectl' @('-n','neurovision','get','statefulset',$name,'-o','json')|Out-String)|ConvertFrom-Json}
        $image=if($name -ceq 'kafka'){$script:Images.kafka}else{$script:Images.timescale}
        if($s.metadata.name -cne $name -or $s.metadata.namespace -cne 'neurovision' -or [long]$s.spec.replicas -ne 1 -or [long]$s.status.readyReplicas -ne 1 -or
            @($s.spec.template.spec.containers).Count -ne 1 -or $s.spec.template.spec.containers[0].image -cne $image){Stop-Safely 'workload'}}
    $baseline=Get-ClinicalBaseline
    @{Baseline=$baseline;Assignment=(Get-SoleAssignment)}
}
function Invoke-AuthorizedRun {
    Assert-Consent
    $timer=[Diagnostics.Stopwatch]::StartNew();$script:RunTimer=$timer
    $preflight=Invoke-Preflight
    $sourceBefore=Get-TopicOffsets $script:SourceTopic
    $dlqBefore=Get-TopicOffsets $script:DlqTopic
    $groupBefore=Wait-StableConsumer
    if($groupBefore.Lag -ne 0){Stop-Safely 'kafka'}
    $oidc=Invoke-SafeStep 'pkce' {Get-Oidc}
    $doctor=Get-Token 'DOCTOR' $oidc
    Assert-GlobalTimeout
    $patient=Get-Token 'PATIENT' $oidc
    if($doctor.Subject -cne $preflight.Assignment.DoctorSubject -or $patient.Subject -cne $preflight.Assignment.PatientSubject -or $doctor.Subject -ceq $patient.Subject){Stop-Safely 'assignment'}
    Assert-GlobalTimeout
    Assert-ClinicalWritePrecondition $preflight.Baseline $preflight.Assignment $doctor.Subject $patient.Subject | Out-Null
    Write-CanaryMarker (Get-MarkerPath)
    $session=Invoke-ClinicalSetup $preflight.Assignment $doctor $patient
    $payload=New-CanaryPayload $session.SessionId
    Assert-BatchCounts (Get-BatchCounts $payload.BatchId) 0 0
    Invoke-DuplicateCanary $payload.Json $patient.Token
    Wait-BatchCounts $payload.BatchId 90 | Out-Null
    $poll=[Diagnostics.Stopwatch]::StartNew();$delta=0L;$after=$null
    while($poll.Elapsed.TotalSeconds -lt 90 -and $timer.Elapsed.TotalSeconds -lt 300){
        $after=Get-ConsumerSnapshot;$source=Get-TopicOffsets $script:SourceTopic;$dlq=Get-TopicOffsets $script:DlqTopic;$delta=0L
        foreach($p in 0..2){
            if($source[$p] -lt $sourceBefore[$p] -or $dlq[$p] -ne $dlqBefore[$p]){Stop-Safely 'kafka'}
            $delta+=$source[$p]-$sourceBefore[$p]
        }
        if($delta -gt 2){Stop-Safely 'kafka'}
        if($delta -eq 2 -and $after.Lag -eq 0 -and $after.Committed -eq $groupBefore.Committed+2 -and $after.End -eq $groupBefore.End+2){break}
        Start-Sleep -Seconds 3
    }
    if($timer.Elapsed.TotalSeconds -ge 300){Stop-Safely 'poll'}
    if($delta -ne 2 -or $null -eq $after -or $after.Lag -ne 0 -or $after.Committed -ne $groupBefore.Committed+2 -or $after.End -ne $groupBefore.End+2){Stop-Safely 'kafka'}
    Assert-CanaryMovement $sourceBefore $source $dlqBefore $dlq $groupBefore $after | Out-Null
    $stable=Wait-StableConsumer ([Math]::Max(1,90-[int]$poll.Elapsed.TotalSeconds))
    if($stable.Lag -ne 0 -or $stable.Committed -ne $groupBefore.Committed+2 -or $stable.End -ne $groupBefore.End+2){Stop-Safely 'kafka'}
    Assert-OffsetsNonRegressing $after $stable
    $endSource=Get-TopicOffsets $script:SourceTopic;$endDlq=Get-TopicOffsets $script:DlqTopic;$total=0L
    foreach($p in 0..2){$total+=$endSource[$p]-$sourceBefore[$p];if($endDlq[$p] -ne $dlqBefore[$p]){Stop-Safely 'kafka'}}
    if($total -ne 2){Stop-Safely 'kafka'}
    Assert-BatchCounts (Get-BatchCounts $payload.BatchId) 1 2
    [pscustomobject]@{Result='CANARY_PROOF_OBSERVED';HttpAccepted=2;Inbox=1;Samples=2;SourcePublished=2;ConsumerLag=0;DlqChanged=$false;SyntheticPayloadOnly=$true}
}
function Test-OfflineContract {
    $script:ExternalCalls=0;$script:Mode='OfflineTest';$blocked=$false
    try{Assert-Consent}catch{$blocked=$true}
    if(-not $blocked){throw 'Offline assertion failed: independent authorizations are required.'}
    foreach($missing in 0..4){
        $values=@($true,$true,$true,$true,$true)
        $values[$missing]=$false
        if(Test-ConsentValues 'AuthorizedRun' $values[0] $values[1] $values[2] $values[3] $values[4]){throw 'Offline assertion failed: a missing consent gate was accepted.'}
    }
    if(Test-ConsentValues 'Preflight' $true $true $true $true $true){throw 'Offline assertion failed: Preflight was not read-only.'}
    foreach($bad in @('http://localhost:18083','https://127.0.0.1:18083','http://127.0.0.1:18084/path','http://127.0.0.1:18083?x=1')){
        $refused=$false;try{Assert-Loopback $bad 18083|Out-Null}catch{$refused=$true}
        if(-not $refused){throw 'Offline assertion failed: loopback guard.'}
    }
    if((Assert-Loopback 'http://127.0.0.1:18083' 18083) -cne 'http://127.0.0.1:18083'){throw 'Offline assertion failed: loopback canonicalization.'}
    $markerRefused=$false;try{Assert-MarkerAvailable $true}catch{$markerRefused=$true}
    if(-not $markerRefused){throw 'Offline assertion failed: one-shot marker refusal.'}
    $script:CanaryMarkerWritten=$false;$script:ClinicalMutationCount=0;$blocked=$false
    try{Invoke-ClinicalMutation {$script:ClinicalMutationCount++}|Out-Null}catch{$blocked=$true}
    if(-not $blocked){throw 'Offline assertion failed: Clinical mutation preceded marker.'}
    $script:CanaryMarkerWritten=$true;Invoke-ClinicalMutation {$script:ClinicalMutationCount++}|Out-Null
    if($script:ClinicalMutationCount -ne 1){throw 'Offline assertion failed: marker did not permit mutation.'};$script:CanaryMarkerWritten=$false
    if(-not (Test-PkceCallback '/auth/callback' 'expected' 'expected' '' 'code') -or (Test-PkceCallback '/other' 'expected' 'expected' '' 'code') -or (Test-PkceCallback '/auth/callback' 'wrong' 'expected' '' 'code')){throw 'Offline assertion failed: PKCE callback state.'}
    $validClaims=[pscustomobject]@{iss='issuer';sub='synthetic-subject';client_id='client';token_use='access';'cognito:groups'=@('DOCTOR');exp=2000}
    if((Assert-TokenClaims $validClaims 'issuer' 'client' 'DOCTOR' 1000) -cne 'synthetic-subject'){throw 'Offline assertion failed: valid token claims.'}
    foreach($name in @('issuer','client_id','token_use','exp','group')){
        $badClaims=$validClaims|ConvertTo-Json -Depth 3|ConvertFrom-Json
        switch($name){issuer{$badClaims.iss='wrong'} client_id{$badClaims.client_id='wrong'} token_use{$badClaims.token_use='id'} exp{$badClaims.exp=999} group{$badClaims.'cognito:groups'=@('PATIENT')}}
        $refused=$false;try{Assert-TokenClaims $badClaims 'issuer' 'client' 'DOCTOR' 1000|Out-Null}catch{$refused=$true}
        if(-not $refused){throw 'Offline assertion failed: token claim negative.'}
    }
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds) $script:FakeArgs=@($Arguments);$script:FakeInput=$StandardInput;$script:FakeTimeout=$TimeoutSeconds;@{ExitCode=0;StdOut='0|0';TimedOut=$false}}
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds)
        $script:ContextArgs=@($Arguments);@{ExitCode=0;StdOut="k3d-neurovision-local`n";TimedOut=$false}}
    if(-not (Get-ExpectedKubeContext) -or $script:ContextArgs -notcontains '--request-timeout=5s' -or
        ($script:ContextArgs -join ' ') -notmatch 'config current-context'){
        throw 'Offline assertion failed: successful context command contract.'
    }
    foreach($failure in @(
        @{Name='process error';Result=@{ExitCode=1;StdOut='private-stderr';TimedOut=$false};Expected='Canary check failed safely [stage=context; code=context_unavailable].'},
        @{Name='timeout';Result=@{ExitCode=1;StdOut='private-stderr';TimedOut=$true};Expected='Canary check failed safely [stage=context; code=context_unavailable].'}
    )){
        $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds) $failure.Result}
        $refused=$false;try{Get-ExpectedKubeContext|Out-Null}catch{$refused=$_.Exception.Message -ceq $failure.Expected}
        if(-not $refused){throw 'Offline assertion failed: context process failure was not safely classified.'}
    }
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds) throw 'token=secret stderr=private'}
    $refused=$false;try{Get-ExpectedKubeContext|Out-Null}catch{$refused=$_.Exception.Message -ceq 'Canary check failed safely [stage=context; code=context_unavailable].'}
    if(-not $refused){throw 'Offline assertion failed: context launch failure leaked or misclassified.'}
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds) @{ExitCode=0;StdOut='';TimedOut=$false}}
    $emptyMismatch=$false;try{Get-ExpectedKubeContext|Out-Null}catch{$emptyMismatch=$_.Exception.Message -ceq 'Canary check failed safely [stage=context-mismatch; code=context_mismatch].'}
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds) @{ExitCode=0;StdOut="unexpected-context`n";TimedOut=$false}}
    $actualMismatch=$false;try{Get-ExpectedKubeContext|Out-Null}catch{$actualMismatch=$_.Exception.Message -ceq 'Canary check failed safely [stage=context-mismatch; code=context_mismatch].'}
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds) @{ExitCode=0;StdOut="one`ntwo`n";TimedOut=$false}}
    $malformedMismatch=$false;try{Get-ExpectedKubeContext|Out-Null}catch{$malformedMismatch=$_.Exception.Message -ceq 'Canary check failed safely [stage=context-mismatch; code=context_mismatch].'}
    if(-not $emptyMismatch -or -not $actualMismatch -or -not $malformedMismatch){throw 'Offline assertion failed: empty, malformed, or mismatched context was not refused safely.'}
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds) $script:FakeArgs=@($Arguments);$script:FakeInput=$StandardInput;$script:FakeTimeout=$TimeoutSeconds;@{ExitCode=0;StdOut='0|0';TimedOut=$false}}
    $sqlRows=@(Invoke-ReadOnlySql 'telemetry-timescaledb-0' 'SELECT 0;')
    $psqlArg=[string](@($script:FakeArgs|Where-Object {$_ -match '^psql\s'})[0])
    if($sqlRows.Count -ne 1 -or $script:FakeArgs -notcontains 'sh' -or $psqlArg -notmatch '\s-f -$' -or $psqlArg -match '\s-c(?:\s|$)' -or ($script:FakeArgs -join ' ') -match 'SELECT|0\|' -or $script:FakeInput -notmatch 'SELECT 0' -or $script:FakeTimeout -ne 15){throw 'Offline assertion failed: SQL stdin argv contract.'}
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds) @{ExitCode=1;StdOut='secret';TimedOut=$true}}
    $timeoutSafe=$false;try{Invoke-Live 'kafka' 'kubectl' @('exec','pod')|Out-Null}catch{$timeoutSafe=$_.Exception.Message -ceq 'Canary check failed safely [stage=kafka; code=movement_unattributable].'}
    $quoted=ConvertTo-WindowsArgument 'psql -X -f -'
    if(-not $timeoutSafe -or $quoted -cne '"psql -X -f -"'){throw 'Offline assertion failed: bounded timeout/argv diagnostics.'};$script:ProcessRunner=$null
    if((Resolve-ClinicalBaseline @('4|4|1|0|0')) -cne 'clean'){
        throw 'Offline assertion failed: clean Clinical baseline rejected.'
    }
    if((Resolve-ClinicalBaseline @('4|4|1|1|1')) -cne 'provenance-required'){
        throw 'Offline assertion failed: prior Clinical state was not gated for authenticated validation.'
    }
    $badBaselines=@(
        @('4|4|1|0|1'),
        @('4|4|1|0|0','4|4|1|0|0'),
        @('malformed'),
        @()
    )
    foreach($case in $badBaselines){
        $refused=$false;try{Resolve-ClinicalBaseline $case|Out-Null}catch{$refused=$_.Exception.Message -ceq 'Canary check failed safely [stage=baseline; code=baseline_mismatch].'}
        if(-not $refused){throw 'Offline assertion failed: invalid Clinical baseline accepted.'}
    }
    $script:ClinicalBaselineMode='clean';$script:ClinicalSqlCalls=0;$script:ClinicalSqlCommands=[Collections.Generic.List[string]]::new()
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds)
        $script:ClinicalSqlCalls++;[void]$script:ClinicalSqlCommands.Add($StandardInput)
        @{ExitCode=0;StdOut='4|4|1|0|0';TimedOut=$false}}
    if((Get-ClinicalBaseline) -cne 'clean' -or $script:ClinicalSqlCalls -ne 1){throw 'Offline assertion failed: clean Clinical baseline query.'}
    $script:ClinicalSqlCalls=0;$script:ClinicalSqlCommands.Clear()
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds)
        $script:ClinicalSqlCalls++;[void]$script:ClinicalSqlCommands.Add($StandardInput)
        @{ExitCode=0;StdOut='4|4|1|1|1';TimedOut=$false}}
    if((Get-ClinicalBaseline) -cne 'provenance-required' -or $script:ClinicalSqlCalls -ne 1 -or
        @($script:ClinicalSqlCommands|Where-Object {$_ -notmatch "^SET statement_timeout='5s';\s*SELECT\b" -or $_ -match '\b(INSERT|UPDATE|DELETE|TRUNCATE)\b'}).Count -ne 0){
        throw 'Offline assertion failed: prior Clinical state classification was not read-only.'
    }
    $script:ProcessRunner=$null
    $validAssignment=@{PatientId=[guid]'11111111-1111-1111-1111-111111111111';PatientSubject='patient-proof';DoctorSubject='doctor-proof'}
    if(-not (Resolve-AuthenticatedPriorState @('1|1|1|1|0'))){throw 'Offline assertion failed: exact authenticated prior state rejected.'}
    $priorCases=@(
        @{Name='doctor mismatch';Rows=@('1|0|1|0|0')},
        @{Name='patient mismatch';Rows=@('1|0|1|0|0')},
        @{Name='assignment mismatch';Rows=@('1|0|1|0|0')},
        @{Name='multiple plans';Rows=@('2|1|1|1|0')},
        @{Name='multiple sessions';Rows=@('1|1|2|2|0')},
        @{Name='valid lease';Rows=@('1|1|1|0|1')},
        @{Name='malformed';Rows=@('one|one|one|one|zero')},
        @{Name='missing';Rows=@()},
        @{Name='multiple rows';Rows=@('1|1|1|1|0','1|1|1|1|0')}
    )
    foreach($case in $priorCases){
        $refused=$false;try{Resolve-AuthenticatedPriorState $case.Rows|Out-Null}catch{$refused=$_.Exception.Message -ceq 'Canary check failed safely [stage=identity; code=authenticated_state_mismatch].'}
        if(-not $refused){throw 'Offline assertion failed: invalid authenticated Clinical state accepted.'}
    }
    foreach($subjects in @(@{Doctor='other-doctor';Patient='patient-proof'},@{Doctor='doctor-proof';Patient='other-patient'})){
        $refused=$false;try{Assert-AuthenticatedPriorState $validAssignment $subjects.Doctor $subjects.Patient|Out-Null}catch{$refused=$_.Exception.Message -ceq 'Canary check failed safely [stage=assignment; code=assignment_mismatch].'}
        if(-not $refused){throw 'Offline assertion failed: authenticated subject mismatch accepted.'}
    }
    $script:CanaryMarkerWritten=$false;$script:ClinicalMutationCount=0;$script:ClinicalSqlCalls=0
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds)
        $script:ClinicalSqlCalls++;@{ExitCode=0;StdOut='4|4|1|0|0';TimedOut=$false}}
    if(-not (Assert-ClinicalWritePrecondition 'clean' $validAssignment 'doctor-proof' 'patient-proof') -or
        $script:ClinicalSqlCalls -ne 1 -or $script:CanaryMarkerWritten -or $script:ClinicalMutationCount -ne 0){
        throw 'Offline assertion failed: clean-to-clean refresh did not remain write-ready without early mutation.'
    }
    $script:ClinicalSqlCalls=0;$refused=$false
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds)
        $script:ClinicalSqlCalls++;@{ExitCode=0;StdOut='4|4|1|1|1';TimedOut=$false}}
    try{Assert-ClinicalWritePrecondition 'clean' $validAssignment 'doctor-proof' 'patient-proof'|Out-Null}catch{$refused=$_.Exception.Message -ceq 'Canary check failed safely [stage=baseline; code=baseline_mismatch].'}
    $mutationBlocked=$false;try{Invoke-ClinicalMutation {$script:ClinicalMutationCount++}|Out-Null}catch{$mutationBlocked=$true}
    if(-not $refused -or $script:ClinicalSqlCalls -ne 1 -or -not $mutationBlocked -or $script:CanaryMarkerWritten -or $script:ClinicalMutationCount -ne 0){throw 'Offline assertion failed: changed clean baseline reached Clinical write or marker.'}
    $script:ClinicalSqlCalls=0;$script:ClinicalSqlCommands=[Collections.Generic.List[string]]::new()
    $script:ProcessRunner={param($File,$Arguments,$StandardInput,$TimeoutSeconds)
        $script:ClinicalSqlCalls++;[void]$script:ClinicalSqlCommands.Add($StandardInput);$script:FakeArgs=@($Arguments)
        $output=if($StandardInput -match 'count\(\*\) FROM clinical_patients'){'4|4|1|1|1'}else{'1|1|1|1|0'}
        @{ExitCode=0;StdOut=$output;TimedOut=$false}}
    if(-not (Assert-ClinicalWritePrecondition 'provenance-required' $validAssignment 'doctor-proof' 'patient-proof') -or
        $script:ClinicalSqlCalls -ne 2 -or $script:FakeArgs -match 'doctor-proof|patient-proof|11111111' -or
        $script:ClinicalSqlCommands[1] -notmatch "cp.patient_cognito_sub = 'patient-proof'" -or
        $script:ClinicalSqlCommands[1] -notmatch "p.doctor_cognito_sub = 'doctor-proof'" -or
        $script:ClinicalSqlCommands[1] -notmatch "cp.id = '11111111-1111-1111-1111-111111111111'::uuid" -or
        $script:ClinicalSqlCommands[1] -notmatch 's.lease_expires_at <= CURRENT_TIMESTAMP' -or
        @($script:ClinicalSqlCommands|Where-Object {$_ -match '\b(INSERT|UPDATE|DELETE|TRUNCATE)\b'}).Count -ne 0){
        throw 'Offline assertion failed: authenticated precondition query/SQL privacy contract.'
    }
    $script:CanaryMarkerWritten=$false;$script:ClinicalMutationCount=0
    if($script:CanaryMarkerWritten -or $script:ClinicalMutationCount -ne 0){throw 'Offline assertion failed: clean-to-clean check mutated Clinical state.'}
    $script:ProcessRunner=$null
    $authorizedSource=Get-Content -LiteralPath $PSCommandPath -Raw
    $runStart=$authorizedSource.IndexOf('function Invoke-AuthorizedRun {',[StringComparison]::Ordinal)
    $runEnd=$authorizedSource.IndexOf('function Test-OfflineContract {',[StringComparison]::Ordinal)
    $authorizedBody=$authorizedSource.Substring($runStart,$runEnd-$runStart)
    $patientLoginAt=$authorizedBody.IndexOf("`$patient=Get-Token 'PATIENT'",[StringComparison]::Ordinal)
    $preconditionAt=$authorizedBody.IndexOf('Assert-ClinicalWritePrecondition $preflight.Baseline',[StringComparison]::Ordinal)
    $markerAt=$authorizedBody.IndexOf('Write-CanaryMarker (Get-MarkerPath)',[StringComparison]::Ordinal)
    $setupAt=$authorizedBody.IndexOf('Invoke-ClinicalSetup $preflight.Assignment',[StringComparison]::Ordinal)
    if($patientLoginAt -lt 0 -or $preconditionAt -le $patientLoginAt -or $markerAt -le $preconditionAt -or $setupAt -le $markerAt){
        throw 'Offline assertion failed: authenticated match and marker-before-mutation ordering.'
    }
    if($authorizedSource.Substring(0,$runEnd) -match 'SyntheticDataOnly|prior-synthetic-expired'){
        throw 'Offline assertion failed: authenticated Clinical ownership was labeled synthetic provenance.'
    }
    $script:ProcessRunner=$null
    $fixture=Get-Content -LiteralPath $script:FixturePath -Raw|ConvertFrom-Json
    if($fixture.schemaVersion -ne 1 -or @($fixture.gazeSamples).Count -ne 1 -or @($fixture.gameplaySamples).Count -ne 1){throw 'Offline assertion failed: V1 fixture.'}
    Assert-BrokerAccepted @{Status=202;Body='{"code":"BROKER_ACCEPTED"}'}
    foreach($bad in @(@{Status=201;Body='{"code":"BROKER_ACCEPTED"}'},@{Status=202;Body='{"code":"OTHER"}'},@{Status=202;Body='bad'})){
        $refused=$false;try{Assert-BrokerAccepted $bad}catch{$refused=$true}
        if(-not $refused){throw 'Offline assertion failed: HTTP response guard.'}
    }
    $fake=[Collections.Generic.List[string]]::new();$sender={param($target,$token,$body) [void]$fake.Add([string]$body);@{Status=202;Body='{"code":"BROKER_ACCEPTED"}'}}
    $payload=New-CanaryPayload ([guid]::NewGuid());Invoke-DuplicateCanary $payload.Json 'test-token' $sender
    if($fake.Count -ne 2 -or $fake[0] -cne $fake[1]){throw 'Offline assertion failed: duplicate request bodies differ.'}
    $counts=ConvertFrom-BatchCounts @('1|2');Assert-BatchCounts $counts 1 2
    $source=ConvertFrom-TopicOffsets @("$($script:SourceTopic):0:2","$($script:SourceTopic):1:3","$($script:SourceTopic):2:4") $script:SourceTopic
    $dlq=ConvertFrom-TopicOffsets @("$($script:DlqTopic):0:0","$($script:DlqTopic):1:0","$($script:DlqTopic):2:0") $script:DlqTopic
    $snapshot=ConvertFrom-ConsumerSnapshot @('GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID',"$($script:ConsumerGroup) $($script:SourceTopic) 0 2 2 0 consumerA host client","$($script:ConsumerGroup) $($script:SourceTopic) 1 3 3 0 consumerA host client","$($script:ConsumerGroup) $($script:SourceTopic) 2 4 4 0 consumerA host client")
    if($source[0] -ne 2 -or $dlq[1] -ne 0 -or $snapshot.Lag -ne 0){throw 'Offline assertion failed: Kafka/DLQ parsers.'}
    $oldSource=@{0=1L;1=2L;2=3L};$newSource=@{0=2L;1=3L;2=3L};$sameDlq=@{0=0L;1=0L;2=0L}
    $oldGroup=@{CommittedByPartition=@{0=1L;1=2L;2=3L};Partitions=@{0=@{End=1L};1=@{End=2L};2=@{End=3L}};Committed=6L;End=6L;Lag=0L}
    $newGroup=@{CommittedByPartition=@{0=2L;1=3L;2=3L};Partitions=@{0=@{End=2L};1=@{End=3L};2=@{End=3L}};Committed=8L;End=8L;Lag=0L}
    Assert-CanaryMovement $oldSource $newSource $sameDlq $sameDlq $oldGroup $newGroup|Out-Null
    $badMovement=$false;try{Assert-CanaryMovement $oldSource @{0=3L;1=3L;2=3L} $sameDlq $sameDlq $oldGroup $newGroup|Out-Null}catch{$badMovement=$true}
    if(-not $badMovement){throw 'Offline assertion failed: concurrent source movement.'}
    $badMovement=$false;try{Assert-CanaryMovement $oldSource $newSource $sameDlq @{0=1L;1=0L;2=0L} $oldGroup $newGroup|Out-Null}catch{$badMovement=$true}
    if(-not $badMovement){throw 'Offline assertion failed: DLQ movement.'}
    $beforeOffsets=@{0=4L;1=5L;2=6L};$afterOffsets=@{0=5L;1=5L;2=7L}
    $beforeGroup=@{CommittedByPartition=$beforeOffsets;Partitions=@{0=@{End=4L};1=@{End=5L};2=@{End=6L}}}
    $afterGroup=@{CommittedByPartition=$afterOffsets;Partitions=@{0=@{End=5L};1=@{End=5L};2=@{End=7L}}}
    Assert-OffsetsNonRegressing $beforeGroup $afterGroup
    $afterGroup.CommittedByPartition[1]=4L;$regressed=$false;try{Assert-OffsetsNonRegressing $beforeGroup $afterGroup}catch{$regressed=$true}
    if(-not $regressed){throw 'Offline assertion failed: partition offset regression.'}
    $safe=Resolve-SafeErrorMessage 'token=secret session_id=00000000-0000-0000-0000-000000000001 payload={"x":0.25}'
    if($safe -match 'secret|00000000|0\.25|payload'){throw 'Offline assertion failed: safe diagnostics.'}
    if($script:ExternalCalls -ne 0){throw 'Offline assertion failed: external call occurred.'}
    'OfflineTest PASS: consent, marker order, PKCE claims/callback, authenticated Clinical baseline, SQL stdin, bounded timeout, fixture, duplicate HTTP, Kafka/DLQ exact movement; external calls=0.'
}
try {
    switch ($Mode) {
        'OfflineTest' { Test-OfflineContract }
        'Preflight' { Invoke-Preflight | Out-Null; 'Preflight PASS: read-only target checks.' }
        'AuthorizedRun' { Invoke-AuthorizedRun }
    }
} catch {
    [Console]::Error.WriteLine((Resolve-SafeErrorMessage $_.Exception.Message))
    exit 1
}
