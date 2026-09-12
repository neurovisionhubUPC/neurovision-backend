# Telemetry and Analytics Service Scope

This document defines the planned responsibilities, boundaries, and technology choices for the Telemetry and Analytics services. The delivery order is deliberate: build a reliable Telemetry path from browser batches to TimescaleDB first, then let Analytics consume the stable Kafka contract.

## Quick path

1. Implement the [Telemetry service plan](../plans/telemetry-service-implementation-plan.md) through its reliable end-to-end MVP.
2. Freeze and version the Kafka event contract using production-like evidence from Telemetry.
3. Implement the [Analytics service plan](../plans/analytics-service-implementation-plan.md) against that stable contract.
4. Add live transports and advanced analytics only after the baseline pipeline is observable and recoverable.

> **Development order:** Telemetry contract -> HTTP batch ingestion -> Kafka publication -> TimescaleDB persistence -> stable versioned event -> Analytics consumption -> analytical projections -> live and advanced capabilities.

## System goals

- Keep video frames on the user's device. MediaPipe, WASM, and Web Workers derive coordinates and metrics locally.
- Receive authenticated, bounded, sequenced batches rather than one HTTP request per sample.
- Preserve enough timing and ordering information to reconstruct a therapy session without treating arrival order as event order.
- Decouple ingestion, persistence, and analytical processing through versioned Kafka events.
- Produce clinician-facing heatmaps, gaze plots, replay projections, metrics, and detection alerts without moving clinical decision ownership out of Clinical.
- Support Docker Compose, k3d/Kubernetes, and an eventual AWS deployment without changing domain contracts.

## Bounded-context ownership

| Context | Owns | Does not own |
| --- | --- | --- |
| Clinical | Patients, clinicians, prescriptions, therapy configuration, patient-specific parameters, and clinician-reviewed decisions | Raw telemetry ingestion, time-series persistence, or analytical computation |
| Telemetry | Authenticated ingestion, batch validation, stream sequencing, Kafka publication, time-series persistence, and replay-oriented sample queries | Prescriptions, clinical decisions, heatmap generation, or long-lived analytical documents |
| Analytics | Computed metrics, heatmaps, gaze plots, replay projections, detections, and analytical read models | Raw video, authoritative patient data, prescriptions, or final clinical decisions |

Cross-context references use pseudonymous identifiers. Services must not duplicate authoritative patient or prescription records merely for convenience.

## Telemetry service scope

### In scope

- A planned HTTP endpoint for authenticated telemetry batch ingestion.
- A versioned batch contract containing `batchId`, `sessionId`, stream identity, `sequence`, client timestamps, server-received timestamps, sampling metadata, and normalized coordinates.
- Validation of payload shape, coordinate range, batch bounds, timestamp plausibility, and caller/session authorization.
- Idempotent acceptance and publication under duplicate client retries.
- Kafka publication with partitioning that preserves useful per-session ordering.
- A Kafka consumer that performs reactive `PgPool` batch inserts into TimescaleDB.
- Explicit duplicate-delivery, out-of-order, retry, dead-letter queue (DLQ), and replay behavior.
- Time-range and replay-oriented queries over persisted time-series data.
- Health, metrics, tracing, structured logging, security controls, retention, and deployment assets.
- An optional later WebSocket adapter that invokes the same application use cases as HTTP.

### Out of scope initially

- Uploading or storing camera frames.
- Per-sample HTTP requests.
- Hibernate Reactive for the high-volume TimescaleDB write path.
- Heatmap, gaze plot, clinical alert, or cross-session similarity computation.
- A vector database.
- A mandatory WebSocket dependency for baseline ingestion.

## Analytics service scope

### In scope

- Consumption of the stable, versioned Telemetry Kafka event.
- Inbox-based idempotency and explicit handling for duplicate or out-of-order delivery.
- A deterministic analytics kernel for testable, transport-independent computations.
- Heatmap, gaze plot, replay, summary metric, and computed detection projections.
- Durable analytical documents in MongoDB.
- Hot read models, short-lived computation results, and live state in Redis.
- Historical query APIs and optional SSE or WebSocket live updates.
- A versioned event or API handoff from computed detections to Clinical for clinician review.
- Reprocessing from retained Kafka or replay inputs without corrupting current projections.

### Out of scope initially

- Raw sample ingestion from browsers.
- Raw video access or storage.
- Ownership of prescriptions, patient identity, or clinician decisions.
- Machine-learning diagnosis or autonomous medical decisions.
- Embedding similarity search and a vector database.
- Advanced algorithms before deterministic baseline metrics are validated.

## End-to-end flow

1. The browser processes video locally using MediaPipe, WASM, and Web Workers.
2. It produces normalized gaze/game coordinates and metrics, groups them into bounded batches, and assigns `batchId` and monotonic `sequence` values per stream.
3. Telemetry authenticates the caller with Cognito/OIDC JWT and authorizes access to the referenced therapy session.
4. Telemetry validates and acknowledges the HTTP batch according to its durability contract.
5. Telemetry publishes a planned, versioned Kafka event, partitioned by a stable session/stream key.
6. The Telemetry persistence consumer idempotently stores time-series samples in TimescaleDB using reactive `PgPool` batch operations.
7. Analytics independently consumes the stable Kafka event, records inbox processing state, and runs deterministic projections.
8. Analytics persists durable documents to MongoDB and uses Redis for hot or live read models.
9. Query APIs expose historical results; SSE or WebSocket may deliver incremental projections.
10. Analytics sends computed detections to Clinical, where a clinician-owned workflow decides their clinical meaning.

Kafka delivery must be treated as at least once: every consumer assumes duplicate delivery and must make its side effects idempotent.

## Planned contract characteristics

The final names and schemas will be established during implementation; the following characteristics are required:

- An explicit `schemaVersion` and compatibility policy.
- Globally unique `batchId` for retry deduplication.
- `sessionId` plus a stream identifier and monotonic `sequence`.
- Client capture/start timestamps and server-received timestamps; neither is silently substituted for the other.
- Coordinates normalized to `0..1` so storage is independent of display resolution.
- Pseudonymous subject/session identifiers rather than direct patient identity in high-volume events.
- Sampling frequency and enough timing offsets to reconstruct individual samples.
- Validation metadata and an explicit reason when a batch is rejected or routed to a DLQ.
- A documented policy for gaps, late events, out-of-order events, and replayed events.

Candidate planned event families may include batch accepted, telemetry samples available, stream status changed, analytics projection updated, and detection produced. Their actual topic and type names remain planning decisions until contract tests establish them.

## Technology matrix

| Concern | Telemetry | Analytics | Rationale |
| --- | --- | --- | --- |
| Runtime | Java 21, Quarkus 3.38.1 | Java 21, Quarkus 3.38.1 | Align with existing services and operational practices |
| HTTP/API | `quarkus-rest-jackson` | `quarkus-rest-jackson` | JSON ingestion and query APIs |
| Validation | `quarkus-hibernate-validator` | Input/API validation | Fail invalid contracts at boundaries |
| Messaging | `quarkus-messaging-kafka` | `quarkus-messaging-kafka` | Decoupled, replayable processing |
| Time series | TimescaleDB via `quarkus-reactive-pg-client` / `PgPool` | Read only if a narrowly defined query requires it | Efficient time-range storage and batch inserts |
| Analytical documents | Not owned | MongoDB reactive client | Durable, evolving projection shapes |
| Hot/cache state | Limited local concerns only | Redis client | Low-latency hot read models and ephemeral live state |
| Live transport | Optional WebSocket; SSE where one-way output is enough | SSE by default for one-way projections; WebSocket for bidirectional control | Do not pay connection-state complexity without a use case |
| Security | Cognito/OIDC JWT | Cognito/OIDC JWT | Consistent identity boundary |
| Observability | Quarkus Health, metrics, OpenTelemetry | Quarkus Health, metrics, OpenTelemetry | End-to-end operational evidence |
| Testing | Unit, component, contract, integration, load, security | Unit, property/determinism, contract, integration, replay, security | Tests follow each vertical behavior |

## Transport decision

| Transport | Planned use | Strength | Cost / limitation |
| --- | --- | --- | --- |
| HTTP batch | Baseline browser-to-Telemetry ingestion | Simple retries, bounded payloads, clear authentication, straightforward testing and rate limiting | Not a server-push channel |
| WebSocket | Later bidirectional monitoring or control where measured latency requires it | Full duplex, connection-oriented stream | Connection state, backpressure, reconnect/resume, load-balancer and security complexity |
| SSE | One-way live progress or analytical projection updates | Simple server push over HTTP with browser reconnect support | Client-to-server commands still need HTTP; text/event framing |

HTTP batch is the initial reliable transport. WebSocket is an adapter added only when a concrete bidirectional use case and capacity evidence justify it. SSE is preferred over WebSocket when the server only needs to push projections.

## Data-store decisions

### TimescaleDB

TimescaleDB is the source for ordered telemetry samples because primary access patterns are time-range scans, session reconstruction, aggregation windows, and retention. The high-volume write path uses prepared reactive `PgPool` batch/bulk inserts rather than an ORM.

### MongoDB

MongoDB stores durable analytical projections whose shapes evolve independently: heatmap metadata, gaze paths, replay segments, summaries, and detection documents. It is not a duplicate raw telemetry store by default.

### Redis

Redis stores cacheable query results, live projection state, short-lived progress, and coordination data with explicit TTL and rebuild semantics. Durable analytical truth remains in MongoDB or can be recomputed from retained source events.

### No initial vector database

Two-dimensional coordinates are vectors mathematically, but the required workload is time-series filtering and aggregation, not high-dimensional embedding similarity. A vector database is deferred unless a validated feature later requires cross-session semantic similarity.

## Kafka responsibilities

- Telemetry owns publication of the planned versioned telemetry event.
- Topic partition keys must support deterministic per-session/stream ordering without assuming global order.
- Producers and consumers must publish correlation, trace, schema version, and event identity metadata.
- Telemetry and Analytics consumers maintain their own idempotency/inbox state.
- Retry topics or bounded retries must not cause unbounded poison-message loops.
- A DLQ preserves non-processable events with safe diagnostic metadata.
- Replay procedures distinguish normal delivery from deliberate reprocessing and keep projections idempotent.
- Compatibility tests protect consumers before a new event schema is deployed.

## Security and privacy

- Authenticate external APIs with Cognito/OIDC JWT and authorize the caller against the therapy session.
- Never send video frames to backend services.
- Use pseudonymous identifiers in telemetry events; resolve clinical identity only inside authorized Clinical workflows.
- Minimize payload fields and avoid duplicating clinical data in Kafka, logs, MongoDB, or Redis.
- Encrypt traffic and platform-managed storage; keep secrets out of images and versioned configuration.
- Prevent coordinates, tokens, or sensitive metadata from entering logs and metric labels.
- Define retention separately for raw time-series samples, Kafka events, analytical projections, caches, DLQs, and backups.
- Support deletion and retention workflows without silently leaving derived projections orphaned.
- Audit access to session data and detection handoffs.

## Deployment mapping

| Environment | Planned mapping |
| --- | --- |
| Quarkus Dev Services | Fast local component development with disposable Kafka/database dependencies where supported; not the integration source of truth |
| Docker Compose | Developer integration environment containing application services plus Kafka, TimescaleDB, MongoDB, and Redis with health checks and reproducible configuration |
| k3d/Kubernetes | Quarkus generates application workloads; versioned Kustomize owns platform dependencies, routing, configuration, and secret references |
| AWS | Cognito remains the OIDC authority; Kafka may map to Amazon MSK, databases to suitable managed or operated services, and Kubernetes workloads to the established AWS platform without changing event contracts |

Provider selection, sizing, multi-zone topology, and cost controls are deferred until measured load and recovery requirements exist.

## Non-functional concerns

- **Capacity:** derive targets from sampling frequency x samples per batch x concurrent sessions, then include payload size, replication, retention, replay, and analytical fan-out. Do not invent an SLA before measuring these inputs.
- **Backpressure:** bound request size, pending publications, consumer concurrency, database batches, and live-client buffers.
- **Reliability:** define acknowledgement points, retries, duplicate handling, ordering, DLQ, replay, and graceful degradation.
- **Observability:** propagate correlation and trace context from HTTP through Kafka consumers and projection writes.
- **Schema evolution:** version API and event schemas; test backward/forward compatibility before rollout.
- **Recovery:** document how to rebuild TimescaleDB persistence or Analytics projections from retained evidence.
- **Performance:** measure batch sizes and query windows against representative session data.
- **Native builds:** verify Quarkus native images with the actual Kafka/database clients and Kubernetes deployment shape.

## Explicit deferred decisions

- Exact topic names, event type names, and schema registry/serialization choice.
- The HTTP acknowledgement durability point: accepted into an internal durable boundary versus confirmed Kafka publication.
- Exact batch size, flush interval, retention periods, and concurrent-session capacity.
- WebSocket adoption and its resume protocol.
- SSE versus WebSocket for each live Analytics screen.
- Compression or binary serialization after JSON baseline measurements.
- Continuous aggregates and downsampling policy in TimescaleDB.
- MongoDB projection granularity and replay-document chunking.
- Detection algorithms, clinical thresholds, and validation governance.
- AWS managed-service selection and production availability targets.
- Vector search, only if a validated similarity-search feature appears.

## Delivery sequence and gates

1. **Telemetry foundation:** planned module, contracts, validation, and tests.
2. **Reliable ingestion slice:** HTTP batch -> Kafka -> TimescaleDB, including duplicates, ordering, and failure recovery.
3. **Telemetry query slice:** time-range/replay API plus observability and deployment proof.
4. **Contract stabilization gate:** versioned Kafka schema and compatibility/replay evidence are accepted before Analytics begins.
5. **Analytics foundation:** consumer/inbox plus deterministic kernel.
6. **Analytics MVP:** MongoDB projections for baseline metrics, heatmaps, gaze plots, and replay queries.
7. **Operational hardening:** Redis, alerts/Clinical handoff, full observability, deployment, replay, and security validation.
8. **Deferred live/advanced increments:** SSE/WebSocket, advanced algorithms, compression, and optional similarity features.

The plans intentionally define vertical, testable work units. They must not be implemented as a domain-folder commit followed by an application-folder commit; each unit should deliver a behavior with its tests and a reversible boundary.

## Related plans

- [Telemetry service implementation plan](../plans/telemetry-service-implementation-plan.md)
- [Analytics service implementation plan](../plans/analytics-service-implementation-plan.md)
