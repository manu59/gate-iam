# Concepts

This page explains the key concepts in GATE IAM. No programming knowledge is required.

---

## Membership

A **Membership** represents the fact that a person belongs to a group in your organisation, carrying a **business role**.

| Field | What it means |
|-------|--------------|
| Who | `PersonRef` — the person's identifier in your upstream system (HR, IdP) |
| Which group | `GroupRef` — the team or organisational unit |
| What role | `businessRole` — a role name from your organisation's vocabulary (e.g., `MEMBER`, `MAINTAINER`, `OWNER`) |

A Membership is **the single source of intent**. GATE does not decide who belongs to a group — that comes from your upstream system. GATE decides what that membership *means* in each downstream system.

A Membership has a **status**:

- `ACTIVE` — normal in-scope membership
- `SUSPENDED` — temporarily inactive; keeps the record but stops access provisioning
- `ARCHIVED` — membership has ended; triggers access revocation

And a **deprovisionPolicy** that controls how quickly an archived membership removes access:

- `IMMEDIATE` — revoke on the same sync cycle
- `GRACE_PERIOD_24H` / `GRACE_PERIOD_7D` — keep access briefly (useful for handover)
- `MANUAL` — human approval required before revocation

---

## BusinessRole

A **businessRole** is a label from your organisation's vocabulary. Examples:

- `MEMBER`
- `MAINTAINER`
- `OWNER`
- `READER`

BusinessRoles have **no built-in meaning** in GATE. Their meaning is defined by your projection rules (see below). The same `MAINTAINER` role might map to `write` permissions on GitHub and `POLICY_ADMIN` in Vault.

---

## Target System

A **TargetSystem** is a downstream system where access is provisioned — a GitHub organisation, a Vault cluster, an Artifactory instance, a Google Workspace.

Each TargetSystem has:

- A **connectorRef** identifying which connector plugin handles it
- Connection configuration (API tokens, URLs)
- A **governanceMode** — whether GATE only governs named groups (`SCOPED`) or all resources (`FULL`)
- A **decommissionPolicy** — what happens to a target resource when all governing memberships end:
  - `ARCHIVE` — keep the resource but mark it inactive
  - `DELETE` — permanently delete it
  - `TRANSFER_TO_ADMIN` — reassign ownership to a designated admin
  - `RETAIN` — do nothing; leave the resource as-is

---

## Projection Rules

Projection rules answer the question: *"For a given group and business role arriving at a given target system — what access should be created?"*

There are two levels:

### SystemProjectionRule (system-level default)

A **SystemProjectionRule** is a target system's baseline mapping:
*"For this target system, when a member has businessRole X, give them targetRole Y."*

| Field | Meaning |
|-------|---------|
| `targetSystemId` | Which target system this applies to |
| `businessRole` | The incoming business role (e.g., `MAINTAINER`) |
| `defaultTargetRole` | The target-system role to assign (e.g., `WRITE`) |
| `targetIdentifierTemplate` | Optional pattern for resource names (e.g., `team-{groupSlug}`) |

Most groups in a system will follow the same mapping. The SystemProjectionRule covers all of them without needing individual configuration.

### GroupProjectionRule (group-level override)

A **GroupProjectionRule** is a per-group exception:
*"For this specific group, in this specific target system, when a member has businessRole X — use this particular resource (and optionally this particular role)."*

| Field | Meaning |
|-------|---------|
| `groupId` | Which group this applies to |
| `businessRole` | Which business role this matches |
| `targetSystemId` | Which target system |
| `targetIdentifier` | Explicit resource name in the target system (e.g., `platform-team` in GitHub) |
| `targetRole` | (Optional) Override the role. If absent, falls back to `SystemProjectionRule.defaultTargetRole` |

**When to use each:**

| Situation | Use |
|-----------|-----|
| All groups get the same role for a business role | `SystemProjectionRule` |
| One group needs a specific resource name or a different role | `GroupProjectionRule` |
| A group must be excluded from a system | `GroupProjectionRule` with no `targetIdentifier` (wildcard) |

---

## Assignment

An **Assignment** is the **executed outcome** of applying a projection rule to a membership. It is the record of what GATE actually did in a downstream system.

An Assignment says: *"Person P has role R in resource X of target system Y, because of membership M processed via rule Z."*

Assignments are:
- Created when a membership is first projected
- Updated when rules or memberships change
- Archived when the membership ends and access is revoked

Assignments are **audit records** — they show the complete chain from business intent (Membership) to technical execution (Assignment).

---

## Resource Lifecycle

Every downstream resource provisioned by GATE goes through a lifecycle:

```
PENDING_CREATION → ACTIVE → PENDING_DECOMMISSION → DECOMMISSIONED
                                                  ↘ RETAINED (if decommissionPolicy=RETAIN)
```

A resource enters `PENDING_DECOMMISSION` when the last Membership governing it is archived. What happens next depends on the TargetSystem's `decommissionPolicy`.

---

## Put it all together

```
Your HR/IdP system
        │
        │ Membership: Alice is MAINTAINER of team-platform
        ↓
    GATE IAM
        │
        │ Finds GroupProjectionRule: team-platform → MAINTAINER → github.com, repo=platform-infra
        │ (falls back to SystemProjectionRule for targetRole: WRITE)
        ↓
    Assignment: Alice gets WRITE on platform-infra in GitHub
        │
        ↓
    GitHub API call: add Alice as WRITE collaborator on platform-infra
```

When Alice leaves `team-platform`, GATE detects the membership change, archives the Assignment, and revokes her access from GitHub — all automatically.
