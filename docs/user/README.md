# GATE IAM — User Documentation

Welcome to GATE IAM user documentation. This guide is for **operators, IAM engineers, and security teams** who configure and manage GATE to govern access across your target systems.

---

## What is GATE?

GATE (Governance & Access for Teams Engine) is a **governance layer** that sits between your organisation's sources of truth (HR systems, identity providers) and your downstream access systems (GitHub, Vault, JFrog, Google Workspace, etc.).

You define rules about which teams get which access, where, and with what role. GATE watches for group membership changes and automatically propagates the right access to the right places — and revokes it when memberships end.

---

## Who is this documentation for?

| Audience | Start here |
|----------|-----------|
| First-time setup | [Concepts](./concepts.md) → [Configure a target system](./how-to/configure-target-system.md) |
| Adding projection rules | [Define projection rules](./how-to/define-projection-rules.md) |
| Managing memberships | [Manage memberships](./how-to/manage-memberships.md) |
| Reference: all concepts | [Concepts](./concepts.md) |

---

## How-to guides

- [Configure a target system](./how-to/configure-target-system.md) — connect GitHub, Vault, JFrog, or Google to GATE
- [Define projection rules](./how-to/define-projection-rules.md) — set up system-level defaults and group-level overrides
- [Manage memberships](./how-to/manage-memberships.md) — grant and revoke access, handle exceptions

---

## Relationship to technical documentation

This guide focuses on **what to configure** and **why**. For **how the engine works internally**, see the [technical documentation](../technical/README.md).
