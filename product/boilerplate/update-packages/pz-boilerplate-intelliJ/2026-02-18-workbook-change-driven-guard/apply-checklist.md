# Apply Checklist: Workbook Change-Driven Guard

1. Create checkpoint in target repo:
```bash
CHECKPOINT_LABEL="workbook-change-driven-guard" ./gradlew createRollbackCheckpoint
./gradlew listRollbackCheckpoints
```

2. Apply patch:
```bash
git apply --index /path/to/patches/0001-workbook-change-driven-guard.patch
```

3. If patch does not apply cleanly, manually apply the same intent:
- `build.gradle`:
  - import `java.util.Locale`
  - add `onlyIf` guard to `projectPlanWorkbook` task so workbook refresh runs only when CSV is newer or workbook is missing
  - support override `AFP_FORCE_WORKBOOK_UPDATE=true`
- `AI-POLICY.md`: add change-driven workbook rule and explicit mirrored-package rule for boilerplate.
- `README.md`: document change-driven behavior and force override.

4. Add one log entry in target repo convention:
- `SESSION_CHANGELOG.md` or `management/docs/process-evolution-log.md`

5. Validate:
```bash
./gradlew documentationManifest
```
Expected signal: `projectPlanWorkbook SKIPPED` when workbook is newer than CSV.

6. Commit:
```bash
git commit -am "Harden workbook updates: change-driven refresh with force override"
```
