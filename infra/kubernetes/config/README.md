# Configure local runtime settings safely

Apply public runtime configuration from Git, but generate credentials and TLS
material directly in the local cluster. Secret values must never be committed.

## Quick path

    kubectl apply -k .\infra\kubernetes\config
    .\infra\kubernetes\config\configure-local-secrets.ps1

The script refuses to write unless the active context is
`k3d-neurovision-local`.

## Resources

| Resource | Namespace | Purpose |
|---|---|---|
| `iam-service-config` | `neurovision` | Cognito identifiers, Kafka address, and IAM database URL |
| `profile-service-config` | `neurovision` | OIDC identifiers, Kafka address, and Profile database URL |
| `iam-database-credentials` | `neurovision` | Stable generated IAM database credentials |
| `profile-database-credentials` | `neurovision` | Stable generated Profile database credentials |
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

## Safety notes

- Database passwords are generated only when their Secrets do not exist.
- AWS credentials are freshly assumed from the limited role configured by the
  `neurovision-iam-dev` profile and are never printed.
- TLS files are generated under the system temporary directory and removed afterward.
- The mkcert root CA private key must never be copied or committed.
- Production must replace temporary AWS credentials with workload identity and local
  mkcert certificates with certificates issued for the public domain.

## Next step

Provision PostgreSQL and the Kafka-compatible broker, then create Deployments that
consume these ConfigMaps and Secrets through `envFrom`.
