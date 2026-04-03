# Application Stack

This document describes the technology choices underpinning GATE IAM's implementation.

> **Source of truth**: [ADR-0006](../adr/ADR-0006-language-and-application-framework.md), [ADR-0007](../adr/ADR-0007-persistence-strategy.md), [ADR-0008](../adr/ADR-0008-event-bus-strategy.md).

---

## Technology Choices

| Concern | Choice | Version |
|---------|--------|---------|
| Language | Java | 25 LTS |
| Framework | Spring Boot | 3.5.x |
| Build | Gradle (Kotlin DSL) | 9.x |
| Database | PostgreSQL | 17+ |
| ORM | Spring Data JPA + Hibernate | — |
| Schema migrations | Flyway | — |
| Event bus (MVP) | Spring Events + PostgreSQL Outbox | — |
| Virtual threads | Project Loom (`spring.threads.virtual.enabled=true`) | stable since Java 21 |

---

## Developer Tooling

| Tool | Role |
|------|------|
| `Makefile` | Shortcuts for common commands (`build`, `test`, `run`, `check`, `install-hooks`) |
| `pre-commit` | Git hooks: Conventional Commits validation (`commit-msg`) + general quality checks (`pre-commit`) |
| `Dependabot` | Automated weekly dependency updates for Gradle and GitHub Actions |

### Local setup

```bash
# Install Git hooks (once per clone)
make install-hooks

# Compile and run all tests
make check
```

Installed hooks:
- **`commit-msg`** — enforces `<type>(<scope>): <description>` format via `conventional-pre-commit`
- **`pre-commit`** — trims trailing whitespace, validates YAML/JSON, detects private keys, prevents direct commits to `main`

---

## Multi-Module Structure

The project is a **multi-module Gradle build**. Module boundaries enforce hexagonal architecture at compile time — a dependency violation is a build failure, not a code review comment.

```
gate-iam/
├── gate-iam-domain/          # Pure Java — zero Spring, zero JPA, zero infrastructure
├── gate-iam-application/     # Spring @Service — depends on domain only
├── gate-iam-infrastructure/  # Spring Boot + JPA + connectors — depends on domain + application
└── gate-iam-backend/         # Launcher: main class, Spring Boot config, assembly
```

### Module dependency rules

```
gate-iam-domain
      ▲
      │
gate-iam-application
      ▲
      │
gate-iam-infrastructure
      ▲
      │
gate-iam-backend
```

**Hard rules (enforced by ArchUnit — see [testing.md](testing.md)):**
- `gate-iam-domain` has **no dependency** on `org.springframework`, `jakarta.persistence`, or any infrastructure module
- `gate-iam-application` has **no dependency** on `gate-iam-infrastructure`
- Spring `@Component`, `@Entity`, `@Repository` annotations only appear in `gate-iam-infrastructure` and `gate-iam-backend`

---

## Persistence

> Full decision rationale: [ADR-0007](../adr/ADR-0007-persistence-strategy.md)

### Two-layer model (mandatory)

JPA `@Entity` classes live in `gate-iam-infrastructure` only. Domain objects in `gate-iam-domain` are plain Java classes/records with no ORM annotations.

```
gate-iam-domain/
└── model/membership/
    └── Membership.java               ← plain Java record

gate-iam-infrastructure/
└── persistence/membership/
    ├── MembershipJpaEntity.java      ← @Entity, JPA annotations
    └── MembershipJpaRepository.java  ← implements MembershipRepository port
                                         maps Membership ↔ MembershipJpaEntity
```

The `MembershipRepository` outbound port is defined in `gate-iam-domain/port/outbound/` and implemented by the JPA adapter. The domain never references JPA.

### Schema migrations (Flyway)

Migration files live in `gate-iam-infrastructure/src/main/resources/db/migration/` and follow the naming convention:

```
V{version}__{description}.sql

V001__create_persons.sql
V002__create_groups.sql
V003__create_memberships.sql
V004__create_target_systems.sql
V005__create_projection_rules.sql
V006__create_assignments.sql
V007__create_sync_operations.sql
V008__create_target_resource_records.sql
V009__create_outbox_events.sql
```

Flyway runs automatically on startup. Modified historical migrations are rejected via checksum validation.

### JSONB columns

`TargetSystem.connectionConfig` is a PostgreSQL `jsonb` column — schema-free storage for connector-specific API credentials. Sensitive values within the JSON are encrypted at the application layer before persistence.

---

## Domain Event Bus

> Full decision rationale: [ADR-0008](../adr/ADR-0008-event-bus-strategy.md)

### MVP: Spring Events + Transactional Outbox

GATE uses the **transactional outbox pattern** to guarantee durable event delivery without Kafka:

1. A domain use case persists the domain entity AND writes an `OutboxEvent` row **in the same database transaction**
2. `@TransactionalEventListener(phase = AFTER_COMMIT)` fires immediately after commit (fast path)
3. A polling scheduler retries any unprocessed `OutboxEvent` rows — guaranteeing **at-least-once delivery**

```
Use case opens @Transactional
  │
  ├── Persist domain entity (e.g., Membership)
  └── Insert OutboxEvent row (same transaction)
          │
          ▼
   Transaction commits
          │
          ├── @TransactionalEventListener fires → process immediately → mark processed
          │
          └── Polling scheduler (every 30s) → retry unprocessed rows
```

### Outbox table

```sql
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
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
```

### Evolution path to Kafka

The outbox table is designed to be consumed by **Debezium CDC** → Kafka when multi-instance deployment or external event consumers are required. No domain code changes are needed for this migration — only a new infrastructure adapter.

### `DomainEventPublisher` port

All domain events are published via the `DomainEventPublisher` outbound port defined in `gate-iam-domain`. The Spring `ApplicationEventPublisher` adapter in `gate-iam-infrastructure` implements this port, keeping Spring entirely out of the domain.

| Event | Aggregate | Trigger |
|-------|-----------|---------|
| `MembershipGranted` | Membership | Status → `ACTIVE` |
| `MembershipRevoked` | Membership | Status → `REVOKED` |
| `MembershipExpired` | Membership | `validUntil` passed |
| `MembershipSuspended` | Membership | Status → `SUSPENDED` |
| `AssignmentRequested` | Assignment | Assignment computed by `ProjectionCalculationService` |
| `AssignmentApplied` | Assignment | Connector confirms success |
| `AssignmentFailed` | Assignment | Connector returns failure |
| `DownstreamResourceProvisioned` | TargetResourceRecord | `ensureResourceExists()` → `CREATED` |
| `DownstreamResourceTakenOver` | TargetResourceRecord | `ensureResourceExists()` → `ALREADY_EXISTS` |
| `DownstreamResourceDecommissioned` | TargetResourceRecord | `decommissionResource()` succeeds |

---

## Package Structure

```
gate-iam-backend/
└── src/main/java/io/gate/iam/
    │
    ├── [gate-iam-domain]
    │   ├── model/
    │   │   ├── person/          # PersonRef, PersonStatus
    │   │   ├── group/           # GroupRef, TeamType, GovernanceMode
    │   │   ├── membership/      # Membership, MembershipKind, MembershipStatus
    │   │   ├── assignment/      # Assignment, AssignmentAction, ProvenanceType
    │   │   ├── projection/      # GroupProjectionRule, SystemProjectionRule, MappingType
    │   │   └── sync/            # SyncOperation, SyncStatus, TargetResourceRecord
    │   ├── event/               # Domain events (immutable value objects)
    │   └── port/
    │       ├── outbound/        # TargetSystemConnector, UpstreamSourceGateway,
    │       │                    # DomainEventPublisher, MembershipRepository, ...
    │       └── inbound/         # Use case interfaces exposed to the REST layer
    │
    ├── [gate-iam-application]
    │   ├── usecase/             # GrantMembership, RevokeMembership, ImportGroups, ...
    │   └── service/             # ProjectionCalculationService, ExpirationEnforcementService
    │
    └── [gate-iam-infrastructure]
        ├── persistence/         # JPA entities + repository adapters
        │   ├── membership/      # MembershipJpaEntity, MembershipJpaRepository
        │   ├── ...
        │   └── outbox/          # OutboxEventJpaEntity, OutboxPollingScheduler
        ├── api/                 # REST controllers (AdminApiPort implementation)
        ├── connector/
        │   ├── github/          # GitHub TargetSystemConnector adapter
        │   ├── vault/           # Vault TargetSystemConnector adapter
        │   ├── jfrog/           # JFrog TargetSystemConnector adapter
        │   ├── google/          # Google Workspace TargetSystemConnector adapter
        │   └── source/          # UpstreamSourceGateway implementations
        └── event/               # DomainEventPublisher → Spring ApplicationEventPublisher
```
