# Apply Checklist

1. Prepare target repo (`pz-boilerplate-intelliJ`) from GitHub in a clean working tree.
2. Optional A/B baseline:
   - If `pz-boilerplate-intelliJ.zip` exists, extract to a separate directory and keep untouched for side-by-side reference.
3. In target repo, create rollback checkpoint/commit before changes.
4. Apply package `2026-02-18-pm-realm-console-sql-rules-rollup` from this repository.
5. Run the validation commands listed in `package-manifest.json` for that roll-up package.
6. Record any deltas using `comparison-checklist.md` in this folder.
7. Commit target repo changes with a clear message referencing the applied package ID(s).
8. Produce final report with:
   - applied package IDs,
   - validation outputs,
   - convergence score and gap list.
