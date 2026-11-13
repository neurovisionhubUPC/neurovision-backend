# IAM: invitations and account activation

Default local origin: `http://127.0.0.1:8081` (`IAM_PORT`). IAM manages invitations and account activation; there is no IAM login endpoint. Use the team's Cognito Managed Login flow (Authorization Code + PKCE) and send the **access token**, not the ID token, as `Authorization: Bearer <access-token>`. Never put AWS credentials or a Cognito client secret in the Vite bundle.

## Browser endpoints

All endpoints require the bearer header. None accepts path parameters. The list endpoint is the only one with query parameters. Send `Content-Type: application/json` when a body is required.

| Method and path | Role | Input | Success |
| --- | --- | --- | --- |
| `POST /api/v1/doctor-invitations` | ADMIN | `InviteDoctorBody` | 201, `DoctorInvitationResponse` |
| `PUT /api/v1/doctor-invitations/me/activation` | DOCTOR | No body | 200, `DoctorInvitationResponse` |
| `GET /api/v1/patient-invitations` | DOCTOR | `ListPatientInvitationsQuery`; no body | 200, `PatientInvitationPageResponse` |
| `POST /api/v1/patient-invitations` | DOCTOR | `InvitePatientBody` | 201, `PatientInvitationResponse` |
| `PUT /api/v1/patient-invitations/me/activation` | PATIENT | No body | 200, `PatientInvitationResponse` |

See [contracts.ts](contracts.ts) for wire types. Invitation bodies contain only the role-specific email field (valid, nonblank, maximum 254 characters). Activation derives the subject and authentication time from the token; do not send a subject, invitation ID, or timestamp.

### Invitation list

- Example: `GET /api/v1/patient-invitations?page=0&size=20&status=INVITED`.
- `page` is zero-based and defaults to 0; `size` defaults to 20 and must be 1–100. Invalid pagination or status returns 400.
- `status` is exact enum equality after trimming and case normalization. Omitted or blank means no status filter. There is **no text search** by email or name.
- The list is scoped to the authenticated doctor and ordered by creation time descending, then ID as a tie-breaker. Read `items` and pagination metadata; an empty result is still 200.

### Errors and handoff

Handle 401 (missing/invalid token) and 403 (wrong role) by status first. Do not assume security or validation failures share a JSON envelope. Mapped invitation errors include `{ code, message }`: 409 `DOCTOR_ALREADY_INVITED` or `PATIENT_ALREADY_INVITED`; 502 `DOCTOR_IDENTITY_PROVIDER_UNAVAILABLE` or `PATIENT_IDENTITY_PROVIDER_UNAVAILABLE`; activation can return 404 `DOCTOR_INVITATION_NOT_FOUND` or `PATIENT_INVITATION_NOT_FOUND`.

IAM publishes invitation events for downstream registration. A successful invitation does not mean the [Profile](../profile/integration.md) record or Clinical registration is already available. The browser does not publish Kafka events or create downstream records itself. IAM activation and Profile completion represent different states.

Sources: IAM invitation controllers and DTOs, `DoctorInvitationControllerTest`, `PatientInvitationControllerTest`, and `DoctorPatientInvitationQueryRepositoryTest`.
