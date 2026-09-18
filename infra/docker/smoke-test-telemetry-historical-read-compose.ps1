[CmdletBinding()]
param(
    [switch] $PreflightOnly,
    [string] $ClinicalBaseUrl = 'http://localhost:8083',
    [string] $TelemetryBaseUrl = 'http://localhost:8084'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envPath = Join-Path $repositoryRoot '.env'
$redirectUri = 'http://localhost:5173/auth/callback'
$listenerPrefix = 'http://localhost:5173/'

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
        $lines = @(& docker compose exec -T telemetry-timescaledb `
            psql -X -q -A -t -F '|' -v ON_ERROR_STOP=1 `
            -U neurovision_telemetry -d neurovision_telemetry -c $sql 2>$null)
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0 -or $lines.Count -ne 1) {
        throw 'Expected one isolated synthetic sample group in the local TimescaleDB.'
    }
    $fields = $lines[0].Split('|')
    if ($fields.Count -ne 7 -or $fields[2] -ne '2' -or
            $fields[3] -ne '1' -or $fields[4] -ne '1') {
        throw 'The local sample group is not the expected synthetic gaze/gameplay pair.'
    }
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
    $group = Get-SyntheticSampleGroup
    Assert-Ready $clinical
    Assert-Ready $telemetry
    $baseQuery = '{0}/api/v1/telemetry/sessions/{1}/samples?streamId={2}&from={3}&to={4}&limit=1' -f `
        $telemetry, $group.SessionId, $group.StreamId,
        [uri]::EscapeDataString($group.From.ToString('o')),
        [uri]::EscapeDataString($group.To.ToString('o'))
    $unauthenticated = Invoke-LocalGet $baseQuery
    Assert-Status $unauthenticated 401
    Write-Host 'LOCAL_READ_PREFLIGHT: ready=200; unauthenticated=401; samples=2; kinds=GAZE,GAMEPLAY'
    if ($PreflightOnly) { return }
    $accessToken = Get-PatientAccessToken
    $first = Assert-Page (Invoke-LocalGet $baseQuery $accessToken) $true
    $secondUrl = "$baseQuery&cursor=$([uri]::EscapeDataString($first.Cursor))"
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
    Invoke-Smoke
} catch {
    # Never leak URLs, tokens, identifiers, SQL rows, response bodies, or coordinates.
    $message = if ($_.Exception.Message -match '^Historical read returned unexpected HTTP status [0-9]+\.$') {
        $_.Exception.Message
    } else {
        'Historical-read smoke stopped safely; inspect local services and the synthetic fixture.'
    }
    Write-Error -Message $message -ErrorAction Continue
    exit 1
}
