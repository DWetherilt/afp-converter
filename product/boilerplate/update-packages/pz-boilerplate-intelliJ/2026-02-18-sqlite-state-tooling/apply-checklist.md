# Apply Checklist

1. Create rollback checkpoint in target repo:
   - `CHECKPOINT_LABEL=sqlite-state-tooling ./gradlew createRollbackCheckpoint`
2. Apply package changes into target repo (policy, Gradle wiring, tooling class).
3. Run validations:
   - `./gradlew :boilerplate-tools:compileJava`
   - `./gradlew documentationManifest`
4. Confirm SQLite state files are emitted under `product/afp-converter/preview/state/`.
5. Commit with a package-reference message.
