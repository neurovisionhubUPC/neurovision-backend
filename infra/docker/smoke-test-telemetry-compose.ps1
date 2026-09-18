[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [guid] $PlanId,
    [Parameter(Mandatory = $true)]
    [guid] $ExpectedPatientSubject,
    [string] $ClinicalBaseUrl = 'http://localhost:8083',
    [string] $TelemetryBaseUrl = 'http://localhost:8084'
)

$ErrorActionPreference = 'Stop'
$redirectUri = 'http://localhost:5173/auth/callback'
$listenerPrefix = 'http://localhost:5173/'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envPath = Join-Path $repositoryRoot '.env'
$fixturePath = Join-Path $repositoryRoot 'telemetry-service/src/test/resources/fixtures/telemetry/v1/dual-typed-batch.json'

function Assert-LoopbackBaseUrl([string] $Value) {
    $uri = $null
    if (-not [uri]::TryCreate($Value, [UriKind]::Absolute, [ref] $uri) -or
            $uri.Scheme -ne 'http' -or
            $uri.Host -notin @('localhost', '127.0.0.1', '::1') -or
            -not [string]::IsNullOrEmpty($uri.UserInfo) -or
            $uri.AbsolutePath -ne '/' -or
            -not [string]::IsNullOrEmpty($uri.Query) -or
            -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw 'Clinical and Telemetry URLs must be plain HTTP loopback origins.'
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
    if ($matches.Count -ne 1) {
        throw "Expected exactly one public $Key setting in .env."
    }
    $value = $matches[0].Substring($Key.Length + 1).Trim()
    if ($value.Length -ge 2 -and $value[0] -eq '"' -and $value[-1] -eq '"') {
        $value = $value.Substring(1, $value.Length - 2)
    }
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "The public $Key setting is empty."
    }
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
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) | ConvertFrom-Json
    } catch {
        throw 'The access token claims could not be read.'
    }
}

function Invoke-LocalRequest([string] $Method, [string] $Uri, [string] $AccessToken,
        [string] $Body = '', [hashtable] $AdditionalHeaders = @{}) {
    $headers = @{ Authorization = "Bearer $AccessToken" }
    foreach ($key in $AdditionalHeaders.Keys) { $headers[$key] = $AdditionalHeaders[$key] }
    try {
        $args = @{
            Method = $Method
            Uri = $Uri
            Headers = $headers
            UseBasicParsing = $true
            TimeoutSec = 15
            ErrorAction = 'Stop'
        }
        if ($Method -eq 'POST') {
            $args['ContentType'] = 'application/json'
            $args['Body'] = $Body
        }
        $response = Invoke-WebRequest @args
        return @{ Status = [int] $response.StatusCode; Content = $response.Content; ErrorCode = $null }
    } catch {
        $status = 0
        if ($null -ne $_.Exception.Response) {
            $status = [int] $_.Exception.Response.StatusCode
        }
        # Retain only a bounded machine-readable code, never the response payload.
        $errorCode = $null
        try {
            $errorBody = [string] $_.ErrorDetails.Message
            if (-not [string]::IsNullOrWhiteSpace($errorBody)) {
                $errorResponse = ConvertFrom-Json -InputObject $errorBody
                if ($errorResponse.code -is [string] -and
                        $errorResponse.code -cmatch '^[A-Z][A-Z0-9_]{0,63}$') {
                    $errorCode = $errorResponse.code
                }
            }
        } catch { }
        return @{ Status = $status; Content = $null; ErrorCode = $errorCode }
    }
}

function Assert-Status([hashtable] $Response, [int] $Expected, [string] $Step) {
    if ($Response.Status -ne $Expected) {
        $codeSuffix = if ($null -ne $Response.ErrorCode) { " (code $($Response.ErrorCode))" } else { '' }
        throw "$Step failed with HTTP status $($Response.Status)$codeSuffix."
    }
}

function Assert-LocalReadiness([string] $BaseUrl, [string] $ServiceName) {
    $status = 0
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get `
            -Uri "$BaseUrl/q/health/ready" -TimeoutSec 5 -ErrorAction Stop
        $status = [int] $response.StatusCode
    } catch {
        if ($null -ne $_.Exception.Response) {
            $status = [int] $_.Exception.Response.StatusCode
        }
    }
    if ($status -ne 200) {
        throw "$ServiceName readiness failed with HTTP status $status."
    }
}

function Invoke-Smoke {
    $clinical = Assert-LoopbackBaseUrl $ClinicalBaseUrl
    $telemetry = Assert-LoopbackBaseUrl $TelemetryBaseUrl
    if ($PlanId -eq [guid]::Empty -or $ExpectedPatientSubject -eq [guid]::Empty) {
        throw 'PlanId and ExpectedPatientSubject must be nonempty UUIDs.'
    }
    if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
        throw 'The synthetic dual-stream fixture is missing.'
    }
    try { $batch = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json } catch {
        throw 'The synthetic dual-stream fixture is invalid JSON.'
    }
    if ($batch.schemaVersion -ne 1 -or @($batch.gazeSamples).Count -lt 1 -or
            @($batch.gameplaySamples).Count -lt 1) {
        throw 'The synthetic fixture is not a dual-stream V1 batch.'
    }

    $clientId = Get-PublicEnvValue 'COGNITO_CLIENT_ID'
    $issuer = (Get-PublicEnvValue 'COGNITO_ISSUER_URL').TrimEnd('/')
    $poolId = Get-PublicEnvValue 'COGNITO_USER_POOL_ID'
    if ($poolId -notmatch '^us-east-1_[A-Za-z0-9]+$' -or
            $issuer -cne "https://cognito-idp.us-east-1.amazonaws.com/$poolId") {
        throw 'The public Cognito settings do not identify the expected us-east-1 pool.'
    }

    try {
        $discovery = Invoke-RestMethod -Method Get -Uri "$issuer/.well-known/openid-configuration" -TimeoutSec 10
    } catch {
        throw 'Cognito OIDC discovery failed.'
    }
    if ($discovery.issuer -cne $issuer) { throw 'Cognito OIDC discovery returned another issuer.' }
    $authorizationEndpoint = $null
    $tokenEndpoint = $null
    if (-not [uri]::TryCreate([string] $discovery.authorization_endpoint,
                [UriKind]::Absolute, [ref] $authorizationEndpoint) -or
            -not [uri]::TryCreate([string] $discovery.token_endpoint,
                [UriKind]::Absolute, [ref] $tokenEndpoint) -or
            $authorizationEndpoint.Scheme -ne 'https' -or $tokenEndpoint.Scheme -ne 'https') {
        throw 'Cognito OIDC discovery returned invalid endpoints.'
    }

    $verifier = New-RandomBase64Url 64
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $challenge = ConvertTo-Base64Url ($sha256.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))
    } finally { $sha256.Dispose() }
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
    $authorizationUrl = "$($authorizationEndpoint.AbsoluteUri)?$encodedQuery"

    $listener = [Net.HttpListener]::new()
    $listener.Prefixes.Add($listenerPrefix)
    try {
        $listener.Start()
        Write-Host 'PKCE_LOGIN_WAITING: complete PATIENT sign-in in the browser.'
        Start-Process $authorizationUrl

        $callbackTask = $listener.GetContextAsync()
        if (-not $callbackTask.Wait([TimeSpan]::FromMinutes(10))) {
            throw 'Timed out waiting for the PKCE callback.'
        }
        $callback = $callbackTask.Result
        $code = $callback.Request.QueryString['code']
        $returnedState = $callback.Request.QueryString['state']
        $oauthError = $callback.Request.QueryString['error']
        $callbackValid = $callback.Request.Url.AbsolutePath -eq '/auth/callback' -and
            $returnedState -ceq $state -and
            [string]::IsNullOrWhiteSpace($oauthError) -and
            -not [string]::IsNullOrWhiteSpace($code)
        $callback.Response.StatusCode = if ($callbackValid) { 200 } else { 400 }
        $callback.Response.ContentType = 'text/plain; charset=utf-8'
        $message = if ($callbackValid) { 'Authentication complete. Return to the terminal.' } else {
            'Authentication callback rejected. Return to the terminal.'
        }
        $responseBytes = [Text.Encoding]::UTF8.GetBytes($message)
        $callback.Response.ContentLength64 = $responseBytes.Length
        $callback.Response.OutputStream.Write($responseBytes, 0, $responseBytes.Length)
        $callback.Response.Close()
        if (-not $callbackValid) { throw 'The PKCE callback was invalid.' }

        try {
            $tokens = Invoke-RestMethod -Method Post -Uri $tokenEndpoint.AbsoluteUri `
                -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 15 -Body @{
                    grant_type = 'authorization_code'
                    client_id = $clientId
                    code = $code
                    redirect_uri = $redirectUri
                    code_verifier = $verifier
                }
        } catch {
            throw 'Cognito token exchange failed.'
        }
        if ([string]::IsNullOrWhiteSpace($tokens.access_token)) {
            throw 'Cognito did not return an access token.'
        }
        $claims = ConvertFrom-AccessTokenPayload $tokens.access_token
        $groups = @($claims.'cognito:groups')
        $expectedSubject = $ExpectedPatientSubject.ToString()
        if ($claims.iss -cne $issuer -or $claims.sub -cne $expectedSubject -or
                $claims.client_id -cne $clientId -or $claims.token_use -cne 'access' -or
                $groups -cnotcontains 'PATIENT' -or
                $groups -contains 'DOCTOR' -or $groups -contains 'ADMIN' -or
                $null -eq $claims.exp -or [long] $claims.exp -le [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) {
            throw 'The access token does not match the expected PATIENT identity and client.'
        }
        Write-Host 'PKCE_PATIENT_VALIDATED'

        # Never create a clinical session if either local service is already unavailable.
        # Health probes do not carry the patient's access token.
        Assert-LocalReadiness $clinical 'Clinical'
        Assert-LocalReadiness $telemetry 'Telemetry'
        Write-Host 'LOCAL_SERVICES_READY: status=200'

        $planResponse = Invoke-LocalRequest 'GET' "$clinical/api/v1/me/therapy-plans" $tokens.access_token
        Assert-Status $planResponse 200 'Patient plan query'
        try { $plans = @(ConvertFrom-Json -InputObject $planResponse.Content) } catch {
            throw 'The patient plan response was invalid JSON.'
        }
        $matchingPlans = @($plans | Where-Object {
            $_.therapyPlanId -eq $PlanId.ToString() -and $_.status -eq 'ACTIVE' -and
            $_.dailyExercisePrescriptions.FIXATION -ge 1
        })
        if ($matchingPlans.Count -ne 1) { throw 'The expected active FIXATION plan is not visible.' }
        Write-Host 'PLAN_VISIBLE: status=200'

        $idempotencyKey = [guid]::NewGuid().ToString()
        $sessionResponse = Invoke-LocalRequest 'POST' `
            "$clinical/api/v1/me/therapy-plans/$PlanId/sessions" $tokens.access_token `
            '{"exerciseCode":"FIXATION"}' @{ 'Idempotency-Key' = $idempotencyKey }
        Assert-Status $sessionResponse 201 'Session start'
        try { $session = ConvertFrom-Json -InputObject $sessionResponse.Content } catch {
            throw 'The session response was invalid JSON.'
        }
        $sessionId = [guid]::Empty
        if (-not [guid]::TryParse([string] $session.therapySessionId, [ref] $sessionId) -or
                $sessionId -eq [guid]::Empty -or $session.status -ne 'ACTIVE' -or
                $session.exerciseCode -ne 'FIXATION' -or
                $session.therapyPlanId -ne $PlanId.ToString()) {
            throw 'Clinical did not return a new ACTIVE FIXATION session.'
        }
        Write-Host 'SESSION_CREATED: status=201'

        $batch.batchId = [guid]::NewGuid().ToString()
        $batch.therapySessionId = $sessionId.ToString()
        $batch.streamId = [guid]::NewGuid().ToString()
        $batch.sequence = 0
        $batch.capturedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        $batch.sentAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        $batchResponse = Invoke-LocalRequest 'POST' "$telemetry/api/v1/telemetry/batches" `
            $tokens.access_token (ConvertTo-Json -InputObject $batch -Depth 10 -Compress)
        Assert-Status $batchResponse 202 'Telemetry batch ingestion'
        Write-Host ('BATCH_BROKER_ACCEPTED: status=202; gazeSamples={0}; gameplaySamples={1}' -f `
            @($batch.gazeSamples).Count, @($batch.gameplaySamples).Count)
    } finally {
        if ($listener.IsListening) { $listener.Stop() }
        $listener.Close()
    }
}

try {
    Invoke-Smoke
} catch {
    # Never surface an exception from an HTTP client or browser launcher: it can contain a URL or body.
    $safeMessage = if ($_.Exception.Message -match '^(PKCE_|PLAN_|SESSION_|BATCH_)') {
        'The synthetic smoke test failed.'
    } elseif ($_.Exception.Message -match '^.+ failed with HTTP status [0-9]+( \(code [A-Z][A-Z0-9_]{0,63}\))?\.$') {
        $_.Exception.Message
    } else {
        'The synthetic smoke test stopped before completion; check local services and public settings.'
    }
    Write-Error -Message $safeMessage -ErrorAction Continue
    exit 1
}
