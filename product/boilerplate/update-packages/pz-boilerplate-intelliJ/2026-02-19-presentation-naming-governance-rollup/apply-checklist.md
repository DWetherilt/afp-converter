# Apply Checklist

1. Create rollback checkpoint in target repo.
2. Apply package files.
3. Run `./gradlew --no-daemon qualityGate`.
4. Verify `management/pm/reports/presentation-ref-lint.json` shows `ok=true`.
5. Commit with a single rollup message.
