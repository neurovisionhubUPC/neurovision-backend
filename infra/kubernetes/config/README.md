# Configure local runtime settings safely

Apply public runtime configuration from Git, but generate credentials and TLS
material directly in the local cluster. Secret values must never be committed.

## Quick path

    kubectl apply -k .\infra\kubernetes\config
    .\infra\kubernetes\config\configure-local-secrets.ps1

The script refuses to write unless the active context is
`k3d-neurovision-local`.

For Analytics-only provisioning, use the isolated mode instead of the full
setup command:

```powershell
.\infra\kubernetes\config\configure-local-secrets.ps1 -OnlyAnalyticsCursorSigningKey
```

It checks the current context and namespace, then reads or writes only the
Analytics cursor-key Secret. Database, AWS, and TLS Secrets are not touched;
`-RotateDatabaseCredentials` is rejected in this mode.

## Resources

| Resource | Namespace | Purpose |
|---|---|---|
| `iam-service-config` | `neurovision` | Cognito identifiers, Kafka address, and IAM database URL |
| `profile-service-config` | `neurovision` | OIDC identifiers, Kafka address, and Profile database URL |
| `clinical-service-config` | `neurovision` | OIDC identifiers, Kafka address, and Clinical database URL |
| `telemetry-service-config` | `neurovision` | OIDC identifiers, Kafka/Clinical addresses, Telemetry database URLs, and local-only bounds |
| `iam-database-credentials` | `neurovision` | Stable generated IAM database credentials |
| `profile-database-credentials` | `neurovision` | Stable generated Profile database credentials |
| `clinical-database-credentials` | `neurovision` | Stable generated Clinical database credentials |
| `telemetry-database-credentials` | `neurovision` | Stable generated Telemetry TimescaleDB credentials |
| `analytics-gaze-plot-cursor-signing-key` | `neurovision` | Stable random HMAC key for Analytics gaze-plot cursors |
| `iam-aws-credentials` | `neurovision` | Temporary credentials exported from the limited AWS role |
| `neurovision-local-tls` | `kube-system` | Local HTTPS certificate consumed by the Traefik Gateway |

## Refresh temporary AWS credentials

AWS credentials expire. Re-run the script before starting IAM after expiration:

    .\infra\kubernetes\config\configure-local-secrets.ps1 -SkipTls

After IAM has a Deployment, restart it so the Pod receives the refreshed Secret:

    kubectl rollout restart deployment/iam-service -n neurovision

The `-RotateDatabaseCredentials` switch exists for controlled local recovery.
Do not use it after PostgreSQL is initialized unless the database password is
rotated in the same operation.

The Analytics cursor key is generated once and preserved by later runs,
including runs with `-RotateDatabaseCredentials`. To deliberately invalidate
outstanding Analytics gaze cursors and replace only this key, use
`-OnlyAnalyticsCursorSigningKey -RotateAnalyticsCursorSigningKey`; this does
not rotate database credentials or any other Secret.
The key is written only to the Kubernetes Secret through a temporary file under
the system temp directory. It is not printed or stored in the repository.

Until an Analytics Kubernetes Deployment exists, this Secret is provisioned
without creating an Analytics workload. A future Deployment must expose its key
as the `ANALYTICS_GAZE_PLOT_CURSOR_SIGNING_KEY` environment variable using
`secretKeyRef` with Secret `analytics-gaze-plot-cursor-signing-key` and key
`ANALYTICS_GAZE_PLOT_CURSOR_SIGNING_KEY`.

## Safety notes

- Database passwords are generated only when their Secrets do not exist.
- For the Telemetry rehearsal, `-SkipAws -SkipTls` creates the missing database
  Secret without refreshing AWS credentials or changing the local certificate.
- AWS credentials are freshly assumed from the limited role configured by the
  `neurovision-iam-dev` profile and are never printed.
- TLS files are generated under the system temporary directory and removed afterward.
- The mkcert root CA private key must never be copied or committed.
- Production must replace temporary AWS credentials with workload identity and local
  mkcert certificates with certificates issued for the public domain.

## Next step

Provision PostgreSQL and the Kafka-compatible broker, then create Deployments that
consume these ConfigMaps and Secrets through `envFrom`.
