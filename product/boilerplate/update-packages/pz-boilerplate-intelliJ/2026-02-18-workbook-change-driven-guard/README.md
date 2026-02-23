# Update Package: Workbook Change-Driven Guard

## Purpose
Mirror the workbook-safety hardening into `pz-boilerplate-intelliJ` so cosmetic/manual workbook edits are preserved by default.

## Intended Target Files
- `build.gradle`
- `AI-POLICY.md`
- `README.md`
- `SESSION_CHANGELOG.md` or `management/docs/process-evolution-log.md` (target convention)

## Summary of Change
- `projectPlanWorkbook` becomes change-driven:
  - run only when CSV is newer than workbook (or workbook missing)
  - skip when workbook is newer
  - allow explicit override via `AFP_FORCE_WORKBOOK_UPDATE=true`
- Policy/docs updated to codify this behavior.

## Contents
- `patches/0001-workbook-change-driven-guard.patch`
- `apply-checklist.md`
- `fresh-session-prompt.md`
- `package-manifest.json`
