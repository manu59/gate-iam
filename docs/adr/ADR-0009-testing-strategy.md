# ADR-0009: Testing Strategy

## Status

`Accepted`

## Date

2026-04-01

---

## Context

GATE IAM adopts a **Test-Driven Development (TDD)** approach. Tests are not written after the fact — they are the primary design tool.

This ADR defines:
- The testing pyramid adopted for GATE
- The tooling selected at each layer
- The architectural rules enforced by ArchUnit
- Testing conventions applied across the codebase
- The specific constraints imposed by the hexagonal architecture (ADR-0003) and the persistence model (ADR-0007)

The testing strategy must serve three goals:
1. **Drive design**: tests written first force clear interfaces and small, focused units
2. **Enforce architecture**: automated rules prevent the hexagonal boundaries from eroding
3. **Provide confidence**: the full test suite must be runnable locally without live external services

---

## Decision Drivers

- **ADR-0003**: hexagonal architecture — domain must be testable without infrastructure
- **ADR-0006**: Java 25 + Spring Boot 3.5.x — testing primitives are Java/Spring-native
- **ADR-0007**: JPA two-layer model — infrastructure adapters need database-level integration tests
- **ADR-0008**: outbox pattern + `@TransactionalEventListener` — event consumers must be tested for idempotency
- **TD-2** — Domain independence: domain tests must have zero Spring/JPA dependencies
- **BD-5** — Traceability: projection and assignment logic is business-critical — must be thoroughly tested

---

## Testing Pyramid

```
             ▲
            / \
           /   \  E2E / contract tests
          /─────\  (few, slow, API-level)
         /       \
        /─────────\  Integration tests
       /           \  (adapters, Spring context, Testcontainers)
      /─────────────\
     /               \  Unit tests
    /─────────────────\  (domain + use cases, pure Java, no Spring)
   ───────────────────────
        ArchUnit rules (continuous, zero-cost)
```

| Layer | Scope | Tools | Speed | Quantity |
|-------|-------|-------|-------|----------|
| Unit | Domain model + use cases | JUnit 5, AssertJ, Mockito | < 1s/test | Many |
| Integration | Adapters (JPA, connectors, events) | Spring Boot Test, Testcontainers, Awaitility | 2–10s | Moderate |
| E2E / Contract | Full API surface | Spring MockMvc / RestAssured | 5–30s | Few |
| Architecture | Package dependencies | ArchUnit | < 1s/rule | Fixed set |

---

## Tooling

### Core

| Tool | Version | Purpose |
|------|---------|---------|
| JUnit 5 | 5.11+ | Test runner — all layers |
| AssertJ | 3.x | Fluent assertions — preferred over Hamcrest |
| Mockito | 5.x | Mocks for ports in use case tests |
| Testcontainers | 1.20+ | Real PostgreSQL instance for integration tests |
| ArchUnit | 1.x | Architectural rule enforcement |
| Awaitility | 4.x | Async assertion for event/outbox tests |

### Spring Boot Test

| Annotation | When to use |
|------------|-------------|
| `@SpringBootTest` | Full context — E2E and contract tests only |
| `@DataJpaTest` | JPA repository tests (PostgreSQL via Testcontainers) |
| `@WebMvcTest` | REST controller tests (mocked service layer) |
| `@RecordApplicationEvents` | Capture Spring events in integration tests |

**Rule**: `@SpringBootTest` must not be used for unit tests of domain classes or use cases.

---

## Layer-by-Layer Testing Rules

### 1. Domain layer (`gate-iam-domain`)

**Approach**: strict TDD — write the test first, then the minimal implementation.

- No Spring annotations (`@Autowired`, `@Component`, etc.)
- No Mockito — domain objects have no dependencies to mock
- No database access
- Test class names: `{ClassName}Test`
- Test method names in English, descriptive: `should_reject_temporary_membership_without_valid_until()`

```java
// Example — pure domain test
class MembershipTest {

    @Test
    void should_reject_temporary_membership_without_valid_until() {
        assertThatThrownBy(() ->
            Membership.create(personId, groupId, TEMPORARY, null, "justification", "creator")
        ).isInstanceOf(MembershipInvariantViolationException.class)
         .hasMessageContaining("validUntil is required for TEMPORARY memberships");
    }
}
```

### 2. Application layer (`gate-iam-application`)

**Approach**: TDD with mocked outbound ports.

- Ports are mocked via Mockito — **never** inject real infrastructure implementations
- Test that use cases emit the expected domain events
- Test that invariants are enforced before ports are called
- Test class names: `{UseCaseName}Test`

```java
// Example — use case test with mocked ports
class GrantMembershipUseCaseTest {

    @Mock MembershipRepository membershipRepository;
    @Mock DomainEventPublisher eventPublisher;
    @InjectMocks GrantMembershipUseCase useCase;

    @Test
    void should_publish_membership_granted_event_on_success() {
        // Given
        var command = GrantMembershipCommand.of(personId, groupId, MEMBER, BASELINE, ...);

        // When
        useCase.execute(command);

        // Then
        verify(membershipRepository).save(any(Membership.class));
        verify(eventPublisher).publish(any(MembershipGranted.class));
    }
}
```

### 3. Infrastructure layer (`gate-iam-infrastructure`)

**Approach**: integration tests with real infrastructure via Testcontainers.

#### JPA adapters (`@DataJpaTest`)

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class MembershipJpaRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Test
    void should_persist_and_retrieve_membership_by_externalRef() { ... }
}
```

**Rule**: JPA adapter tests use a real PostgreSQL container. Mocked `DataSource` or H2 are forbidden — H2 does not support PostgreSQL JSONB, and mocks do not catch SQL errors.

#### Event consumer tests (outbox + idempotency)

Every `@TransactionalEventListener` class must have a test that delivers the same event twice and asserts the second delivery produces no side effect:

```java
@Test
void should_be_idempotent_when_assignment_requested_delivered_twice() {
    var event = new AssignmentRequested(...);
    consumer.handle(event);
    consumer.handle(event); // second delivery
    assertThat(assignmentRepository.count()).isEqualTo(1); // not 2
}
```

#### Connector adapter tests

Connector adapters (`TargetSystemConnector` implementations) are tested with either:
- A **mock HTTP server** (WireMock) for REST-based connectors (GitHub, JFrog)
- A **Testcontainers instance** for connectors with available Docker images (Vault)

**Rule**: connector tests must verify idempotency — calling `apply()` or `ensureResourceExists()` twice with the same input must produce the same result.

### 4. ArchUnit rules

ArchUnit rules live in `gate-iam-infrastructure/src/test/java/io/gate/iam/architecture/`. They run as standard JUnit tests on every build.

#### Enforced rules

```java
@AnalyzeClasses(packages = "io.gate.iam")
class HexagonalArchitectureTest {

    // Domain has no Spring or JPA dependency
    @ArchTest
    ArchRule domain_must_not_depend_on_spring =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..");

    // Domain has no dependency on infrastructure
    @ArchTest
    ArchRule domain_must_not_depend_on_infrastructure =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    // Infrastructure must not be imported by domain or application
    @ArchTest
    ArchRule infrastructure_is_not_imported_by_domain_or_application =
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    // Use cases only depend on domain and ports
    @ArchTest
    ArchRule use_cases_must_not_depend_on_spring_web =
        noClasses().that().resideInAPackage("..application.usecase..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.springframework.web..");

    // JPA entities must not appear in domain packages
    @ArchTest
    ArchRule jpa_entities_must_reside_in_infrastructure =
        classes().that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..infrastructure.persistence..");

    // Event listeners must not reside in domain
    @ArchTest
    ArchRule event_listeners_must_not_reside_in_domain =
        noClasses().that().resideInAPackage("..domain..")
            .should().beAnnotatedWith(EventListener.class);
}
```

---

## Naming Conventions

| Class type | Suffix | Module |
|------------|--------|--------|
| Domain unit test | `Test` | `gate-iam-domain` |
| Use case unit test | `Test` | `gate-iam-application` |
| JPA adapter integration test | `IntegrationTest` | `gate-iam-infrastructure` |
| Connector adapter test | `IntegrationTest` | `gate-iam-infrastructure` |
| REST controller test | `ControllerTest` | `gate-iam-infrastructure` |
| ArchUnit rule class | `ArchitectureTest` | `gate-iam-infrastructure` |

### Source set layout

```
src/
├── main/java/
└── test/java/
    └── io/gate/iam/
        ├── domain/              # Unit tests (no Spring)
        ├── application/         # Use case tests (Mockito)
        ├── infrastructure/
        │   ├── persistence/     # @DataJpaTest tests
        │   ├── connector/       # Connector integration tests
        │   └── api/             # @WebMvcTest controller tests
        └── architecture/        # ArchUnit rules
```

---

## TDD Cycle

For each user story:

```
1. RED   — Write a failing test that describes the expected behaviour
2. GREEN — Write the minimal production code to make it pass
3. REFACTOR — Clean up without breaking tests
```

**Commit discipline**: each Red → Green → Refactor cycle produces **one commit**. The commit message follows Conventional Commits (`test:`, `feat:`, `refactor:`).

Commits with failing tests must never be pushed to `main`. Feature branches may have in-progress red tests during active development.

---

## Consequences

### Positive
- Domain tests run in milliseconds — fast feedback loop during TDD cycles
- ArchUnit catches architectural violations instantly on every build — no manual review needed
- Testcontainers integration tests use real PostgreSQL — no H2 incompatibility surprises in production
- The full test suite runs without any live external service

### Negative / Trade-offs
- Testcontainers integration tests require Docker on all developer machines and CI runners
- The two-layer JPA model (ADR-0007) generates more test surface (both domain classes and JPA entities need coverage)
- ArchUnit rules require maintenance when packages are reorganized

### Risks
- **Slow CI from Testcontainers**: mitigated by reusable containers (`@Container static`) and Testcontainers' reuse mode in local development
- **ArchUnit false positives on legitimate patterns**: rules must be kept precise — overly broad rules that reject valid code erode trust in the test suite
