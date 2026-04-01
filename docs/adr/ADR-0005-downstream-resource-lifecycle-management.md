# ADR-0005: Downstream Resource Lifecycle Management

## Status

`Accepted`

## Date

2026-03-25

---

## Context

[ADR-0004](ADR-0004-role-mapping-resolution-strategy.md) defines how GATE resolves role mappings and computes `Assignment` records. That model contains an **implicit assumption**: the `targetIdentifier` (e.g., `org/payments-engineers`) refers to a resource that **already exists** in the downstream system before GATE attempts to assign members to it.

This assumption is operationally fragile:

- If the target resource does not exist, `Assignment` application fails with a connector error that is **semantically misleading** — it looks like an assignment failure, but the root cause is a missing resource.
- Creating target resources manually (GitHub teams, Vault policies, JFrog groups) outside of GATE duplicates effort and produces access structures invisible to GATE's audit trail.
- Naming conventions for downstream resources would be enforced only by human discipline, not by tooling.
- When a `GroupRef` is renamed or decommissioned, the corresponding downstream resources are not updated — creating drift between the GATE model and the actual state of downstream systems.

### What "resource" means in the downstream context

Downstream systems represent a GATE `GroupRef` as different constructs depending on the system:

| Target System | Resource type | Example identifier |
|---------------|---------------|--------------------|
| GitHub | Team | `org/payments-engineers` |
| HashiCorp Vault | Policy | `vault/policies/platform-read` |
| JFrog Artifactory | Group | `artifactory/groups/payments-dev` |
| Google Workspace | Group | `payments-engineers@corp.example.com` |

All of these share the same semantics: they are **containers of access**, and GATE must be able to create, update, and decommission them — not just manage their membership.

### Scope of this ADR

This ADR covers:
- The decision that GATE **pilots the full lifecycle** of downstream resources
- The extension of the **connector contract** to include resource provisioning operations
- The **naming convention strategy** and its relationship to `targetIdentifierTemplate` from ADR-0004
- The **lifecycle states** of a downstream resource as tracked by GATE
- The **takeover scenario**: a resource pre-exists in a downstream system before GATE manages it
- The **decommission policy** when a `GroupRef` is disabled or its `GroupProjectionRule` is removed

---

## Decision Drivers

- **BD-5** — Traceability and explainability of propagated assignments _(ADR-0001)_
- **FD-4** — Deprovisioning must be as controlled as provisioning _(ADR-0001)_
- **OD-1** — Observable synchronization state at all times _(ADR-0001)_
- **OD-3** — No silent mutations — all state changes must be auditable _(ADR-0001)_
- **TD-1** — Loose coupling: connectors remain the only integration point with downstream systems _(ADR-0001)_

---

## Considered Options

### Option A — Manual resource pre-provisioning (rejected implicit baseline)

Operators create downstream resources manually before configuring GATE mappings. GATE only manages memberships.

**Rejected because:**
- Naming drift: operator-defined names may not match GATE-computed `targetIdentifier` values
- No audit trail for resource creation
- Cannot detect orphaned resources when a group is decommissioned
- Assignment failures caused by missing resources are diagnostically opaque

### Option B — GATE pilots downstream resource lifecycle _(selected)_

GATE is responsible for ensuring that the downstream resource corresponding to a `(GroupRef, TargetSystem)` pair **exists before any member assignment is attempted**, and for **decommissioning it** when the group is no longer projected to that system.

Naming conventions are derived from the same `targetIdentifierTemplate` mechanism defined in ADR-0004, ensuring naming and existence are co-managed by the same model.

**Selected because:**
- Coherent ownership: the system that decides who gets access also decides that the access container exists
- Naming is normalized, auditable, and not dependent on operator discipline
- Decommissioning is explicit and tracked through GATE
- Semantically clear failure modes: provisioning failures are distinct from assignment failures

---

## Decision

**GATE pilots the full lifecycle of downstream resources.** A downstream resource is created by GATE before members are assigned to it, and decommissioned by GATE when the group ceases to be projected to that system.

The connector is the single integration point for all downstream operations, including resource lifecycle management. The domain retains all decisional logic about *whether* and *how* to provision.

---

## Extended Connector Contract

The `TargetSystemConnector` port (defined in ADR-0003) is extended with resource lifecycle operations:

```java
public interface TargetSystemConnector {

    // --- Membership operations (existing) ---
    AssignmentResult apply(Assignment assignment, PersonRef person);
    AssignmentResult revoke(Assignment assignment, PersonRef person);
    HealthStatus isHealthy();

    // --- Resource lifecycle operations (new) ---

    /**
     * Ensures the downstream resource identified by targetIdentifier exists.
     * If it already exists, the connector must verify it and return ALREADY_EXISTS.
     * This operation must be idempotent.
     */
    ResourceProvisioningResult ensureResourceExists(
        String targetIdentifier,
        GroupRef group,
        TargetSystem system
    );

    /**
     * Decommissions the downstream resource.
     * Behavior is governed by the TargetSystem's decommissionPolicy.
     * Must not fail if the resource does not exist.
     */
    ResourceProvisioningResult decommissionResource(
        String targetIdentifier,
        GroupRef group,
        TargetSystem system
    );
}
```

`ResourceProvisioningResult` carries:

| Field | Type | Description |
|-------|------|-------------|
| `status` | Enum | `CREATED` \| `ALREADY_EXISTS` \| `UPDATED` \| `DECOMMISSIONED` \| `FAILED` |
| `targetIdentifier` | String | The identifier actually used in the downstream system |
| `rawResponse` | String? | Raw connector response for audit |
| `errorMessage` | String? | Set when `status=FAILED` |

---

## Naming Convention Strategy

The `targetIdentifier` for a downstream resource is **the single source of truth for the resource's identity in GATE**. It is derived from:

1. **Explicit `GroupProjectionRule.targetIdentifier`**: operator-defined, used as-is. GATE provisions exactly this identifier.
2. **`SystemProjectionRule.targetIdentifierTemplate`** (ADR-0004): computed from `GroupRef` attributes. GATE provisions the computed value.

Naming convention templates are configured per `TargetSystem` and enforce naming discipline across the organization without requiring per-group configuration.

**Template variable reference:**

| Variable | Source | Example |
|----------|--------|---------|
| `{groupSlug}` | `GroupRef.slug` | `payments` |
| `{groupExternalRef}` | `GroupRef.externalRef` | `GRP-042` |
| `{defaultTargetRole}` | `SystemProjectionRule.defaultTargetRole` | `engineers` |
| `{targetSystemSlug}` | `TargetSystem.slug` | `github` |

**Example templates:**

| System | Template | Result |
|--------|----------|--------|
| GitHub | `org/{groupSlug}-{defaultTargetRole}` | `org/payments-engineers` |
| Vault | `vault/policies/{groupSlug}-{defaultTargetRole}` | `vault/policies/payments-read` |
| JFrog | `artifactory/groups/{groupSlug}` | `artifactory/groups/payments` |

---

## Downstream Resource Lifecycle States

GATE tracks the provisioning state of each `(GroupRef, TargetSystem)` pair:

```
PENDING_PROVISIONING
      │
      ▼
   ACTIVE ───────────────────────────────────────┐
      │                                          │
      │  (group deactivated or mapping removed)  │
      ▼                                          │
PENDING_DECOMMISSION                             │
      │                                          │
      ▼                                          │
DECOMMISSIONED                                   │
                                                 │
                    TAKEOVER ────────────────────┘
                  (resource existed before GATE)
```

These states are carried by a new entity: **`TargetResourceRecord`**.

### Entity: `TargetResourceRecord`

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Internal GATE identifier |
| `groupId` | UUID | The `GroupRef` this resource represents |
| `targetSystemId` | UUID | The downstream system |
| `targetIdentifier` | String | The resolved identifier used in the downstream system |
| `lifecycleState` | Enum | `PENDING_PROVISIONING` \| `ACTIVE` \| `PENDING_DECOMMISSION` \| `DECOMMISSIONED` \| `TAKEOVER` |
| `provisionedAt` | Instant? | When GATE first successfully created or confirmed the resource |
| `decommissionedAt` | Instant? | When GATE decommissioned the resource |
| `decommissionPolicy` | Enum | `MEMBERS_REVOKED_RESOURCE_PRESERVED` \| `RESOURCE_DESTROYED` |
| `lastSyncOperationId` | UUID? | Reference to the last `SyncOperation` that touched this resource |

---

## Provisioning Trigger

A downstream resource is provisioned when **all of the following are true**:

1. A `GroupRef` is active and reachable in GATE
2. A `GroupProjectionRule` or active `SystemProjectionRule` exists for the `(group, targetSystem)` pair
3. No `TargetResourceRecord` in `ACTIVE` state exists for this pair

**Provisioning precedes assignment**: GATE must not attempt to apply an `Assignment` for a `(group, targetSystem)` pair whose `TargetResourceRecord` is not in `ACTIVE` state. If provisioning is pending, assignments are queued and applied once the resource transitions to `ACTIVE`.

---

## Decommission Trigger and Policy

### Relationship with `deprovisionPolicy`

GATE defines two distinct policies that govern different layers of the lifecycle. They are intentionally separate because they answer different questions:

| Policy | Defined on | Governs | Values |
|--------|-----------|---------|--------|
| `deprovisionPolicy` | `TargetSystem`, `GroupRef` | **Speed and modality** of membership revocation when a person or group is deactivated in the upstream | `IMMEDIATE` \| `GRACE_PERIOD` \| `MANUAL_REVIEW` |
| `decommissionPolicy` | `TargetSystem`, `GroupProjectionRule` | **Disposition of the downstream resource** (container) once GATE stops managing it | `MEMBERS_REVOKED_RESOURCE_PRESERVED` \| `RESOURCE_DESTROYED` |

**Sequencing rule**: `decommissionPolicy` applies *after* `deprovisionPolicy` has fully converged. GATE does not evaluate the decommission policy for a resource until all pending member revocations triggered by `deprovisionPolicy` have reached a terminal state (`IMMEDIATE` is applied, grace period has elapsed, or manual review has been validated). Only then does GATE transition the `TargetResourceRecord` to `PENDING_DECOMMISSION` and apply the decommission policy.

This separation ensures:
- The speed of individual access removal remains independently configurable from the fate of the access container itself.
- A `MANUAL_REVIEW` on membership does not implicitly preserve the downstream resource forever — the resource transitions to `PENDING_DECOMMISSION` once all memberships are resolved.
- A `RESOURCE_DESTROYED` policy does not bypass membership grace periods — members are always properly offboarded before the container is destroyed.

### Decommission trigger

A downstream resource decommissioning is triggered when **any of the following occur**:

- The `GroupRef` transitions to `inactive` / `archived`
- All `GroupProjectionRule` entries for the `(group, targetSystem)` pair are removed
- The `TargetSystem` is disabled in GATE

### Decommission policy values

**Decommission policy** is configured per `TargetSystem` (or overridden per `GroupProjectionRule`):

| Policy | Behavior |
|--------|----------|
| `MEMBERS_REVOKED_RESOURCE_PRESERVED` | All member assignments are revoked. The resource itself (e.g., GitHub team) is kept. GATE releases ownership but does not delete it. |
| `RESOURCE_DESTROYED` | All member assignments are revoked, then the resource is deleted via the connector. **This is destructive and irreversible.** |

The default policy for all systems is `MEMBERS_REVOKED_RESOURCE_PRESERVED` unless explicitly configured otherwise. Operators must explicitly opt into `RESOURCE_DESTROYED`.

---

## Takeover Scenario

When GATE is configured to manage a `(GroupRef, TargetSystem)` pair for which a resource **already exists** in the downstream system, the connector's `ensureResourceExists()` returns `ALREADY_EXISTS`. GATE records the `TargetResourceRecord` with `lifecycleState=TAKEOVER` and then transitions it to `ACTIVE`.

Takeover behavior:
- GATE does **not** clean up pre-existing members in the downstream resource automatically.
- A **reconciliation cycle** (ADR-0003) will reconcile the actual downstream membership against the expected state derived from GATE's `Assignment` records.
- Memberships in the downstream system that have no corresponding `Assignment` in GATE are flagged as `DRIFT_DETECTED` in the next reconciliation report — not silently removed.

---

## New Domain Events

| Event | Trigger |
|-------|---------|
| `DownstreamResourceProvisioned` | `ensureResourceExists()` returns `CREATED` |
| `DownstreamResourceTakenOver` | `ensureResourceExists()` returns `ALREADY_EXISTS` |
| `DownstreamResourceDecommissioned` | `decommissionResource()` succeeds |
| `DownstreamResourceProvisioningFailed` | `ensureResourceExists()` or `decommissionResource()` returns `FAILED` |

---

## Impact on `SyncOperation`

`SyncOperation.triggerType` (ADR-0003) gains new values:

| Value | Meaning |
|-------|---------|
| `RESOURCE_PROVISIONING` | SyncOperation tracking a resource creation or takeover |
| `RESOURCE_DECOMMISSION` | SyncOperation tracking a resource decommission |

Resource lifecycle operations are logged as `SyncOperation` entries, ensuring a single unified audit trail for all GATE-driven changes across member management and resource management.

---

## MVP Scope

For the initial implementation:

- `ensureResourceExists()` and `decommissionResource()` are **required** in the connector contract from day one — connectors that do not implement them are incomplete.
- Default decommission policy is `MEMBERS_REVOKED_RESOURCE_PRESERVED` — `RESOURCE_DESTROYED` may be implemented post-MVP.
- Takeover scenario must be supported from day one — it is the realistic onboarding path for organizations that already have downstream resources.
- Assignment queuing pending resource provisioning (race condition between provisioning and first assignment) must be handled: `AssignmentRequested` events for a `PENDING_PROVISIONING` resource are deferred until the resource reaches `ACTIVE`.
- Drift detection on takeover is part of the reconciliation cycle already defined in ADR-0003 — no additional work required beyond emitting `TargetResourceTakenOver` events into the reconciliation pipeline.

---

## Consequences

### Positive

- GATE has coherent, end-to-end ownership of the downstream access model: it creates, populates, and decommissions access containers
- Naming conventions are enforced systematically, not by operator discipline
- Assignment failures are semantically unambiguous: they are membership failures, not resource-not-found errors
- Decommissioning is explicit, auditable, and policy-driven
- Takeover enables progressive adoption without requiring a clean-slate migration

### Negative / Trade-offs

- Connector implementation effort increases — two additional operations must be implemented and tested per connector
- Operators must grant GATE broader permissions in downstream systems (resource creation, not only member management)
- `RESOURCE_DESTROYED` policy must be used with care: irreversible operation, requires guardrails in the API

### Risks

- **Permission escalation**: GATE now needs write access at the resource level in downstream systems, not just member level. Mitigation: document minimum required permissions per connector in the connector's README; default policy is non-destructive.
- **Orphaned resources**: if GATE decommissions a group and a connector fails, downstream resource may persist with no members and no GATE tracking. Mitigation: `TargetResourceRecord` persists in `PENDING_DECOMMISSION` state and retries are scheduled.
- **Naming conflicts**: a computed `targetIdentifier` may collide with a resource created outside GATE. Mitigation: `ensureResourceExists()` must return `ALREADY_EXISTS` without error; GATE records a `TAKEOVER` and proceeds to reconcile.

---

## Related ADRs

- [ADR-0001](ADR-0001-architectural-drivers-and-system-boundaries.md) — Architectural Drivers and System Boundaries
- [ADR-0002](ADR-0002-source-of-truth-and-ownership-model.md) — Source of Truth and Ownership Model
- [ADR-0003](ADR-0003-canonical-model-and-projection-architecture.md) — Canonical Model and Projection Architecture
- [ADR-0004](ADR-0004-role-mapping-resolution-strategy.md) — Role Mapping Resolution Strategy
