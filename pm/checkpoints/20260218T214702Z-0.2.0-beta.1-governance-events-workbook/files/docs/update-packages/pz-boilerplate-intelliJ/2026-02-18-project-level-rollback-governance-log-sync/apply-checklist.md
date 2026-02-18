# Apply Checklist (Log Sync)

1. Create rollback checkpoint in target repo:
```bash
CHECKPOINT_LABEL="policy-log-sync" ./gradlew createRollbackCheckpoint
```

2. Apply logging updates by target convention:
- If `SESSION_CHANGELOG.md` exists:
  - append `templates/session-changelog-entry.md`.
- If `docs/process-evolution-log.md` exists:
  - append `templates/process-evolution-entry.md`.
- If both exist, append both.

3. Optional: attempt helper patch first:
```bash
git apply --index /path/to/patches/0001-log-sync-template.patch || true
```
(Manual append remains the source of truth.)

4. Validate and record:
```bash
./gradlew documentationManifest
./gradlew listRollbackCheckpoints
```

5. Commit suggestion:
```bash
git add SESSION_CHANGELOG.md docs/process-evolution-log.md || true
git commit -m "Sync rollback-governance changelog/process-log entries"
```
