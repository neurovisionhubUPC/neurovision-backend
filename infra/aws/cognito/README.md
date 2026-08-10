# Manage Cognito and the local IAM role

This directory reproduces the development User Pool and the least-privilege role used by `iam-service`. Use synthetic data only; this environment is not approved for real patient or clinical data.

## Prerequisites

- AWS CLI authenticated through the `neurovision-personal` profile.
- A non-root IAM user behind that profile.
- Permission to deploy CloudFormation, Cognito, and the development IAM role.
- A PowerShell terminal opened in this directory.

Real account IDs, User Pool IDs, App Client IDs, credentials, and tokens are never committed.

## PowerShell quick path

Set the local values and resolve the developer identity from the configured profile:

```powershell
$AwsRegion = "us-east-1"
$StackName = "neurovision-dev-cognito"
$WebCallbackUrls = "http://localhost:5173/auth/callback"
$WebLogoutUrls = "http://localhost:5173/"
$DeveloperPrincipalArn = aws sts get-caller-identity `
  --profile neurovision-personal `
  --query Arn `
  --output text

$DeveloperPrincipalArn
```

The returned ARN must identify an IAM user. Do not continue if it ends in `:root` or is an `arn:aws:sts` assumed-role session.

Validate and deploy the stack:

```powershell
aws cloudformation validate-template `
  --template-body file://template.yaml `
  --region $AwsRegion `
  --profile neurovision-personal

aws cloudformation deploy `
  --stack-name $StackName `
  --template-file template.yaml `
  --parameter-overrides Environment=dev DeveloperPrincipalArn=$DeveloperPrincipalArn WebCallbackUrls=$WebCallbackUrls WebLogoutUrls=$WebLogoutUrls `
  --capabilities CAPABILITY_NAMED_IAM `
  --region $AwsRegion `
  --profile neurovision-personal `
  --no-fail-on-empty-changeset
```

`CAPABILITY_NAMED_IAM` is mandatory because the template creates the physical role name `neurovision-iam-dev-role`. `CAPABILITY_IAM` is not sufficient for this template.

## Web login and future deployment URLs

The development React application uses these fixed URLs:

| Purpose | Development value |
| --- | --- |
| Web origin | `http://localhost:5173` |
| OAuth callback | `http://localhost:5173/auth/callback` |
| Post-logout redirect | `http://localhost:5173/` |

Configure Vite with `server.port: 5173` and `server.strictPort: true`. Cognito requires the callback URL to match an allowed value, so Vite must not silently switch to another port.

When the web application is deployed, pass its HTTPS URLs instead of editing account-specific values into the template:

```powershell
$WebCallbackUrls = "https://app.example.com/auth/callback"
$WebLogoutUrls = "https://app.example.com/"
```

For a temporary transition where both local development and the deployed application must use the same stack, provide comma-separated values:

```powershell
$WebCallbackUrls = "http://localhost:5173/auth/callback,https://app.example.com/auth/callback"
$WebLogoutUrls = "http://localhost:5173/,https://app.example.com/"
```

Replace `app.example.com` with the real production hostname. Production should ultimately have its own stack and should not allow localhost redirects.

> **CORS is separate:** `WebCallbackUrls` and `WebLogoutUrls` protect OAuth redirects; they do not configure CORS for the REST APIs. Add the deployed web origin, such as `https://app.example.com`, to the future Ingress/API CORS allowlist. Cognito managed login does not support a custom CORS origin policy.

The app client is public (`GenerateSecret: false`) and permits only the OAuth authorization-code grant. The React application must generate and send the PKCE `code_verifier` and `code_challenge`; PKCE is client behavior, not a CloudFormation switch.

The template also assigns Cognito's default managed-login branding to the app client. Managed Login v2 does not expose usable login pages for a programmatically created app client until a branding style is associated with it.

## Configure the assumed-role profile

Read the role ARN from the stack, then create a profile that assumes it through `neurovision-personal`:

```powershell
$RoleArn = aws cloudformation describe-stacks `
  --stack-name $StackName `
  --region $AwsRegion `
  --profile neurovision-personal `
  --query "Stacks[0].Outputs[?OutputKey=='IamServiceDevelopmentRoleArn'].OutputValue" `
  --output text

aws configure set role_arn $RoleArn --profile neurovision-iam-dev
aws configure set source_profile neurovision-personal --profile neurovision-iam-dev
aws configure set role_session_name neurovision-iam-dev --profile neurovision-iam-dev
aws configure set region $AwsRegion --profile neurovision-iam-dev

aws sts get-caller-identity --profile neurovision-iam-dev
```

The verification ARN must look like:

```text
arn:aws:sts::<account-id>:assumed-role/neurovision-iam-dev-role/neurovision-iam-dev
```

The AWS CLI can use that role profile directly. For local Java SDK compatibility with credentials created by `aws login`, expose the same temporary role credentials through a process profile (no access keys are stored):

```powershell
$PersonalAwsCli = "$env:LOCALAPPDATA\Programs\Amazon\AWSCLIV2\aws.exe"
$CredentialProcess = "$PersonalAwsCli configure export-credentials --profile neurovision-iam-dev --format process"

aws configure set credential_process $CredentialProcess --profile neurovision-iam-dev-sdk
aws configure set region $AwsRegion --profile neurovision-iam-dev-sdk

aws sts get-caller-identity --profile neurovision-iam-dev-sdk
```

Select the SDK bridge profile only in the terminal that runs the project:

```powershell
$env:AWS_PROFILE = "neurovision-iam-dev-sdk"
```

Remove it when the local session ends:

```powershell
Remove-Item Env:AWS_PROFILE -ErrorAction SilentlyContinue
```

Assumed-role credentials are temporary and expire automatically. Closing the terminal removes the environment selection but does not delete the AWS CLI cache. To close the console-login session explicitly, run:

```powershell
aws logout --profile neurovision-personal
```

## Inspect the stack

Show every stack output:

```powershell
aws cloudformation describe-stacks `
  --stack-name $StackName `
  --region $AwsRegion `
  --profile neurovision-personal `
  --query "Stacks[0].Outputs" `
  --output table
```

Confirm the Cognito groups:

```powershell
$UserPoolId = aws cloudformation describe-stacks `
  --stack-name $StackName `
  --region $AwsRegion `
  --profile neurovision-personal `
  --query "Stacks[0].Outputs[?OutputKey=='UserPoolId'].OutputValue" `
  --output text

aws cognito-idp list-groups `
  --user-pool-id $UserPoolId `
  --region $AwsRegion `
  --profile neurovision-personal `
  --query "Groups[].{Name:GroupName,Description:Description}" `
  --output table
```

The result must contain `DOCTOR` and `PATIENT`.

## CloudShell alternative

CloudShell can deploy the same template after it is uploaded. Resolve a persistent IAM user ARN rather than using an STS session ARN:

```bash
export AWS_REGION=us-east-1
export STACK_NAME=neurovision-dev-cognito
export DEVELOPER_PRINCIPAL_ARN='arn:aws:iam::<account-id>:user/<developer-user>'
export WEB_CALLBACK_URLS='http://localhost:5173/auth/callback'
export WEB_LOGOUT_URLS='http://localhost:5173/'

aws cloudformation deploy \
  --stack-name "$STACK_NAME" \
  --template-file template.yaml \
  --parameter-overrides Environment=dev DeveloperPrincipalArn="$DEVELOPER_PRINCIPAL_ARN" WebCallbackUrls="$WEB_CALLBACK_URLS" WebLogoutUrls="$WEB_LOGOUT_URLS" \
  --capabilities CAPABILITY_NAMED_IAM \
  --region "$AWS_REGION" \
  --no-fail-on-empty-changeset
```

## Delete the development stack

Deletion removes the User Pool, its development users, and `neurovision-iam-dev-role`:

```powershell
aws cloudformation delete-stack `
  --stack-name $StackName `
  --region $AwsRegion `
  --profile neurovision-personal

aws cloudformation wait stack-delete-complete `
  --stack-name $StackName `
  --region $AwsRegion `
  --profile neurovision-personal
```

Do not delete an environment that contains required data.

## Operational notes

| Topic | Decision |
| --- | --- |
| Permissions | The development role can create, group, read, and delete users only in this stack's User Pool. Deletion is restricted to compensation of incomplete patient provisioning. |
| Email | `COGNITO_DEFAULT` is for early development and has delivery limits. Configure Amazon SES before production use. |
| Cost | Cognito may remain within its free allowance, but email, SMS, logs, and related AWS services can incur separate charges. Check Billing regularly. |
| Local configuration | Copy `iam-service/.env.example` to the ignored `iam-service/.env` and replace its placeholders with stack outputs. |
| Managed login | Cognito hosts sign-in, temporary-password replacement, and password recovery. The React client uses Authorization Code with PKCE. |
| Redirect URLs | Local and deployed callback/logout URLs are supplied as CloudFormation parameters. They are not REST API CORS settings. |

## Patient provisioning compensation

The Cognito adapter performs the patient invitation in this order:

1. Create the account with email delivery suppressed.
2. Add the generated Cognito username to the `PATIENT` group.
3. Resend the invitation explicitly through email.

If a step after account creation fails, the adapter calls `AdminDeleteUser` as a best-effort compensation and preserves the original failure. The role does not grant general Cognito administration; `AdminDeleteUser` is scoped to this stack's User Pool and exists only because this compensation is implemented.

## Real Cognito smoke test

This development-only harness exercises the real browser login and the patient invitation endpoint without adding password-handling endpoints to `iam-service`:

1. Start `iam-service` with the `neurovision-iam-dev` AWS profile and its `.env` values. Disable only the Cognito and STS Dev Services so the application uses real AWS while PostgreSQL and Kafka remain local:

```powershell
$env:AWS_PROFILE = "neurovision-iam-dev-sdk"
$env:QUARKUS_COGNITO_USER_POOLS_DEVSERVICES_ENABLED = "false"
$env:QUARKUS_STS_DEVSERVICES_ENABLED = "false"
..\mvnw.cmd quarkus:dev
```
2. Run the smoke-test script from the repository root:

```powershell
.\infra\aws\cognito\smoke-test-patient-invitation.ps1 `
  -PatientEmail "synthetic-patient@example.com"
```

The script generates an ephemeral PKCE verifier, opens Cognito Managed Login, listens once on the registered `http://localhost:5173/auth/callback`, exchanges the authorization code without printing tokens, and invokes `POST /api/v1/patient-invitations`. Use only controlled synthetic email addresses.

This script is not an application login endpoint. The React SPA will own the same Authorization Code + PKCE redirect flow; `iam-service` remains a bearer-token resource server and never receives user passwords.
