# How to Define Projection Rules

This guide explains how to configure the rules that map group memberships to downstream access.

> **Prerequisites**: At least one [target system is configured](./configure-target-system.md). You understand the difference between [SystemProjectionRule and GroupProjectionRule](../concepts.md#projection-rules).

---

## Overview

Projection rules work at two levels:

1. **SystemProjectionRule** — the default for all groups in a target system
2. **GroupProjectionRule** — a per-group override for specific resources or roles

A typical setup:
1. Create one `SystemProjectionRule` per business role per target system (the baseline)
2. Add `GroupProjectionRules` only for groups that need a non-standard resource name or a different role

---

## Part 1 — System-level defaults (SystemProjectionRule)

### When to use

Use a `SystemProjectionRule` when:
- You want all groups in a system to receive the same role mapping for a given business role
- You want to define a naming template for auto-generated resources (e.g., `team-{groupSlug}`)

### Create a SystemProjectionRule (API)

```http
POST /api/v1/system-projection-rules
Content-Type: application/json

{
  "targetSystemId": "github-acme",
  "businessRole": "MAINTAINER",
  "defaultTargetRole": "WRITE",
  "targetIdentifierTemplate": "team-{groupSlug}"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `targetSystemId` | ✓ | The target system this rule applies to |
| `businessRole` | ✓ | The incoming business role to match |
| `defaultTargetRole` | ✓ | The role to grant in the target system |
| `targetIdentifierTemplate` | — | Template for auto-deriving the resource name. Variables: `{groupId}`, `{groupSlug}`, `{groupName}` |

### Template variables

| Variable | Resolves to |
|----------|-------------|
| `{groupId}` | The group's internal UUID |
| `{groupSlug}` | The group's URL-safe slug (e.g., `platform-team`) |
| `{groupName}` | The group's display name |

**Example**: template `team-{groupSlug}` for the group `platform-team` → resource name `team-platform-team`.

---

## Part 2 — Group-level overrides (GroupProjectionRule)

### When to use

Use a `GroupProjectionRule` when:
- A specific group needs access to a resource with a **non-standard name**
- A specific group needs a **different role** than the system default
- A specific group should be **excluded** from a target system (wildcard rule with no target identifier)

### Create a GroupProjectionRule (API)

```http
POST /api/v1/group-projection-rules
Content-Type: application/json

{
  "groupId": "group-platform",
  "businessRole": "MAINTAINER",
  "targetSystemId": "github-acme",
  "targetIdentifier": "platform-infra",
  "targetRole": "ADMIN"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `groupId` | ✓ | The group this rule applies to |
| `businessRole` | ✓ | The incoming business role to match |
| `targetSystemId` | ✓ | The target system |
| `targetIdentifier` | ✓ | The exact resource name in the target system |
| `targetRole` | — | Override the role. If omitted, falls back to `SystemProjectionRule.defaultTargetRole` |

### Omitting `targetRole`

If you leave `targetRole` empty, GATE will look up the matching `SystemProjectionRule` for that system and business role, and use its `defaultTargetRole`. This is useful when you only need to override **where** the group is projected, not **what role** they get.

---

## Rule resolution order

For each `(group, businessRole, targetSystem)` combination, GATE resolves access as follows:

1. Look for a `GroupProjectionRule` matching `(groupId, businessRole, targetSystemId)` → use its `targetIdentifier`, and `targetRole` if present
2. If step 1 found no `targetRole`, read `defaultTargetRole` from the matching `SystemProjectionRule`
3. If no `GroupProjectionRule` was found, fall back to the `SystemProjectionRule` and derive `targetIdentifier` from `targetIdentifierTemplate`
4. If no `SystemProjectionRule` is found → projection is skipped (`NO_MAPPING_FOUND`)

---

## Common patterns

### All groups: same role, auto-named resource

Set a `SystemProjectionRule` with a template. No `GroupProjectionRule` needed.

```
SystemProjectionRule: github-acme, MEMBER → "READ", template: "team-{groupSlug}"
→ group "frontend-team" gets READ access on resource "team-frontend-team"
→ group "backend-team" gets READ access on resource "team-backend-team"
```

### One group: different resource name

```
SystemProjectionRule: github-acme, MEMBER → "READ"
GroupProjectionRule: group=infra-ops, MEMBER, github-acme → targetIdentifier="ops-core"
→ group "infra-ops" gets READ access on resource "ops-core" (not "team-infra-ops")
```

### One group: elevated role

```
SystemProjectionRule: github-acme, MAINTAINER → "WRITE"
GroupProjectionRule: group=security-team, MAINTAINER, github-acme → targetIdentifier="security-scanner", targetRole="ADMIN"
→ group "security-team" gets ADMIN access (instead of WRITE) on "security-scanner"
```

### One group: different resource AND elevated role

Combine both in a single `GroupProjectionRule`:
```json
{
  "groupId": "group-security",
  "businessRole": "MAINTAINER",
  "targetSystemId": "github-acme",
  "targetIdentifier": "security-scanner",
  "targetRole": "ADMIN"
}
```

---

## Updating a rule

```http
PATCH /api/v1/system-projection-rules/{id}
PATCH /api/v1/group-projection-rules/{id}
```

Rule changes take effect on the **next sync cycle**. All affected Assignments are re-evaluated.

---

## Deleting a rule

Deleting a rule does not immediately revoke access. Existing Assignments are archived on the next sync cycle, and access is revoked according to the Membership's `deprovisionPolicy`.

```http
DELETE /api/v1/system-projection-rules/{id}
DELETE /api/v1/group-projection-rules/{id}
```
