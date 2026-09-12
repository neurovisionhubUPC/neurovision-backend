# Analytics Service Implementation Plan

Analytics begins only after Telemetry has stabilized and versioned its Kafka event contract. Its first objective is deterministic, replayable analytical projections—not live sockets, machine-learning diagnosis, or a vector database.

## Read first

- [Telemetry and Analytics service scope](../architecture/telemetry-analytics-service-scope.md)
- [Telemetry service implementation plan](telemetry-service-implementation-plan.md) — owns the prerequisite end-to-end ingestion and contract stabilization gate.

## Entry gate

Do not scaffold Analytics merely to run in parallel with an unstable producer. Begin when Telemetry provides:

- A stable schema version and compatibility fixtures.
- Defined `batchId`, per-stream `sequence`, client/server timestamps, normalized coordinates, and pseudonymous identifiers.
- Tested duplicate, out-of-order, gap, DLQ, and replay semantics.
- Representative event fixtures and measured volume inputs.
- A documented retention and privacy contract.

## Delivery principles

- Implement vertical analytical behaviors with tests, persistence, and API evidence in the same work unit.
- Preserve `domain`, `application`, `infrastructure`, and `interfaces` with ports/adapters and application services.
- Keep the computation kernel deterministic and independent of Kafka, MongoDB, Redis, HTTP, SSE, or WebSocket.
- Treat package and file names below as planned, not as existing code.
- Make reprocessing safe: Kafka is at least once and Analytics must tolerate duplicate and late delivery.
- Keep clinician decisions in Clinical; Analytics emits computed detections with evidence and version metadata.

## Planned module shape

```text
analytics-service/
  src/main/java/.../
    domain/
    application/
    infrastructure/
    interfaces/
  src/main/resources/
  src/test/java/.../
```

Likely vertical areas include event admission, analytics kernels, projection persistence, query delivery, live projection updates, and detection handoff. Folders should be created only when a phase needs them.

## MVP boundary

### Included

- Module scaffold and stable Telemetry event consumption.
- Inbox/idempotency and ordering policies.
- Deterministic baseline metrics.
- Durable MongoDB analytical projections.
- Heatmap, gaze plot, and replay projection/query slices.
- Operational observability, deployment, replay, privacy, and security evidence.

### Deferred

- Redis until a measured hot-read/live requirement exists.
- SSE/WebSocket live updates until historical projections are correct.
- Advanced detection algorithms or machine learning.
- Autonomous clinical decisions.
- Cross-session embedding similarity and a vector database.
- Binary event formats or compression without measurement evidence.

## Phase 0 — Contract admission and analytical definitions

**Objective:** accept the Telemetry contract as an explicit dependency and define testable analytical meaning before implementation.

**Scope**

- Import or reference the versioned Telemetry event contract without copying divergent definitions.
- Define compatibility behavior for supported and unsupported schema versions.
- Agree on heatmap bins/coordinate space, gaze-plot ordering, replay timing, metric units, missing-data behavior, and detection terminology.
- Record workload inputs from sampling frequency x samples per batch x concurrent sessions and expected analytical fan-out.
- Define which projections are rebuildable and which data source is authoritative during replay.

**Planned package/file areas:** shared/versioned contract dependency, analytical decision records, fixtures, and compatibility tests.

**Tests/evidence:** consume representative Telemetry fixtures; reject unsupported versions deterministically; documented formulas/units; golden examples reviewed with product/clinical stakeholders.

**Completion criteria:** every MVP output has an agreed deterministic definition and source fields; no algorithm is described as clinically diagnostic.

**Dependencies:** completed Telemetry handoff gate.

**Rollback boundary:** remove Analytics planning/contract admission assets; Telemetry remains unaffected.

## Phase 1 — Module scaffold and operational heartbeat

**Objective:** create a deployable `analytics-service` skeleton aligned with repository conventions.

**Scope**

- Add the Maven module with Quarkus Kafka, REST, validation, MongoDB reactive client, OIDC/JWT, health, metrics, tracing, and test support.
- Keep Redis and WebSocket dependencies out until their phases justify them.
- Add configuration profiles without secrets, health/readiness behavior, container/native build wiring, and minimal required packages.

**Planned package/file areas:** `analytics-service/pom.xml`, root build descriptor, `src/main/resources/application.properties`, operational adapter/resource packages, and focused tests.

**Tests/evidence:** root/module build; Quarkus startup; health/readiness; JVM package; configuration validation.

**Completion criteria:** the empty service starts, is observable, and contains no placeholder analytics claimed as implemented.

**Dependencies:** Phase 0 accepted contract.

**Rollback boundary:** remove module registration and `analytics-service`; no producer or Clinical behavior changes.

## Phase 2 — Kafka consumer, inbox, and ordering admission

**Objective:** admit Telemetry events exactly once at the logical projection boundary despite at-least-once delivery.

**Scope**

- Implement the Kafka inbound adapter and application admission use case.
- Persist inbox identity/state durably with the projection transaction strategy.
- Validate schema version, event identity, `batchId`, sequence, pseudonymous IDs, timestamps, and normalized coordinates.
- Define duplicate, late, gap, and out-of-order admission outcomes without assuming global order.
- Add bounded retry, DLQ, and operator-controlled replay behavior.

**Planned package/file areas:** `interfaces` or `infrastructure/kafka/consumer`, `application/admission`, inbox ports/adapters, MongoDB collections/index initialization or migration mechanism, and integration tests.

**Tests/evidence:** duplicate delivery; reordered batches; sequence gaps; malformed/unsupported event; transient Mongo failure; poison event to DLQ; replay twice.

**Completion criteria:** duplicate events cannot duplicate logical work; every non-success outcome is observable and bounded; offsets are committed only after the chosen durable boundary.

**Dependencies:** Phase 1 and development Kafka/MongoDB.

**Rollback boundary:** stop/remove the Analytics consumer and its owned collections/indexes before adoption; Telemetry publication remains untouched.

## Phase 3 — Deterministic analytics kernel

**Objective:** implement pure, repeatable baseline calculations independent of infrastructure.

**Scope**

- Model normalized samples, time windows, stream continuity, quality/confidence, and calculation version.
- Implement baseline path distance, duration, velocity summaries, valid/invalid sample counts, coverage, and game-specific metric reductions approved in Phase 0.
- Specify behavior for missing values, zero duration, duplicate samples, gaps, late samples, and coordinate boundaries.
- Make algorithms versioned so replay can distinguish changed calculation semantics.

**Planned package/file areas:** `domain/analytics`, `application/calculation`, fixtures, unit/property tests, and golden expected outputs.

**Tests/evidence:** deterministic repeated execution; shuffled-input normalization where permitted; edge cases; property tests for invariants; golden fixtures with calculation version.

**Completion criteria:** identical admitted input plus calculation version always yields identical output; no database, clock, network, or framework dependency exists in the kernel.

**Dependencies:** Phase 0 definitions and Phase 2 admitted event model.

**Rollback boundary:** remove the kernel version and tests before projections depend on it; later replacements require a new calculation version rather than silent mutation.

## Phase 4 — MongoDB projection vertical slice

**Objective:** persist a durable, rebuildable session summary from consumed events.

**Scope**

- Define projection documents with pseudonymous session/stream identity, source schema range, calculation version, source sequence watermark, completeness/gap metadata, and timestamps.
- Apply kernel results through idempotent upsert/compare-and-advance semantics.
- Keep raw sample duplication out unless a documented projection needs bounded embedded points.
- Define atomicity between inbox state and projection state or a recoverable alternative.

**Planned package/file areas:** application projection use cases/ports, `infrastructure/persistence/mongo`, document mappers/indexes, configuration, and integration tests.

**Tests/evidence:** create/update projection; duplicate and late event; optimistic/concurrent update; restart recovery; replay rebuild; Mongo failure before/after inbox transition.

**Completion criteria:** the durable summary converges to the same result after normal delivery or replay; document indexes support planned query keys.

**Dependencies:** Phases 2–3.

**Rollback boundary:** disable the projection consumer and preserve the collection for inspection; a new projection version can rebuild into a separate collection/name before cutover.

## Phase 5 — Heatmap projection

**Objective:** produce a deterministic heatmap from normalized coordinates with explicit binning semantics.

**Scope**

- Implement configurable grid/bin mapping in normalized `0..1` space.
- Define weighting, confidence filtering, viewport/aspect metadata, invalid coordinate handling, and completeness markers.
- Persist heatmap projection metadata and bounded cell values in MongoDB.
- Add a historical API for an authorized session/time window or completed projection.

**Planned package/file areas:** `domain/heatmap`, application projection/query services, Mongo adapter/documents, `interfaces/rest`, DTOs, and tests.

**Tests/evidence:** boundary coordinates; known point distributions; confidence filtering; gaps/out-of-order delivery; golden grid; API authorization and serialization; replay convergence.

**Completion criteria:** a known fixture produces the same versioned grid every time; API output identifies coordinate space, calculation version, and completeness.

**Dependencies:** Phase 4 projection persistence.

**Rollback boundary:** remove the heatmap projection/API and its owned collection/indexes; baseline summary and ingestion remain intact.

## Phase 6 — Gaze plot projection

**Objective:** create a bounded, ordered gaze path representation suitable for clinician visualization.

**Scope**

- Define fixation/point selection, ordering, duration, confidence, and optional simplification rules.
- Preserve normalized coordinates and explicit timing; never infer order from Mongo insertion order.
- Persist bounded path segments and expose an authorized historical query API.
- Version any simplification algorithm and preserve completeness/gap metadata.

**Planned package/file areas:** `domain/gazeplot`, application projection/query services, Mongo documents/adapters, REST DTOs/resources, and tests.

**Tests/evidence:** stable ordering; duplicate/late samples; gap markers; simplification golden fixtures; segment pagination; authorization; replay convergence.

**Completion criteria:** the same source range and calculation version produces the same ordered gaze plot, including explicit gaps.

**Dependencies:** Phase 4 and approved fixation/simplification definition.

**Rollback boundary:** remove the gaze-plot projection/API independently; retain source events and other projections.

## Phase 7 — Replay analytical model

**Objective:** expose a frontend-friendly, versioned replay projection without turning Analytics into the raw time-series owner.

**Scope**

- Define replay segments containing normalized coordinates, timing, game state/metrics, gaps, and projection version.
- Consume sufficient event data or obtain bounded Telemetry ranges through an explicit port when reconstruction requires them.
- Chunk and paginate replay data; avoid one unbounded session document/response.
- Define interpolation as a versioned presentation projection, never as invented observed data.

**Planned package/file areas:** `domain/replay`, application builders/query ports, Telemetry client adapter if needed, Mongo projection adapter, REST API, and tests.

**Tests/evidence:** deterministic timeline; gaps and late events; chunk boundaries; interpolation labeling; large-session pagination; authorization; rebuild from replayed events.

**Completion criteria:** the frontend can animate a bounded session segment without camera data and can distinguish observed, missing, and interpolated points.

**Dependencies:** Phases 4–6 and a stable Telemetry replay/query contract if used.

**Rollback boundary:** remove replay projections/API; heatmap, gaze plot, and summary projections continue.

## Phase 8 — Redis hot read models and cache

**Objective:** add Redis only for measured hot/live access patterns with explicit rebuild and expiry behavior.

**Scope**

- Identify queries or live states whose measured latency/load justify caching.
- Define keys without direct patient identity, TTLs, size limits, invalidation, stampede protection, and outage behavior.
- Treat MongoDB projections or deterministic recomputation as durable truth.
- Keep correctness independent of cache availability.

**Planned package/file areas:** cache ports, `infrastructure/cache/redis`, query-service integration, configuration, and tests.

**Tests/evidence:** hit/miss; expiry; invalidation after projection update; Redis outage; stale-entry protection; concurrent fill; key/privacy scan.

**Completion criteria:** disabling or losing Redis affects performance/freshness within documented bounds but does not corrupt durable projections.

**Dependencies:** measured behavior from Phases 4–7.

**Rollback boundary:** disable/remove the Redis adapter and dependency; queries fall back to MongoDB/recomputation.

## Phase 9 — Query API hardening

**Objective:** provide coherent, authorized historical APIs across summary, heatmap, gaze plot, and replay projections.

**Scope**

- Standardize resource naming, API/schema versions, pagination, completeness, calculation-version, and error metadata.
- Authenticate with Cognito/OIDC JWT and authorize access through Clinical-owned relationships or a defined authorization port.
- Add conditional/cache semantics where appropriate without exposing Redis implementation details.
- Bound time ranges, response sizes, and expensive recomputation.

**Planned package/file areas:** `interfaces/rest`, application query services/authorization ports, DTO/mappers, exception handling, OpenAPI configuration, and tests.

**Tests/evidence:** role/session authorization matrix; pagination; unsupported versions; incomplete projection; cache/no-cache equivalence; payload bounds; OpenAPI/contract tests.

**Completion criteria:** all MVP projections have consistent, bounded, documented APIs and cannot be queried using direct patient identity alone.

**Dependencies:** Phases 4–8.

**Rollback boundary:** roll back one endpoint/version independently while retaining projection generation and stored data.

## Phase 10 — Optional live updates with SSE or WebSocket

**Objective:** deliver incremental projections after correctness and operational capacity are proven.

**Scope**

- Prefer SSE for one-way progress/projection updates.
- Use WebSocket only for a validated bidirectional control or interaction requirement.
- Define authentication expiry, reconnect, last-event/projection resume, backpressure, slow-client eviction, and bounded buffers.
- Publish references/deltas rather than unbounded full projections where possible.
- Keep historical REST APIs as recovery and source-of-truth access.

**Planned package/file areas:** `interfaces/sse` and, only if justified, `interfaces/websocket`; live application ports; Redis-backed live state if needed; configuration; and tests.

**Tests/evidence:** reconnect/resume; duplicate update; slow client; node restart; authorization expiry; fan-out load derived from concurrent sessions; REST recovery.

**Completion criteria:** live delivery cannot bypass authorization or become the only way to obtain a correct projection; measured capacity supports the chosen transport.

**Dependencies:** Phases 8–9 and an approved live use case.

**Rollback boundary:** disable live endpoints and direct clients to REST polling/query; durable projections are unchanged.

## Phase 11 — Computed detections and Clinical handoff

**Objective:** produce transparent, versioned detections while preserving clinician decision ownership in Clinical.

**Scope**

- Define detection rules, required evidence, calculation/rule version, confidence/quality constraints, and non-diagnostic language with clinical stakeholders.
- Persist detection projections and publish or call a versioned handoff contract to Clinical.
- Ensure repeated events/replay do not create duplicate Clinical notifications.
- Track acknowledgment/resolution references without copying Clinical's decision record.
- Route invalid or unprocessable handoffs through bounded retry/DLQ behavior.

**Planned package/file areas:** `domain/detection`, application rule/handoff services and ports, Mongo documents, Kafka or HTTP Clinical adapter, contract fixtures, and tests.

**Tests/evidence:** rule golden fixtures; insufficient-quality suppression; duplicate/replay delivery; handoff failure/retry/DLQ; Clinical contract compatibility; audit metadata.

**Completion criteria:** each detection is reproducible from versioned evidence; Clinical remains the sole owner of reviewed decisions and patient-facing action.

**Dependencies:** validated baseline projections and an agreed Clinical handoff contract.

**Rollback boundary:** disable a rule/calculation version and handoff publisher while retaining historical detections for audit; never delete Clinical decisions during Analytics rollback.

## Phase 12 — Observability, replay, retention, and privacy hardening

**Objective:** operate and rebuild Analytics safely without sensitive-data leakage.

**Scope**

- Measure consumer lag, admission outcomes, duplicates, gaps, projection latency, version distribution, Mongo/Redis behavior, DLQ, replay, and live-client state.
- Trace Kafka admission through calculation, Mongo/Redis, APIs, and Clinical handoff.
- Add operator-controlled full/partial projection rebuild with shadow/versioned cutover where needed.
- Define retention for inbox, projections, Redis, DLQ, Kafka source evidence, logs, and backups.
- Implement deletion propagation for pseudonymous sessions and audit access.

**Planned package/file areas:** operational adapters/configuration, replay/rebuild tooling, runbooks, dashboards/alerts if versioned here, and privacy tests.

**Tests/evidence:** dependency outages; trace continuity; replay/rebuild convergence; version cutover; deletion rehearsal; log/metric privacy scan; cache recovery.

**Completion criteria:** operators can explain and rebuild a projection using safe identifiers; retention/deletion covers derived data; no sensitive payload is required in logs.

**Dependencies:** all enabled projection and delivery phases.

**Rollback boundary:** revert individual operational instruments without deleting source/projection evidence; rebuild tooling must refuse ambiguous target versions.

## Phase 13 — Docker Compose, Kubernetes, native, load, and security validation

**Objective:** prove the Analytics MVP in both supported local environments and under representative event volume.

**Scope**

- Extend Docker Compose with Analytics, MongoDB, and Redis only if Phase 8 is enabled, plus health checks and non-secret defaults.
- Generate Quarkus application workload assets; let versioned Kustomize own dependencies, routing, configuration, and secret references.
- Verify JVM and native images with Kafka, MongoDB, Redis if enabled, Cognito/OIDC, and live transports if enabled.
- Test rolling updates, readiness, graceful shutdown, consumer rebalance, index initialization/migrations, replay, and version coexistence.
- Load-test using Telemetry's measured sampling frequency x samples per batch x concurrent sessions, analytical fan-out, replay headroom, query concurrency, and live subscribers.
- Run authorization, malicious-event, resource-exhaustion, privacy, and Clinical-handoff security tests.

**Planned package/file areas:** Compose files, Quarkus Kubernetes generation config, Kustomize overlays, native/container config, load/security harnesses, and deployment runbooks.

**Tests/evidence:** clean Compose pipeline; k3d deployment; native smoke; rolling restart/rebalance; measured load report; security report; replay and projection equality proof.

**Completion criteria:** the Analytics MVP processes the stable Telemetry contract, survives duplicate/reordered delivery and restarts, serves authorized projections, and has measured—not invented—capacity limits.

**Dependencies:** MVP phases plus whichever deferred phases are selected for the release.

**Rollback boundary:** roll back the Analytics workload/config independently; preserve versioned Mongo projections for inspection and rebuild, and do not change Telemetry or Clinical ownership.

## Recommended release sequence

1. **Analytics foundation:** Phases 0–2.
2. **Deterministic summary slice:** Phases 3–4.
3. **Clinician visualization MVP:** Phases 5–7 and 9.
4. **Operational MVP:** Phases 12–13.
5. **Measured optimizations:** Phase 8 only when hot-read evidence exists.
6. **Live delivery:** Phase 10 only when a live use case and capacity evidence exist.
7. **Clinical detections:** Phase 11 only after rule definitions and Clinical handoff governance are approved.

This sequence keeps the foundation honest: stable input, deterministic computation, durable projections, and recoverable operations come before live channels or advanced algorithms.
