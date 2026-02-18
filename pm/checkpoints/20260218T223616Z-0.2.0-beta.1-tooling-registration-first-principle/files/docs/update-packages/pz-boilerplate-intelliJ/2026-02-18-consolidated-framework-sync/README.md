# Update Package: Consolidated Framework Sync

## Purpose
Single package that consolidates all currently approved boilerplate promotions into one apply flow.

## Supersedes
- `2026-02-18-project-level-rollback-governance`
- `2026-02-18-project-level-rollback-governance-log-sync`
- `2026-02-18-workbook-change-driven-guard`
- `2026-02-18-boilerplate-sync-triage-workbook`

## Scope
- Policy governance hardening (rollback + external package workflow).
- Version-control write mandate and explicit rollback-on-request requirement.
- Build safety hardening for workbook updates (change-driven + force override).
- Boilerplate sync triage workbook framework.
- Documentation alignment (`README.md`) and helper packaging tooling.
- Changelog/process-log sync guidance.

## Primary Contents
- `patches/0001-consolidated-framework-sync.patch`
- `apply-checklist.md`
- `fresh-session-prompt.md`
- `templates/changelog-entry.md`
- `package-manifest.json`
