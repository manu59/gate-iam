# ADR-0006: Language and Application Framework

## Status

`Accepted`

## Date

2026-04-01

---

## Context

Following the domain model and architecture defined in ADR-0001 through ADR-0005, this ADR formalizes the choice of programming language, application framework, and build tooling for the GATE IAM backend.

The architectural drivers from ADR-0001 impose constraints on this choice:

- **TD-1** — Loose coupling: the framework must not leak into the domain layer
- **TD-2** — Domain independence from vendor-specific concepts
- **TD-6** — Extensible connector architecture: new connectors must be addable without modifying the core
- **FD-3** — Synchronization status must be a first-class visible concept: the framework must support async patterns
- Hexagonal architecture: the chosen stack must make it natural to isolate the domain from infrastructure

The team (solo, open-source project) also imposes practical constraints: strong ecosystem, wide community support, available libraries for all planned integrations (GitHub API, Vault, JFrog, Google Workspace), and long-term maintainability.

---

## Decision Drivers

- **TD-1** — Loose coupling with infrastructure
- **TD-2** — Domain independence
- **TD-6** — Extensible connector architecture
- **Practical**: large ecosystem of integration libraries (GitHub, Vault, JFrog, Google Workspace clients)
- **Practical**: strong TDD support (see ADR-0009)
- **Practical**: familiarity and long-term support horizon

---

## Considered Options

### Language

#### Option A — Kotlin
Strong type system, concise syntax, null safety built in, excellent Spring Boot support.

**Not selected because:**
- Adds cognitive overhead for contributors unfamiliar with Kotlin
- Interop with Java libraries is seamless but the inverse is occasionally awkward
- No material benefit for this project that justifies the onboarding cost

#### Option B — Go
Excellent for lightweight services, fast compile times, strong concurrency model.

**Not selected because:**
- Spring Boot ecosystem not available; all integrations (JPA, Flyway, Spring Security) would need to be rebuilt
- DDD and hexagonal architecture patterns are less idiomatic in Go
- Smaller pool of contributors comfortable with Go + DDD

#### Option C — Java 25 _(selected)_
Mature, strongly typed, extensive ecosystem, virtual threads (Project Loom, stable since Java 21) available for async I/O without reactive complexity, widespread DDD and hexagonal architecture community and tooling.

**Selected because:**
- JVM ecosystem has first-class clients for all planned target and source integrations
- Spring Boot + hexagonal architecture is a well-documented, well-understood combination
- Java 25 LTS (released September 2025): current LTS at project start, long support horizon
- Virtual threads reduce async complexity — no need for reactive programming (WebFlux)
- ArchUnit, Testcontainers, JUnit 5 — all first-class Java citizens (ADR-0009)
- Large open-source contributor pool

### Application Framework

#### Option A — Quarkus
Very fast startup, native compilation, excellent for containerized deployments.

**Not selected because:**
- Smaller ecosystem and community than Spring Boot
- Native compilation introduces build complexity without clear benefit for this workload (GATE is not a serverless function — startup time is not critical)
- Less mature support for hexagonal architecture patterns in documentation and tooling

#### Option B — Micronaut
Similar to Quarkus, AOT compilation, fast startup.

**Not selected because:**
- Same reasoning as Quarkus
- Spring Boot's maturity and ecosystem depth outweigh the startup performance advantage for this use case

#### Option C — Spring Boot 3.5.x _(selected)_
Mature, widely adopted, excellent support for JPA, Flyway, async processing, hexagonal architecture patterns, REST APIs, and all planned integrations.

**Selected because:**
- Spring's dependency injection model maps naturally to hexagonal architecture port/adapter injection
- Spring Events provides a viable in-memory event bus for MVP (ADR-0008)
- Spring Boot 3.5.x requires Java 17+ — fully compatible with Java 25
- First-class support for virtual threads (`spring.threads.virtual.enabled=true`)
- Extensive documentation and community resources for DDD/hexagonal patterns with Spring

### Build Tooling

#### Option A — Maven
XML-based, verbose but universally understood. Standard in many enterprise Java shops.

**Not selected because:**
- Verbose configuration for multi-module projects
- Slower than Gradle for incremental builds
- Convention over configuration is harder to achieve

#### Option B — Gradle (Kotlin DSL) _(selected)_
Concise, powerful, fast incremental builds, excellent IDE support with the Kotlin DSL.

**Selected because:**
- Faster build times via incremental compilation and build cache
- Kotlin DSL provides type-safe build scripts with IDE completion
- Superior multi-module project support (relevant as connectors are separate modules)
- Standard in modern Spring Boot projects alongside Maven

---

## Decision

| Concern | Choice |
|---------|--------|
| Language | **Java 25** (LTS) |
| Framework | **Spring Boot 3.5.x** |
| Build | **Gradle 9.x** with Kotlin DSL |
| Target JVM | Java 25 with virtual threads enabled |

---

## Module Structure

The project uses a **multi-module Gradle build** to enforce hexagonal isolation via compilation boundaries:

```
gate-iam/
├── gate-iam-domain/          # Pure Java, zero Spring/JPA dependencies
├── gate-iam-application/     # Spring @Service, depends on domain only
├── gate-iam-infrastructure/  # Spring Boot, JPA, connectors — depends on domain + application
└── gate-iam-backend/         # Spring Boot launcher (main class, config, assembly)
```

**Key rule:** `gate-iam-domain` has **no Spring dependency** in its `build.gradle.kts`. This is enforced by ArchUnit (ADR-0009).

---

## Consequences

### Positive
- Java 25 virtual threads eliminate the need for reactive programming (WebFlux) in the MVP — the codebase stays imperative and readable
- Multi-module Gradle build enforces hexagonal boundaries at compile time, not just convention
- Spring Boot's maturity means no integration library is missing for any planned connector

### Negative / Trade-offs
- Spring Boot adds startup overhead (~2s) — acceptable for this workload
- Gradle Kotlin DSL has a steeper learning curve than Maven for occasional contributors
- Java's verbosity compared to Kotlin increases boilerplate for value objects — mitigated by Java records (available since Java 16)

### Risks
- **Spring annotations leaking into the domain**: mitigated by multi-module build boundaries and ArchUnit rules (ADR-0009)
- **JPA entity classes used as domain model**: requires discipline and explicit mapping layer — addressed in ADR-0007
