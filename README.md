# GATE IAM

**Governance & Access for Teams Engine**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-implementation%20phase-blue)]()

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
├── Makefile                        # Commandes courantes (build, test, run, …)
├── docs/
│   ├── adr/                        # Architecture Decision Records
│   ├── technical/                  # Documentation développeur
│   └── user/                       # Documentation opérateur / IAM engineer
├── gate-iam-domain/                # Domaine pur — zéro Spring, zéro JPA
├── gate-iam-application/           # Use cases — dépend du domaine uniquement
├── gate-iam-infrastructure/        # Adapters JPA, connecteurs, outbox
├── gate-iam-backend/               # Launcher Spring Boot, configuration, assemblage
├── gradle/
│   └── libs.versions.toml          # Version catalog centralisé
└── .github/
    ├── dependabot.yml              # Mises à jour automatiques des dépendances
    └── workflows/                  # CI/CD
```

---

## Getting Started

**Prérequis :** Java 25 (Temurin), Docker (pour les tests d'intégration)

```bash
# Installer les git hooks (à faire une fois)
make install-hooks

# Compiler
make build

# Lancer les tests
make test

# Démarrer l'application
make run

# Afficher toutes les commandes disponibles
make help
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
| [ADR-0006](docs/adr/ADR-0006-language-and-application-framework.md) | Language and Application Framework | Accepted |
| [ADR-0007](docs/adr/ADR-0007-persistence-strategy.md) | Persistence Strategy | Accepted |
| [ADR-0008](docs/adr/ADR-0008-event-bus-strategy.md) | Domain Event Bus Strategy | Accepted |
| [ADR-0009](docs/adr/ADR-0009-testing-strategy.md) | Testing Strategy | Accepted |

---

## Status

> Ce projet est en phase d'**implémentation**. Les décisions d'architecture sont finalisées. Le développement suit TDD sur des branches de fonctionnalités (`feat/US-NNN-slug`), avec semantic-release pour la gestion des versions et Dependabot pour la maintenance des dépendances.

---

## Pitch

> *"GATE IAM is a governance and orchestration layer that manages affiliations independently from target systems. It decouples the definition of memberships from their technical implementation, and propagates them to external platforms through connectors, ensuring consistency across tools like GitHub, Vault, or Google Workspace."*
