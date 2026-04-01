# Contributing to GATE IAM

Thank you for your interest in GATE IAM. This document explains how to contribute effectively.

---

## Project status

GATE IAM is currently in its **architecture phase**. The domain model and core decisions are being stabilized through Architecture Decision Records (ADRs) before implementation begins. Contributions to the architecture documentation are welcome and particularly valuable at this stage.

---

## Ways to contribute

### Architecture and design
- Review existing [ADRs](docs/adr/README.md) and open an issue to discuss disagreements or gaps
- Propose a new ADR for an architectural question not yet addressed
- Improve domain model documentation or examples

### Code (once implementation begins)
- Bug fixes
- New connector implementations (GitHub, Vault, JFrog, Google Workspace, …)
- Domain feature additions aligned with the architectural decisions

### Documentation
- Improve README clarity
- Add usage examples or integration guides
- Fix typos or broken links

---

## Opening an issue

Before submitting a pull request, open an issue to discuss your intent. This avoids wasted effort if the change does not align with the project direction.

**Good issue titles:**
- `ADR: proposal for audit log retention strategy`
- `Bug: GroupProjectionRule resolution fails when targetRole is null and no SystemProjectionRule exists`
- `Feature: GitHub connector — support for org-level team sync`

---

## Submitting a pull request

1. Fork the repository and create a branch from `main`
2. Name your branch descriptively: `feat/github-connector`, `fix/projection-null-role`, `adr/audit-log-retention`
3. Keep commits focused and atomic — one logical change per commit
4. Reference the related issue in your PR description: `Closes #42`
5. Ensure your changes are consistent with the existing ADRs — if your change requires a new architectural decision, propose the ADR first

### ADR contributions

- Use the [ADR template](docs/adr/template.md)
- ADR status starts as `Proposed`
- Discuss in the associated issue before opening the PR
- ADRs are accepted by the maintainer after discussion; they are not merged unilaterally

---

## Code conventions (once implementation begins)

- Language: **Java 21**, framework: **Spring Boot**
- Architecture: hexagonal (Ports & Adapters) — domain must have zero infrastructure dependencies
- No vendor-specific types in the `domain` package (no Keycloak, GitHub, Vault classes)
- All domain decisions must be documented or reference an existing ADR
- Tests are required for domain logic; integration tests for connectors

---

## Commit message format

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short description>

[optional body]

[optional footer: Closes #issue]
```

**Types:** `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `adr`

**Examples:**
```
feat(connector): add GitHub team membership sync
fix(projection): handle null targetRole fallback correctly
adr(lifecycle): propose downstream resource decommission strategy
docs(readme): add architecture overview diagram
```

---

## License

By contributing to GATE IAM, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
