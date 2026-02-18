Apply package:
`docs/update-packages/pz-boilerplate-intelliJ/2026-02-18-consolidated-framework-sync`

to target repo `pz-boilerplate-intelliJ`.

Requirements:
1. Follow `apply-checklist.md` strictly.
2. This package supersedes the four earlier packages from 2026-02-18.
3. Create rollback checkpoint first.
4. Apply patch; if context differs, apply manual equivalent.
5. Run `./gradlew documentationManifest` and `./gradlew listRollbackCheckpoints`.
6. Report changed files and validation summary.
