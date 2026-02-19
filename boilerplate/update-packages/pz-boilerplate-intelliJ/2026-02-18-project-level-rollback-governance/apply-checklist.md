# Apply Checklist (for fresh session in `pz-boilerplate-intelliJ`)

1. Create safety checkpoint before mutation:
```bash
CHECKPOINT_LABEL="policy-rollback-governance" ./gradlew createRollbackCheckpoint
./gradlew listRollbackCheckpoints
```

2. Try patch apply:
```bash
git apply --index /path/to/0001-policy-and-readme-rollback-governance.patch
```

3. If patch fails, manually apply equivalent edits:
- In `AI-POLICY.md` add:
  - `Project-Level Change Control` section
  - rollback preflight step in `Standard Development Cycle`
  - workbook corruption restore-first and XML-fallback restrictions
  - plan-driven execution note requiring checkpoint/validation/rollback command recording
- In `README.md` add:
  - project/process mutation safety flow:
    - `createRollbackCheckpoint`
    - `documentationManifest`
    - `listRollbackCheckpoints`

4. Add changelog/process entry in target repo (choose one):
- `SESSION_CHANGELOG.md` entry, or
- `docs/process-evolution-log.md` entry

5. Run validations:
```bash
./gradlew documentationManifest
./gradlew listRollbackCheckpoints
```

6. Confirm files changed:
```bash
git status --short
```

7. Commit suggestion:
```bash
git commit -am "Harden project-level rollback governance in policy and docs"
```

