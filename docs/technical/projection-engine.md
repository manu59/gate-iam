# Projection Engine

This document describes how GATE computes `Assignment` records from `Membership` records and how those assignments are synchronized to downstream target systems.

> **Source of truth**: [ADR-0003](../adr/ADR-0003-canonical-model-and-projection-architecture.md) and [ADR-0004](../adr/ADR-0004-role-mapping-resolution-strategy.md).

---

## Overview

The projection engine is the heart of GATE. Its job is to answer one question:

> *Given an active `Membership` (person X belongs to group Y with role Z), what concrete actions must be applied in each downstream target system?*

The answer is a set of `Assignment` records — one per `(targetSystem, targetIdentifier)` pair.

```
Membership (business intent)
       │
       ▼
ProjectionCalculationService
       │  resolves projection rules (two-level cascade)
       ▼
Assignment records (technical execution units)
       │
       ▼
SyncOperation → TargetSystemConnector → GitHub / Vault / JFrog / ...
```

---

## Two-Level Resolution Cascade

GATE resolves projection rules using a **two-level cascade** per `(membership, targetSystem)` pair:

```
For each TargetSystem configured in GATE:

  1. Find GroupProjectionRule where
        groupId        = membership.groupId
        businessRole   = membership.businessRole  (or null = wildcard)
        targetSystemId = current targetSystem

     → If found:
           use targetIdentifier from GroupProjectionRule
           if GroupProjectionRule.targetRole is not null:
               use targetRole from GroupProjectionRule
           else:
               find SystemProjectionRule for (targetSystemId, businessRole)
               if found: use SystemProjectionRule.defaultTargetRole
               else: record ProjectionSkipped (NO_TARGET_ROLE_FOUND); skip
           generate Assignment with resolved targetIdentifier + targetRole

  2. Else, find SystemProjectionRule where
        targetSystemId = current targetSystem
        businessRole   = membership.businessRole

     → If found AND targetIdentifierTemplate is not null:
           compute targetIdentifier from template
           generate Assignment with computed targetIdentifier + defaultTargetRole

  3. Else:
     → No Assignment generated
        Record ProjectionSkipped audit entry (NO_MAPPING_FOUND)
```

### Key rule

> **No mapping = no assignment.** The absence of any matching rule at either level is an explicit governance choice — the person is not projected to that system for that group.

### projectionSource field

Every generated `Assignment` carries a `projectionSource` identifying which rule produced it:

| Value | Meaning |
|-------|---------|
| `GROUP_PROJECTION_RULE` | Resolved from a group-specific `GroupProjectionRule` with an explicit `businessRole` |
| `WILDCARD_PROJECTION_RULE` | Resolved from a `GroupProjectionRule` with `businessRole=null` (matches any role) |
| `SYSTEM_PROJECTION_RULE` | Resolved from a system-level `SystemProjectionRule` using a template |

---

## Template Variable Resolution

`SystemProjectionRule.targetIdentifierTemplate` supports interpolation of `GroupRef` attributes:

| Variable | Source | Example |
|----------|--------|---------|
| `{groupSlug}` | `GroupRef.slug` | `payments` |
| `{groupExternalRef}` | `GroupRef.externalRef` | `GRP-042` |
| `{defaultTargetRole}` | `SystemProjectionRule.defaultTargetRole` | `engineers` |
| `{targetSystemSlug}` | `TargetSystem.slug` | `github` |

**Examples:**

| Template | Resolved with | Result |
|----------|--------------|--------|
| `acme-corp/{groupSlug}-engineers` | `groupSlug=payments` | `acme-corp/payments-engineers` |
| `vault/policies/{groupSlug}-{defaultTargetRole}` | `groupSlug=platform, defaultTargetRole=read` | `vault/policies/platform-read` |

---

## Event Flow

The projection engine is triggered by domain events — it never polls for membership state.

```
1. [Trigger]
   A membership becomes ACTIVE — either via API call or upstream import event.

2. [Domain]
   Membership entity created, validated, persisted.
   Domain event emitted: MembershipGranted

3. [Application — ProjectionCalculationService]
   Receives MembershipGranted event.
   Runs the two-level cascade for each configured TargetSystem.
   Generates one Assignment per resolved (targetSystem, targetIdentifier) pair.

4. [Domain event]
   One AssignmentRequested event emitted per Assignment.

5. [Async — SyncDispatchService]
   One SyncOperation created per target system.
   TargetSystemConnector.apply(assignment, person) called.

6. [Connector]
   GitHub Connector → adds person to team
   Vault Connector  → adds person to policy
   ...

7. [Result]
   Assignment.status updated: APPLIED or FAILED
   SyncOperation counts updated.

8. [Audit]
   AuditLogPort records the projection event with full provenance.
```

**Revocation** follows the same flow in reverse, triggered by `MembershipRevoked` or `MembershipExpired` events.

---

## Reconciliation

Event-driven projection alone does not guarantee long-term consistency. Target systems may miss events, be modified externally, or be temporarily unavailable.

GATE combines event-driven projection with **scheduled reconciliation**:

| Mechanism | Purpose | Trigger |
|-----------|---------|---------|
| Event-driven | Near real-time reaction to membership changes | `MembershipGranted`, `MembershipRevoked`, `MembershipExpired` |
| Scheduled reconciliation | Detect and correct drift regardless of event history | Periodic scheduler, configurable per `TargetSystem` |

### Reconciliation process

1. Compute the **expected state**: all active `Membership` records resolved through the projection cascade → expected `Assignment` set
2. Query the **actual state** from the target system via `TargetSystemConnector`
3. Compute the **delta**: assignments to grant, to revoke, or already in sync (`NO_OP`)
4. Apply the delta and record results as `Assignment` records with `triggerType=RECONCILIATION`

Reconciliation ensures GATE remains **eventually consistent** with downstream systems.

---

## Downstream Resource Lifecycle

Before any `Assignment` can be applied to a target system, the **resource must exist** (e.g., the GitHub team must be created). GATE manages this through `TargetResourceRecord`.

> GATE must not attempt to apply an `Assignment` for a `(group, targetSystem)` pair whose `TargetResourceRecord` is not in `ACTIVE` state. If provisioning is pending, assignments are queued.

See [domain-model.md](domain-model.md#targetresourcerecord) for the lifecycle state machine, and [ADR-0005](../adr/ADR-0005-downstream-resource-lifecycle-management.md) for the full decision.

---

## ProjectionSkipped audit entries

When the engine cannot generate an assignment, it records a `ProjectionSkipped` entry with one of the following reasons:

| Reason | Meaning |
|--------|---------|
| `NO_MAPPING_FOUND` | No `GroupProjectionRule` or `SystemProjectionRule` exists for the `(group, businessRole, targetSystem)` triple |
| `NO_TARGET_ROLE_FOUND` | A `GroupProjectionRule` was found but has `targetRole=null` and no `SystemProjectionRule` exists to provide a fallback role |
| `RESOURCE_NOT_ACTIVE` | A `TargetResourceRecord` exists but is not in `ACTIVE` state — assignment is queued, not skipped permanently |
| `MEMBERSHIP_SUSPENDED` | The membership is in `SUSPENDED` state — not projected until restored |

These entries are visible in the operational dashboard and allow operators to diagnose misconfigured projection rules.
