# Shared only by the explicit live capacity path. Dot-sourcing defines functions only.

function Get-CapacityPublicSetting([string] $Key, [string] $EnvPath) {
    if ($Key -cnotin @('COGNITO_CLIENT_ID', 'COGNITO_ISSUER_URL', 'COGNITO_USER_POOL_ID')) {
        throw 'The requested public OIDC setting is not allowlisted.'
    }
    if (-not (Test-Path -LiteralPath $EnvPath -PathType Leaf)) {
        throw 'Local public OIDC settings are unavailable.'
    }
    $matches = @(Get-Content -LiteralPath $EnvPath | Where-Object {
        $_ -match ('^' + [regex]::Escape($Key) + '=')
    })
    if ($matches.Count -ne 1) { throw 'A public OIDC setting is missing or ambiguous.' }
    $value = $matches[0].Substring($Key.Length + 1).Trim().Trim('"')
    if ([string]::IsNullOrWhiteSpace($value)) { throw 'A public OIDC setting is empty.' }
    return $value
}

function ConvertFrom-CapacityAccessToken([string] $Token) {
    $parts = $Token.Split('.')
    if ($parts.Count -ne 3) { throw 'PKCE token validation failed.' }
    $payload = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) {
        1 { throw 'PKCE token validation failed.' }
        2 { $payload += '==' }
        3 { $payload += '=' }
    }
    try {
        $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload))
        return ConvertFrom-Json -InputObject $json
    } catch { throw 'PKCE token validation failed.' }
}

function ConvertTo-CapacityBase64Url([byte[]] $Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-CapacityRandomBase64Url([int] $ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ConvertTo-CapacityBase64Url $bytes
}

function New-CapacityAuthorizationParameters([string] $ClientId, [string] $RedirectUri,
        [string] $State, [string] $Challenge, [string] $Nonce) {
    return [ordered]@{
        client_id = $ClientId; response_type = 'code'; redirect_uri = $RedirectUri
        scope = 'openid email profile'; state = $State; nonce = $Nonce
        code_challenge = $Challenge; code_challenge_method = 'S256'; prompt = 'login'
    }
}

function Assert-CapacityIdTokenNonce([object] $Claims, [string] $Issuer,
        [string] $ClientId, [string] $Nonce) {
    # This is only an OIDC nonce/claim consistency precheck; the Telemetry API validates the access-token signature.
    if ($null -eq $Claims -or $Claims.iss -cne $Issuer -or
            $Claims.aud -cne $ClientId -or $null -eq $Claims.exp -or
            [long] $Claims.exp -le [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() -or
            [string]::IsNullOrWhiteSpace([string] $Claims.nonce) -or $Claims.nonce -cne $Nonce) {
        throw 'OIDC ID-token nonce validation failed.'
    }
}

function Get-CapacityPatientAccessToken([string] $EnvPath) {
    $clientId = Get-CapacityPublicSetting 'COGNITO_CLIENT_ID' $EnvPath
    $issuer = (Get-CapacityPublicSetting 'COGNITO_ISSUER_URL' $EnvPath).TrimEnd('/')
    $poolId = Get-CapacityPublicSetting 'COGNITO_USER_POOL_ID' $EnvPath
    if ($poolId -notmatch '^us-east-1_[A-Za-z0-9]+$' -or
            $issuer -cne "https://cognito-idp.us-east-1.amazonaws.com/$poolId") {
        throw 'The configured development OIDC issuer is invalid.'
    }
    try {
        $discovery = Invoke-RestMethod -Method Get -Uri "$issuer/.well-known/openid-configuration" `
            -TimeoutSec 10 -ErrorAction Stop
    } catch { throw 'Development OIDC discovery failed.' }
    if ($discovery.issuer -cne $issuer) { throw 'Development OIDC issuer did not match.' }
    $authorize = $null
    $tokenEndpoint = $null
    if (-not [uri]::TryCreate([string] $discovery.authorization_endpoint,
                [UriKind]::Absolute, [ref] $authorize) -or
            -not [uri]::TryCreate([string] $discovery.token_endpoint,
                [UriKind]::Absolute, [ref] $tokenEndpoint) -or
            $authorize.Scheme -ne 'https' -or $tokenEndpoint.Scheme -ne 'https' -or
            $authorize.Host -notlike '*.auth.us-east-1.amazoncognito.com' -or
            $tokenEndpoint.Host -cne $authorize.Host) {
        throw 'Development OIDC endpoints are invalid.'
    }

    $redirectUri = 'http://localhost:5173/auth/callback'
    $listener = [Net.HttpListener]::new()
    $listener.Prefixes.Add('http://localhost:5173/')
    $verifier = New-CapacityRandomBase64Url 64
    $state = New-CapacityRandomBase64Url 32
    $nonce = New-CapacityRandomBase64Url 32
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $challenge = ConvertTo-CapacityBase64Url (
            $sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))
    } finally { $sha.Dispose() }
    $query = New-CapacityAuthorizationParameters $clientId $redirectUri $state $challenge $nonce
    $encoded = ($query.GetEnumerator() | ForEach-Object {
        '{0}={1}' -f [uri]::EscapeDataString($_.Key), [uri]::EscapeDataString($_.Value)
    }) -join '&'
    try {
        $listener.Start()
        Write-Host 'PKCE_LOGIN_WAITING: complete PATIENT sign-in in the browser.'
        Start-Process ("{0}?{1}" -f $authorize.AbsoluteUri, $encoded)
        $callbackTask = $listener.GetContextAsync()
        if (-not $callbackTask.Wait([TimeSpan]::FromMinutes(10))) {
            throw 'PKCE callback timed out.'
        }
        $callback = $callbackTask.Result
        $code = $callback.Request.QueryString['code']
        $valid = $callback.Request.Url.AbsolutePath -ceq '/auth/callback' -and
            $callback.Request.QueryString['state'] -ceq $state -and
            [string]::IsNullOrEmpty($callback.Request.QueryString['error']) -and
            -not [string]::IsNullOrWhiteSpace($code)
        $callback.Response.StatusCode = if ($valid) { 200 } else { 400 }
        $callback.Response.ContentType = 'text/plain; charset=utf-8'
        $message = if ($valid) { 'Authentication complete. Return to the terminal.' } `
            else { 'Authentication callback rejected. Return to the terminal.' }
        $bytes = [Text.Encoding]::UTF8.GetBytes($message)
        $callback.Response.ContentLength64 = $bytes.Length
        $callback.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $callback.Response.Close()
        if (-not $valid) { throw 'PKCE callback validation failed.' }
        try {
            $tokens = Invoke-RestMethod -Method Post -Uri $tokenEndpoint.AbsoluteUri `
                -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 15 -ErrorAction Stop -Body @{
                    grant_type = 'authorization_code'; client_id = $clientId; code = $code
                    redirect_uri = $redirectUri; code_verifier = $verifier
                }
        } catch { throw 'Development OIDC token exchange failed.' }
        if ([string]::IsNullOrWhiteSpace([string] $tokens.access_token) -or
                [string]::IsNullOrWhiteSpace([string] $tokens.id_token)) {
            throw 'Development OIDC returned no required token.'
        }
        $idClaims = ConvertFrom-CapacityAccessToken ([string] $tokens.id_token)
        Assert-CapacityIdTokenNonce $idClaims $issuer $clientId $nonce
        $claims = ConvertFrom-CapacityAccessToken ([string] $tokens.access_token)
        $groups = @($claims.'cognito:groups')
        if ($claims.iss -cne $issuer -or [string]::IsNullOrWhiteSpace([string] $claims.sub) -or
                $claims.client_id -cne $clientId -or $claims.token_use -cne 'access' -or
                $groups -cnotcontains 'PATIENT' -or $groups -contains 'DOCTOR' -or
                $groups -contains 'ADMIN' -or $null -eq $claims.exp -or
                [long] $claims.exp -le [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) {
            throw 'The authenticated identity is not a valid PATIENT access token.'
        }
        Write-Host 'PKCE_PATIENT_VALIDATED'
        return [string] $tokens.access_token
    } finally {
        if ($listener.IsListening) { $listener.Stop() }
        $listener.Close()
    }
}
