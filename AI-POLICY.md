# AI Policy

## Purpose
This file defines the operating policy for AI agents working in this repository so development remains consistent over long dormant periods.

Use this file as the first-read operational contract before making changes.

## Policy Governance
- Changes to `AI-POLICY.md` require explicit human approval.
- AI agents must not autonomously redefine, relax, or remove policy requirements in this file.
- If a policy update is needed, propose the change and wait for human confirmation before applying it.
- If a showstopper blocks progress (auth, permissions, service limits, missing prerequisites), escalate immediately to the human owner with exact blocker details and the smallest unblock action required.

## Project-Level Change Control
- Project-level operations (policy/process/build/workbook/plan/versioning changes) must follow the same control model as product code changes:
  - issue linkage (existing issue or create a new issue entry),
  - checkpoint creation before high-risk mutation,
  - post-change validation,
  - rollback readiness verification.
- High-risk project-level mutation includes:
  - workbook structure or updater-path changes,
  - build/manifest/gate task changes in `build.gradle`,
  - policy/process contract changes (`AI-POLICY.md`, plan workflow rules),
  - release/version/rollback automation changes.
- Before high-risk project-level mutation:
  - run `./gradlew createRollbackCheckpoint` with a meaningful `CHECKPOINT_LABEL`.
- After mutation:
  - run targeted validation (for workbook/process changes this includes `documentationManifest`),
  - verify checkpoint list with `./gradlew listRollbackCheckpoints`,
  - record checkpoint ID/label in `SESSION_CHANGELOG.md`.
- If validation fails or corruption is detected:
  - stop forward changes,
  - log/update issue in `docs/issues-log.csv`,
  - perform dry-run rollback first,
  - apply rollback only with explicit human instruction (`ROLLBACK_APPLY=true`).

## Project Context
- Repository: `afp-converter`
- Language/runtime: Java 21
- Build system: Gradle wrapper (`./gradlew`)
- Main modules:
  - `afp-api` (public API contracts)
  - `afp-engine` (core conversion/rendering/diagnostics)
  - `afp-cli` (CLI entrypoint)
  - `afp-tools` (project utilities, including POI workbook updater)

## Source of Truth Files
- Project version: `VERSION`
- Project plan: `docs/project-plan.md`
- Plan progress tracker (editable by humans): `docs/project-plan-progress.xlsx`
- Progress source table: `docs/project-plan-progress.csv`
- Boilerplate sync triage workbook: `docs/boilerplate-sync-candidates.xlsx`
- Issues log: `docs/issues-log.csv`
- Issues log template: `docs/issues-log-template.csv`
- Progress JSON intermediary: `preview/project-plan-progress-data.json`
- Project state database: `preview/state/project-state.sqlite`
- Boilerplate state database: `preview/state/boilerplate-state.sqlite`
- Excel macro module source: `tools/WorkbookUpdater.bas`
- Session history: `SESSION_CHANGELOG.md`
- API behavior contract: `docs/API.md`

## Non-Destructive Editing Rules
- Never overwrite or reset unrelated local changes.
- Treat `docs/project-plan-progress.xlsx` as human-owned and user-editable.
- Workbook updates must preserve user filters/sort/layout.
- `projectPlanWorkbook` may update `docs/project-plan-progress.xlsx` only via the guarded updater script.
- If `docs/project-plan-progress.xlsx.zip` exists, use it as a filter/sort template fallback when target filter nodes are missing.
- Never force-template overwrite filter/sort nodes when target nodes already exist unless explicitly requested by a human.
- Managed workbook sheets are `Current Progress`, `Status History`, `Completion Trend`, and `Progress Graph`; any other workbook sheets must remain untouched unless a human explicitly requests updates to those sheets.
- Avoid destructive git/file operations unless explicitly requested.

## Version-Control Write Mandate
- Any write to project files must remain fully traceable through version control (`git diff`/commit history) or documented rollback checkpoints.
- No project-file mutation should be treated as ephemeral or out-of-band.
- Before high-risk write sets (multi-file framework/process/build/workbook mutations), create a rollback checkpoint first.
- If the human requests rollback, execute rollback promptly using the established rollback workflow; do not substitute manual partial reverts unless explicitly requested.
- Rollback validation sequence:
  - list checkpoints,
  - dry-run rollback,
  - apply rollback only with explicit human approval (`ROLLBACK_APPLY=true`),
  - report exactly what was restored.

## Default Rendering and Behavior
- Strict native renderer is the default for PDF output.
- HTML renderer is retained for fallback and future usage.
- Diagnostics and metadata are first-class outputs and must be kept in sync with renderer behavior.

## Standard Development Cycle
When implementing any meaningful change:
0. Issues-log preflight (when applicable):
   - Review `docs/issues-log.csv` before editing components that may intersect known incidents.
   - If touched files overlap issue `components`, record that issue ID in `SESSION_CHANGELOG.md` notes for the change.
   - If no relevant issue exists and a new incident is observed, add an issue entry before concluding the change.
   - If an output is accepted but known to be imperfect (for example visual/layout quality), log it as a low/medium issue before handoff, even when a human intends to fix it manually.
0.5. Rollback preflight (required for high-risk project-level mutations):
   - Create checkpoint: `CHECKPOINT_LABEL=<label> ./gradlew createRollbackCheckpoint`
   - Note checkpoint label/intent in `SESSION_CHANGELOG.md` at change start.
1. Update code/tests.
2. Run targeted tests first.
3. Run full quality gate:
   - `./gradlew qualityGate`
4. Ensure documentation artifacts are refreshed (quality gate already includes this):
   - preview artifacts
   - fidelity report
   - project plan next-step manifest
   - project progress workbook
   - documentation manifest

## Build and Documentation Tasks
- `VERSION` is the canonical semantic version source; Gradle module versions must resolve from this file.
- `versionBump` is the standard bump path (`VERSION_PART=major|minor|patch|prerelease`).
- `releaseSnapshot` is the standard rollback-safe release checkpoint flow (quality gate + checkpoint creation).
- `rollbackCheckpoint` is dry-run by default and requires explicit `ROLLBACK_APPLY=true` to restore files.
- `prodBuild` is the production packaging path and must avoid test compilation/execution.
- `prodBuild` assumes runtime resources/environment are already correctly provisioned.
- `qualityGate` is the canonical readiness command.
- `documentationManifest` must include current docs/changelog/plan artifacts.
- `projectPlanNextStep` must reflect the current immediate plan instruction.
- `projectPlanWorkbook` updates `docs/project-plan-progress.xlsx` via Apache POI updater by default.
- `projectStateDb` is the default project-management state sync path and must populate `preview/state/project-state.sqlite` from:
  - `docs/issues-log.csv`
  - `docs/project-plan-progress.csv`
  - `preview/fidelity-report.json`
- `boilerplateStateDb` is the default boilerplate-governance state sync path and must populate `preview/state/boilerplate-state.sqlite` from:
  - `docs/update-packages/pz-boilerplate-intelliJ`
- State separation rule:
  - project execution/reporting state belongs only in `project-state.sqlite`,
  - boilerplate sync/package governance state belongs only in `boilerplate-state.sqlite`.
- Database-backed tooling rule:
  - prefer SQLite-backed `afp-tools` tasks over ad-hoc scripts for project/workflow automation outputs.
- `projectPlanWorkbook` must be change-driven:
  - run when `docs/project-plan-progress.csv` is newer than workbook or workbook is missing,
  - skip when workbook is newer (to preserve human cosmetic updates),
  - allow explicit override with `AFP_FORCE_WORKBOOK_UPDATE=true`.
- Set `AFP_WORKBOOK_MODE=excel` for Excel-native scripting path using `preview/project-plan-progress-data.json` + `tools/WorkbookUpdater.bas`.
- For non-Excel/headless environments, set `AFP_WORKBOOK_MODE=xml` to use guarded XML fallback.
- Workbook updates must remain cell-level on managed sheets and must not rewrite non-managed sheets.
- Excel-native refresh must be timeout-bounded and must emit `preview/workbook-refresh-status.json`.
- XML fallback writes to `docs/project-plan-progress.xlsx` are **disabled by default** for corruption control; only run XML fallback with explicit human instruction for that specific operation.
- On any workbook-corruption signal, apply restore-first protocol:
  - restore `docs/project-plan-progress.xlsx` from `docs/project-plan-progress.xlsx.zip`,
  - validate archive integrity,
  - log the event in `docs/issues-log.csv`,
  - then continue with Excel-native or POI-managed update paths only.
- `issuesLogTickle` enforces issue-log hygiene: if files referenced by `docs/issues-log.csv` change, the issues log must be updated in the same change.
- `projectPlanProgressJson`, `issuesLogTickle`, and `issuesEffectivenessReport` should execute from SQLite-backed state generated by `projectStateDb`.
- `boilerplateSyncWorkbook` maintains `docs/boilerplate-sync-candidates.xlsx` from `docs/update-packages/pz-boilerplate-intelliJ` and must preserve manual triage columns (`decision`, `state`, `owner_notes`).
- `issuesEffectivenessReport` provides mechanized debug reasoning signals from:
  - issue triggers (`preview/issues-log-tickle.json`)
  - task progress (`docs/project-plan-progress.csv`)
  - fidelity metrics (`preview/fidelity-report.json`)
  and writes `preview/issues-effectiveness.json`.
- Pitfall guardrail:
  - Issues-log coupling must guide troubleshooting, not block urgent fixes; apply issue-linked context first, but do not force unrelated updates to closed/stable incidents.
  - Do not suppress "known imperfection" logging due to low severity; low-severity issues still must be captured to prevent repeated rediscovery.
- External update package workflow:
  - For frozen/external sibling repos, store transfer bundles under `docs/update-packages/<target-repo>/<package-name>/`.
  - If the required package folder does not exist, create a new one (do not block on missing folder state).
  - When a shared process/policy/build workflow change should be mirrored to `pz-boilerplate-intelliJ`, create or update the corresponding transfer package in the same working turn before handoff.
  - When multiple candidates are approved for promotion in the same cycle, consolidate them into one outbound package and mark earlier package folders as superseded in the consolidated package manifest.
  - After the target repo has merged the package, the package folder may be removed by a human; future updates should generate a fresh package.
  - If any boilerplate sync candidate remains in `review` state, include that outstanding item list in user-facing status/closure responses.

## Plan-Driven Execution Policy
- Work should follow `docs/project-plan.md`.
- After completing a planned step:
  - update `docs/project-plan.md` immediate next step,
  - update `docs/project-plan-progress.csv`,
  - update `docs/project-plan-progress.xlsx` via `projectPlanWorkbook`,
  - append `SESSION_CHANGELOG.md`,
  - regenerate manifests.
- For project-level workflow/process updates, also record:
  - checkpoint label used before change,
  - validation commands run,
  - rollback command that would be used if reversion is required.

## Fidelity and Regression Discipline
- Primary KPI file: `preview/fidelity-report.json`.
- Quality gate enforces minimum thresholds; do not relax without explicit direction.
- If metrics do not improve, keep diagnostics-rich changes and move to the next highest-signal plan step.

## Artifact Expectations
After a normal cycle, these should be current:
- `preview/afp-output.pdf`
- `preview/afp-output.html`
- `preview/afp-meta.json`
- `preview/afp-diag.json`
- `preview/fidelity-report.json`
- `preview/project-plan-next-step.json`
- `preview/documentation-manifest.json`
- `preview/ci-artifacts/latest/*`

## Environment Metadata Change Policy
If environment/process metadata changes (new task, new file, renamed plan path, new gate, new artifact):
1. Update this `AI-POLICY.md`.
2. Update `README.md` if user-facing workflow changed.
3. Update `SESSION_CHANGELOG.md`.
4. Ensure build tasks/manifests include the new metadata source.

## Agent Handover Prompt (for a fresh AI)
If you are a new agent on this repo:
- Read: `AI-POLICY.md`, `docs/project-plan.md`, `docs/project-plan-progress.csv`, `SESSION_CHANGELOG.md`.
- Continue from `preview/project-plan-next-step.json`.
- Maintain strict native rendering defaults.
- Keep edits non-destructive, especially workbook/history updates.
- Run `./gradlew qualityGate` before concluding substantive work.
- Update plan/progress/changelog/manifests as part of completion.

## Conversation Resumption Prompt
Use this prompt verbatim when chat history is missing and you must resume this exact workflow:

`Resume the workbook JSON/VBA flow for project-plan tracking. Treat AI-POLICY.md as the operating contract. Rebuild preview/project-plan-progress-data.json from docs/project-plan-progress.csv, then continue with tools/WorkbookUpdater.bas as the Excel-native update path. Do not use low-level XLSX chart/drawing XML mutation. Preserve user filters/sort and avoid touching non-managed sheets. Before changing implementation, review SESSION_CHANGELOG.md and docs/project-plan.md for the last checkpoint and pending follow-up to review restored workbook content against CSV/JSON sources.`
