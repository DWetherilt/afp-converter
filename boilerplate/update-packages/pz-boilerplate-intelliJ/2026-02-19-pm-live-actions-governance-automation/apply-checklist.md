# Apply Checklist

1. Apply the listed file updates into the target boilerplate repository.
2. Ensure `pm/workflow/workflow-manifest.json` includes `pmConsoleEnsureLive` in default execute phase.
3. Ensure `build.gradle` includes:
   - `pmConsoleEnsureLive`, `pmConsoleLiveStatus`
   - action governance tasks (`actionItemsReport`, `actionDecisionSuggestionReport`, `actionIngestStrictGate`)
   - realm governance tasks (`realmPolicySync`, `realmPolicyDiffReport`, realm-policy exports)
   - `crossRealmActionTokenSearch`
4. Verify `pm/policy/policy-rule-tables.sql` contains action/realm-governance execution rules.
5. Run validation commands from package manifest.
6. Confirm PM console daemon remains running after repeated status checks.
