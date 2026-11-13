# Profile: personal and professional details

Default local origin: `http://127.0.0.1:8082` (`PROFILE_PORT`). Profile owns the signed-in user's personal and professional details. Use the Cognito **access token** as `Authorization: Bearer <access-token>`. The backend resolves the profile from the token subject; the browser must not send another user's ID.

## Browser endpoints

None of these endpoints accepts path or query parameters. Send `Content-Type: application/json` only for the requests with a body.

| Method and path | Role | Input | Success |
| --- | --- | --- | --- |
| `GET /api/v1/profile` | PATIENT or DOCTOR | No body | 200, `ProfileResponse` |
| `PUT /api/v1/profile` | PATIENT | `CompleteProfileBody` | 200, `ProfileResponse` |
| `PUT /api/v1/profile/professional` | DOCTOR | `OnboardDoctorBody` | 200, `ProfileResponse` |

See [contracts.ts](contracts.ts). Send first and last names (nonblank, up to 100 characters each). Optional fields include document number (3–32 characters), phone (7–20), and birth date (ISO date `YYYY-MM-DD`, not in the future). The backend normalizes personal text and checks the birth date against UTC.

The professional body also accepts specialty (2–100 characters) and `medicalLicense`. When supplied, the license must have exactly six ASCII digits after trimming and cannot be `000000`. Send it as a string to preserve leading zeros. This validates the format, not registration; older stored values can still appear in responses. Do not treat these PUTs as PATCH requests: send the intended form values rather than assuming omission preserves prior values.

The server reports `PENDING_COMPLETION` or `ACTIVE` in `status`; do not infer activation from a single field or from IAM's invitation state. Responses may contain null for details that are not set. These self-service endpoints have no list, text search, filter, or pagination.

## Errors and handoff

Handle 401 (token), 403 (role), and 400 (invalid input) by HTTP status; do not assume every error has a JSON envelope. Mapped errors use `{ code, message }`: 404 `PROFILE_NOT_FOUND` when the required profile does not exist, and 409 `DOCUMENT_NUMBER_ALREADY_REGISTERED` or `MEDICAL_LICENSE_ALREADY_REGISTERED` on uniqueness conflicts.

[IAM](../iam/integration.md) publishes invitation events that can register pending profiles asynchronously. If the self-profile is missing after an invitation, do not fabricate a profile ID or call an internal endpoint. The assigned-patient names route under `/api/internal/` supports Clinical's authorized reads; it is not a browser profile search or assignment API.

Sources: `ProfileController`, its request/response DTOs, `ProfileControllerTest`, and `AssignedPatientNamesControllerTest`.
