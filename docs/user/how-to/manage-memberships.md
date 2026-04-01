# How to Manage Memberships

This guide covers how memberships are created, modified, suspended, and revoked in GATE IAM.

> **Prerequisites**: You understand [what a Membership is](../concepts.md#membership) and have at least one [target system configured](./configure-target-system.md) with [projection rules defined](./define-projection-rules.md).

---

## Overview

Memberships are the **input signal** for GATE. Most memberships come from upstream systems (HR, IdP) automatically. This guide covers cases where you need to:

- Grant access manually (exception-based access)
- Suspend a membership temporarily
- Revoke access immediately or with a grace period
- Track what was granted and why

---

## Part 1 — How memberships normally arrive

In production, GATE ingests memberships from your upstream systems automatically:

- **Push**: upstream systems post events to the GATE API when memberships change
- **Pull**: GATE polls upstream systems on a configured schedule

You do not normally create memberships by hand for regular organisation members. GATE reflects the state of your upstream source of truth.

---

## Part 2 — Manual memberships (exception access)

Sometimes a person needs access that is not reflected in the upstream system:
- Temporary elevated access for an incident response
- Access for an external contractor not in your HR system
- Access pending an upstream system update

### Grant manual access (API)

```http
POST /api/v1/memberships
Content-Type: application/json

{
  "personId": "alice@acme.com",
  "groupId": "group-platform",
  "businessRole": "MAINTAINER",
  "ownershipMode": "MANAGED",
  "deprovisionPolicy": "GRACE_PERIOD_24H",
  "validUntil": "2026-03-01T00:00:00Z",
  "justification": "Incident response: P1 outage on platform infra"
}
```

| Field | Description |
|-------|-------------|
| `personId` | The person's identifier (must match upstream `PersonRef`) |
| `groupId` | The group to add them to |
| `businessRole` | The role to grant (`MEMBER`, `MAINTAINER`, etc.) |
| `ownershipMode` | `MANAGED` = owned by GATE; `DELEGATED` = owned upstream |
| `deprovisionPolicy` | How quickly to revoke when the membership ends |
| `validUntil` | Optional expiry date — membership is auto-archived when reached |
| `justification` | Free-text reason for audit trail |

GATE will project this membership to all matching target systems on the next sync cycle.

---

## Part 3 — Suspending a membership

A **suspended** membership keeps the record in GATE but stops access provisioning. Existing Assignments are paused — GATE will not add or remove access until the suspension is lifted.

Use suspension for:
- Temporary leave (parental leave, extended absence)
- Security investigation (pending review, without revoking permanently)

### Suspend a membership (API)

```http
PATCH /api/v1/memberships/{membershipId}
Content-Type: application/json

{
  "status": "SUSPENDED",
  "suspensionReason": "Parental leave — return expected 2026-06-01"
}
```

### Lift a suspension

```http
PATCH /api/v1/memberships/{membershipId}
Content-Type: application/json

{
  "status": "ACTIVE"
}
```

When the suspension is lifted, GATE re-projects the membership on the next sync cycle and restores all access.

---

## Part 4 — Revoking access

Revocation happens when a membership is **archived**. GATE then removes the corresponding Assignments in downstream systems.

### Archive a membership immediately (API)

```http
PATCH /api/v1/memberships/{membershipId}
Content-Type: application/json

{
  "status": "ARCHIVED"
}
```

The `deprovisionPolicy` on the Membership controls the timing:

| Policy | What happens |
|--------|-------------|
| `IMMEDIATE` | Access removed on the next sync cycle |
| `GRACE_PERIOD_24H` | Access removed after 24 hours |
| `GRACE_PERIOD_7D` | Access removed after 7 days |
| `MANUAL` | Access removed only after an operator confirms via the API |

### Override the deprovision policy for a specific revocation

If you need to revoke immediately regardless of the Membership's default policy:

```http
PATCH /api/v1/memberships/{membershipId}
Content-Type: application/json

{
  "status": "ARCHIVED",
  "deprovisionPolicy": "IMMEDIATE"
}
```

---

## Part 5 — Confirming manual revocation

If `deprovisionPolicy=MANUAL`, GATE waits for an explicit confirmation before revoking access:

```http
POST /api/v1/memberships/{membershipId}/confirm-deprovision
Content-Type: application/json

{
  "confirmedBy": "security-admin@acme.com",
  "note": "Verified offboarding complete"
}
```

---

## Part 6 — Viewing current access

### List a person's memberships

```http
GET /api/v1/memberships?personId=alice@acme.com
```

### List a group's active members

```http
GET /api/v1/memberships?groupId=group-platform&status=ACTIVE
```

### View what access a membership produced

```http
GET /api/v1/assignments?membershipId={membershipId}
```

This shows the full list of Assignments created from this membership — one per target system where a projection rule matched.

---

## Audit trail

Every Membership and Assignment change is recorded with:
- Who changed it (operator identifier)
- When
- The reason / justification if provided
- The `projectionSource` that triggered the Assignment (GROUP_PROJECTION_RULE, SYSTEM_PROJECTION_RULE, or WILDCARD_PROJECTION_RULE)

Use the audit log for compliance evidence and incident investigation:

```http
GET /api/v1/audit?entityType=MEMBERSHIP&entityId={membershipId}
GET /api/v1/audit?entityType=ASSIGNMENT&personId=alice@acme.com
```
