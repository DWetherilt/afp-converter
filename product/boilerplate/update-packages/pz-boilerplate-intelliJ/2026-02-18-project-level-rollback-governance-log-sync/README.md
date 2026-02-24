# Update Package: Rollback Governance Log Sync

## Purpose
Apply matching changelog/process-log entries in `pz-boilerplate-intelliJ` after policy/doc governance patch is merged.

## Scope
- Add a session/process entry documenting that project-level rollback governance was hardened.
- Keep the target repo's own logging convention:
  - prefer `SESSION_CHANGELOG.md` when present,
  - otherwise use `management/docs/process-evolution-log.md`,
  - if both exist, update both.

## Contents
- `apply-checklist.md`
- `fresh-session-prompt.md`
- `templates/session-changelog-entry.md`
- `templates/process-evolution-entry.md`
- `patches/0001-log-sync-template.patch` (optional helper; manual apply fallback expected)
- `package-manifest.json`

## Note
This package is intentionally log-focused and can be applied independently from policy patching.
