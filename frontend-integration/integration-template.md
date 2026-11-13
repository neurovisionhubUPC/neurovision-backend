# Service integration template

Use the [local environment guide](local-backend-environment-setup.md) to start the backend. Each service directory contains `integration.md` and `contracts.ts`. These are reference contracts, not a client SDK.

1. **Purpose and local origin:** state what the service owns and when the browser should call it.
2. **Endpoint matrix:** list HTTP method, full path, allowed roles, request inputs, success status and response type. Separate path parameters, query parameters, headers and JSON bodies. Mark absent inputs as “none”; do not send an empty JSON body just because a request is authenticated.
3. **Behavior:** explain defaults, ranges, exact vs. partial matching, normalization, ordering and empty results where applicable. Do not invent filters or pagination.
4. **Errors and service boundaries:** document verified status codes and error shapes. Separate browser calls from internal HTTP routes and events. Do not treat an asynchronous side effect as completed just because an HTTP call succeeded.
5. **Source of truth:** check controllers, request/response DTOs, services and focused tests. Use OpenAPI and the API catalog as cross-checks. Label proposed or unverified behavior; do not expose it as an implemented contract.

In `contracts.ts`, name path/query/header types separately from body types. Use `?` for fields that may be omitted and `| null` for fields that may carry JSON null. Model UUIDs and dates as strings on the wire; do not assume a non-JSON response is JSON.
