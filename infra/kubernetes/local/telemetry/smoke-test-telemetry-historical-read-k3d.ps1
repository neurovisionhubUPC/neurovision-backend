[CmdletBinding()]
param(
    [switch] $PreflightOnly,
    [switch] $AuthorizedSyntheticRun,
    [string] $ClinicalBaseUrl = 'http://127.0.0.1:18083',
    [string] $TelemetryBaseUrl = 'http://127.0.0.1:18084'
)

$ErrorActionPreference = 'Stop'
$script:stage = 'preflight'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$envPath = Join-Path $root '.env'
$fixturePath = Join-Path $root 'telemetry-service/src/test/resources/fixtures/telemetry/v1/dual-typed-batch.json'
$redirectUri = 'http://localhost:5173/auth/callback'
$listenerPrefix = 'http://localhost:5173/'

function Assert-Origin([string] $Value, [int] $ExpectedPort) {
    $uri = $null
    if (-not [uri]::TryCreate($Value, [UriKind]::Absolute, [ref] $uri) -or
            $uri.Scheme -ne 'http' -or $uri.Host -notin @('localhost', '127.0.0.1', '::1') -or
            $uri.Port -ne $ExpectedPort -or
            $uri.AbsolutePath -ne '/' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw 'Invalid loopback origin.'
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

function Assert-Status([hashtable] $Response, [int] $Expected) {
    if ($Response.Status -ne $Expected) { throw 'Unexpected HTTP status.' }
}

function Invoke-LocalHttp([string] $Method, [string] $Uri, [string] $AccessToken = '',
        [string] $Body = '', [hashtable] $AdditionalHeaders = @{}) {
    $headers = @{}
    if ($AccessToken) { $headers.Authorization = "Bearer $AccessToken" }
    foreach ($key in $AdditionalHeaders.Keys) { $headers[$key] = $AdditionalHeaders[$key] }
    try {
        $args = @{
            Method = $Method; Uri = $Uri; Headers = $headers; UseBasicParsing = $true
            TimeoutSec = 15; MaximumRedirection = 0; ErrorAction = 'Stop'
        }
        if ($Method -eq 'POST') {
            $args.ContentType = 'application/json'
            $args.Body = $Body
        }
        $response = Invoke-WebRequest @args
        return @{ Status = [int] $response.StatusCode; Body = $response.Content }
    } catch {
        $status = 0
        if ($null -ne $_.Exception.Response) { $status = [int] $_.Exception.Response.StatusCode }
        return @{ Status = $status; Body = $null }
    }
}

function Assert-Ready([string] $Origin) {
    Assert-Status (Invoke-LocalHttp 'GET' "$Origin/q/health/ready") 200
}

function Get-DbRows([string] $Pod, [string] $Sql) {
    # SQL is fixed in this script. Never write returned identities to the pipeline except to callers.
    $rows = @(& kubectl -n neurovision exec $Pod -- sh -c `
        'psql -X -q -A -t -F "|" -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' `
        sh $Sql 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Local aggregate database query failed.' }
    return @($rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-ClinicalBaseline {
    $sql = @'
SELECT (SELECT count(*) FROM clinical_patients),
       (SELECT count(*) FROM clinical_patients WHERE status = 'PENDING_ASSESSMENT'),
       (SELECT count(*) FROM doctor_patient_assignments WHERE status = 'ACTIVE'),
       (SELECT count(*) FROM therapy_plans),
       (SELECT count(*) FROM therapy_sessions);
'@
    $rows = @(Get-DbRows 'clinical-postgresql-0' $sql)
    if ($rows.Count -ne 1 -or $rows[0] -cne '4|4|1|0|0') {
        throw 'Clinical test baseline differs from the approved empty-plan state.'
    }
}

function Get-TelemetryCounts {
    $sql = @'
SELECT (SELECT count(*) FROM telemetry_batch_inbox),
       (SELECT count(*) FROM telemetry_samples),
       (SELECT count(*) FROM telemetry_samples WHERE sample_kind = 'GAZE'),
       (SELECT count(*) FROM telemetry_samples WHERE sample_kind = 'GAMEPLAY');
'@
    $rows = @(Get-DbRows 'telemetry-timescaledb-0' $sql)
    if ($rows.Count -ne 1 -or $rows[0] -notmatch '^\d+\|\d+\|\d+\|\d+$') {
        throw 'Telemetry aggregate counts are unavailable.'
    }
    return $rows[0]
}

function Get-TopicTotal([string] $Topic) {
    if ($Topic -cnotin @('telemetry-batch-accepted.v1', 'telemetry-batch-accepted.v1.dlq')) {
        throw 'Unexpected Kafka topic.'
    }
    $rows = @(& kubectl -n neurovision exec kafka-0 -- /opt/kafka/bin/kafka-get-offsets.sh `
        --bootstrap-server kafka:9092 --topic $Topic 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 3) { throw 'Kafka offsets are unavailable.' }
    $total = 0L
    $partitions = @()
    foreach ($row in $rows) {
        $fields = $row.Split(':')
        if ($fields.Count -ne 3 -or $fields[0] -cne $Topic -or
                $fields[1] -notmatch '^[0-2]$' -or $fields[2] -notmatch '^\d+$') {
            throw 'Unexpected Kafka offset shape.'
        }
        $partitions += $fields[1]
        $total += [long] $fields[2]
    }
    if ((@($partitions | Sort-Object -Unique) -join ',') -cne '0,1,2') {
        throw 'Kafka partitions differ from the synthetic baseline.'
    }
    return $total
}

function Assert-EmptyBaseline {
    Get-ClinicalBaseline
    if ((Get-TelemetryCounts) -cne '0|0|0|0' -or
            (Get-TopicTotal 'telemetry-batch-accepted.v1') -ne 0 -or
            (Get-TopicTotal 'telemetry-batch-accepted.v1.dlq') -ne 0) {
        throw 'The synthetic pipeline is not empty; do not create another run.'
    }
}

function Get-SoleAssignment {
    $sql = @'
SELECT p.id, p.patient_cognito_sub, a.doctor_cognito_sub
FROM clinical_patients p
JOIN doctor_patient_assignments a ON a.clinical_patient_id = p.id
WHERE a.status = 'ACTIVE';
'@
    $rows = @(Get-DbRows 'clinical-postgresql-0' $sql)
    if ($rows.Count -ne 1) { throw 'The active test assignment is not unique.' }
    $fields = $rows[0].Split('|')
    $patientId = [guid]::Empty
    if ($fields.Count -ne 3 -or -not [guid]::TryParse($fields[0], [ref] $patientId) -or
            $patientId -eq [guid]::Empty -or
            [string]::IsNullOrWhiteSpace($fields[1]) -or
            [string]::IsNullOrWhiteSpace($fields[2])) {
        throw 'The active test assignment is malformed.'
    }
    return @{ PatientId = $patientId; PatientSubject = $fields[1]; DoctorSubject = $fields[2] }
}

function Get-PublicEnvValue([string] $Key) {
    if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) { throw 'Local Cognito settings are missing.' }
    $matches = @(Get-Content -LiteralPath $envPath | Where-Object {
        $_ -match ('^' + [regex]::Escape($Key) + '=')
    })
    if ($matches.Count -ne 1) { throw 'Local Cognito settings are ambiguous.' }
    $value = $matches[0].Substring($Key.Length + 1).Trim().Trim('"')
    if (-not $value) { throw 'Local Cognito setting is empty.' }
    return $value
}

function Get-IamConfigValue([string] $Key) {
    # Checked-in local IAM configuration is the independent dev-pool pin, not a remote lookup.
    $path = Join-Path $root 'infra/kubernetes/config/iam-configmap.yaml'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'Local IAM Cognito configuration is missing.'
    }
    $matches = @(Get-Content -LiteralPath $path | Where-Object {
        $_ -match ('^\s*' + [regex]::Escape($Key) + ':\s*')
    })
    if ($matches.Count -ne 1) { throw 'Local IAM Cognito configuration is ambiguous.' }
    $value = $matches[0].Split(':', 2)[1].Trim().Trim('"').Trim([char] 39)
    if (-not $value) { throw 'Local IAM Cognito configuration is empty.' }
    return $value
}

function ConvertTo-Base64Url([byte[]] $Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-RandomBase64Url([int] $ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ConvertTo-Base64Url $bytes
}

function ConvertFrom-TokenPayload([string] $Token) {
    $segments = $Token.Split('.')
    if ($segments.Count -ne 3) { throw 'Access token is malformed.' }
    $encoded = $segments[1].Replace('-', '+').Replace('_', '/')
    switch ($encoded.Length % 4) {
        2 { $encoded += '==' }
        3 { $encoded += '=' }
        1 { throw 'Access token is malformed.' }
    }
    try {
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) |
            ConvertFrom-Json
    } catch { throw 'Access token claims are unreadable.' }
}

function Get-RoleAccessToken([string] $Role, [hashtable] $Oidc) {
    $verifier = New-RandomBase64Url 64
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $challenge = ConvertTo-Base64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))
    } finally { $sha.Dispose() }
    $state = New-RandomBase64Url 32
    $query = [ordered]@{
        client_id = $Oidc.ClientId; response_type = 'code'; redirect_uri = $redirectUri
        scope = 'openid email profile'; state = $state; code_challenge = $challenge
        code_challenge_method = 'S256'; prompt = 'login'
    }
    $encodedQuery = ($query.GetEnumerator() | ForEach-Object {
        '{0}={1}' -f [uri]::EscapeDataString($_.Key), [uri]::EscapeDataString($_.Value)
    }) -join '&'
    $listener = [Net.HttpListener]::new()
    $listener.Prefixes.Add($listenerPrefix)
    try {
        $listener.Start()
        Write-Host "PKCE_LOGIN_WAITING: complete $Role sign-in in the browser."
        Start-Process "$($Oidc.Authorize)?$encodedQuery"
        $callbackTask = $listener.GetContextAsync()
        if (-not $callbackTask.Wait([TimeSpan]::FromMinutes(10))) {
            throw 'PKCE callback timed out.'
        }
        $callback = $callbackTask.Result
        $code = $callback.Request.QueryString['code']
        $valid = $callback.Request.Url.AbsolutePath -eq '/auth/callback' -and
            $callback.Request.QueryString['state'] -ceq $state -and
            [string]::IsNullOrWhiteSpace($callback.Request.QueryString['error']) -and
            -not [string]::IsNullOrWhiteSpace($code)
        $callback.Response.StatusCode = if ($valid) { 200 } else { 400 }
        $callback.Response.ContentType = 'text/plain; charset=utf-8'
        $message = if ($valid) { 'Authentication complete. Return to the terminal.' } else {
            'Authentication callback rejected. Return to the terminal.'
        }
        $responseBytes = [Text.Encoding]::UTF8.GetBytes($message)
        $callback.Response.ContentLength64 = $responseBytes.Length
        $callback.Response.OutputStream.Write($responseBytes, 0, $responseBytes.Length)
        $callback.Response.Close()
        if (-not $valid) { throw 'PKCE callback is invalid.' }
        try {
            $tokens = Invoke-RestMethod -Method Post -Uri $Oidc.Token `
                -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 15 `
                -MaximumRedirection 0 -Body @{
                    grant_type = 'authorization_code'; client_id = $Oidc.ClientId
                    code = $code; redirect_uri = $redirectUri; code_verifier = $verifier
                }
        } catch { throw 'Cognito token exchange failed.' }
        if (-not $tokens.access_token) { throw 'Cognito did not return an access token.' }
        $claims = ConvertFrom-TokenPayload $tokens.access_token
        $groups = @($claims.'cognito:groups')
        if ($claims.iss -cne $Oidc.Issuer -or -not $claims.sub -or
                $claims.client_id -cne $Oidc.ClientId -or $claims.token_use -cne 'access' -or
                $groups -cnotcontains $Role -or $groups -contains 'ADMIN' -or
                ($Role -eq 'DOCTOR' -and $groups -contains 'PATIENT') -or
                ($Role -eq 'PATIENT' -and $groups -contains 'DOCTOR') -or
                $null -eq $claims.exp -or
                [long] $claims.exp -le [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) {
            throw 'Cognito role or identity does not match the expected test account.'
        }
        return @{ Token = [string] $tokens.access_token; Subject = [string] $claims.sub }
    } finally {
        if ($listener.IsListening) { $listener.Stop() }
        $listener.Close()
    }
}

function Get-OidcSettings {
    $clientId = Get-PublicEnvValue 'COGNITO_CLIENT_ID'
    $issuer = (Get-PublicEnvValue 'COGNITO_ISSUER_URL').TrimEnd('/')
    $poolId = Get-PublicEnvValue 'COGNITO_USER_POOL_ID'
    $expectedPoolId = Get-IamConfigValue 'COGNITO_USER_POOL_ID'
    $expectedClientId = Get-IamConfigValue 'COGNITO_CLIENT_ID'
    $expectedIssuer = (Get-IamConfigValue 'COGNITO_ISSUER_URL').TrimEnd('/')
    if ($poolId -notmatch '^us-east-1_[A-Za-z0-9]+$' -or
            $poolId -cne $expectedPoolId -or $clientId -cne $expectedClientId -or
            $issuer -cne $expectedIssuer -or
            $issuer -cne "https://cognito-idp.us-east-1.amazonaws.com/$poolId") {
        throw 'Unexpected development Cognito issuer.'
    }
    try {
        $discovery = Invoke-RestMethod -Method Get -Uri "$issuer/.well-known/openid-configuration" `
            -TimeoutSec 10 -MaximumRedirection 0
    } catch { throw 'Cognito discovery failed.' }
    $authorize = $null
    $tokenEndpoint = $null
    if ($discovery.issuer -cne $issuer -or
            -not [uri]::TryCreate([string] $discovery.authorization_endpoint,
                [UriKind]::Absolute, [ref] $authorize) -or
            -not [uri]::TryCreate([string] $discovery.token_endpoint,
                [UriKind]::Absolute, [ref] $tokenEndpoint) -or
            $authorize.Scheme -ne 'https' -or $tokenEndpoint.Scheme -ne 'https') {
        throw 'Unexpected Cognito discovery endpoints.'
    }
    return @{ ClientId = $clientId; Issuer = $issuer
        Authorize = $authorize.AbsoluteUri; Token = $tokenEndpoint.AbsoluteUri }
}

function Assert-Page([hashtable] $Response, [bool] $ExpectCursor) {
    Assert-Status $Response 200
    try { $page = ConvertFrom-Json -InputObject $Response.Body } catch {
        throw 'Historical page is invalid JSON.'
    }
    if ($page.contractVersion -ne 1 -or @($page.samples).Count -ne 1 -or
            @($page.samples)[0].kind -cnotin @('GAZE', 'GAMEPLAY') -or
            -not @($page.samples)[0].batchId -or -not @($page.samples)[0].sampleTime -or
            ([bool] $page.nextCursor) -ne $ExpectCursor) {
        throw 'Historical page shape differs from the synthetic pair.'
    }
    return @{ Sample = @($page.samples)[0]; Cursor = $page.nextCursor }
}

function Invoke-Smoke {
    if ([bool] $PreflightOnly -eq [bool] $AuthorizedSyntheticRun) {
        throw 'Select exactly one mode: -PreflightOnly or -AuthorizedSyntheticRun.'
    }
    $clinical = Assert-Origin $ClinicalBaseUrl 18083
    $telemetry = Assert-Origin $TelemetryBaseUrl 18084
    $context = (& kubectl config current-context 2>$null)
    if ($LASTEXITCODE -ne 0 -or $context -cne 'k3d-neurovision-local') {
        throw 'Unexpected Kubernetes context.'
    }
    Assert-Ready $clinical
    Assert-Ready $telemetry
    Assert-EmptyBaseline
    $now = [DateTimeOffset]::UtcNow
    $anonymousUrl = '{0}/api/v1/telemetry/sessions/{1}/samples?streamId={2}&from={3}&to={4}&limit=1' -f `
        $telemetry, [guid]::NewGuid(), [guid]::NewGuid(),
        [uri]::EscapeDataString($now.AddSeconds(-1).ToString('o')),
        [uri]::EscapeDataString($now.AddSeconds(1).ToString('o'))
    Assert-Status (Invoke-LocalHttp 'GET' $anonymousUrl) 401
    Write-Host 'K3D_PREFLIGHT_OK: ready=200; anonymous=401; clinicalPlans=0; clinicalSessions=0; samples=0; sourceOffsets=0; dlqOffsets=0'
    if ($PreflightOnly) { return }

    $script:stage = 'identity'
    $assignment = Get-SoleAssignment
    $oidc = Get-OidcSettings
    $doctor = Get-RoleAccessToken 'DOCTOR' $oidc
    $patient = Get-RoleAccessToken 'PATIENT' $oidc
    if ($doctor.Subject -cne $assignment.DoctorSubject -or
            $patient.Subject -cne $assignment.PatientSubject -or
            $doctor.Subject -ceq $patient.Subject) {
        throw 'The two test accounts do not match the sole active Clinical assignment.'
    }
    Assert-EmptyBaseline
    Write-Host 'TEST_IDENTITIES_MATCH_ASSIGNMENT: no identifiers emitted'

    $script:stage = 'clinical-plan'
    $planBody = '{"dailySessionLimit":1,"dailyTargetDurationMinutes":10,' +
        '"dailyHardSafetyLimitMinutes":null,"minimumValidDurationMinutes":1,' +
        '"maximumSessionDurationMinutes":5,"minimumValidSampleCount":2,' +
        '"dailyExercisePrescriptions":{"fixation":1}}'
    $planBase = "$clinical/api/v1/clinical-patients/$($assignment.PatientId)/therapy-plans"
    $planResponse = Invoke-LocalHttp 'POST' $planBase $doctor.Token $planBody
    Assert-Status $planResponse 201
    try { $plan = ConvertFrom-Json -InputObject $planResponse.Body } catch {
        throw 'Plan response is invalid JSON.'
    }
    $planId = [guid]::Empty
    if (-not [guid]::TryParse([string] $plan.therapyPlanId, [ref] $planId) -or
            $planId -eq [guid]::Empty -or $plan.status -cne 'ACTIVE' -or
            $plan.clinicalPatientId -cne $assignment.PatientId.ToString()) {
        throw 'Clinical returned an unexpected plan.'
    }
    Write-Host 'SYNTHETIC_PLAN_CREATED: status=201'

    $script:stage = 'configuration'
    $effectiveFrom = [DateTimeOffset]::UtcNow.AddSeconds(5)
    $configBody = @{
        exerciseCode = 'fixation'; parameterSchemaVersion = 1
        effectiveFrom = $effectiveFrom.ToString('o')
        parameters = @{
            mode = 'SUSTAINED_TARGET_FIXATION'; targetPosition = 'CENTER'
            minimumFixationMs = 1000; maximumFixationMs = 3000
            targetSizePx = 70; repetitions = 10
            restBetweenRepetitionsMs = 1000; difficultyLevel = 1
        }
    } | ConvertTo-Json -Depth 8 -Compress
    $configResponse = Invoke-LocalHttp 'POST' "$planBase/$planId/configurations" `
        $doctor.Token $configBody
    Assert-Status $configResponse 201
    Write-Host 'SYNTHETIC_CONFIGURATION_CREATED: status=201'
    while ([DateTimeOffset]::UtcNow -lt $effectiveFrom.AddMilliseconds(500)) {
        Start-Sleep -Milliseconds 300
    }

    $script:stage = 'patient-session'
    $plansResponse = Invoke-LocalHttp 'GET' "$clinical/api/v1/me/therapy-plans" $patient.Token
    Assert-Status $plansResponse 200
    try { $visiblePlans = @(ConvertFrom-Json -InputObject $plansResponse.Body) } catch {
        throw 'Patient plan response is invalid JSON.'
    }
    $visible = @($visiblePlans | Where-Object {
        $_.therapyPlanId -ceq $planId.ToString() -and $_.status -ceq 'ACTIVE' -and
        $_.dailyExercisePrescriptions.fixation -eq 1 -and
        @($_.effectiveConfigurations | Where-Object { $_.exerciseCode -ceq 'fixation' }).Count -eq 1
    })
    if ($visible.Count -ne 1) { throw 'Synthetic plan is not visible to the assigned patient.' }
    $sessionResponse = Invoke-LocalHttp 'POST' `
        "$clinical/api/v1/me/therapy-plans/$planId/sessions" $patient.Token `
        '{"exerciseCode":"fixation"}' @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    Assert-Status $sessionResponse 201
    try { $session = ConvertFrom-Json -InputObject $sessionResponse.Body } catch {
        throw 'Session response is invalid JSON.'
    }
    $sessionId = [guid]::Empty
    if (-not [guid]::TryParse([string] $session.therapySessionId, [ref] $sessionId) -or
            $sessionId -eq [guid]::Empty -or $session.status -cne 'ACTIVE' -or
            $session.therapyPlanId -cne $planId.ToString() -or
            $session.exerciseCode -cne 'fixation' -or
            [DateTimeOffset]::Parse([string] $session.leaseExpiresAt) -le
                [DateTimeOffset]::UtcNow.AddSeconds(30)) {
        throw 'Clinical did not return a usable new ACTIVE session.'
    }
    Write-Host 'SYNTHETIC_SESSION_CREATED: status=201'

    $script:stage = 'telemetry-batch'
    if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
        throw 'Synthetic fixture is missing.'
    }
    try { $batch = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json } catch {
        throw 'Synthetic fixture is invalid JSON.'
    }
    if ($batch.schemaVersion -ne 1 -or @($batch.gazeSamples).Count -ne 1 -or
            @($batch.gameplaySamples).Count -ne 1) {
        throw 'Synthetic fixture must contain one gaze and one gameplay sample.'
    }
    $batch.batchId = [guid]::NewGuid().ToString()
    $batch.therapySessionId = $sessionId.ToString()
    $batch.streamId = [guid]::NewGuid().ToString()
    $batch.sequence = 0
    $captured = [DateTimeOffset]::UtcNow
    $batch.capturedAt = $captured.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $batch.sentAt = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $batchResponse = Invoke-LocalHttp 'POST' "$telemetry/api/v1/telemetry/batches" `
        $patient.Token (ConvertTo-Json -InputObject $batch -Depth 10 -Compress)
    Assert-Status $batchResponse 202
    Write-Host 'SYNTHETIC_BATCH_ACCEPTED: status=202; gaze=1; gameplay=1'

    $script:stage = 'persistence'
    $persisted = $false
    for ($i = 0; $i -lt 30; $i++) {
        if ((Get-TelemetryCounts) -ceq '1|2|1|1') { $persisted = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $persisted -or (Get-TopicTotal 'telemetry-batch-accepted.v1') -ne 1 -or
            (Get-TopicTotal 'telemetry-batch-accepted.v1.dlq') -ne 0) {
        throw 'The synthetic batch did not persist without DLQ movement.'
    }
    Write-Host 'K3D_PERSISTENCE_VERIFIED: inbox=1; samples=2; sourceOffsets=1; dlqOffsets=0'

    $script:stage = 'historical-read'
    $from = $captured.AddSeconds(-1)
    $to = $captured.AddSeconds(1)
    $readUrl = '{0}/api/v1/telemetry/sessions/{1}/samples?streamId={2}&from={3}&to={4}&limit=1' -f `
        $telemetry, $sessionId, $batch.streamId,
        [uri]::EscapeDataString($from.ToString('o')),
        [uri]::EscapeDataString($to.ToString('o'))
    $first = Assert-Page (Invoke-LocalHttp 'GET' $readUrl $patient.Token) $true
    $second = Assert-Page (Invoke-LocalHttp 'GET' `
        "$readUrl&cursor=$([uri]::EscapeDataString($first.Cursor))" $patient.Token) $false
    $kinds = @($first.Sample.kind, $second.Sample.kind) | Sort-Object
    $firstKey = '{0}|{1}|{2}|{3}' -f $first.Sample.sampleTime, $first.Sample.batchId,
        $first.Sample.kind, $first.Sample.index
    $secondKey = '{0}|{1}|{2}|{3}' -f $second.Sample.sampleTime, $second.Sample.batchId,
        $second.Sample.kind, $second.Sample.index
    if ($kinds[0] -cne 'GAMEPLAY' -or $kinds[1] -cne 'GAZE' -or
            $firstKey -ceq $secondKey -or
            $first.Sample.batchId -cne $batch.batchId -or
            $second.Sample.batchId -cne $batch.batchId) {
        throw 'Historical pages do not reconstruct the synthetic pair.'
    }
    $unknownUrl = $readUrl.Replace($sessionId.ToString(), [guid]::NewGuid().ToString())
    Assert-Status (Invoke-LocalHttp 'GET' $unknownUrl $patient.Token) 403
    Write-Host 'K3D_HISTORICAL_READ_VERIFIED: page1=200,count=1,cursor=present; page2=200,count=1,cursor=absent; kinds=GAZE,GAMEPLAY; unknownSession=403; anonymous=401'
}

try {
    Invoke-Smoke
} catch {
    # Do not emit exception text: it may contain a URL, token, identifier, SQL row, or sample body.
    Write-Error -Message "K3D_SMOKE_STOPPED: stage=$script:stage; no automatic retry." -ErrorAction Continue
    exit 1
}
