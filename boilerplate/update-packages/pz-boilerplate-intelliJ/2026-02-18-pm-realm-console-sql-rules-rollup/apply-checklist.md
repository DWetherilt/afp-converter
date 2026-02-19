# Apply Checklist

1. Create rollback checkpoint in target repo:
   - `CHECKPOINT_LABEL=pm-realm-console-sql-rules-rollup ./gradlew createRollbackCheckpoint`
2. Apply patch:
   - `git apply boilerplate/update-packages/pz-boilerplate-intelliJ/2026-02-18-pm-realm-console-sql-rules-rollup/patches/0001-pm-realm-console-sql-rules-rollup.patch`
3. If module/path names differ in target boilerplate, apply equivalent manual mapping for:
   - PM state/report/checkpoint paths,
   - PM console module wiring,
   - SQL policy rule sync/export tasks,
   - workflow manifest + adapter interface tasks.
4. Validate:
   - `./gradlew :pm-tools:classes`
   - `./gradlew :pm-console:run --args="status"`
   - `./gradlew pmWorkflowList pmWorkflowRun -PpmPhase=pm_refresh_and_reports`
   - `./gradlew enforceProjectBoundaries enforceManagedTooling enforcePmApplicationRealmSeparation`
   - `./gradlew documentationManifest`
5. Confirm PM artifacts are not emitted under `preview/`.
6. Commit with package reference.
