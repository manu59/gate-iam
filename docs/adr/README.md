# Architecture Decision Records

This directory contains all Architecture Decision Records (ADRs) for the GATE IAM project.

An ADR is a document that captures an important architectural decision made along with its context and consequences.

---

## Index

| ID | Title | Status | Date |
|----|-------|--------|------|
| [ADR-0001](ADR-0001-architectural-drivers-and-system-boundaries.md) | Architectural Drivers and System Boundaries | Accepted | 2026-03-23 |
| [ADR-0002](ADR-0002-source-of-truth-and-ownership-model.md) | Source of Truth and Ownership Model | Accepted | 2026-03-24 |
| [ADR-0003](ADR-0003-canonical-model-and-projection-architecture.md) | Canonical Model and Projection Architecture | Accepted | 2026-03-24 |
| [ADR-0004](ADR-0004-role-mapping-resolution-strategy.md) | Role Mapping Resolution Strategy | Accepted | 2026-03-25 |
| [ADR-0005](ADR-0005-downstream-resource-lifecycle-management.md) | Downstream Resource Lifecycle Management | Accepted | 2026-03-25 |
| [ADR-0006](ADR-0006-language-and-application-framework.md) | Language and Application Framework | Accepted | 2026-04-01 |
| [ADR-0007](ADR-0007-persistence-strategy.md) | Persistence Strategy | Accepted | 2026-04-01 |
| [ADR-0008](ADR-0008-event-bus-strategy.md) | Domain Event Bus Strategy | Accepted | 2026-04-01 |
| [ADR-0009](ADR-0009-testing-strategy.md) | Testing Strategy | Accepted | 2026-04-01 |

---

## ADR Lifecycle

```
Proposed → Accepted → Deprecated
                    ↘ Superseded by ADR-XXXX
```

- **Proposed** — Under discussion, not yet validated
- **Accepted** — Validated and applied to the project
- **Deprecated** — No longer relevant but kept for history
- **Superseded** — Replaced by a newer ADR

---

## Creating a new ADR

Copy the [template](template.md) and follow the naming convention:

```
ADR-XXXX-short-title-in-kebab-case.md
```

Then add it to the index table above.
