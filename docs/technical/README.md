# Technical Documentation

This section is intended for **developers, contributors, and connector implementors**.

It describes the internal architecture, domain model, and contracts that govern GATE IAM's implementation.

---

## Contents

| Document | Description |
|----------|-------------|
| [Domain Model](domain-model.md) | Complete reference of all domain entities, their fields, invariants, and relationships |
| [Projection Engine](projection-engine.md) | How GATE computes `Assignment` records from `Membership` records — the resolution algorithm, event flow, and reconciliation model |
| [Connector Contract](connector-contract.md) | How to implement a `TargetSystemConnector` — the port interface, lifecycle operations, and testing guidelines |

---

## Related

- [Architecture Decision Records](../adr/README.md) — The rationale behind every significant design decision
- [Domain model sample data](../examples/canonical-model-sample-data.json) — A concrete JSON scenario illustrating the full model
