# Testing Guide

This document describes the testing strategy, tooling, and conventions for GATE IAM contributors.

> **Source of truth**: [ADR-0009](../adr/ADR-0009-testing-strategy.md).

GATE uses **Test-Driven Development (TDD)**. Tests are not written after the fact — they are the primary design tool. The Red → Green → Refactor cycle drives every implementation.

---

## Testing Pyramid

```
             ▲
            / \
           /   \  E2E / contract tests
          /─────\  (few — full API surface)
         /       \
        /─────────\  Integration tests
       /           \  (adapters, Spring context, Testcontainers)
      /─────────────\
     /               \  Unit tests
    /─────────────────\  (domain + use cases — pure Java, no Spring)
   ───────────────────────
        ArchUnit  (continuous — runs on every build)

   ════════════════════════
        SonarCloud  (static analysis — runs on every pull request)
```

| Layer | Scope | Tools | Approximate speed |
|-------|-------|-------|------------------|
| Unit | Domain model + use cases | JUnit 5, AssertJ, Mockito | < 1 ms/test |
| Integration | JPA adapters, connectors, event consumers | Spring Boot Test, Testcontainers, Awaitility | 2–10 s |
| E2E / Contract | Full REST API | Spring MockMvc / RestAssured | 5–30 s |
| ArchUnit | Package dependency rules | ArchUnit | < 1 s/rule |
| SonarCloud | Static analysis (bugs, security hotspots, code smells) | SonarCloud SaaS | async (CI) |

> SonarCloud is **not a test runner** — it does not replace the pyramid. It analyses the compiled code and test results produced by `./gradlew test` and posts a Quality Gate result on the pull request.

---

## Tooling

| Tool | Version | Purpose |
|------|---------|---------|
| JUnit 5 | 5.11+ | Test runner at all layers |
| AssertJ | 3.x | Fluent assertions — preferred over Hamcrest |
| Mockito | 5.x | Port mocking in use case tests |
| Testcontainers | 1.20+ | Real PostgreSQL for integration tests |
| ArchUnit | 1.x | Architectural rule enforcement |
| Awaitility | 4.x | Async assertion for event and outbox tests |

---

## TDD Cycle

```
RED    — Write a failing test that describes the expected behaviour
GREEN  — Write the minimal production code to make it pass
REFACTOR — Clean up without breaking tests
```

**Commit discipline**: each Red → Green → Refactor cycle produces one commit:

```
test: add failing test for temporary membership without validUntil
feat: enforce validUntil required for TEMPORARY memberships
refactor: extract membership invariant validation to dedicated method
```

Commits with failing tests must **never** be pushed to `main`. Feature branches may have in-progress red tests during active development.

---

## Layer-by-Layer Rules

### Domain layer (`gate-iam-domain`) — pure Java, no Spring

```java
// ✅ Correct: no Spring, no Mockito, no infrastructure
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

- No `@SpringBootTest`, no `@Autowired`, no Mockito
- Domain objects have no dependencies to mock — test them directly
- Exercise all invariants documented in [domain-model.md](domain-model.md)

### Application layer (`gate-iam-application`) — mock the ports

```java
class GrantMembershipUseCaseTest {

    @Mock MembershipRepository membershipRepository;
    @Mock DomainEventPublisher eventPublisher;
    @InjectMocks GrantMembershipUseCase useCase;

    @Test
    void should_publish_membership_granted_event_on_success() {
        var command = GrantMembershipCommand.of(personId, groupId, MEMBER, BASELINE, ...);

        useCase.execute(command);

        verify(membershipRepository).save(any(Membership.class));
        verify(eventPublisher).publish(any(MembershipGranted.class));
    }
}
```

- Outbound ports (`MembershipRepository`, `DomainEventPublisher`, `TargetSystemConnector`) are mocked via Mockito
- Never inject a real infrastructure implementation in a use case test
- Test that use cases emit the correct domain events and call the correct ports

### Infrastructure layer (`gate-iam-infrastructure`) — real PostgreSQL

#### JPA adapters

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class MembershipJpaRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Test
    void should_persist_and_retrieve_membership_by_external_ref() { ... }
}
```

- **No H2, no mocked `DataSource`** — H2 does not support PostgreSQL JSONB
- Testcontainers reusable mode for fast local iteration
- Flyway migrations run automatically — tests verify the actual production schema

#### Connector adapters

- REST-based connectors (GitHub, JFrog): **WireMock** mock HTTP server
- Service-based connectors (Vault): **Testcontainers** with the official Docker image
- Every connector test must verify **idempotency**: calling `apply()` or `ensureResourceExists()` twice with the same input produces the same result and no duplication

#### Event consumers — idempotency test

Every `@TransactionalEventListener` must have a test that delivers the same event twice:

```java
@Test
void should_be_idempotent_when_assignment_requested_delivered_twice() {
    var event = new AssignmentRequested(...);
    consumer.handle(event);
    consumer.handle(event); // second delivery — outbox guarantees at-least-once

    assertThat(assignmentRepository.count()).isEqualTo(1); // not 2
}
```

### E2E / Contract tests — Spring MockMvc

```java
@WebMvcTest(MembershipController.class)
class MembershipControllerTest {

    @MockBean GrantMembershipUseCase grantMembershipUseCase;

    @Test
    void should_return_201_when_membership_created() { ... }
}
```

- `@WebMvcTest` slices test only the controller layer
- `@SpringBootTest` reserved for full-context E2E tests (few, slow)

---

## ArchUnit Rules

ArchUnit rules live in `gate-iam-infrastructure/src/test/java/io/gate/iam/architecture/HexagonalArchitectureTest.java`. They run as standard JUnit tests on every build.

### Enforced rules

```java
@AnalyzeClasses(packages = "io.gate.iam")
class HexagonalArchitectureTest {

    // Domain must not depend on Spring or JPA
    @ArchTest
    ArchRule domain_has_no_framework_dependency =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..");

    // Domain must not depend on infrastructure
    @ArchTest
    ArchRule domain_has_no_infrastructure_dependency =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    // Application must not depend on infrastructure
    @ArchTest
    ArchRule application_has_no_infrastructure_dependency =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    // JPA entities must live in infrastructure only
    @ArchTest
    ArchRule jpa_entities_reside_in_infrastructure =
        classes().that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..infrastructure.persistence..");

    // Use cases must not depend on Spring Web
    @ArchTest
    ArchRule use_cases_have_no_web_dependency =
        noClasses().that().resideInAPackage("..application.usecase..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.springframework.web..");
}
```

These rules catch violations **at compile time** — no JPA annotation can silently creep into the domain during a feature branch.

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

### Test source layout

```
src/test/java/io/gate/iam/
├── domain/              # Unit tests — no Spring
├── application/         # Use case tests — Mockito port mocks
├── infrastructure/
│   ├── persistence/     # @DataJpaTest + Testcontainers
│   ├── connector/       # Connector integration tests
│   └── api/             # @WebMvcTest controller tests
└── architecture/        # ArchUnit rules
```

### Test method naming

Use descriptive `should_..._when_...()` naming in English:

```java
should_reject_exception_membership_without_justification()
should_publish_membership_granted_event_on_successful_grant()
should_return_no_op_when_apply_called_twice_with_same_assignment()
```

---

## Running Tests

```bash
# All unit tests (fast — no Docker required)
./gradlew :gate-iam-domain:test :gate-iam-application:test

# All tests including integration (requires Docker)
./gradlew test

# ArchUnit rules only
./gradlew :gate-iam-infrastructure:test --tests "*.architecture.*"

# Single test class
./gradlew test --tests "io.gate.iam.domain.membership.MembershipTest"
```

Testcontainers reuse mode (faster local iteration):

```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```
