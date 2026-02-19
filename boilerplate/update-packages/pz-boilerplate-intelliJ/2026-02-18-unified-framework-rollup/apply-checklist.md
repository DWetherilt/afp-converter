# Apply Checklist

1. Create rollback checkpoint in target repo:
   - `CHECKPOINT_LABEL=unified-framework-rollup ./gradlew createRollbackCheckpoint`
2. Apply package changes into `pz-boilerplate-intelliJ`.
3. Validate:
   - `./gradlew :boilerplate-tools:compileJava`
   - `./gradlew enforceProjectBoundaries`
   - `./gradlew documentationManifest`
4. Verify generated state/docs artifacts are present and valid.
5. Commit with package reference.
