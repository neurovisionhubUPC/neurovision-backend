[CmdletBinding()]
param(
    [string]$AwsProfile = "neurovision-iam-dev",
    [string]$StackName = "neurovision-dev-cognito",
    [string]$AwsRegion = "us-east-1"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-LastExitCode {
    param([string]$Operation)

    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE."
    }
}

function New-RandomSecret {
    $bytes = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Read-ExistingEnvironment {
    param([string]$Path)

    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $values
    }
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#=\s]+)=(.*)$') {
            $values[$matches[1]] = $matches[2]
        }
    }
    return $values
}

function Get-OrCreateSecret {
    param(
        [hashtable]$Existing,
        [string]$Name
    )

    if ($Existing.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace($Existing[$Name])) {
        return $Existing[$Name]
    }
    return New-RandomSecret
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$envPath = Join-Path $repositoryRoot ".env"
$existing = Read-ExistingEnvironment $envPath

$awsCli = Join-Path $env:LOCALAPPDATA "Programs\Amazon\AWSCLIV2\aws.exe"
if (-not (Test-Path -LiteralPath $awsCli)) {
    $awsCli = (Get-Command aws -ErrorAction Stop).Source
}

$roleArn = (& $awsCli configure get role_arn --profile $AwsProfile).Trim()
Assert-LastExitCode "Reading role_arn from profile $AwsProfile"
$sourceProfile = (& $awsCli configure get source_profile --profile $AwsProfile).Trim()
Assert-LastExitCode "Reading source_profile from profile $AwsProfile"
if (-not $roleArn -or -not $sourceProfile) {
    throw "Profile $AwsProfile must define role_arn and source_profile."
}

# The limited runtime role intentionally has no CloudFormation permissions.
# Resolve non-secret infrastructure outputs with its administrative source profile,
# then assume the limited role only for the credentials injected into IAM.
$stackJson = & $awsCli cloudformation describe-stacks `
    --stack-name $StackName `
    --region $AwsRegion `
    --profile $sourceProfile `
    --query "Stacks[0].Outputs" `
    --output json
Assert-LastExitCode "Reading Cognito CloudFormation outputs"
$outputs = @{}
foreach ($output in ($stackJson | ConvertFrom-Json)) {
    $outputs[$output.OutputKey] = $output.OutputValue
}
foreach ($requiredOutput in @("UserPoolId", "UserPoolClientId", "IssuerUrl")) {
    if (-not $outputs.ContainsKey($requiredOutput)) {
        throw "CloudFormation output '$requiredOutput' is missing from stack '$StackName'."
    }
}

$sessionName = "neurovision-compose-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$credentialJson = & $awsCli sts assume-role `
    --role-arn $roleArn `
    --role-session-name $sessionName `
    --profile $sourceProfile `
    --duration-seconds 3600 `
    --output json
Assert-LastExitCode "Assuming the limited AWS role configured by $AwsProfile"
$credentials = ($credentialJson | ConvertFrom-Json).Credentials

$lines = @(
    "COMPOSE_PROJECT_NAME=neurovision-rehab"
    "NEUROVISION_IMAGE_TAG=compose-native"
    "IAM_PORT=8081"
    "PROFILE_PORT=8082"
    "CLINICAL_PORT=8083"
    "KAFKA_PORT=9092"
    "IAM_DB_PASSWORD=$(Get-OrCreateSecret $existing 'IAM_DB_PASSWORD')"
    "PROFILE_DB_PASSWORD=$(Get-OrCreateSecret $existing 'PROFILE_DB_PASSWORD')"
    "CLINICAL_DB_PASSWORD=$(Get-OrCreateSecret $existing 'CLINICAL_DB_PASSWORD')"
    "AWS_REGION=$AwsRegion"
    "COGNITO_USER_POOL_ID=$($outputs.UserPoolId)"
    "COGNITO_CLIENT_ID=$($outputs.UserPoolClientId)"
    "COGNITO_ISSUER_URL=$($outputs.IssuerUrl)"
    "AWS_ACCESS_KEY_ID=$($credentials.AccessKeyId)"
    "AWS_SECRET_ACCESS_KEY=$($credentials.SecretAccessKey)"
    "AWS_SESSION_TOKEN=$($credentials.SessionToken)"
)
$lines | Set-Content -LiteralPath $envPath -Encoding Ascii

Write-Output "Local Compose environment written to the Git-ignored .env file."
Write-Output "Temporary AWS credentials expire at $($credentials.Expiration)."
