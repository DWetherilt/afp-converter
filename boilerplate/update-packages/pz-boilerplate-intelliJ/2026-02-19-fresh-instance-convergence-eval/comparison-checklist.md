# Comparison Checklist

Use this after the fresh instance applies the package(s).

## A. Build and gates
- `:pm-tools:classes` passes.
- `:pm-console:run --args="status"` runs.
- `pmWorkflowList` and `pmWorkflowRun -PpmPhase=pm_refresh_and_reports` run.
- `enforceProjectBoundaries` passes.
- `enforceManagedTooling` passes.
- `enforcePmApplicationRealmSeparation` passes.

## B. PM structure parity
- `pm/state` is used for PM databases (not application folders).
- `pm/reports` is used for PM report outputs.
- `pm/checkpoints` is used for rollback artifacts.
- `pm/workflow/workflow-manifest.json` exists and is wired.
- `pm/policy/policy-rule-tables.sql` exists and is consumed.

## C. Runtime behavior parity
- PM console command surface includes `status`, `tools`, and `refresh`.
- Workflow execution is adapter-based via `tools/pm_workflow.py`.
- Documentation/report refresh path aligns with policy.

## D. Governance parity
- Boilerplate roll-up enforcement gate exists and triggers correctly.
- Outstanding boilerplate candidate reporting behavior is preserved.
- Policy/document references for PM realm separation remain intact.

## E. Delta log
- List any file-level differences that are expected.
- List any behavioral differences that are unexpected.
- Record final verdict: `equivalent`, `mostly_equivalent`, or `not_equivalent`.
