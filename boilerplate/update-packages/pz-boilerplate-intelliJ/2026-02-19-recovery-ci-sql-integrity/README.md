# Recovery + CI SQL Integrity Rollup

## Purpose
Promote SQL-first recovery resilience and CI pre-build integrity checks into the boilerplate baseline.

## Key outcomes
- Adds recovery orchestration tooling (`recovery_doctor`, binary restore, drift summary, checkpoint restore).
- Adds CI pre-Gradle SQL integrity checks and recovery artifact upload.
- Adds hook/install helpers and SQL upsert helper for PM-managed files.
- Extends policy SQL rule catalog for CI SQL-integrity governance.

## Included files
- `package-manifest.json`
- `apply-checklist.md`
- `fresh-session-prompt.md`
