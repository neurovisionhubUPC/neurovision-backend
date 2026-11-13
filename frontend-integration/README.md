# Integrate the frontend with the local backend

Start with the [local backend setup](local-backend-environment-setup.md). From the repository root, follow its instructions for `docker compose up --build`, then verify readiness on all five APIs. The setup guide lists the local API origins and explains what the native Compose validation covered.

## Connect the frontend

Coordinate the development Cognito settings, the exact Vite origin and login callback URL, and the synthetic accounts with the team. Follow the IAM guide for the existing Cognito login flow. Send the **access token**, not the ID token, as a bearer token to protected APIs. Do not commit credentials or tokens, and do not put AWS credentials or a Cognito client secret in the browser bundle.

| Service | Guide | TypeScript contracts |
| --- | --- | --- |
| IAM | [Login, invitations, and activation](iam/integration.md) | [Types](iam/contracts.ts) |
| Profile | [Self-profile](profile/integration.md) | [Types](profile/contracts.ts) |
| Clinical | [Patients, plans, sessions, and reports](clinical/integration.md) | [Types](clinical/contracts.ts) |
| Telemetry | [Batch ingestion and historical samples](telemetry/integration.md) | [Types](telemetry/contracts.ts) |
| Analytics | [Streams, visualizations, and exercise results](analytics/integration.md) | [Types](analytics/contracts.ts) |

## Verify one read-only flow

Use an existing synthetic doctor and patient with an active assignment and a plan already visible to the patient in this environment. A fresh stack does not copy those records or create accounts. If the fixture is missing, coordinate setup separately; do not create data as part of this check.

1. Sign in as **DOCTOR**. Call `GET /api/v1/clinical-patients` and use the returned `clinicalPatientId` for the assigned synthetic patient. Call `GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-plans` and note the expected `therapyPlanId`.
2. Sign out and sign in as **PATIENT**. With the patient's access token, call `GET /api/v1/me/therapy-plans` and verify that the same `therapyPlanId` appears. Do not send a patient ID on this route.

Both roles should receive HTTP 200 for their reads and see the expected patient or plan. Do not start a session, send telemetry, or create a report. This check is for frontend authentication and API access; the previous native readiness validation used simulated OIDC and did not verify this flow with development Cognito.
