# Apply Checklist: Boilerplate Sync Triage Workbook Framework

1. Create rollback checkpoint in target repo:
```bash
CHECKPOINT_LABEL="boilerplate-sync-triage-framework" ./gradlew createRollbackCheckpoint
```

2. Apply patch first:
```bash
git apply --index /path/to/patches/0001-boilerplate-sync-triage-framework.patch
```

3. If patch context differs, apply using templates:
- Add Java file from `templates/BoilerplateSyncWorkbookUpdater.java`
  - target path: `afp-management/tools/src/main/java/solutions/pointzero/symphony/afp/management/tools/BoilerplateSyncWorkbookUpdater.java`
- Apply `templates/build-gradle-snippets.md` changes to `build.gradle`
- Apply `templates/policy-readme-snippets.md` changes to `AI-POLICY.md` and `README.md`

4. Validate:
```bash
./gradlew documentationManifest
```
Expected: new workbook `management/docs/boilerplate-sync-candidates.xlsx` generated.

5. Confirm and commit:
```bash
git status --short
git commit -am "Add boilerplate sync triage workbook framework"
```
