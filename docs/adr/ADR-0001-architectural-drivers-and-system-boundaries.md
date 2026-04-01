# ADR-0001: Architectural Drivers and System Boundaries

## Status

`Accepted`

## Date

2026-03-23

---

## Context

GATE IAM (Governance & Access for Teams Engine) is a governance and orchestration layer designed to manage team affiliations and propagate them to downstream access-control systems such as GitHub, Vault, JFrog, or Google Groups.

The core challenge that motivated this project is that **access rights are fragmented across many tools**, and no existing system manages the organizational membership model as a first-class concern with governance, temporality, and auditability. Existing IAM solutions (Keycloak, midPoint, Okta) focus on identity, authentication, and permissions — not on the business-level management of *who belongs to what* and how that translates consistently across tool ecosystems.

GATE is not a traditional IAM system. It is a **membership projection and synchronization layer**. It does not:
- authenticate users
- store credentials
- manage native permissions in target systems
- replace an Identity Provider

It **does**:
- maintain a canonical model of teams, affiliations, and roles
- govern membership lifecycle (creation, expiration, exceptions)
- translate business affiliations into technical assignments per target system
- orchestrate synchronization through loosely coupled connectors
- provide traceability, auditability, and operational visibility

A key design constraint is that **GATE is not the source of truth for organizational structures**. Teams, people, and business roles are often already mastered by upstream systems (HR referentials, product catalogs, organizational directories, IdPs). GATE consumes those references and builds a governance overlay on top of them.

However, upstream systems frequently cannot express certain access patterns — transverse memberships, temporary accesses, technical groups with no business counterpart, governance-driven exceptions. For these cases, **GATE provides controlled local extensions**.

---

## Architectural Drivers

Architectural drivers are the key forces that shape the system. They are organized into four categories.

---

### Business Drivers

> Forces that come from the organization and the core problem being solved.

**BD-1 — Business affiliations are mastered by upstream domain systems**
Teams, members, and business roles are often carried by legitimate external systems (HR, product catalog, organizational directory). GATE must not become an accidental duplicate of those referentials.
_Implication: GATE references upstream entities; it does not own them._

**BD-2 — Access is enforced by downstream target systems, not by GATE**
Permissions, group memberships in GitHub/Vault/Jfrog/Google are enforced natively by each tool. GATE's role is to feed those systems with the right data at the right time.
_Implication: GATE manages projection and synchronization, not authorization enforcement._

**BD-3 — A single business affiliation may need to propagate to multiple target systems**
One membership (e.g., "Alice is a contributor on team Payments") must translate into several concrete assignments: a GitHub team, a Vault policy, a Google Group, etc.
_Implication: The model must decouple membership from assignment. A membership generates one or more assignments._

**BD-4 — Upstream systems cannot express all required access patterns**
Upstream systems typically lack support for: temporary memberships, transverse roles (person belonging to multiple teams), governance exceptions, technical groups with no business equivalent (on-call, audit access, support bridges).
_Implication: GATE must provide controlled local extensions for governance-driven scenarios._

**BD-5 — Traceability and explainability are essential to governance**
It must always be possible to answer: *Why does this person have access to this system?* and trace that back to a membership, a rule, and a synchronization event.
_Implication: Every assignment must be traceable to its provenance: source membership, projection rule, sync status._

---

### Functional Drivers

> Forces that come from product requirements and expected behavior.

**FD-1 — GATE must ingest externally managed groups, people, and memberships**
The platform must integrate with source systems to receive or pull teams, persons, and membership data via events, APIs, or scheduled imports.
_Implication: An integration layer with configurable source connectors is required._

**FD-2 — One business affiliation must project into multiple technical assignments**
A single `Membership` in GATE must produce multiple `Assignment` records — one per relevant target system, driven by `SystemProjectionRule` or `GroupProjectionRule` configuration.
_Implication: The model separates `Membership` (business intent) from `Assignment` (technical execution)._

**FD-3 — Synchronization status must be a first-class visible concept**
Operators and platform teams must be able to see in real time which assignments are pending, applied, or failed — and why.
_Implication: Sync state, retry count, error messages, and sync history are part of the core model._

**FD-4 — Local extensions must be unambiguously identifiable**
When GATE creates data locally (a transverse team, a temporary membership), that data must be visibly distinct from data imported from upstream systems — both in the UI and in the API.
_Implication: All entities carry an `origin` field (`EXTERNAL` | `LOCAL`), an `ownershipMode`, and local entries require justification._

**FD-5 — Membership temporality must be a native model concept**
Time-bound memberships (with start date and expiration) and their automatic lifecycle management (expiration, deprovisioning) must be built into the core model, not added as an extension.
_Implication: `Membership` carries `validFrom`, `validUntil`, and `status`. An expiration engine handles lifecycle transitions._

**FD-6 — Periodic access reviews (recertification) should be supportable**
The platform must be designed to allow future implementation of access review campaigns, where existing memberships are reviewed and either reconfirmed or revoked.
_Implication: The domain model must support lifecycle review states; not required in MVP but must not be structurally blocked._

---

### Technical Drivers

> Forces that come from the desired quality attributes of the system.

**TD-1 — Loose coupling with upstream and downstream systems**
Technologies and models of external systems must be able to evolve without breaking GATE's core domain. Provider-specific concepts must not leak into the business model.
_Implication: Hexagonal architecture (Ports & Adapters). The domain depends only on stable interfaces, never on connector implementations._

**TD-2 — Domain independence from vendor-specific concepts**
The GATE core domain must contain zero concepts from Keycloak, GitHub, Azure AD, Vault, LDAP, LDIF, etc.
_Implication: All provider-specific logic is encapsulated in adapters. The domain manipulates `GroupRef`, `Assignment`, `TargetSystem`, not `KeycloakGroup` or `GitHubTeamMembership`._

**TD-3 — Resilience against synchronization failures**
Target systems may be temporarily unavailable, slow, or return partial errors. GATE must handle this gracefully without losing intent.
_Implication: Asynchronous sync, retry logic, idempotent operations, and explicit failure states on `SyncOperation`._

**TD-4 — Asynchronous synchronization**
Propagation to downstream systems must not block the business/governance layer. Decisions are made immediately; execution is deferred and observable.
_Implication: Event-driven or job-based sync pipeline. Domain events (`MembershipGranted`, `MembershipRevoked`, `MembershipExpired`) trigger async projections._

**TD-5 — Observability and auditability**
Every meaningful action in GATE produces an auditable trace. The system must be exploitable by platform and security teams.
_Implication: Structured audit log, domain event history, operational dashboard for sync status, correlation IDs._

**TD-6 — Extensible connector architecture**
New target systems must be integrable without modifying the core domain. A developer should be able to add a Jira connector or a Slack connector without touching business logic.
_Implication: Stable `TargetSystemConnector` port. Each connector is an independent adapter._

---

### Organizational Drivers

> Forces that come from teams, governance structures, and how the system will be operated.

**OD-1 — Source-of-truth ownership must remain explicit**
Every data entity in GATE must have a clearly identified owner: either an external system or GATE itself. Ownership must be enforced, not just documented.
_Implication: `origin`, `sourceSystem`, and `ownershipMode` fields on all entities. API blocks writes on attributes mastered externally._

**OD-2 — GATE must not become an accidental master of business entities**
As GATE grows, there will be pressure to use it as the primary referential for teams and members. This must be structurally prevented.
_Implication: Write restrictions on external entities, typed `teamType` and `creationPolicy`, mandatory justification for local entities._

**OD-3 — Local governance extensions must be controlled and justified**
Every team, membership, or role created locally in GATE must be an explicit, justified governance extension — not a default operational convenience.
_Implication: Mandatory `justification`, `createdBy`, `origin=LOCAL`, optional expiration and approval workflow for local entities._

**OD-4 — The platform must remain understandable to non-developer teams**
Product owners, IAM engineers, and security teams must be able to understand and operate GATE without reading source code.
_Implication: Clear vocabulary aligned with business language. UI must visually distinguish external from local data. Audit log must be human-readable._

---

## Constraints

> Hard constraints that cannot be worked around.

**C-1 — External systems are heterogeneous and will diverge**
Each upstream and downstream system has its own data model, API protocol, and change frequency. GATE must accommodate this heterogeneity.

**C-2 — Upstream systems may be incomplete for access governance purposes**
Many upstream systems do not support: temporality, transverse roles, governance exceptions, access approval, expiration. GATE must compensate for these gaps.

**C-3 — GATE must never store or manage user authentication secrets**
GATE is not an IdP. It must not store passwords, tokens, or authentication credentials. Identity verification is always delegated to upstream identity systems.

**C-4 — Synchronizations may succeed partially**
One sync operation may succeed for GitHub but fail for Vault. GATE must model partial success and support per-target retry without full re-execution.

**C-5 — The system must support operation by small platform teams**
GATE will not have a dedicated 24/7 operations team. Operational complexity must be kept low. Failures must be visible and recoverable without deep technical intervention.

---

## Non-Goals

The following are explicitly **outside the scope of GATE IAM**:

- Being an Identity Provider (IdP) — GATE does not manage user identities, authentication, or credentials
- Managing native permissions in downstream systems — permissions are enforced by the systems themselves
- Replacing an HR or organizational referential
- Replacing a product catalog or any upstream business referential
- Serving as the primary source of truth for business team structures or member lists when these exist upstream
- Providing a full IAM suite comparable to Keycloak, midPoint, or Okta
- Managing SSO, MFA, or session management

---

## Consequences on Design

These drivers directly shape the following architectural decisions:

**1. Hexagonal architecture (Ports & Adapters)**
The core domain contains no infrastructure or vendor dependencies. All external interactions go through defined ports. Connectors and source adapters are plug-in implementations.

**2. Canonical internal model**
GATE defines its own internal representation for persons, groups, memberships, and assignments. This model maps to/from any target system model via adapters — never the reverse.

**3. Strict separation between imported references and native GATE entities**
`PersonRef`, `GroupRef`, and `MembershipRef` represent externally mastered facts. `Assignment`, `SystemProjectionRule`, `GroupProjectionRule`, `SyncOperation` are native GATE governance objects.

**4. Asynchronous synchronization pipeline**
Business membership decisions do not synchronously call target systems. They produce domain events (`MembershipGranted`, `MembershipRevoked`, `MembershipExpired`) that drive an async projection engine.

**5. Data ownership enforcement at the model level**
Every entity carries `origin` and `ownershipMode`. API operations on external entities are restricted to enrichment. Core attributes (name, email, org structure) of external entities are read-only in GATE.

**6. Local governance extensions are first-class but bounded**
GATE can create `LOCAL` teams (transverse, technical, governance-only) and `LOCAL` memberships (temporary, exception, transverse). These require explicit typing, justification, and optionally expiration and approval. The `TeamType` and `MembershipKind` enumerations define allowed creation policies.

---

## Architectural Rules (to be referenced in all subsequent ADRs)

The following rules derive from this ADR and must be respected throughout:

1. GATE never becomes the master of externally governed business entities.
2. External entities may be enriched but not redefined.
3. Local entities are allowed only for governance-driven extensions.
4. Every local extension must be justified, traceable, and optionally time-bound.
5. All propagated assignments must be explainable by their source membership and projection rule.
6. The core domain contains no vendor-specific or infrastructure-specific concepts.
7. Synchronization is always asynchronous and idempotent.

---

## Related ADRs

- [ADR-0002](ADR-0002-source-of-truth-and-ownership-model.md) — Source of Truth and Ownership Model _(to be written)_
- [ADR-0003](ADR-0003-canonical-model-and-projection-architecture.md) — Canonical Model and Projection Architecture _(to be written)_
