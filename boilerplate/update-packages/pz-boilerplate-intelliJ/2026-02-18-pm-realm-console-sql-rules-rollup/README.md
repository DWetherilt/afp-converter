# PM Realm + Console + SQL Rules Rollup

## Purpose
Single roll-up transfer package for `pz-boilerplate-intelliJ` that consolidates the latest project-management framework changes:
- strict PM vs application realm separation,
- normalized PM workflow manifest + adapter interfaces,
- dev-only PM console module,
- SQL-backed mutable policy-rule tables,
- updated build gates and PM report wiring.

## Includes
- PM artifact relocation to `pm/state`, `pm/reports`, `pm/checkpoints`.
- New PM quality gate: `enforcePmApplicationRealmSeparation`.
- New dev-only module: `pm-console` (`status`, `tools`, `refresh`).
- New normalized workflow source: `pm/workflow/workflow-manifest.json`.
- New toolchain-neutral workflow runner: `tools/pm_workflow.py`.
- SQL rule source: `pm/policy/policy-rule-tables.sql`.
- Rule export report: `pm/reports/policy-rules.json`.

## Outcome
Apply this package as the new single review candidate and treat the previous unified roll-up as superseded.
