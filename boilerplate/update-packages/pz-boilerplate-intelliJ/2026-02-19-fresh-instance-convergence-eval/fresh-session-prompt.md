Use this repository as source-of-truth and execute a boilerplate convergence run.

Scope:
- Target repository: `pz-boilerplate-intelliJ` (GitHub)
- Source package set: `boilerplate/update-packages/pz-boilerplate-intelliJ`
- Primary package to apply: `2026-02-18-pm-realm-console-sql-rules-rollup`
- Evaluation package: `2026-02-19-fresh-instance-convergence-eval`

Execution rules:
1. Follow `apply-checklist.md` in `2026-02-19-fresh-instance-convergence-eval`.
2. Apply the primary roll-up package and run its listed validations.
3. Compare resulting target state against this repo's PM framework expectations using `comparison-checklist.md`.
4. Output a concise report with:
   - completed checklist steps,
   - pass/fail per validation,
   - explicit differences,
   - any blocker and proposed remediation.
