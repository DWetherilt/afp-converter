Apply the update package located at:
`boilerplate/update-packages/pz-boilerplate-intelliJ/2026-02-18-project-level-rollback-governance`

Target repository: `pz-boilerplate-intelliJ` (separate repo).

Requirements:
1. Use `apply-checklist.md` exactly.
2. Prefer `git apply` with `patches/0001-policy-and-readme-rollback-governance.patch`.
3. If patch context differs, apply the same intent manually.
4. Do not relax policy requirements; this is a governance hardening change.
5. Run `./gradlew documentationManifest` and `./gradlew listRollbackCheckpoints` before concluding.
6. Provide a concise summary of final diffs and validation output.
