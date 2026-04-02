# ADR-0008: Domain Event Bus Strategy

## Status

`Accepted`

## Date

2026-04-01

---

## Context

ADR-0003 established an event-driven projection architecture: the domain emits business events (`MembershipGranted`, `AssignmentRequested`, etc.) that drive downstream projection. The implementation of this event bus was explicitly left open in ADR-0003:

> *"Event bus adapter (Kafka, in-memory, etc.)"*

This ADR resolves that open question by deciding **how domain events are dispatched and consumed** in GATE IAM.

Domain events serve two distinct purposes in GATE:

1. **Intra-service projection trigger**: a `MembershipGranted` event causes `ProjectionCalculationService` to compute `Assignment` records. This is an internal coordination concern within the same JVM process.
2. **Future inter-service or external integration**: notification of external systems (webhooks, audit sinks, monitoring) when significant domain events occur.

These two purposes have very different latency, durability, and ordering requirements, and may warrant different solutions.

The choice is also influenced by the project phase: GATE is currently in the **architecture and initial implementation phase**, with a single-developer team. Operational complexity must be minimized until the system is proven.

---

## Decision Drivers

- **TD-3** — Resilience against synchronization failures: event processing failures must not corrupt domain state
- **TD-4** — Asynchronous synchronization: projection must not block membership write operations
- **FD-3** — Synchronization status as a first-class concept: assignment processing must be observable
- **Operational**: minimize infrastructure dependencies in development and test environments
- **ADR-0006**: Spring Boot is the framework — prefer Spring-native solutions where appropriate
- **ADR-0009**: events must be testable without a running message broker

---

## Considered Options

### Option A — Apache Kafka
Distributed, durable, ordered, partitioned event log. Industry standard for event-driven architectures.

**Pros:**
- True durability: events survive process restarts
- Consumer group semantics: multiple processor instances can share load
- Replay capability: reprocess events from any offset

**Cons:**
- Requires a running Kafka cluster (ZooKeeper/KRaft) even in development
- Significant operational overhead (topic management, consumer group offsets, monitoring)
- Test complexity: Testcontainers Kafka is available but adds CI build time
- Over-engineered for a single-process MVP — projection runs in the same JVM as the domain

**Not selected for MVP.** Retained as the target evolution when GATE runs as multiple instances or when external consumers of domain events are required.

### Option B — Spring Application Events (in-process, synchronous)
Spring's `ApplicationEventPublisher` dispatches events synchronously within the same thread.

**Pros:**
- Zero infrastructure dependency — no broker, no container
- Simple to test (`ApplicationEvents` in Spring Boot Test, or direct service calls)
- Transactional consistency: events fired within a `@Transactional` method are part of the same transaction

**Cons:**
- Synchronous dispatch: projection runs in the same thread as the membership write, introducing latency
- No durability: if the JVM crashes after the `Membership` is persisted but before the `MembershipGranted` event is processed, the projection is silently lost
- Does not scale to multiple JVM instances

**Not selected as the sole mechanism** due to lack of durability.

### Option C — Spring Application Events + `@TransactionalEventListener` + Outbox pattern _(selected)_

Combines Spring's built-in event mechanism with the **transactional outbox pattern** to provide durability without a broker:

1. The domain use case persists the `Membership` AND writes an `OutboxEvent` row **in the same database transaction**.
2. A `@TransactionalEventListener(phase = AFTER_COMMIT)` fires after the transaction commits and attempts to process the event immediately (fast path).
3. A polling scheduler reads unprocessed `OutboxEvent` rows and retries failed events — guaranteeing **at-least-once delivery** without a broker.

**Selected because:**
- Durability without Kafka: an `OutboxEvent` row is durable and survives JVM crashes
- Zero additional infrastructure: uses the same PostgreSQL database already chosen in ADR-0007
- Testable: Spring's `@RecordApplicationEvents` captures events in unit/integration tests without any infrastructure
- Natural evolution path: the outbox table can be consumed by Debezium + Kafka when multi-instance deployment is required — no domain changes needed
- `@TransactionalEventListener` is a Spring primitive — no additional library

---

## Decision

**MVP**: Spring Application Events + `@TransactionalEventListener` + PostgreSQL Outbox table.

**Target (post-MVP)**: Debezium CDC on the outbox table → Kafka, when multi-instance deployment or external consumers are required.

### Outbox table schema

```sql
CREATE TABLE outbox_events (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(200) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ,
    failed_at      TIMESTAMPTZ,
    retry_count    INT          NOT NULL DEFAULT 0,
    error_message  TEXT
);

CREATE INDEX idx_outbox_unprocessed ON outbox_events (created_at)
    WHERE processed_at IS NULL AND failed_at IS NULL;
```

### Event publication flow

```
1. Use case opens @Transactional
2. Domain entity persisted (e.g., Membership)
3. OutboxEvent row inserted (same transaction)
4. Transaction commits
        │
        ├── @TransactionalEventListener (AFTER_COMMIT)
        │   └── Process immediately → mark outbox row as processed (fast path)
        │
        └── Polling scheduler (every 30s)
            └── SELECT unprocessed rows → retry → mark processed or increment retry_count
```

### Domain event types

All domain events implement `DomainEvent` and are published via `DomainEventPublisher` (outbound port in `gate-iam-domain`). The Spring `ApplicationEventPublisher` adapter in `gate-iam-infrastructure` implements this port.

| Event | Aggregate | Trigger |
|-------|-----------|---------|
| `MembershipGranted` | Membership | Membership status → `ACTIVE` |
| `MembershipRevoked` | Membership | Membership status → `REVOKED` |
| `MembershipExpired` | Membership | `validUntil` passed |
| `MembershipSuspended` | Membership | Membership status → `SUSPENDED` |
| `AssignmentRequested` | Assignment | Assignment computed by `ProjectionCalculationService` |
| `AssignmentApplied` | Assignment | Connector confirms success |
| `AssignmentFailed` | Assignment | Connector returns failure |
| `DownstreamResourceProvisioned` | TargetResourceRecord | `ensureResourceExists()` → `CREATED` |
| `DownstreamResourceTakenOver` | TargetResourceRecord | `ensureResourceExists()` → `ALREADY_EXISTS` |
| `DownstreamResourceDecommissioned` | TargetResourceRecord | `decommissionResource()` succeeds |

### Failure handling

- **Fast path failure**: exception in `@TransactionalEventListener` is caught; the outbox row remains unprocessed for the scheduler to retry
- **Max retries**: configurable per event type (default: 5). After max retries, event is marked `failed_at` and an alert is raised
- **Idempotency**: all event consumers must be idempotent — the outbox guarantees at-least-once delivery

---

## Consequences

### Positive
- Durable event delivery without any infrastructure beyond PostgreSQL
- The domain port `DomainEventPublisher` hides all Spring details — the domain stays pure
- Testable: events can be captured in tests via Spring's `@RecordApplicationEvents` or by asserting against the outbox table
- Clean migration path to Kafka via Debezium CDC — no domain code changes

### Negative / Trade-offs
- At-least-once delivery requires all consumers to be idempotent — enforced by convention and tested (ADR-0009)
- The outbox polling scheduler adds a background thread and slightly increases database load
- Outbox table can grow without a retention policy — a cleanup job must be scheduled

### Risks
- **Outbox table growth**: mitigated by a scheduled cleanup of rows older than a configurable retention window (default: 30 days)
- **Consumer idempotency violated**: mitigated by ArchUnit test rule requiring all `@EventListener` / `@TransactionalEventListener` classes to implement `IdempotentEventConsumer` marker interface, and by integration tests exercising double-delivery
