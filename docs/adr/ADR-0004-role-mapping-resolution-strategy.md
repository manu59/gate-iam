# ADR-0004: Role Mapping Resolution Strategy

## Status

`Accepted`

## Date

2026-03-25

---

## Context

Following [ADR-0003](ADR-0003-canonical-model-and-projection-architecture.md), which established the `GroupProjectionRule` entity as the translation table between a `GroupRef` + `businessRole` and a concrete identifier in a downstream system, this ADR addresses a gap in that model.

`GroupProjectionRule` as defined in ADR-0003 is **group-scoped**: each entry specifies how a particular `(groupId, businessRole)` pair maps to a target system identifier. This works well for fine-grained overrides but does not scale as a default mechanism.

### The problem

Consider the following scenario:

> A PMO (Project Management Officer) is added to the `Payments` team with `businessRole=PMO`. An `AssignmentRequested` event is raised. For the GitHub connector, the question becomes: should the PMO role be projected at all, and if so, into which GitHub team role?

Two questions arise:

1. **Projection eligibility**: should every `businessRole` always be projected to every downstream system, or should the absence of a mapping mean the person is not projected?
2. **Role translation**: downstream systems have their own role semantics (GitHub has `member`/`maintainer`, Vault has policies, JFrog has access levels). Who is responsible for translating the `businessRole` into the downstream role — the connector or the domain?

### The connector must not hold projection logic

The connector is a pure technical translator: it receives a fully resolved `Assignment` (target identifier, action, person reference) and applies it. It must not decide *who* gets projected or *with which role*. Placing that logic in the connector would:
- violate TD-2 (domain independence from vendor concepts)
- make projection decisions invisible to governance and audit
- prevent operators from understanding or overriding projection rules without touching connector code

→ **All mapping resolution must happen in the domain, before the `Assignment` is generated.**

### The scaling problem with group-scoped mappings only

With `GroupProjectionRule` as the only mapping layer:
- Each combination of `(group, businessRole, targetSystem)` requires an explicit entry
- An organization with 50 groups, 10 roles, and 5 target systems would require up to 2500 entries to configure
- Most of these entries would be identical or follow a simple, repetitive pattern

A default mapping layer is needed to cover the common case without requiring exhaustive group-by-group configuration.

---

## Decision Drivers

- **TD-1** — Loose coupling with upstream and downstream systems _(ADR-0001)_
- **TD-2** — Domain independence from vendor-specific concepts _(ADR-0001)_
- **FD-2** — One business affiliation must project into multiple technical assignments _(ADR-0001)_
- **BD-5** — Traceability and explainability of propagated assignments _(ADR-0001)_

---

## Considered Options

### Option A — Connector-internal default mapping

Each connector holds its own internal table of `businessRole → technical role` defaults.

**Rejected because:**
- Projection logic is hidden in infrastructure, invisible to governance and audit
- Operators cannot override or inspect role translation without modifying connector code
- Violates the principle that connectors are pure technical translators

### Option B — Group-scoped `GroupProjectionRule` only (current ADR-0003 model)

Mapping is always explicit per `(group, businessRole, targetSystem)`. No defaults.

**Rejected as sole mechanism because:**
- Does not scale for organizations with many groups and roles
- Results in massive configuration overhead for commonly repeated patterns
- Forces operators to define trivially identical mappings on every group

### Option C — Two-level hierarchical resolution _(selected)_

Introduce a `SystemProjectionRule` entity at the `TargetSystem` level, defining the default projection behavior `(targetSystem, businessRole) → defaultTargetRole + targetIdentifierTemplate`.

`ProjectionCalculationService` resolves mappings using a **two-level cascade**:
1. Look for a group-specific `GroupProjectionRule` — if found, use it
2. Fall back to `SystemProjectionRule` for the target system — if found, use it with a computed target identifier
3. No mapping found at either level → **no `Assignment` generated** (explicit non-projection)

**Selected because:**
- Scales naturally: most groups use the default, specific groups override when needed
- All projection logic remains in the domain and is auditable
- The connector stays a pure technical translator
- Explicit non-projection (no mapping = no assignment) prevents silent access grants

---

## Decision

We adopt **Option C**: a two-level hierarchical mapping resolution.

The rule is:

> **No mapping = no assignment.** Absence of any matching rule at either level means the person with that `businessRole` is not projected to that target system for that group. This is a deliberate, explicit governance choice.

---

## New Entity: `SystemProjectionRule`

Defines the default projection behavior for a given `(targetSystem, businessRole)` pair: both the **role translation** (`businessRole → defaultTargetRole`) and the **target resource resolution** (`targetIdentifierTemplate`). Applicable across all groups unless overridden by a group-specific `GroupProjectionRule`.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Internal GATE identifier |
| `targetSystemId` | UUID | The target system this default applies to |
| `businessRole` | String | The business role value from the upstream or local membership |
| `defaultTargetRole` | String | The role or access level to apply in the target system (e.g., `member`, `read-only`, `maintainer`) |
| `targetIdentifierTemplate` | String? | Optional template for computing the target identifier when no group-specific mapping exists (e.g., `org/{groupSlug}-{defaultTargetRole}`) |
| `active` | Boolean | Whether this default projection rule is currently active |

**Notes:**
- `defaultTargetRole` is a string in the target system's own vocabulary — not a GATE concept
- `targetIdentifierTemplate` allows group-level target identifiers to be computed dynamically without requiring a `GroupProjectionRule` entry per group
- If `targetIdentifierTemplate` is null and no group-specific `GroupProjectionRule` exists, no `Assignment` is generated even if a `SystemProjectionRule` matches

---

## Resolution Algorithm

> **Assumption**: this algorithm resolves *who* is assigned *with which role* to a downstream resource. It does not create that resource. The downstream resource identified by `targetIdentifier` must exist before any `Assignment` can be applied. The lifecycle of downstream resources (creation, naming, decommission) is governed by [ADR-0005](ADR-0005-downstream-resource-lifecycle-management.md). GATE must not attempt to apply an `Assignment` for a `(group, targetSystem)` pair whose `TargetResourceRecord` (ADR-0005) is not in `ACTIVE` state.

Executed by `ProjectionCalculationService` when computing `Assignment` records from an active `Membership`:

```
For each TargetSystem configured in GATE:

  1. Find GroupProjectionRule where
        groupId      = membership.groupId
        businessRole = membership.businessRole (or null/wildcard)
        targetSystemId = current targetSystem

     → If found:
           use targetIdentifier from GroupProjectionRule
           if GroupProjectionRule.targetRole is not null:
               use targetRole from GroupProjectionRule
           else:
               find SystemProjectionRule for (targetSystemId, businessRole)
               if found: use SystemProjectionRule.defaultTargetRole
               else: record ProjectionSkipped with reason NO_TARGET_ROLE_FOUND; skip
           generate Assignment with resolved targetIdentifier + targetRole

  2. Else, find SystemProjectionRule where
        targetSystemId = current targetSystem
        businessRole   = membership.businessRole

     → If found AND targetIdentifierTemplate is not null:
           compute targetIdentifier from template
           generate Assignment with computed targetIdentifier

  3. Else:
     → No Assignment generated for this (membership, targetSystem) pair
        Record a ProjectionSkipped audit entry with reason: NO_MAPPING_FOUND
```

**Template variable resolution example:**

| Template | Variables | Result |
|----------|-----------|--------|
| `org/{groupSlug}-members` | `groupSlug=payments` | `org/payments-members` |
| `vault/policies/{groupSlug}-{defaultTargetRole}` | `groupSlug=platform, defaultTargetRole=read` | `vault/policies/platform-read` |

The variables available in templates are drawn from the `GroupRef` attributes (e.g., `slug`, `externalRef`, `name`) and the `SystemProjectionRule` itself (`defaultTargetRole`).

---

## MVP Scope

For the initial implementation, the following simplifications apply:

- `GroupProjectionRule` with `businessRole=null` acts as a **wildcard**: it matches any business role for the given `(group, targetSystem)` pair. All members of the group are projected to the same target identifier, regardless of role. This covers the simplest case with minimal configuration.
- `SystemProjectionRule` with template support is part of the design but **may be deferred to a post-MVP iteration**. The resolution algorithm should be written to accommodate it from day one.
- The fallback behavior at step 3 (no mapping = no assignment + audit entry) must be implemented from the start — it is a governance guarantee, not an optimization.

---

## Impact on `Assignment`

`Assignment` records produced by the resolution algorithm must carry the information needed to explain their origin:

| Field | Value |
|-------|-------|
| `projectionSource` | `GROUP_PROJECTION_RULE` \| `SYSTEM_PROJECTION_RULE` \| `WILDCARD_PROJECTION_RULE` |
| `projectionRuleRef` | ID of the `GroupProjectionRule` or `SystemProjectionRule` that produced this assignment |

This allows operators to answer: *"Why does this person have access to this group in GitHub?"* — and trace the answer to a specific, auditable mapping rule.

---

## Consequences

### Positive

- Scales to large organizations without requiring exhaustive per-group configuration
- All projection decisions are visible, auditable, and overridable in the GATE domain
- Connectors remain pure technical translators with zero projection logic
- Explicit non-projection (no mapping = no assignment) prevents silent access grants
- The wildcard `businessRole=null` on `GroupProjectionRule` provides a practical MVP default

### Negative / Trade-offs

- Introduces a new entity (`SystemProjectionRule`) and a resolution algorithm that must be maintained
- Two-level lookup adds a small amount of complexity to `ProjectionCalculationService`
- Operators must understand the precedence rules (group mapping overrides default projection rule)

### Risks

- **Misconfigured templates** producing invalid `targetIdentifier` values. Mitigation: validate template output before generating `Assignment`; surface errors as `ProjectionSkipped` audit entries.
- **Role vocabulary mismatch**: `businessRole` values from the upstream may not match the strings configured in `SystemProjectionRule`. Mitigation: surfaced as `ProjectionSkipped` with reason `NO_MAPPING_FOUND`; visible on the operational dashboard.

---

## Related ADRs

- [ADR-0001](ADR-0001-architectural-drivers-and-system-boundaries.md) — Architectural Drivers and System Boundaries
- [ADR-0002](ADR-0002-source-of-truth-and-ownership-model.md) — Source of Truth and Ownership Model
- [ADR-0003](ADR-0003-canonical-model-and-projection-architecture.md) — Canonical Model and Projection Architecture
- [ADR-0005](ADR-0005-downstream-resource-lifecycle-management.md) — Downstream Resource Lifecycle Management
