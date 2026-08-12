[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = "High")]
<#
.SYNOPSIS
Bootstraps the first NeuroVision administrator in the development Cognito User Pool.

.DESCRIPTION
Creates an administrator with a Cognito-generated temporary password and assigns the
user to the ADMIN group. Re-running the script for an existing ADMIN is idempotent.
An existing non-ADMIN user is never elevated unless -AllowExistingUser is explicit.

.EXAMPLE
.\infra\aws\cognito\bootstrap-admin.ps1 `
  -AdminEmail "controlled-admin@example.com"

.EXAMPLE
.\infra\aws\cognito\bootstrap-admin.ps1 `
  -AdminEmail "controlled-admin@example.com" `
  -WhatIf
#>
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string] $AdminEmail,

    [string] $StackName = "neurovision-dev-cognito",
    [string] $AwsProfile = "neurovision-personal",
    [string] $AwsRegion = "us-east-1",

    [switch] $AllowExistingUser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-AwsCli {
    param(
        [Parameter(Mandatory)]
        [string[]] $Arguments,

        [Parameter(Mandatory)]
        [string] $Operation
    )

    # Windows PowerShell turns native stderr into ErrorRecord objects. Temporarily
    # keep those records capturable so this wrapper can report the failed operation.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:AwsCli @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        $details = ($output | Out-String).Trim()
        throw "$Operation failed. $details"
    }
    return ($output | Out-String).Trim()
}

function ConvertTo-NormalizedEmail {
    param([string] $Value)

    try {
        $mailAddress = [Net.Mail.MailAddress]::new($Value.Trim())
    }
    catch {
        throw "AdminEmail must be a valid email address."
    }

    if ($mailAddress.Address -ne $Value.Trim()) {
        throw "AdminEmail must contain only the email address, without a display name."
    }
    return $mailAddress.Address.ToLowerInvariant()
}

$script:AwsCli = (Get-Command aws -ErrorAction Stop).Source
$normalizedEmail = ConvertTo-NormalizedEmail $AdminEmail

$callerArn = Invoke-AwsCli `
    -Operation "Resolving AWS caller identity" `
    -Arguments @(
        "sts", "get-caller-identity",
        "--profile", $AwsProfile,
        "--query", "Arn",
        "--output", "text"
    )

if ($callerArn -notmatch '^arn:(aws|aws-us-gov|aws-cn):iam::[0-9]{12}:user/.+$') {
    throw "Profile '$AwsProfile' must resolve to a non-root IAM user. Actual ARN: $callerArn"
}

$userPoolId = Invoke-AwsCli `
    -Operation "Resolving the Cognito User Pool" `
    -Arguments @(
        "cloudformation", "describe-stacks",
        "--stack-name", $StackName,
        "--region", $AwsRegion,
        "--profile", $AwsProfile,
        "--query", "Stacks[0].Outputs[?OutputKey=='UserPoolId'].OutputValue | [0]",
        "--output", "text"
    )

if ([string]::IsNullOrWhiteSpace($userPoolId) -or $userPoolId -eq "None") {
    throw "Stack '$StackName' does not expose UserPoolId."
}

$null = Invoke-AwsCli `
    -Operation "Verifying the Cognito ADMIN group" `
    -Arguments @(
        "cognito-idp", "get-group",
        "--group-name", "ADMIN",
        "--user-pool-id", $userPoolId,
        "--region", $AwsRegion,
        "--profile", $AwsProfile,
        "--output", "json"
    )

# AWS CLI v2 on Windows requires the embedded Cognito filter quotes to survive
# native argument serialization, hence the literal backslash escapes.
$emailFilter = 'email = \"' + $normalizedEmail + '\"'
$username = Invoke-AwsCli `
    -Operation "Looking up the administrator email in Cognito" `
    -Arguments @(
        "cognito-idp", "list-users",
        "--user-pool-id", $userPoolId,
        "--filter", $emailFilter,
        "--region", $AwsRegion,
        "--profile", $AwsProfile,
        "--query", "Users[0].Username",
        "--output", "text"
    )

$userExists = -not [string]::IsNullOrWhiteSpace($username) -and $username -ne "None"
if ($userExists) {
    $groupNamesJson = Invoke-AwsCli `
        -Operation "Reading the existing user's Cognito groups" `
        -Arguments @(
            "cognito-idp", "admin-list-groups-for-user",
            "--username", $username,
            "--user-pool-id", $userPoolId,
            "--region", $AwsRegion,
            "--profile", $AwsProfile,
            "--query", "Groups[].GroupName",
            "--output", "json"
        )
    # Windows PowerShell 5.1 wraps a JSON array in another Object[] when
    # ConvertFrom-Json is used through a pipeline, breaking -contains checks.
    $parsedGroupNames = ConvertFrom-Json -InputObject $groupNamesJson
    $groupNames = @($parsedGroupNames)

    if ($groupNames -contains "ADMIN") {
        Write-Output "Administrator bootstrap is already complete for $normalizedEmail."
        Write-Output "Cognito username: $username"
        return
    }

    if (-not $AllowExistingUser) {
        throw "A Cognito user already exists for $normalizedEmail but is not an ADMIN. Re-run with -AllowExistingUser only after verifying that this privilege elevation is intentional."
    }
}

$createdByThisRun = $false
try {
    if (-not $userExists) {
        if (-not $PSCmdlet.ShouldProcess($normalizedEmail, "Create Cognito administrator with a temporary password")) {
            return
        }

        $username = Invoke-AwsCli `
            -Operation "Creating the Cognito administrator" `
            -Arguments @(
                "cognito-idp", "admin-create-user",
                "--user-pool-id", $userPoolId,
                "--username", $normalizedEmail,
                "--user-attributes", "Name=email,Value=$normalizedEmail",
                "--desired-delivery-mediums", "EMAIL",
                "--region", $AwsRegion,
                "--profile", $AwsProfile,
                "--query", "User.Username",
                "--output", "text"
            )
        $createdByThisRun = $true
    }
    elseif (-not $PSCmdlet.ShouldProcess($normalizedEmail, "Elevate existing Cognito user to ADMIN")) {
        return
    }

    $null = Invoke-AwsCli `
        -Operation "Assigning the Cognito ADMIN group" `
        -Arguments @(
            "cognito-idp", "admin-add-user-to-group",
            "--user-pool-id", $userPoolId,
            "--username", $username,
            "--group-name", "ADMIN",
            "--region", $AwsRegion,
            "--profile", $AwsProfile
        )
}
catch {
    if ($createdByThisRun -and -not [string]::IsNullOrWhiteSpace($username)) {
        try {
            $null = Invoke-AwsCli `
                -Operation "Compensating the incomplete administrator bootstrap" `
                -Arguments @(
                    "cognito-idp", "admin-delete-user",
                    "--user-pool-id", $userPoolId,
                    "--username", $username,
                    "--region", $AwsRegion,
                    "--profile", $AwsProfile
                )
        }
        catch {
            Write-Warning "The incomplete Cognito user could not be removed automatically: $($_.Exception.Message)"
        }
    }
    throw
}

$verifiedGroupsJson = Invoke-AwsCli `
    -Operation "Verifying the administrator group assignment" `
    -Arguments @(
        "cognito-idp", "admin-list-groups-for-user",
        "--username", $username,
        "--user-pool-id", $userPoolId,
        "--region", $AwsRegion,
        "--profile", $AwsProfile,
        "--query", "Groups[].GroupName",
        "--output", "json"
    )
$parsedVerifiedGroups = ConvertFrom-Json -InputObject $verifiedGroupsJson
$verifiedGroups = @($parsedVerifiedGroups)
if ($verifiedGroups -notcontains "ADMIN") {
    throw "Cognito did not confirm the ADMIN group assignment."
}

Write-Output "Administrator bootstrap completed for $normalizedEmail."
Write-Output "Cognito username: $username"
Write-Output "Cognito will require the temporary password to be replaced at first sign-in."
