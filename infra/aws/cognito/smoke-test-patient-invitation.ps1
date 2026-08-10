[CmdletBinding()]
param(
    [string] $PatientEmail = "u202114714@upc.edu.pe",
    [string] $StackName = "neurovision-dev-cognito",
    [string] $AwsProfile = "neurovision-personal",
    [string] $AwsRegion = "us-east-1",
    [string] $IamServiceBaseUrl = "http://localhost:8080"
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

try {
    Invoke-WebRequest -UseBasicParsing -Uri "$IamServiceBaseUrl/q/health/ready" -TimeoutSec 5 | Out-Null
} catch {
    throw "iam-service is not ready at $IamServiceBaseUrl. Start it before running this smoke test."
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
}
$query = ($authorizationParameters.GetEnumerator() | ForEach-Object {
    "{0}={1}" -f [Uri]::EscapeDataString($_.Key), [Uri]::EscapeDataString($_.Value)
}) -join "&"
$authorizationUrl = "$authorizationEndpoint`?$query"

$listener = [Net.HttpListener]::new()
$listener.Prefixes.Add($listenerPrefix)
$listener.Start()

try {
    Write-Host "Opening Cognito Managed Login. Complete the temporary-password challenge in the browser."
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

    $invitation = Invoke-RestMethod `
        -Method Post `
        -Uri "$IamServiceBaseUrl/api/v1/patient-invitations" `
        -ContentType "application/json" `
        -Headers @{ Authorization = "Bearer $($tokens.access_token)" } `
        -Body (@{ patientEmail = $PatientEmail } | ConvertTo-Json -Compress)

    Write-Host "Smoke test passed: Cognito authenticated the doctor and iam-service created the patient invitation."
    $invitation
} finally {
    if ($listener.IsListening) {
        $listener.Stop()
    }
    $listener.Close()
}
