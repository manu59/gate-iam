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
| ORM | Spring Data JPA + Hibernate | — (managed by Spring Boot BOM) |
| Schema migrations | Flyway | — (managed by Spring Boot BOM) |
| Integration test runtime | Testcontainers | 1.20.6 |
| Event bus (MVP) | Spring Events + PostgreSQL Outbox | — |
| Virtual threads | Project Loom (`spring.threads.virtual.enabled=true`) | stable since Java 21 |

---

## Developer Tooling

| Tool | Role |
|------|------|
| `Makefile` | Shortcuts for common commands (`build`, `test`, `run`, `check`, `install-hooks`) |
| `pre-commit` | Git hooks: Conventional Commits validation (`commit-msg`) + general quality checks (`pre-commit`) |
| `Dependabot` | Automated weekly dependency updates for Gradle and GitHub Actions |
| `Testcontainers` | Spins up real PostgreSQL 17 containers for integration tests (no mocks) |
| `SonarCloud` | Static analysis and Quality Gate on every pull request (security, bugs, code smells) |

### Local setup

```bash
# 1. Install Git hooks (once per clone)
make install-hooks

# 2. Compile and run all tests (unit + integration)
make check
```

### SonarCloud (CI static analysis)

SonarCloud analyses every pull request targeting `main`. The analysis runs after `./gradlew test` so that test results are included.

**Configuration** (no file to maintain locally for developers):
- Plugin: `org.sonarqube` — configured in root `build.gradle.kts`
- `sonar.projectKey` and `sonar.organization` set in the `sonar {}` block
- `SONAR_TOKEN` injected via GitHub Actions secret (Settings → Secrets → `SONAR_TOKEN`)
- `GITHUB_TOKEN` provided automatically by GitHub Actions for PR decoration

**To run locally** (requires a personal token from SonarCloud → My Account → Security):
```bash
SOMAR_TOKEN=<your_token> ./gradlew test sonar
```
> Replace `<your_token>` with a token generated in SonarCloud → My Account → Security.
> The Quality Gate result appears as a check on the pull request. A failing Quality Gate does **not** block the merge by default — configure branch protection in SonarCloud if you want to enforce it.

> **Prerequisite for integration tests**: a running Docker-compatible daemon is required (Docker Desktop, Rancher Desktop, OrbStack, Colima, etc.). Testcontainers auto-detects the socket; no extra configuration is needed.

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
```

**Current migrations (US-002 baseline):**

```
V001__init.sql   ← schema baseline; domain tables added in US-004+
```

Flyway runs automatically on startup. Modified historical migrations are rejected via checksum validation.

### Integration testing (Testcontainers)

Persistence integration tests spin up a real PostgreSQL 17 container via **Testcontainers** — no mocks, no in-memory database.

```
gate-iam-backend/src/test/
└── persistence/
    └── FlywayMigrationIntegrationTest.java   ← verifies all migrations apply cleanly
```

The test verifies that `flyway_schema_history` contains at least one successful migration entry after Spring Boot context startup.

#### Local container runtime

Testcontainers requires a running Docker-compatible daemon. Any Docker-compatible runtime should work (Docker Desktop, OrbStack, Colima, Rancher Desktop).

> **Validated runtime**: only **Rancher Desktop** has been tested so far. Other runtimes are expected to work but have not been verified on this project.

`gate-iam-backend/build.gradle.kts` includes an automatic workaround for **Rancher Desktop** specifically: it uses containerd as its runtime, which does not support the privileged socket bind-mount that Ryuk (the Testcontainers cleanup container) requires. When `~/.rd/docker.sock` is detected, the test JVM automatically receives:

```kotlin
environment("DOCKER_HOST", "unix://${rancherSocket.absolutePath}")
environment("TESTCONTAINERS_RYUK_DISABLED", "true")
```

No manual configuration is needed — this activates only when Rancher Desktop is present and has no effect on other runtimes.

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
