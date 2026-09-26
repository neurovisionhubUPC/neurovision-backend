[CmdletBinding()]
param(
    [switch] $PreflightOnly,
    [switch] $OfflineTests,
    [string] $ClinicalBaseUrl = 'http://localhost:8083',
    [string] $TelemetryBaseUrl = 'http://localhost:8084'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envPath = Join-Path $repositoryRoot '.env'
$redirectUri = 'http://localhost:5173/auth/callback'
$listenerPrefix = 'http://localhost:5173/'
$script:historicalStage = 'mode_guard'

function Get-FailureDiagnostic([string] $Stage, [string] $Message) {
    $safeStage = if ($Stage -in @(
        'mode_guard', 'synthetic_fixture', 'clinical_readiness', 'telemetry_readiness',
        'unauthenticated_read', 'patient_pkce_login', 'authenticated_page_one',
        'authenticated_page_two')) { $Stage } else { 'unknown' }
    $guard = if ($Message -match '^Historical read returned unexpected HTTP status [0-9]+\.$') {
        if ($safeStage -eq 'unauthenticated_read') { 'unauthenticated_status_unexpected' } else { 'authenticated_status_unexpected' }
    } else { switch -CaseSensitive ($Message) {
        'Only plain HTTP loopback service origins are allowed.' { 'service_origin_invalid' }
        'The local .env file is missing.' { 'public_settings_unavailable' }
        'A required public Cognito setting is missing or duplicated.' { 'public_settings_invalid' }
        'A required public Cognito setting is empty.' { 'public_settings_invalid' }
        'The synthetic fixture query failed.' { 'synthetic_query_failed' }
        'The synthetic fixture query returned an unexpected row count.' { 'synthetic_row_count_invalid' }
        'The synthetic fixture row has an unexpected field count.' { 'synthetic_field_count_invalid' }
        'The synthetic fixture has an unexpected sample count.' { 'synthetic_sample_count_invalid' }
        'The synthetic fixture has an unexpected gaze count.' { 'synthetic_gaze_count_invalid' }
        'The synthetic fixture has an unexpected gameplay count.' { 'synthetic_gameplay_count_invalid' }
        'The local synthetic group has invalid identifiers.' { 'synthetic_identifiers_invalid' }
        'The local synthetic group has invalid sample times.' { 'synthetic_timestamps_invalid' }
        'The synthetic sample interval exceeds the historical-read limit.' { 'synthetic_window_invalid' }
        'A required local service is not ready.' { 'service_not_ready' }
        'The access token is malformed.' { 'identity_token_invalid' }
        'The access token claims could not be read.' { 'identity_claims_invalid' }
        'The access token is not for the expected PATIENT.' { 'patient_identity_invalid' }
        'Cognito OIDC discovery failed.' { 'oidc_discovery_failed' }
        'Cognito OIDC issuer mismatch.' { 'oidc_issuer_invalid' }
        'Cognito OIDC endpoints are invalid.' { 'oidc_endpoint_invalid' }
        'The PKCE callback was invalid.' { 'pkce_callback_invalid' }
        'Timed out waiting for the PKCE callback.' { 'pkce_callback_timeout' }
        'Cognito token exchange failed.' { 'pkce_exchange_failed' }
        'Cognito did not return an access token.' { 'pkce_exchange_invalid' }
        'Historical read returned invalid JSON.' { 'historical_page_invalid' }
        'Historical read returned an unexpected page.' { 'historical_page_invalid' }
        'Historical read returned an unexpected cursor state.' { 'historical_cursor_invalid' }
        'Historical pages did not reconstruct the synthetic gaze/gameplay pair.' { 'historical_pages_invalid' }
        default { 'operation_failed' }
    } }
    return [pscustomobject]@{ Stage = $safeStage; Guard = $guard }
}

function Format-FailureDiagnostic([string] $Stage, [string] $Message) {
    $diagnostic = Get-FailureDiagnostic $Stage $Message
    return ('HISTORICAL_READ_FAILED: stage={0}; guard={1}' -f $diagnostic.Stage, $diagnostic.Guard)
}

function Invoke-NativeCommand([string] $Executable, [string[]] $Arguments) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 promotes native stderr to an error record; scope Continue to this call.
        $ErrorActionPreference = 'Continue'
        $output = @(& $Executable @Arguments 2>$null)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{ Output = $output; ExitCode = $exitCode }
}

function Assert-OfflineCase([bool] $Condition, [string] $Name) {
    if (-not $Condition) { throw "Offline historical-read test failed: $Name" }
}

function Assert-OfflineSampleGuard([psobject] $Result, [string] $ExpectedGuard) {
    $failure = $null
    try { $null = ConvertFrom-SyntheticSampleGroupResult $Result } catch {
        $failure = $_.Exception.Message
    }
    Assert-OfflineCase ($null -ne $failure) "$ExpectedGuard-thrown"
    $diagnostic = Get-FailureDiagnostic 'synthetic_fixture' $failure
    Assert-OfflineCase ($diagnostic.Guard -ceq $ExpectedGuard) "$ExpectedGuard-mapped"
}

function Invoke-OfflineTests {
    $cmd = [IO.Path]::Combine([Environment]::SystemDirectory, 'cmd.exe')
    $nativeSuccess = Invoke-NativeCommand $cmd @('/d', '/c', 'echo NATIVE-STDOUT-CANARY & echo NATIVE-STDERR-CANARY 1>&2')
    Assert-OfflineCase ($nativeSuccess.ExitCode -eq 0) 'native-stderr-zero-exit-captured'
    Assert-OfflineCase (($nativeSuccess.Output -join "`n") -match 'NATIVE-STDOUT-CANARY') 'native-stdout-captured'
    Assert-OfflineCase (($nativeSuccess.Output -join "`n") -notmatch 'NATIVE-STDERR-CANARY') 'native-stderr-discarded'
    Assert-OfflineCase ($ErrorActionPreference -ceq 'Stop') 'native-zero-exit-preference-restored'
    $nativeFailure = Invoke-NativeCommand $cmd @('/d', '/c', 'echo NATIVE-STDERR-CANARY 1>&2 & exit /b 7')
    Assert-OfflineCase ($nativeFailure.ExitCode -eq 7) 'native-stderr-nonzero-exit-captured'
    Assert-OfflineCase (($nativeFailure.Output -join "`n") -notmatch 'NATIVE-STDERR-CANARY') 'native-failure-stderr-discarded'
    Assert-OfflineCase ($ErrorActionPreference -ceq 'Stop') 'native-nonzero-exit-preference-restored'

    $validFields = @(
        '11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222',
        '2', '1', '1', '2026-01-01T00:00:00.000Z', '2026-01-01T00:01:00.000Z')
    $validResult = [pscustomobject]@{ Output = @(($validFields -join '|')); ExitCode = 0 }
    $validGroup = ConvertFrom-SyntheticSampleGroupResult $validResult
    Assert-OfflineCase ($validGroup.SessionId -ne [guid]::Empty -and
        $validGroup.StreamId -ne [guid]::Empty -and $validGroup.From -lt $validGroup.To) `
        'synthetic-valid-result'
    Assert-OfflineSampleGuard ([pscustomobject]@{ Output = $validResult.Output; ExitCode = 7 }) `
        'synthetic_query_failed'
    Assert-OfflineSampleGuard ([pscustomobject]@{ Output = @(); ExitCode = 0 }) `
        'synthetic_row_count_invalid'
    Assert-OfflineSampleGuard ([pscustomobject]@{ Output = @($validResult.Output[0], $validResult.Output[0]); ExitCode = 0 }) `
        'synthetic_row_count_invalid'
    $invalidCases = @(
        @{ Index = 6; Value = $null; Guard = 'synthetic_field_count_invalid' },
        @{ Index = 2; Value = '3'; Guard = 'synthetic_sample_count_invalid' },
        @{ Index = 3; Value = '0'; Guard = 'synthetic_gaze_count_invalid' },
        @{ Index = 4; Value = '0'; Guard = 'synthetic_gameplay_count_invalid' },
        @{ Index = 0; Value = 'invalid'; Guard = 'synthetic_identifiers_invalid' },
        @{ Index = 5; Value = 'invalid'; Guard = 'synthetic_timestamps_invalid' },
        @{ Index = 6; Value = '2026-01-01T00:16:00.000Z'; Guard = 'synthetic_window_invalid' })
    foreach ($case in $invalidCases) {
        $fields = @($validFields)
        if ($null -eq $case.Value) { $fields = @($fields[0..5]) } else {
            $fields[$case.Index] = $case.Value
        }
        Assert-OfflineSampleGuard ([pscustomobject]@{ Output = @(($fields -join '|')); ExitCode = 0 }) `
            $case.Guard
    }
    $statusFailure = Get-FailureDiagnostic 'authenticated_page_one' `
        'Historical read returned unexpected HTTP status 503.'
    Assert-OfflineCase ($statusFailure.Guard -ceq 'authenticated_status_unexpected') 'http-status-guard-mapped'
    $unknown = Get-FailureDiagnostic 'untrusted-stage' 'untrusted exception TOKEN-DO-NOT-PRINT'
    Assert-OfflineCase ($unknown.Stage -ceq 'unknown' -and $unknown.Guard -ceq 'operation_failed') `
        'unknown-stage-and-guard-fallback'
    $formatted = Format-FailureDiagnostic 'patient_pkce_login' 'untrusted exception TOKEN-DO-NOT-PRINT'
    Assert-OfflineCase ($formatted -match '^HISTORICAL_READ_FAILED: stage=patient_pkce_login; guard=operation_failed$' -and
        $formatted -notmatch 'TOKEN-DO-NOT-PRINT') 'diagnostic-redacts-exception'
    Write-Output 'HISTORICAL_OFFLINE_TESTS_PASSED: native-exits=0,7; stderr=discarded; preference-restored=2; synthetic-guards=10; safe-diagnostics=3'
}

function Assert-LoopbackOrigin([string] $Value) {
    $uri = $null
    if (-not [uri]::TryCreate($Value, [UriKind]::Absolute, [ref] $uri) -or
            $uri.Scheme -ne 'http' -or
            $uri.Host -notin @('localhost', '127.0.0.1', '::1') -or
            -not [string]::IsNullOrEmpty($uri.UserInfo) -or
            $uri.AbsolutePath -ne '/' -or
            -not [string]::IsNullOrEmpty($uri.Query) -or
            -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw 'Only plain HTTP loopback service origins are allowed.'
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

function Get-PublicEnvValue([string] $Key) {
    if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
        throw 'The local .env file is missing.'
    }
    $matches = @(Get-Content -LiteralPath $envPath | Where-Object {
        $_ -match ('^' + [regex]::Escape($Key) + '=')
    })
    if ($matches.Count -ne 1) { throw 'A required public Cognito setting is missing or duplicated.' }
    $value = $matches[0].Substring($Key.Length + 1).Trim().Trim('"')
    if ([string]::IsNullOrWhiteSpace($value)) { throw 'A required public Cognito setting is empty.' }
    return $value
}

function ConvertFrom-SyntheticSampleGroupResult([psobject] $Result) {
    # Do not expose native output: it contains pseudonymous identifiers and sample times.
    if ($Result.ExitCode -ne 0) { throw 'The synthetic fixture query failed.' }
    $lines = @($Result.Output)
    if ($lines.Count -ne 1) { throw 'The synthetic fixture query returned an unexpected row count.' }
    $fields = $lines[0].Split('|')
    if ($fields.Count -ne 7) { throw 'The synthetic fixture row has an unexpected field count.' }
    if ($fields[2] -ne '2') { throw 'The synthetic fixture has an unexpected sample count.' }
    if ($fields[3] -ne '1') { throw 'The synthetic fixture has an unexpected gaze count.' }
    if ($fields[4] -ne '1') { throw 'The synthetic fixture has an unexpected gameplay count.' }
    $sessionId = [guid]::Empty
    $streamId = [guid]::Empty
    if (-not [guid]::TryParse($fields[0], [ref] $sessionId) -or
            -not [guid]::TryParse($fields[1], [ref] $streamId) -or
            $sessionId -eq [guid]::Empty -or $streamId -eq [guid]::Empty) {
        throw 'The local synthetic group has invalid identifiers.'
    }
    $first = [DateTimeOffset]::MinValue
    $last = [DateTimeOffset]::MinValue
    $styles = [Globalization.DateTimeStyles]::AssumeUniversal -bor
        [Globalization.DateTimeStyles]::AdjustToUniversal
    if (-not [DateTimeOffset]::TryParse($fields[5], [Globalization.CultureInfo]::InvariantCulture,
                $styles, [ref] $first) -or
            -not [DateTimeOffset]::TryParse($fields[6], [Globalization.CultureInfo]::InvariantCulture,
                $styles, [ref] $last)) {
        throw 'The local synthetic group has invalid sample times.'
    }
    $from = $first.AddSeconds(-1)
    $to = $last.AddSeconds(1)
    if ($from -ge $to -or ($to - $from).TotalMinutes -ge 15) {
        throw 'The synthetic sample interval exceeds the historical-read limit.'
    }
    return @{ SessionId = $sessionId; StreamId = $streamId; From = $from; To = $to }
}

function Get-SyntheticSampleGroup {
    # Never emit the row: it contains pseudonymous identifiers and sample times.
    $sql = @'
SELECT therapy_session_id, stream_id, count(*),
       count(*) FILTER (WHERE sample_kind = 'GAZE'),
       count(*) FILTER (WHERE sample_kind = 'GAMEPLAY'),
       to_char(min(sample_time) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
       to_char(max(sample_time) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
FROM telemetry_samples
GROUP BY therapy_session_id, stream_id;
'@
    Push-Location $repositoryRoot
    try {
        $result = Invoke-NativeCommand 'docker' @(
            'compose', 'exec', '-T', 'telemetry-timescaledb', 'psql', '-X', '-q', '-A', '-t', '-F', '|',
            '-v', 'ON_ERROR_STOP=1', '-U', 'neurovision_telemetry', '-d', 'neurovision_telemetry', '-c', $sql)
    } finally {
        Pop-Location
    }
    return ConvertFrom-SyntheticSampleGroupResult $result
}

function Assert-Ready([string] $Origin) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get `
            -Uri "$Origin/q/health/ready" -TimeoutSec 5 -ErrorAction Stop
        if ([int] $response.StatusCode -ne 200) { throw 'Not ready.' }
    } catch { throw 'A required local service is not ready.' }
}

function Invoke-LocalGet([string] $Uri, [string] $AccessToken = '') {
    $headers = @{}
    if (-not [string]::IsNullOrEmpty($AccessToken)) {
        $headers['Authorization'] = "Bearer $AccessToken"
    }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $Uri `
            -Headers $headers -TimeoutSec 15 -ErrorAction Stop
        return @{ Status = [int] $response.StatusCode; Body = $response.Content }
    } catch {
        $status = 0
        if ($null -ne $_.Exception.Response) {
            $status = [int] $_.Exception.Response.StatusCode
        }
        return @{ Status = $status; Body = $null }
    }
}

function Assert-Status([hashtable] $Response, [int] $Expected) {
    if ($Response.Status -ne $Expected) {
        throw "Historical read returned unexpected HTTP status $($Response.Status)."
    }
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

function ConvertFrom-AccessTokenPayload([string] $Token) {
    $segments = $Token.Split('.')
    if ($segments.Count -ne 3) { throw 'The access token is malformed.' }
    $encoded = $segments[1].Replace('-', '+').Replace('_', '/')
    switch ($encoded.Length % 4) {
        2 { $encoded += '==' }
        3 { $encoded += '=' }
        1 { throw 'The access token is malformed.' }
    }
    try {
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) |
            ConvertFrom-Json
    } catch { throw 'The access token claims could not be read.' }
}

function Get-PatientAccessToken {
    $clientId = Get-PublicEnvValue 'COGNITO_CLIENT_ID'
    $issuer = (Get-PublicEnvValue 'COGNITO_ISSUER_URL').TrimEnd('/')
    $poolId = Get-PublicEnvValue 'COGNITO_USER_POOL_ID'
    if ($poolId -notmatch '^us-east-1_[A-Za-z0-9]+$' -or
            $issuer -cne "https://cognito-idp.us-east-1.amazonaws.com/$poolId") {
        throw 'The public Cognito settings do not identify the expected development region.'
    }
    try {
        $discovery = Invoke-RestMethod -Method Get -Uri "$issuer/.well-known/openid-configuration" `
            -TimeoutSec 10
    } catch { throw 'Cognito OIDC discovery failed.' }
    if ($discovery.issuer -cne $issuer) { throw 'Cognito OIDC issuer mismatch.' }
    $authorize = $null
    $tokenEndpoint = $null
    if (-not [uri]::TryCreate([string] $discovery.authorization_endpoint,
                [UriKind]::Absolute, [ref] $authorize) -or
            -not [uri]::TryCreate([string] $discovery.token_endpoint,
                [UriKind]::Absolute, [ref] $tokenEndpoint) -or
            $authorize.Scheme -ne 'https' -or $tokenEndpoint.Scheme -ne 'https') {
        throw 'Cognito OIDC endpoints are invalid.'
    }
    $verifier = New-RandomBase64Url 64
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $challenge = ConvertTo-Base64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))
    } finally { $sha.Dispose() }
    $state = New-RandomBase64Url 32
    $query = [ordered]@{
        client_id = $clientId
        response_type = 'code'
        redirect_uri = $redirectUri
        scope = 'openid email profile'
        state = $state
        code_challenge = $challenge
        code_challenge_method = 'S256'
        prompt = 'login'
    }
    $encodedQuery = ($query.GetEnumerator() | ForEach-Object {
        '{0}={1}' -f [uri]::EscapeDataString($_.Key), [uri]::EscapeDataString($_.Value)
    }) -join '&'
    $listener = [Net.HttpListener]::new()
    $listener.Prefixes.Add($listenerPrefix)
    try {
        $listener.Start()
        Write-Host 'PKCE_LOGIN_WAITING: complete PATIENT sign-in in the browser.'
        Start-Process "$($authorize.AbsoluteUri)?$encodedQuery"
        $callbackTask = $listener.GetContextAsync()
        if (-not $callbackTask.Wait([TimeSpan]::FromMinutes(10))) {
            throw 'Timed out waiting for the PKCE callback.'
        }
        $callback = $callbackTask.Result
        $code = $callback.Request.QueryString['code']
        $valid = $callback.Request.Url.AbsolutePath -eq '/auth/callback' -and
            $callback.Request.QueryString['state'] -ceq $state -and
            [string]::IsNullOrEmpty($callback.Request.QueryString['error']) -and
            -not [string]::IsNullOrWhiteSpace($code)
        $callback.Response.StatusCode = if ($valid) { 200 } else { 400 }
        $callback.Response.ContentType = 'text/plain; charset=utf-8'
        $message = if ($valid) { 'Authentication complete. Return to the terminal.' } else {
            'Authentication callback rejected. Return to the terminal.'
        }
        $bytes = [Text.Encoding]::UTF8.GetBytes($message)
        $callback.Response.ContentLength64 = $bytes.Length
        $callback.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $callback.Response.Close()
        if (-not $valid) { throw 'The PKCE callback was invalid.' }
        try {
            $tokens = Invoke-RestMethod -Method Post -Uri $tokenEndpoint.AbsoluteUri `
                -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 15 -Body @{
                    grant_type = 'authorization_code'
                    client_id = $clientId
                    code = $code
                    redirect_uri = $redirectUri
                    code_verifier = $verifier
                }
        } catch { throw 'Cognito token exchange failed.' }
        if ([string]::IsNullOrWhiteSpace($tokens.access_token)) {
            throw 'Cognito did not return an access token.'
        }
        $claims = ConvertFrom-AccessTokenPayload $tokens.access_token
        $groups = @($claims.'cognito:groups')
        if ($claims.iss -cne $issuer -or
                [string]::IsNullOrWhiteSpace([string] $claims.sub) -or
                $claims.client_id -cne $clientId -or $claims.token_use -cne 'access' -or
                $groups -cnotcontains 'PATIENT' -or $groups -contains 'DOCTOR' -or
                $groups -contains 'ADMIN' -or $null -eq $claims.exp -or
                [long] $claims.exp -le [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) {
            throw 'The access token is not for the expected PATIENT.'
        }
        return $tokens.access_token
    } finally {
        if ($listener.IsListening) { $listener.Stop() }
        $listener.Close()
    }
}

function Assert-Page([hashtable] $Response, [bool] $ExpectCursor) {
    Assert-Status $Response 200
    try { $page = ConvertFrom-Json -InputObject $Response.Body } catch {
        throw 'Historical read returned invalid JSON.'
    }
    if ($page.contractVersion -ne 1 -or $null -eq $page.samples -or
            @($page.samples).Count -ne 1 -or
            @($page.samples)[0].kind -cnotin @('GAZE', 'GAMEPLAY') -or
            [string]::IsNullOrWhiteSpace([string] @($page.samples)[0].batchId) -or
            [string]::IsNullOrWhiteSpace([string] @($page.samples)[0].sampleTime)) {
        throw 'Historical read returned an unexpected page.'
    }
    $cursorPresent = -not [string]::IsNullOrWhiteSpace([string] $page.nextCursor)
    if ($cursorPresent -ne $ExpectCursor) {
        throw 'Historical read returned an unexpected cursor state.'
    }
    return @{ Sample = @($page.samples)[0]; Cursor = $page.nextCursor }
}

function Invoke-Smoke {
    $clinical = Assert-LoopbackOrigin $ClinicalBaseUrl
    $telemetry = Assert-LoopbackOrigin $TelemetryBaseUrl
    $script:historicalStage = 'synthetic_fixture'
    $group = Get-SyntheticSampleGroup
    $script:historicalStage = 'clinical_readiness'
    Assert-Ready $clinical
    $script:historicalStage = 'telemetry_readiness'
    Assert-Ready $telemetry
    $baseQuery = '{0}/api/v1/telemetry/sessions/{1}/samples?streamId={2}&from={3}&to={4}&limit=1' -f `
        $telemetry, $group.SessionId, $group.StreamId,
        [uri]::EscapeDataString($group.From.ToString('o')),
        [uri]::EscapeDataString($group.To.ToString('o'))
    $script:historicalStage = 'unauthenticated_read'
    $unauthenticated = Invoke-LocalGet $baseQuery
    Assert-Status $unauthenticated 401
    Write-Host 'LOCAL_READ_PREFLIGHT: ready=200; unauthenticated=401; samples=2; kinds=GAZE,GAMEPLAY'
    if ($PreflightOnly) { return }
    $script:historicalStage = 'patient_pkce_login'
    $accessToken = Get-PatientAccessToken
    $script:historicalStage = 'authenticated_page_one'
    $first = Assert-Page (Invoke-LocalGet $baseQuery $accessToken) $true
    $secondUrl = "$baseQuery&cursor=$([uri]::EscapeDataString($first.Cursor))"
    $script:historicalStage = 'authenticated_page_two'
    $second = Assert-Page (Invoke-LocalGet $secondUrl $accessToken) $false
    $kinds = @($first.Sample.kind, $second.Sample.kind) | Sort-Object
    $firstKey = '{0}|{1}|{2}|{3}' -f $first.Sample.sampleTime, $first.Sample.batchId,
        $first.Sample.kind, $first.Sample.index
    $secondKey = '{0}|{1}|{2}|{3}' -f $second.Sample.sampleTime, $second.Sample.batchId,
        $second.Sample.kind, $second.Sample.index
    if ($kinds[0] -cne 'GAMEPLAY' -or $kinds[1] -cne 'GAZE' -or
            $firstKey -ceq $secondKey) {
        throw 'Historical pages did not reconstruct the synthetic gaze/gameplay pair.'
    }
    Write-Host 'HISTORICAL_READ_VERIFIED: page1=200,count=1,cursor=present; page2=200,count=1,cursor=absent; kinds=GAZE,GAMEPLAY'
}

try {
    if ($OfflineTests) {
        Invoke-OfflineTests
        exit 0
    }
    Invoke-Smoke
} catch {
    # Never leak URLs, tokens, identifiers, SQL rows, response bodies, or coordinates.
    $message = Format-FailureDiagnostic $script:historicalStage $_.Exception.Message
    Write-Error -Message $message -ErrorAction Continue
    exit 1
}
