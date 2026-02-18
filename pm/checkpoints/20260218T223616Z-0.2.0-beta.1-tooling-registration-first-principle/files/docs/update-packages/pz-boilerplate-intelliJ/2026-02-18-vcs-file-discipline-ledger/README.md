# VCS File Discipline Ledger Sync Package

## Purpose
Add mirrored version-control/file-discipline ledgering to the SQLite project state workflow.

## Scope
- Extend state tooling schema with repo snapshot and per-path file-state inventory.
- Add ledger export artifact from SQLite state (`version-control-ledger.json`).
- Document canonical boundary: Git is source of truth, SQLite is governance/audit mirror.
