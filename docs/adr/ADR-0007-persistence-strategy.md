# ADR-0007: Persistence Strategy

## Status

`Accepted`

## Date

2026-04-01

---

## Context

GATE IAM requires persistent storage for its canonical domain model: `PersonRef`, `GroupRef`, `Membership`, `TargetSystem`, `GroupProjectionRule`, `SystemProjectionRule`, `Assignment`, `SyncOperation`, and `TargetResourceRecord` (defined in ADR-0003, ADR-0004, ADR-0005).

The persistence strategy must satisfy the hexagonal architecture constraint from ADR-0003: **the domain model must have zero dependency on the persistence layer**. This is a non-negotiable architectural invariant enforced by the multi-module build (ADR-0006).

The key tension in this choice is between:
- **Developer productivity**: an ORM reduces boilerplate and handles schema mapping automatically
- **Architectural purity**: an ORM tends to push towards anemic domain models with persistence annotations leaking into domain classes

Additionally, the choice of database impacts auditability (structured queries over history), consistency (ACID transactions for membership lifecycle transitions), and operational simplicity.

---

## Decision Drivers

- **TD-2** — Domain independence: persistence annotations must not appear in `gate-iam-domain`
- **TD-3** — Resilience: ACID transactions required for membership lifecycle state transitions
- **BD-5** — Traceability: rich queryability for audit log and assignment history
- **OD-1** — Observable synchronization state: `SyncOperation` and `Assignment` records must support complex aggregation queries
- Hexagonal architecture: repositories are **outbound ports** in the domain, **implemented** in the infrastructure module
- **ADR-0006**: Spring Boot 3.5.x is the framework; the persistence solution must integrate naturally

---

## Considered Options

### Database Engine

#### Option A — MongoDB
Document store, flexible schema, natural fit for `connectionConfig` JSON blobs.

**Not selected because:**
- Weak transactional guarantees for multi-entity state transitions (e.g., archiving a `Membership` + generating `Assignment` records must be atomic)
- Complex relational queries for assignment provenance tracing are awkward in MongoDB
- The domain model is fundamentally relational (foreign keys between `Membership`, `GroupRef`, `PersonRef`, `Assignment`)

#### Option B — MySQL / MariaDB
Mature relational database, widely available.

**Not selected because:**
- PostgreSQL is strictly superior for this use case: JSONB for `connectionConfig`, better window functions for analytics, `uuid-ossp` extension, `LISTEN/NOTIFY` for future event bus integration

#### Option C — PostgreSQL _(selected)_
Mature, ACID-compliant, excellent JSONB support, rich ecosystem.

**Selected because:**
- JSONB type for `connectionConfig` (connector-specific, schema-free configuration)
- Full ACID transactions for membership lifecycle state machines
- UUID primary keys via `gen_random_uuid()`
- Strong support for complex audit queries (window functions, CTEs)
- Widely available on all cloud providers (managed: RDS, Cloud SQL, Supabase, Neon)
- Native `LISTEN/NOTIFY` available for future lightweight event bus integration (ADR-0008)

---

### ORM / Data Access Layer

#### Option A — Spring Data JPA + Hibernate _(selected)_
The standard Spring persistence stack. Annotations on entity classes, automatic schema management, JPQL/Criteria API.

**Risk**: JPA `@Entity` classes are habitually placed in the domain package and carry `@Id`, `@Column`, `@OneToMany` annotations — violating domain independence.

**Selected with a mandatory mapping layer** (see Decision below).

#### Option B — jOOQ
Type-safe SQL DSL, code generated from the database schema. Keeps domain objects pure by design.

**Pros:**
- No ORM magic — explicit, readable SQL
- Domain objects are never touched by persistence annotations
- Type-safe queries catch SQL errors at compile time

**Cons:**
- Code generation step adds build complexity
- More boilerplate for simple CRUD operations
- Less familiar to contributors comfortable with Spring Data JPA
- Spring Data JPA's repository pattern aligns naturally with the outbound port pattern

**Not selected for MVP**: retained as the preferred evolution path if JPA mapping complexity grows. The port/adapter isolation means jOOQ can replace JPA without touching the domain.

#### Option C — Spring Data JDBC
Simpler than JPA (no lazy loading, no first-level cache, no magic), better fit for DDD aggregates.

**Not selected because:**
- Less mature ecosystem than JPA for complex relationships
- Less community familiarity
- JPA with a strict mapping layer achieves the same architectural goals

---

### Schema Migration

#### Option A — Liquibase
XML/YAML/JSON changeset files, rich rollback support, checksum validation.

**Not selected because:**
- More complex configuration and tooling than Flyway
- XML/YAML changesets are more verbose than SQL migration files

#### Option B — Flyway _(selected)_
SQL-first migration files (plain `.sql`), versioned by filename convention, simple and battle-tested.

**Selected because:**
- SQL migrations are readable by anyone — no DSL to learn
- Automatic execution on startup via Spring Boot integration
- Supports PostgreSQL-specific DDL (JSONB columns, UUID defaults, indexes)
- Flyway Community edition covers all needed features

---

## Decision

| Concern | Choice |
|---------|--------|
| Database | **PostgreSQL 17+** |
| ORM | **Spring Data JPA + Hibernate** with explicit JPA entity / domain object mapping |
| Migrations | **Flyway** (SQL migrations, versioned) |

### Mandatory architectural rule: two-layer persistence model

JPA `@Entity` classes **must never appear in `gate-iam-domain`**. The persistence layer uses a two-layer model:

```
gate-iam-domain/
└── model/membership/
    └── Membership.java          ← pure Java record / class, no JPA annotations

gate-iam-infrastructure/
└── persistence/membership/
    ├── MembershipJpaEntity.java ← @Entity, @Table, @Id — infrastructure only
    └── MembershipJpaRepository.java ← implements domain port MembershipRepository
        (maps between Membership ↔ MembershipJpaEntity)
```

The domain port interface lives in `gate-iam-domain/port/outbound/MembershipRepository.java`. The infrastructure adapter implements it and handles all ORM mapping. This rule is enforced by ArchUnit (ADR-0009).

### Migration naming convention

```
V{version}__{description}.sql
V001__create_persons.sql
V002__create_groups.sql
V003__create_memberships.sql
...
```

Migrations live in `gate-iam-infrastructure/src/main/resources/db/migration/`.

### JSONB usage

`TargetSystem.connectionConfig` is stored as a PostgreSQL `jsonb` column. The JPA entity maps it as `String` (serialized JSON). Encryption of sensitive values within the JSON blob is the responsibility of the application layer before persistence.

---

## Consequences

### Positive
- ACID transactions protect membership lifecycle state transitions
- Two-layer model enforces domain purity — the domain never sees `@Entity`
- Flyway SQL migrations are readable and auditable in version control
- JSONB gives schema flexibility for connector configuration without sacrificing relational integrity for other fields

### Negative / Trade-offs
- The two-layer model requires explicit mapping code for each entity (boilerplate)
- JPA lazy loading and first-level cache are available but must be used carefully — prefer explicit fetching to avoid N+1 queries
- Flyway does not support automatic rollback — forward-only migration discipline required

### Risks
- **JPA entity leaking into domain**: mitigated by multi-module build (compile-time boundary) and ArchUnit (ADR-0009)
- **Schema drift**: mitigated by Flyway checksums — modified historical migrations are rejected at startup
- **`connectionConfig` plaintext in JPA entity**: mitigated by encrypting sensitive values at the application layer before persisting
