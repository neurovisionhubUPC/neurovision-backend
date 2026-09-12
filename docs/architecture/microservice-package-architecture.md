# Quarkus Microservice Package Architecture

Every NeuroVision backend microservice uses four top-level architectural layers: `domain`, `application`, `infrastructure`, and `interfaces`. A service creates folders below those layers only when the corresponding behavior exists. This keeps package placement predictable without filling new modules with empty architecture.

## Quick placement guide

| Code responsibility | Package |
| --- | --- |
| Business concepts and rules | `domain` |
| Use-case orchestration and boundary ports | `application` |
| Technology-specific adapters and configuration | `infrastructure` |
| Inbound REST or event contracts and adapters | `interfaces` |

The allowed dependency direction is:

```text
interfaces ---------> application ---------> domain
                           ^                    ^
                           |                    |
infrastructure ------------+--------------------+

outer-adapter collaboration: infrastructure/messaging
                             -> interfaces/events/contracts
```

`domain` depends on no outer layer. `application` may depend on `domain`. Inbound interfaces call application use cases. Infrastructure implements application output ports and may translate infrastructure data to domain data. Outer adapters may collaborate through a versioned wire-only contract package—for example, a Kafka publisher serializing `interfaces/events/contracts`—but application and domain never depend on that contract. Wiring points may see both a port and its adapter, but business decisions stay inward.

## Mandatory and optional structure

The four layer packages are mandatory architectural boundaries for a production microservice. Their capability folders are optional and must be added only with working behavior and tests.

```text
<service-package>/
  application/                     # required layer
    acl/                            # optional: external bounded-context translation
    internal/
      commandservices/             # optional: state-changing use cases
      queryservices/               # optional: read use cases
      eventhandlers/               # optional: inbound event orchestration
      validation/                  # optional: application-boundary validation
    ports/
      in/                           # optional: explicit inbound use-case interfaces
      out/
        authorization/             # optional: authorization boundary
        messaging/                 # optional: event publication boundary
        persistence/               # optional: storage boundary
  domain/                          # required layer
    model/
      aggregates/                  # optional
      commands/                    # optional
      events/                      # optional
      queries/                     # optional
      valueobjects/                # optional
    services/                      # optional: domain behavior spanning concepts
  infrastructure/                  # required layer
    configuration/                 # optional
    security/                      # optional
    messaging/                     # optional
    persistence/                   # optional
    health/                        # optional
  interfaces/                      # required layer
    rest/
      resource/                    # optional: REST resources and versioned DTOs
      transform/                   # optional: REST-to-application/domain mapping
    events/
      contracts/                   # optional: versioned integration-event schemas
      listeners/                   # optional: inbound message adapters
      transform/                   # optional: event-to-application/domain mapping
```

Do not create empty directories from this tree. A small service may initially contain only one package in a layer. The tree describes placement when a capability is introduced, not a scaffold completion checklist.

## Layer rules

### Domain

The domain represents business meaning without Quarkus, HTTP, Kafka, database, or identity-provider dependencies.

| Package | Use it for | Do not use it for |
| --- | --- | --- |
| `model/aggregates` | Objects with identity, lifecycle, consistency boundaries, and behavior | Every record carrying an ID |
| `model/valueobjects` | Immutable descriptive values whose equality is value-based | REST or Kafka serialization models |
| `model/commands` | Domain intent that changes state | HTTP request DTOs |
| `model/queries` | Domain/application read intent | Database query implementations |
| `model/events` | Facts raised by the domain | Kafka wire schemas |
| `services` | Domain rules that do not naturally belong to one model object | Use-case orchestration or adapter logic |

`TelemetryBatch` currently belongs in `domain/model/valueobjects`: it is an immutable, value-based validated batch description. The presence of `batchId` supports idempotency but does not by itself give the record an aggregate lifecycle or consistency boundary. Promote it to an aggregate only if future behavior establishes those semantics.

### Application

The application layer coordinates use cases, enforces application-boundary policy, and declares what it needs from external systems.

- `internal/commandservices` implements state-changing use cases.
- `internal/queryservices` implements reads.
- `internal/eventhandlers` coordinates reactions to inbound events.
- A focused package such as `internal/validation` may hold reusable application validation when it is not itself a complete command service.
- `ports/out` declares authorization, messaging, persistence, clock, or other external capabilities required by a use case.
- `ports/in` is optional. Introduce it when multiple inbound adapters need a stable use-case boundary or when an explicit contract improves testing. Do not mirror every class with an interface automatically.
- `acl` translates the language and semantics of another bounded context into local application concepts. It does not contain HTTP clients or vendor SDK code.

Application code must not import REST DTOs, Kafka contracts, database entities, or concrete infrastructure adapters.

### Interfaces

Interfaces define external interface models and inbound adapters. Inbound adapters deserialize an external representation, perform transport-level checks, map it, and invoke an application use case. Outbound integration contracts expose the versioned wire representation used by an infrastructure adapter.

REST and event contracts are separate versioned models:

- REST DTOs: `interfaces/rest/resource/v1`.
- REST mappers: `interfaces/rest/transform`.
- Kafka contracts: `interfaces/events/contracts/v1`.
- Kafka mappers: `interfaces/events/transform`.

Never reuse a REST DTO as a Kafka event. They have different consumers, evolution rules, compatibility windows, metadata, and failure semantics. They may map to the same domain or application model without sharing their wire type.

REST resources must not perform business authorization, publish directly to Kafka, or access persistence adapters. Event listeners must not embed domain rules or database details.

### Infrastructure

Infrastructure contains replaceable technology adapters:

- `configuration`: Quarkus configuration and dependency wiring.
- `security`: OIDC/Cognito identity extraction and security adapters.
- `messaging`: Kafka producers, consumers, serializers, retry/DLQ integration, and channel configuration.
- `persistence`: PostgreSQL, TimescaleDB, MongoDB, or Redis adapters, entities, migrations, and transaction mechanics.
- `health`: operational dependency and heartbeat checks.

Infrastructure implements application output ports. It may enforce technical constraints such as timeouts or batch sizes, but it must not decide clinical access, telemetry validity, or other domain policy.

## Integration examples

### Synchronous Clinical authorization

The Telemetry application use case depends on a local port such as an authorization lookup expressed in Telemetry terms. An infrastructure HTTP adapter calls Clinical. An application ACL converts Clinical's response into the local authorization result.

```text
REST resource
  -> Telemetry ingestion use case
    -> application port/out/authorization
      <- infrastructure Clinical HTTP adapter
         -> Clinical service
    -> application ACL translation
```

The REST resource does not call Clinical directly, and Telemetry does not copy Clinical patient records. The exact request, response, timeout, failure, and ownership semantics must be defined before implementing Phase 3.

### Kafka publication

An application use case publishes through a messaging port. The Kafka adapter maps the local event or accepted model to its own versioned event contract.

```text
application use case
  -> application port/out/messaging
    <- infrastructure/messaging Kafka publisher
       -> interfaces/events/contracts/v1 wire contract
```

The Kafka contract includes transport and compatibility metadata that does not belong in the REST DTO. Consumers use the Kafka schema, never the REST schema.

## `shared` is exceptional

A service may use `shared` only for small, stable, genuinely cross-layer primitives that have no bounded-context meaning, such as a local error envelope convention. It must not become a dumping ground for DTOs, utilities, entities, business rules, or cross-service source sharing.

Prefer a layer-owned package. Cross-service integration is through explicit REST or event contracts, not Java class sharing. A new `shared` package requires the same architectural justification as any other exception.

## New-service checklist

Use this checklist when adding behavior to a microservice:

- [ ] Identify the business behavior and owning bounded context.
- [ ] Keep business concepts free of Quarkus and adapter dependencies.
- [ ] Place orchestration in `application/internal`.
- [ ] Declare an output port before adding an external adapter used by the application.
- [ ] Put technology implementation in `infrastructure`.
- [ ] Keep inbound REST and event contracts separate and versioned.
- [ ] Map external contracts at the interface boundary.
- [ ] Add only capability folders backed by implemented behavior.
- [ ] Keep tests with the behavior and verify forbidden imports.
- [ ] Document authentication, authorization, retries, idempotency, privacy, and observability where applicable.

Minimal procedure:

1. Add the smallest domain/application behavior that expresses the use case.
2. Define required ports in application terms.
3. Add one inbound interface and the required infrastructure adapters.
4. Verify dependency direction and contract separation.
5. Update the service plan and architecture documentation in the same work unit.

## Exceptions and ADRs

Consistency is the default, not a prohibition on better designs. A service may deviate when its domain or runtime constraints justify it, but the exception must be deliberate.

Before merging an exception:

1. Record an Architecture Decision Record that states the context, decision, alternatives, tradeoffs, affected services, and migration impact.
2. Update this document if the decision establishes a new repository-wide convention.
3. Add an architecture test or review checklist item when the rule can be enforced mechanically.

An undocumented one-off package layout is architectural drift, not an accepted exception.
