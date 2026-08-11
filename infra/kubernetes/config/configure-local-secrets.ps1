[CmdletBinding()]
param(
    [string]$AwsProfile = "neurovision-iam-dev",
    [string]$ExpectedContext = "k3d-neurovision-local",
    [string]$Namespace = "neurovision",
    [switch]$RotateDatabaseCredentials,
    [switch]$SkipAws,
    [switch]$SkipTls
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }

    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Assert-LastExitCode {
    param([string]$Operation)

    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE."
    }
}

function Apply-SecretFromEnvFile {
    param(
        [string]$Name,
        [string]$SecretNamespace,
        [string]$Path
    )

    $arguments = @(
        "create", "secret", "generic", $Name,
        "--namespace", $SecretNamespace,
        "--from-env-file=$Path",
        "--dry-run=client",
        "-o", "yaml"
    )
    $manifest = & kubectl @arguments
    Assert-LastExitCode "Rendering Secret $SecretNamespace/$Name"

    $manifest | & kubectl apply -f -
    Assert-LastExitCode "Applying Secret $SecretNamespace/$Name"
}

function Ensure-DatabaseSecret {
    param(
        [string]$Name,
        [string]$Username,
        [string]$SecretNamespace,
        [string]$TemporaryDirectory,
        [bool]$Rotate
    )

    $existingSecret = & kubectl get secret $Name --namespace $SecretNamespace --ignore-not-found -o name
    Assert-LastExitCode "Checking Secret $SecretNamespace/$Name"
    if ($existingSecret -and -not $Rotate) {
        Write-Output "Secret $SecretNamespace/$Name already exists; its password was preserved."
        return
    }

    $password = New-RandomSecret
    $envFile = Join-Path $TemporaryDirectory "$Name.env"
    @(
        "QUARKUS_DATASOURCE_USERNAME=$Username"
        "QUARKUS_DATASOURCE_PASSWORD=$password"
    ) | Set-Content -LiteralPath $envFile -Encoding Ascii

    Apply-SecretFromEnvFile -Name $Name -SecretNamespace $SecretNamespace -Path $envFile
}

$currentContext = (& kubectl config current-context).Trim()
Assert-LastExitCode "Reading the current Kubernetes context"
if ($currentContext -ne $ExpectedContext) {
    throw "Refusing to write Secrets to context '$currentContext'. Expected '$ExpectedContext'."
}

& kubectl get namespace $Namespace *> $null
Assert-LastExitCode "Checking namespace $Namespace"

$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("neurovision-secrets-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null

try {
    Ensure-DatabaseSecret -Name "iam-database-credentials" -Username "neurovision_iam" -SecretNamespace $Namespace -TemporaryDirectory $temporaryRoot -Rotate $RotateDatabaseCredentials.IsPresent
    Ensure-DatabaseSecret -Name "profile-database-credentials" -Username "neurovision_profile" -SecretNamespace $Namespace -TemporaryDirectory $temporaryRoot -Rotate $RotateDatabaseCredentials.IsPresent

    if (-not $SkipAws) {
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

        $sessionName = "neurovision-k3d-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $credentialJson = & $awsCli sts assume-role --role-arn $roleArn --role-session-name $sessionName --profile $sourceProfile --duration-seconds 3600 --output json
        Assert-LastExitCode "Assuming the limited AWS role configured by $AwsProfile"

        $credentials = ($credentialJson | ConvertFrom-Json).Credentials
        $awsEnvFile = Join-Path $temporaryRoot "iam-aws-credentials.env"
        @(
            "AWS_ACCESS_KEY_ID=$($credentials.AccessKeyId)"
            "AWS_SECRET_ACCESS_KEY=$($credentials.SecretAccessKey)"
            "AWS_SESSION_TOKEN=$($credentials.SessionToken)"
        ) | Set-Content -LiteralPath $awsEnvFile -Encoding Ascii

        Apply-SecretFromEnvFile -Name "iam-aws-credentials" -SecretNamespace $Namespace -Path $awsEnvFile
        Write-Output "Temporary AWS credentials expire at $($credentials.Expiration)."
    }

    if (-not $SkipTls) {
        $mkcert = (Get-Command mkcert -ErrorAction Stop).Source
        $previousTrustStores = $env:TRUST_STORES
        try {
            $env:TRUST_STORES = "system"
            & $mkcert -install
            Assert-LastExitCode "Installing the local mkcert CA"
        }
        finally {
            if ($null -eq $previousTrustStores) {
                Remove-Item Env:TRUST_STORES -ErrorAction SilentlyContinue
            }
            else {
                $env:TRUST_STORES = $previousTrustStores
            }
        }

        $certificatePath = Join-Path $temporaryRoot "tls.crt"
        $privateKeyPath = Join-Path $temporaryRoot "tls.key"
        $mkcertArguments = @(
            "-cert-file", $certificatePath,
            "-key-file", $privateKeyPath,
            "api.neurovision.localhost",
            "localhost",
            "127.0.0.1",
            "::1"
        )
        & $mkcert @mkcertArguments
        Assert-LastExitCode "Generating the local TLS certificate"

        $tlsArguments = @(
            "create", "secret", "tls", "neurovision-local-tls",
            "--namespace", "kube-system",
            "--cert=$certificatePath",
            "--key=$privateKeyPath",
            "--dry-run=client",
            "-o", "yaml"
        )
        $tlsManifest = & kubectl @tlsArguments
        Assert-LastExitCode "Rendering Secret kube-system/neurovision-local-tls"

        $tlsManifest | & kubectl apply -f -
        Assert-LastExitCode "Applying Secret kube-system/neurovision-local-tls"
    }
}
finally {
    $resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
    $resolvedSystemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemporaryRoot.StartsWith($resolvedSystemTemp) -and (Test-Path -LiteralPath $resolvedTemporaryRoot)) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}

Write-Output "Local Kubernetes Secrets are configured without writing secret values to the repository."
