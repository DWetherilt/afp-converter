# Apply Checklist: Consolidated Framework Sync

1. In target repo (`pz-boilerplate-intelliJ`), create rollback checkpoint:
```bash
CHECKPOINT_LABEL="consolidated-framework-sync" ./gradlew createRollbackCheckpoint
./gradlew listRollbackCheckpoints
```

2. Apply patch:
```bash
git apply --index /path/to/patches/0001-consolidated-framework-sync.patch
```

3. If any hunks fail, manually apply equivalent intent from patch sections:
- `AI-POLICY.md`
- `README.md`
- `build.gradle`
- `afp-tools/src/main/java/com/upland/connect/afp/tools/BoilerplateSyncWorkbookUpdater.java`
- `tools/init_update_package.sh` (if target includes tools workflow)
- `docs/update-packages/README.md` (if target includes external package workflow docs)

4. Update target log by convention:
- append `templates/changelog-entry.md` to `SESSION_CHANGELOG.md` and/or `docs/process-evolution-log.md`

5. Validate:
```bash
./gradlew documentationManifest
./gradlew listRollbackCheckpoints
```

6. Verify workbook behaviors:
- `projectPlanWorkbook` skips when workbook is newer than CSV.
- `boilerplateSyncWorkbook` generates/refreshes `docs/boilerplate-sync-candidates.xlsx`.

7. Commit:
```bash
git status --short
git commit -am "Apply consolidated framework sync from afp-converter"
```
