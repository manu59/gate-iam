# ADR-0002: Source of Truth and Ownership Model

## Status

`Accepted`

## Date

2026-03-24

---

## Context

Following [ADR-0001](ADR-0001-architectural-drivers-and-system-boundaries.md), which establishes that GATE must not become an accidental master of business entities, this ADR defines the precise rules governing **who owns what** in the GATE system.

### The problem GATE addresses

Modern engineering organizations rely on a variety of tools to manage access at the technical level: GitHub teams, HashiCorp Vault policies, JFrog permission groups, Google Workspace groups, etc. These tools each enforce access in their own model, and they accumulate over time as organizations grow.

At the same time, the actual definition of *who belongs to what* typically lives somewhere else — in HR systems, organizational directories, product management platforms, or identity providers. None of these upstream systems were designed to drive multi-tool access synchronization with governance, temporality, and auditability.

The result is a structural gap: **organizational memberships exist in one place, access rights need to be enforced in many others, and no system manages the translation between the two with proper governance**.

### The typical architecture GATE integrates with

In the kinds of organizations GATE is designed to serve, a common pattern emerges:

- An **Identity Provider** (LDAP, Active Directory, Okta, Ping…) holds user identity records and technical account references
- An **organizational or product referential** (product catalog, HR system, org chart tool…) defines business groupings and their core members
- Multiple **access-bearing target systems** (GitHub, Vault, JFrog, Google…) hold the actual permissions, but have no knowledge of the organizational logic that should drive them

GATE inserts itself as the governance intermediary in this chain: it consumes organizational facts from upstream systems, applies governance logic (temporality, transverse roles, exceptions, projection rules), and propagates the resulting assignments to downstream systems.

**GATE is neither the first nor the last system in the chain.** It does not replace the identity provider, and it does not directly enforce permissions. Its role is the translation and governance layer between organizational truth and technical access.

### The ownership question

Because GATE operates between upstream referentials and downstream systems, a precise answer to the following question is essential:

> **For each entity type, who is the source of truth — and what can GATE do with it?**

A second critical question arises from the governance model:

> **When an upstream source cannot express a required access pattern, who owns the extension?**

---

## Decision Drivers

- **BD-1** — Business affiliations are mastered by upstream domain systems _(ADR-0001)_
- **BD-4** — Upstream systems cannot express all required access patterns _(ADR-0001)_
- **OD-1** — Source-of-truth ownership must remain explicit _(ADR-0001)_
- **OD-2** — GATE must not become an accidental master of business entities _(ADR-0001)_
- **OD-3** — Local governance extensions must be controlled and justified _(ADR-0001)_

---

## Considered Options

### Option A — GATE as full referential

GATE manages all entities (Person, Team, Membership) as its own master data. External systems synchronize into GATE and GATE is the single source of truth.

**Rejected because:**
- conflicts with existing legitimate upstream referentials
- duplicates business data without added value
- makes GATE a pseudo-HR / pseudo-product catalog without the domain knowledge to be one
- creates ownership ambiguity and data drift

### Option B — GATE as pure passthrough (no local ownership)

GATE only reflects upstream data with no local state. It cannot create any data.

**Rejected because:**
- upstream systems systematically lack support for: temporary access, transverse roles, technical groups, governance exceptions
- GATE would have no value for the access patterns that motivated its creation

### Option C — GATE as governance overlay with explicit ownership tiers _(selected)_

GATE maintains a tiered ownership model:
- **Imported entities**: consumed from upstream, locally projected with minimal attributes, read-only for core business fields
- **Enriched entities**: imported from upstream, GATE adds governance metadata (mappings, policies, extensions)
- **Local entities**: created in GATE when upstream cannot express the access pattern, fully owned by GATE, subject to strict governance rules

---

## Decision

We adopt **Option C**: a tiered ownership model where every GATE entity carries an explicit `origin` and `ownershipMode`.

GATE applies the following principle consistently:

> **GATE is master of what it creates. GATE is slave of what it receives.**

---

## Ownership Tiers

### Tier 1 — `EXTERNAL` / `READ_ONLY`

The entity exists in an upstream system. GATE holds a **minimal local projection** (shadow copy) for reference and correlation purposes. Core business attributes are immutable in GATE.

**Applies to:**
- `PersonRef` imported from an IdP, LDAP, or HR system
- `GroupRef` imported from an organizational referential or product catalog
- `MembershipRef` imported from a source business system (e.g., product-to-member association)

**What GATE can do:**
- Read and display
- Reference in Memberships and Assignments
- Correlate to audit entries
- Use as anchor for projection rules

**What GATE cannot do:**
- Modify the name, email, organizational unit, manager, or any core identity/org attribute
- Delete or deactivate (lifecycle is driven by the source system)

---

### Tier 2 — `EXTERNAL` / `ENRICHED`

The entity exists in an upstream system and is locally projected. GATE **adds governance metadata** on top of the imported base, but does not redefine the entity's identity or organizational attributes.

**Applies to:**
- `GroupRef` for which GATE manages projection rules, target mappings, sync policies, and governance exceptions

**What GATE can do:**
- Add and modify: `groupProjectionRules`, `systemProjectionRules`, `syncPolicy`, `governanceMode`, `extensionType`
- Define which downstream systems this group projects to and how
- Override or restrict automatic synchronization behavior for this group

**What GATE cannot do:**
- Modify: name, description, owner, parent team, or organizational position from the source system

---

### Tier 3 — `LOCAL` / `LOCAL_AUTHORITATIVE`

The entity was created within GATE because the upstream system does not cover the access pattern. GATE is the **sole source of truth** for this entity.

**Applies to:**
- Transverse teams with no equivalent in any business referential
- Technical or governance-only groups (on-call rotations, audit access pools, incident bridges)
- Local memberships: temporary access grants, transverse role assignments, governance exceptions

**Rules that apply:**
- Creation requires an explicit `teamType` or `membershipKind` that is allowed for local creation (see enumeration below)
- A `justification` field is mandatory
- `createdBy` and `createdAt` are always recorded
- Time-bound entities (`validUntil`) are strongly encouraged; some `membershipKind` values make expiration **mandatory**
- Approval may be required depending on the configured `creationPolicy`

---

## Entity Ownership Matrix

| Entity | Upstream mastered? | GATE creates it? | Core attributes owner | Governance attributes owner |
|--------|--------------------|------------------|-----------------------|-----------------------------|
| `PersonRef` | Yes | No (import only) | Upstream IdP / HR | — |
| `GroupRef` | Yes or No | Only if `LOCAL` | Upstream referential | GATE |
| `MembershipRef` | Yes or No | Yes (for local extensions) | Upstream system (if EXTERNAL) | GATE |
| `Assignment` | No | Yes (computed) | GATE | GATE |
| `SystemProjectionRule` | No | Yes | GATE | GATE |
| `GroupProjectionRule` | No | Yes | GATE | GATE |
| `TargetSystem` | No | Yes | GATE | GATE |
| `SyncOperation` | No | Yes (runtime) | GATE | GATE |

---

## Allowed Local Entity Types

To prevent scope creep, local creation is only allowed for explicitly enumerated types.

### Allowed `TeamType` values for local (`LOCAL`) creation

| Value | Description |
|-------|-------------|
| `TRANSVERSE` | Group spanning multiple business domains with no single upstream owner |
| `TECHNICAL` | Infrastructure or platform group not represented in business referentials |
| `GOVERNANCE_ONLY` | Group created purely for IAM projection purposes (audit, compliance, support) |
| `TEMPORARY_COALITION` | Short-lived group for incident response, project delivery, etc. |

### `TeamType` values that require an upstream source

| Value | Description |
|-------|-------------|
| `BUSINESS_MANAGED` | Team defined and owned by an upstream business referential |

### Allowed `MembershipKind` values for local creation

| Value | Description | Expiration required? |
|-------|-------------|----------------------|
| `TRANSVERSE` | Person contributing to multiple teams across business domains | No |
| `TEMPORARY` | Time-bounded access for a defined purpose | **Yes** |
| `EXCEPTION` | Access granted outside normal policy, requires justification + approval | **Yes** |
| `GOVERNANCE_OVERRIDE` | System- or compliance-driven override of normal projection | Yes (or explicit review date) |

### `MembershipKind` values that cannot be created locally

| Value | Description |
|-------|-------------|
| `BASELINE` | Standard membership imported from an upstream source system |

---

## Business Role Semantics

`businessRole` in GATE is a string label that originates from the upstream source system. **GATE does not define or own a global role catalog.**

Each organization defines its own role vocabulary (e.g., `OWNER`, `CONTRIBUTOR`, `TECH_LEAD`, `SQUAD_MEMBER`). These values are imported as part of `MembershipRef` records and carried as-is through GATE's governance layer into `GroupProjectionRule` configuration.

**Implications:**

- `businessRole` is treated as an externally mastered concept, consistent with how `PersonRef` and `GroupRef` attributes are handled
- GATE does not validate `businessRole` values against a predefined taxonomy
- Operators define `GroupProjectionRule` rules using the role values their upstream source system actually produces
- The set of role values in active use can be discovered by inspecting imported `MembershipRef` records
- Local memberships (`origin=LOCAL`) may use any role value, but these should remain consistent with the upstream vocabulary to ensure `GroupProjectionRule` rules apply correctly

This design reflects that role semantics are an organizational concern belonging to the upstream referential, not to GATE.

---

## Modification and Deletion Rules for External Entities

### Modification

When the upstream source updates a `PersonRef` or `GroupRef`:
- Core attributes (name, email, org hierarchy, status) are **updated in GATE** via the import/sync process
- Governance attributes added by GATE (mappings, policies) are **preserved** unless GATE explicitly resets them
- No manual modification of core attributes is permitted through the GATE API or UI

### Deletion / Deactivation

When the upstream source deactivates or removes a `PersonRef` or `GroupRef`, GATE applies a configurable `deprovisionPolicy`:

| Policy | Behavior |
|--------|----------|
| `IMMEDIATE` | Assignments are revoked immediately; entity is marked as `INACTIVE` |
| `GRACE_PERIOD` | Assignments remain active for a configured duration before revocation |
| `MANUAL_REVIEW` | Entity is flagged; a human operator must confirm deprovisioning |

The `deprovisionPolicy` is configured at the `TargetSystem` or `GroupRef` level.

### PersonRef status and projection impact

`PersonRef.status` reflects a normalized value imported from the upstream source system. GATE does not manage this value autonomously. When a `PersonRef` status changes, the following governance rules apply:

| Status transition | GATE behavior |
|-------------------|---------------|
| → `INACTIVE` | All active `Assignment` records for that person are immediately revoked, regardless of `deprovisionPolicy`. The person no longer receives access in any downstream system. |
| → `SUSPENDED` | Behavior follows the `deprovisionPolicy` of each associated `GroupRef` or `TargetSystem`. Access may be maintained during a grace period or flagged for manual review. |
| → `ACTIVE` (reactivation) | Assignments are not automatically reinstated. A new projection cycle must be triggered to rebuild assignments from current active memberships. |

This behavior is enforced by the expiration/deprovisioning engine and is not delegated to individual connectors.

---

## Conflict Resolution Between External and Local Memberships

When both an external `MembershipRef` (baseline) and a local `Membership` (extension) exist for the same person/group pair, the following rules apply:

1. **External membership defines the baseline** — it cannot be overridden or hidden by a local membership
2. **Local memberships are additive** — they add roles or access scopes not covered by the baseline
3. **Revocation of an external membership does not automatically revoke local extensions** — local extensions must be separately reviewed and revoked per their `deprovisionPolicy`
4. **Assignments must always identify their provenance** — `provenanceType` values: `EXTERNAL_BASELINE` | `LOCAL_EXTENSION`
5. **There must never be a silent merge** — the origins of all active assignments are always visible and queryable

---

## API Enforcement

The GATE API enforces ownership rules at the transport layer:

- `PATCH /groups/{id}` on a field controlled by the upstream source returns `HTTP 422` with `reason: "FIELD_MASTERED_EXTERNALLY"`
- `POST /groups` with `teamType=BUSINESS_MANAGED` and no upstream `sourceRef` returns `HTTP 422`
- `POST /memberships` with `membershipKind=BASELINE` returns `HTTP 422`
- `POST /memberships` with `membershipKind=TEMPORARY` without `validUntil` returns `HTTP 422`
- `POST /memberships` with `membershipKind=EXCEPTION` without `justification` returns `HTTP 422`

---

## Consequences

### Positive

- Clear, non-ambiguous ownership semantics for all data in the system
- GATE cannot accidentally become a shadow HR or shadow product catalog
- Audit entries can always identify whether an assignment originates from an upstream authority or a local governance decision
- External systems remain the natural home for business-level modifications

### Negative / Trade-offs

- Additional model complexity: every entity must carry `origin`, `ownershipMode`, and sometimes `sourceSystem`
- Operators must understand the tier model to use GATE correctly
- Import/sync pipelines for upstream data become a core operational concern, not an optional feature

### Risks

- **Stale upstream data**: If the import pipeline fails or lags, GATE's view of external entities may become outdated. Mitigation: `lastImportedAt` timestamp on all external entities; operational alerts on import age.
- **Ownership creep**: Teams may be tempted to use GATE local entities to bypass upstream governance. Mitigation: `teamType` and `membershipKind` enumerations enforce bounded creation; all local entities require justification and appear with a `LOCAL` badge in the UI.

---

## Related ADRs

- [ADR-0001](ADR-0001-architectural-drivers-and-system-boundaries.md) — Architectural Drivers and System Boundaries
- [ADR-0003](ADR-0003-canonical-model-and-projection-architecture.md) — Canonical Model and Projection Architecture
