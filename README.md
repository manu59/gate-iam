# GATE IAM

**Governance & Access for Teams Engine**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-architecture%20phase-yellow)]()

> A governance and synchronization platform for propagating externally managed memberships to downstream access-control systems.

---

## What is GATE IAM?

GATE IAM is a governance and orchestration layer that manages affiliations independently from target systems.

It **decouples the definition of memberships from their technical implementation**, and propagates them to external platforms through connectors, ensuring consistency across tools like GitHub, Vault, JFrog, or Google Workspace.

GATE is **not the source of truth** for organizational structures, but it provides controlled local extensions when external systems cannot express certain access patterns.

---

## Core Concept

```
Upstream Business Systems          GATE IAM                  Downstream Target Systems
┌───────────────────────┐    ┌───────────────────────┐    ┌───────────────────────────┐
│  HR / Org Referential │───▶│  Governance Layer     │───▶│  GitHub Teams             │
│  Product Catalog      │    │  - Membership rules   │    │  HashiCorp Vault policies │
│  Identity Provider    │    │  - Local extensions   │    │  JFrog permissions        │
│  (LDAP / IdP)         │    │  - Sync orchestration │    │  Google Groups            │
└───────────────────────┘    │  - Audit & tracing    │    │  ...                      │
                             └───────────────────────┘    └───────────────────────────┘
```

---

## Key Principles

- **Loose coupling** — The core model contains no provider-specific concepts (no Keycloak, GitHub, Vault types in the domain).
- **Canonical model** — A single internal representation translates to any number of target systems via connectors.
- **Source-of-truth awareness** — GATE governs what it creates; it references what comes from upstream.
- **Controlled local extensions** — GATE can define transverse/temporary memberships when upstream systems cannot express the access pattern.
- **Traceability first** — Every propagated assignment is explainable: source, rule, target, status.

---

## Governance Dimensions

A governance solution covers:

| Dimension | Description |
|-----------|-------------|
| **Rules** | Who can have what, under which conditions (policies) |
| **Control** | Validation, workflows, separation of duties |
| **Tracing** | Audit log, change history |
| **Lifecycle** | Temporary access, periodic reviews, recertification |
| **Alignment** | Teams, transverse roles, organizational structure |

---

## Project Structure

```
gate-iam/
├── README.md
├── docs/
│   ├── adr/                    # Architecture Decision Records
│   ├── architecture/           # Architecture diagrams & explanations
│   └── domain/                 # Domain model documentation
├── backend/                    # Spring Boot application (core domain + API)
├── frontend/                   # Admin UI
├── connectors/                 # Target system connectors (GitHub, Vault, etc.)
├── infrastructure/             # Docker Compose, deployment config
└── .github/                    # CI/CD workflows, issue templates
```

---

## Architecture Decision Records

All significant decisions are documented as ADRs in [`docs/adr/`](docs/adr/README.md).

| ADR | Title | Status |
|-----|-------|--------|
| [ADR-0001](docs/adr/ADR-0001-architectural-drivers-and-system-boundaries.md) | Architectural Drivers and System Boundaries | Accepted |
| [ADR-0002](docs/adr/ADR-0002-source-of-truth-and-ownership-model.md) | Source of Truth and Ownership Model | Accepted |
| [ADR-0003](docs/adr/ADR-0003-canonical-model-and-projection-architecture.md) | Canonical Model and Projection Architecture | Accepted |
| [ADR-0004](docs/adr/ADR-0004-role-mapping-resolution-strategy.md) | Role Mapping Resolution Strategy | Accepted |
| [ADR-0005](docs/adr/ADR-0005-downstream-resource-lifecycle-management.md) | Downstream Resource Lifecycle Management | Accepted |

---

## Status

> This project is in the **scoping phase**. The domain model and architecture decisions are being defined before implementation begins.

---

## Pitch

> *"GATE IAM is a governance and orchestration layer that manages affiliations independently from target systems. It decouples the definition of memberships from their technical implementation, and propagates them to external platforms through connectors, ensuring consistency across tools like GitHub, Vault, or Google Workspace."*
