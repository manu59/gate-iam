# How to Configure a Target System

This guide explains how to register a downstream system with GATE so it can receive access assignments.

> **Prerequisites**: You have admin access to the GATE IAM API and credentials for the target system's API.

---

## Overview

A **TargetSystem** registration tells GATE:
1. Which connector plugin to use (GitHub, Vault, JFrog, etc.)
2. How to authenticate against the target system's API
3. How the system is governed (scope and decommission policy)

---

## Step 1 — Gather your credentials

Each connector requires different connection information:

| System | Required credentials |
|--------|---------------------|
| GitHub | GitHub App ID + private key, organisation name |
| HashiCorp Vault | Vault address, AppRole role-id + secret-id (or token) |
| JFrog Artifactory | Base URL, API key or access token |
| Google Workspace | Service account JSON, domain, delegated admin email |

Keep these ready — they will be stored encrypted in GATE's configuration store.

---

## Step 2 — Choose governance settings

Before creating the target system, decide:

### `governanceMode`

| Value | Meaning |
|-------|---------|
| `SCOPED` | GATE only governs resources explicitly named in a `GroupProjectionRule`. Other resources in the system are untouched. |
| `FULL` | GATE governs all resources in the system. Resources not matching any projection rule are candidates for decommission. |

Start with `SCOPED` unless you are migrating a fully managed system to GATE.

### `deprovisionPolicy` (default for memberships)

Sets the default delay before access is revoked when a membership ends. Can be overridden per `Membership`.

| Value | Behaviour |
|-------|-----------|
| `IMMEDIATE` | Revoke on the next sync cycle |
| `GRACE_PERIOD_24H` | Keep access for 24 hours |
| `GRACE_PERIOD_7D` | Keep access for 7 days |
| `MANUAL` | Wait for an operator to confirm revocation |

### `decommissionPolicy` (for resources)

Sets what happens to a downstream resource when the last governing membership ends.

| Value | Behaviour |
|-------|-----------|
| `ARCHIVE` | Mark the resource inactive in the target system |
| `DELETE` | Permanently delete the resource |
| `TRANSFER_TO_ADMIN` | Reassign ownership to a configured admin account |
| `RETAIN` | Leave the resource untouched |

> **Recommendation**: Use `RETAIN` while establishing GATE governance over an existing system. Switch to `ARCHIVE` or `DELETE` once you are confident in your projection rules.

---

## Step 3 — Register the target system (API)

```http
POST /api/v1/target-systems
Content-Type: application/json

{
  "id": "github-acme",
  "displayName": "GitHub (acme-corp)",
  "connectorRef": "github-connector-v1",
  "governanceMode": "SCOPED",
  "deprovisionPolicy": "GRACE_PERIOD_24H",
  "decommissionPolicy": "RETAIN",
  "connectionConfig": {
    "org": "acme-corp",
    "appId": "12345",
    "privateKeyRef": "secret/github/app-private-key"
  }
}
```

> `connectionConfig` is encrypted at rest. The `privateKeyRef` value is a reference to a secret manager path — the key never leaves your secrets store.

---

## Step 4 — Verify connectivity

After registering, verify GATE can reach the system:

```http
GET /api/v1/target-systems/github-acme/health
```

Expected response:
```json
{
  "status": "HEALTHY",
  "checkedAt": "2026-01-15T10:00:00Z"
}
```

If the status is `UNHEALTHY`, check your connection config and confirm the target system is reachable from GATE's network.

---

## Step 5 — Define projection rules

A connected target system with no projection rules will not receive any assignments. Continue to [Define projection rules](./define-projection-rules.md).

---

## Updating a target system

To update governance settings (e.g., change `decommissionPolicy`):

```http
PATCH /api/v1/target-systems/github-acme
Content-Type: application/json

{
  "decommissionPolicy": "ARCHIVE"
}
```

Changes to `governanceMode` or `decommissionPolicy` take effect on the **next sync cycle**. In-progress operations are not interrupted.

---

## Removing a target system

> **Warning**: Removing a target system will stop all projection activity for that system. Existing Assignments will be archived. Downstream access is **not automatically revoked** — revoke manually first or ensure `deprovisionPolicy=IMMEDIATE` before deletion.

```http
DELETE /api/v1/target-systems/github-acme
```
