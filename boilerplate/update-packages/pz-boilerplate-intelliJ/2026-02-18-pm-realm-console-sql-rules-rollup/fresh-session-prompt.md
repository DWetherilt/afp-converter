Apply package:
`boilerplate/update-packages/pz-boilerplate-intelliJ/2026-02-18-pm-realm-console-sql-rules-rollup`

to target repo `pz-boilerplate-intelliJ`.

Requirements:
1. Follow `apply-checklist.md` strictly.
2. This package supersedes `2026-02-18-unified-framework-rollup`.
3. Preserve production boundary: PM modules remain dev-only and excluded from `prodBuild`.
4. Ensure PM realm lives under `pm/*` and does not leak into `preview/`.
5. Preserve normalized workflow contract in `pm/workflow/workflow-manifest.json` and keep Gradle interface tasks manifest-driven.
