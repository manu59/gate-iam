# Connector Contract

This document describes the contract that all `TargetSystemConnector` implementations must satisfy, and provides guidance for implementing a new connector.

> **Source of truth**: [ADR-0003](../adr/ADR-0003-canonical-model-and-projection-architecture.md) and [ADR-0005](../adr/ADR-0005-downstream-resource-lifecycle-management.md).

---

## Overview

A connector is the **only point of contact** between GATE and a downstream target system. It is a pure technical translator:

- It receives fully resolved `Assignment` objects from the domain
- It applies them to the target system via that system's API
- It returns a result indicating success or failure
- It **never** holds projection logic, role translation, or governance decisions

All decisions about *who* gets *which role* in *which resource* are made by the domain layer before the connector is called.

---

## Port Interface

```java
// Defined in: domain/port/outbound/TargetSystemConnector.java
public interface TargetSystemConnector {

    /**
     * Unique identifier for the system type this connector handles.
     * Must match TargetSystem.connectorRef values in the database.
     */
    String systemType();

    // ── Membership operations ────────────────────────────────────────

    /**
     * Grants the person described by PersonRef the access defined in Assignment.
     * Must be idempotent: if the person already has access, return ALREADY_EXISTS.
     */
    AssignmentResult apply(Assignment assignment, PersonRef person);

    /**
     * Revokes the access defined in Assignment from the person.
     * Must be idempotent: if the person does not have access, return NO_OP.
     */
    AssignmentResult revoke(Assignment assignment, PersonRef person);

    /**
     * Returns whether the downstream system is reachable and operational.
     */
    HealthStatus isHealthy();

    // ── Resource lifecycle operations ────────────────────────────────

    /**
     * Ensures the downstream resource identified by targetIdentifier exists.
     * If it already exists, verify it and return ALREADY_EXISTS.
     * Must be idempotent.
     */
    ResourceProvisioningResult ensureResourceExists(
        String targetIdentifier,
        GroupRef group,
        TargetSystem system
    );

    /**
     * Decommissions the downstream resource.
     * Behavior is governed by TargetSystem.decommissionPolicy.
     * Must not fail if the resource does not exist.
     */
    ResourceProvisioningResult decommissionResource(
        String targetIdentifier,
        GroupRef group,
        TargetSystem system
    );
}
```

---

## Result Types

### `AssignmentResult`

| Field | Type | Description |
|-------|------|-------------|
| `status` | Enum | `APPLIED` \| `ALREADY_EXISTS` \| `NO_OP` \| `FAILED` |
| `rawResponse` | String? | Raw API response for audit |
| `errorMessage` | String? | Set when `status=FAILED` |

### `ResourceProvisioningResult`

| Field | Type | Description |
|-------|------|-------------|
| `status` | Enum | `CREATED` \| `ALREADY_EXISTS` \| `UPDATED` \| `DECOMMISSIONED` \| `FAILED` |
| `targetIdentifier` | String | The identifier actually used in the downstream system |
| `rawResponse` | String? | Raw API response for audit |
| `errorMessage` | String? | Set when `status=FAILED` |

---

## Connector Responsibilities

A connector **must**:

- Translate `Assignment.targetIdentifier` and `Assignment.targetRole` into target-system-specific API calls
- Be idempotent — calling `apply()` or `ensureResourceExists()` multiple times with the same input must produce the same result
- Return `ALREADY_EXISTS` / `NO_OP` instead of failing when state is already correct
- Return a `FAILED` result (not throw an exception) on recoverable API errors
- Throw only for unrecoverable errors (misconfiguration, invalid credentials)
- Log the raw API response in `rawResponse` for auditability

A connector **must not**:

- Decide which persons to project or with which role
- Hold any business rule about projection eligibility
- Read domain state directly (no direct database access)
- Call other connectors or domain services

---

## Configuration

Each connector is identified by a `connectorRef` string on `TargetSystem`. Connection parameters are passed via `TargetSystem.connectionConfig` (a JSON blob, encrypted at rest).

Example for GitHub:
```json
{
  "org": "acme-corp",
  "appId": "12345",
  "privateKeyRef": "secret/github-app-key"
}
```

Connection config is passed to the connector at construction time via dependency injection. Connectors should validate their config at startup and surface misconfigurations via `isHealthy()`.

---

## Implementing a New Connector

### 1. Create the adapter class

```
infrastructure/connector/<system-name>/
├── <SystemName>Connector.java          # implements TargetSystemConnector
├── <SystemName>ConnectorConfig.java    # config binding
└── <SystemName>ConnectorTest.java      # integration test (Testcontainers or mock)
```

### 2. Implement all interface methods

Start with `isHealthy()` and `ensureResourceExists()`. These are called before any membership operations.

### 3. Register the connector

Annotate the class with `@Component` (Spring). GATE discovers connectors by scanning for `TargetSystemConnector` beans and matching them to `TargetSystem.connectorRef` via `systemType()`.

### 4. Test requirements

- Unit test: mock the downstream API client; verify correct translation of `Assignment` fields to API calls
- Integration test: use a real (sandboxed) or mock server to verify end-to-end behavior including idempotence
- All four lifecycle methods must be covered: `apply`, `revoke`, `ensureResourceExists`, `decommissionResource`

---

## Existing Connectors

| System | Status | `connectorRef` |
|--------|--------|---------------|
| GitHub | Planned | `github-connector-v1` |
| HashiCorp Vault | Planned | `vault-connector-v1` |
| JFrog Artifactory | Planned | `jfrog-connector-v1` |
| Google Workspace | Planned | `google-connector-v1` |

> Connectors marked **Planned** have their configuration schema defined but are not yet implemented. Contributions welcome — see [CONTRIBUTING.md](../../CONTRIBUTING.md).

---

## Upstream Source Gateway

GATE also defines an outbound port for **importing** organizational data from upstream systems:

```java
// Defined in: domain/port/outbound/UpstreamSourceGateway.java
public interface UpstreamSourceGateway {

    String sourceSystem();

    List<PersonRef> fetchPersons();

    List<GroupRef> fetchGroups();

    List<MembershipRef> fetchMemberships();
}
```

This port is called by import use cases (`ImportPersonsFromSourceUseCase`, `ImportGroupsFromSourceUseCase`, `HandleUpstreamMembershipChangedUseCase`) and follows the same isolation principles as `TargetSystemConnector`.
