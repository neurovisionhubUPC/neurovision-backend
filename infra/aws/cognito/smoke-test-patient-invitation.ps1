[CmdletBinding()]
<#
.SYNOPSIS
Runs a real Cognito PKCE IAM smoke test against a reachable IAM service.

.EXAMPLE
./infra/aws/cognito/smoke-test-patient-invitation.ps1 `
  -PatientEmail "synthetic-patient@example.com"

Uses the local k3d HTTPS Gateway at https://localhost:8443.

.EXAMPLE
./infra/aws/cognito/smoke-test-patient-invitation.ps1 `
  -PatientEmail "synthetic-patient@example.com" `
  -IamServiceBaseUrl "http://localhost:8080"

Uses iam-service running directly in Quarkus Dev Mode. PostgreSQL and Kafka can be
provided by Quarkus Dev Services; Cognito remains the real AWS development pool.
#>
param(
    [ValidateSet("InvitePatient", "ActivateCurrentPatient")]
    [string] $Operation = "InvitePatient",
    [string] $PatientEmail = "u202114714@upc.edu.pe",
    [string] $StackName = "neurovision-dev-cognito",
    [string] $AwsProfile = "neurovision-personal",
    [string] $AwsRegion = "us-east-1",
    [string] $IamServiceBaseUrl = "https://localhost:8443"
)

$ErrorActionPreference = "Stop"
$redirectUri = "http://localhost:5173/auth/callback"
$listenerPrefix = "http://localhost:5173/"

function ConvertTo-Base64Url([byte[]] $Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function New-RandomBase64Url([int] $ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    } finally {
        $random.Dispose()
    }
    return ConvertTo-Base64Url $bytes
}

function ConvertFrom-JwtPayload([string] $Token) {
    $segments = $Token.Split(".")
    if ($segments.Count -ne 3) {
        throw "Cognito returned a malformed access token."
    }
    $payload = $segments[1].Replace("-", "+").Replace("_", "/")
    switch ($payload.Length % 4) {
        2 { $payload += "==" }
        3 { $payload += "=" }
    }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) |
        ConvertFrom-Json
}

function Get-StackOutput([string] $OutputKey) {
    $value = aws cloudformation describe-stacks `
        --stack-name $StackName `
        --region $AwsRegion `
        --profile $AwsProfile `
        --query "Stacks[0].Outputs[?OutputKey=='$OutputKey'].OutputValue | [0]" `
        --output text

    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value) -or $value -eq "None") {
        throw "Unable to resolve CloudFormation output '$OutputKey'."
    }
    return $value.Trim()
}

$invitationEndpoint = "$IamServiceBaseUrl/api/v1/patient-invitations"
$activationEndpoint = "$invitationEndpoint/me/activation"
$preflightEndpoint = if ($Operation -eq "ActivateCurrentPatient") {
    $activationEndpoint
} else {
    $invitationEndpoint
}
$preflightStatus = $null
try {
    $preflightResponse = Invoke-WebRequest `
        -UseBasicParsing `
        -Method Get `
        -Uri $preflightEndpoint `
        -TimeoutSec 5
    $preflightStatus = [int] $preflightResponse.StatusCode
} catch {
    if ($null -ne $_.Exception.Response) {
        $preflightStatus = [int] $_.Exception.Response.StatusCode
    } else {
        throw "iam-service is not reachable through $IamServiceBaseUrl. $($_.Exception.Message)"
    }
}

if ($preflightStatus -notin @(401, 405)) {
    throw "Unexpected IAM preflight status $preflightStatus at $preflightEndpoint."
}

$clientId = Get-StackOutput "UserPoolClientId"
$authorizationEndpoint = Get-StackOutput "AuthorizationEndpoint"
$tokenEndpoint = Get-StackOutput "TokenEndpoint"

$codeVerifier = New-RandomBase64Url 64
$verifierBytes = [Text.Encoding]::ASCII.GetBytes($codeVerifier)
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $codeChallenge = ConvertTo-Base64Url ($sha256.ComputeHash($verifierBytes))
} finally {
    $sha256.Dispose()
}
$state = New-RandomBase64Url 32

$authorizationParameters = [ordered]@{
    client_id = $clientId
    response_type = "code"
    redirect_uri = $redirectUri
    scope = "openid email profile"
    state = $state
    code_challenge = $codeChallenge
    code_challenge_method = "S256"
    prompt = "login"
}
$query = ($authorizationParameters.GetEnumerator() | ForEach-Object {
    "{0}={1}" -f [Uri]::EscapeDataString($_.Key), [Uri]::EscapeDataString($_.Value)
}) -join "&"
$authorizationUrl = "$authorizationEndpoint`?$query"

$listener = [Net.HttpListener]::new()
$listener.Prefixes.Add($listenerPrefix)
$listener.Start()

try {
    Write-Host "Opening Cognito Managed Login. Complete authentication in the browser."
    Start-Process $authorizationUrl

    $callbackTask = $listener.GetContextAsync()
    if (-not $callbackTask.Wait([TimeSpan]::FromMinutes(10))) {
        throw "Timed out waiting for the Cognito callback."
    }

    $context = $callbackTask.Result
    $callbackState = $context.Request.QueryString["state"]
    $authorizationCode = $context.Request.QueryString["code"]
    $oauthError = $context.Request.QueryString["error"]

    if ($callbackState -ne $state) {
        throw "The OAuth state returned by Cognito is invalid."
    }
    if (-not [string]::IsNullOrWhiteSpace($oauthError)) {
        throw "Cognito returned OAuth error '$oauthError'."
    }
    if ([string]::IsNullOrWhiteSpace($authorizationCode)) {
        throw "Cognito did not return an authorization code."
    }

    $html = "<html><body><h1>Authentication completed</h1><p>You can return to the terminal.</p></body></html>"
    $responseBytes = [Text.Encoding]::UTF8.GetBytes($html)
    $context.Response.StatusCode = 200
    $context.Response.ContentType = "text/html; charset=utf-8"
    $context.Response.ContentLength64 = $responseBytes.Length
    $context.Response.OutputStream.Write($responseBytes, 0, $responseBytes.Length)
    $context.Response.Close()

    $tokens = Invoke-RestMethod `
        -Method Post `
        -Uri $tokenEndpoint `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "authorization_code"
            client_id = $clientId
            code = $authorizationCode
            redirect_uri = $redirectUri
            code_verifier = $codeVerifier
        }

    $accessTokenClaims = ConvertFrom-JwtPayload $tokens.access_token

    if ($Operation -eq "ActivateCurrentPatient") {
        $groups = @($accessTokenClaims.'cognito:groups')
        if ($groups -notcontains "PATIENT") {
            throw "The authenticated Cognito account is not in the PATIENT group. Groups: $($groups -join ', ')."
        }
        if ($null -eq $accessTokenClaims.auth_time) {
            throw "The Cognito access token does not contain auth_time."
        }
        Write-Host "Validated Cognito access token: group=PATIENT, auth_time=$($accessTokenClaims.auth_time)."
        $activation = Invoke-RestMethod `
            -Method Put `
            -Uri $activationEndpoint `
            -Headers @{ Authorization = "Bearer $($tokens.access_token)" }

        Write-Host "Smoke test passed: Cognito authenticated the patient and iam-service recorded account activation."
        $activation
    } else {
        $invitation = Invoke-RestMethod `
            -Method Post `
            -Uri $invitationEndpoint `
            -ContentType "application/json" `
            -Headers @{ Authorization = "Bearer $($tokens.access_token)" } `
            -Body (@{ patientEmail = $PatientEmail } | ConvertTo-Json -Compress)

        Write-Host "Smoke test passed: Cognito authenticated the doctor and iam-service created the patient invitation."
        $invitation
    }
} finally {
    if ($listener.IsListening) {
        $listener.Stop()
    }
    $listener.Close()
}
