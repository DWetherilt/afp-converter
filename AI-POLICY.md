# AI Policy

## Purpose
This file defines the operating policy for AI agents working in this repository so development remains consistent over long dormant periods.

Use this file as the first-read operational contract before making changes.

## First Principles (Constitutional Layer)
- Constitutional wording lock:
  - The wording of this First Principles section may only be changed by a human.
  - AI agents must not edit, rephrase, reinterpret, or relocate these principles without explicit human instruction in the active session.
- Constitutional conflict handling:
  - If an AI agent believes progress requires breaking any first principle, it must stop immediately and notify a human before taking the conflicting action.
  - No silent exceptions are permitted.
- Governance breach notification duty:
  - Any governance breach, sequencing violation, or constitutional risk must be surfaced to the human owner in the next status/closure response until explicitly acknowledged by a human.
  - Do not treat governance breach reporting as optional.
- Standard procedure invariance:
  - Policy/constitutional edits must follow the same governance sequence every time: issue-log preflight, checkpoint creation, change application, validation, checkpoint listing, and changelog recording.
  - If any mandatory step fails or is blocked, the agent must halt, report the blocker, and wait for human direction before continuing.
- Dedicated workstream formalization:
  - When a clear, bounded, multi-step execution need is detected, the active cycle must formalize it as a dedicated workstream before implementation continues.
  - Workstream formalization must include scoped tasks, explicit status/progress tracking, and alignment with machine-readable next-step/report artifacts.
- Non-destructive stewardship:
  - Preserve human-managed artifacts and local edits; do not overwrite or reset unrelated user changes.
  - Workbook handling must remain cell-level and preserve user filters/sort/layout unless explicitly directed by a human.
- Traceable and reversible project mutation:
  - Project file writes must be traceable via version control and/or rollback checkpoints.
  - High-risk mutations require checkpoint-first discipline and rollback readiness.
- State separation discipline:
  - Project execution/reporting state and boilerplate governance state must remain separated and must not be merged into one datastore/workflow.
- Database single-source discipline:
  - For project-management and governance mechanics, `management/pm/state/project-state.sqlite` is the single source of operational truth for this repo.
  - For boilerplate governance/package mechanics, `management/pm/state/boilerplate-state.sqlite` is the single source of operational truth.
  - Any human trust grant or trust boundary instruction must be logged via governance mechanics (`management/docs/policy-governance-events.csv` -> SQLite sync) before completion handoff.
  - Version-control truth boundary:
    - Git remains the canonical source of repository history and rollback lineage.
    - SQLite must mirror a verifiable VCS snapshot (`HEAD`, branch, dirty state, file-status inventory) for governance/audit workflows.
- Managed tooling discipline:
  - No unmanaged project-management tooling is allowed.
  - Any tool/script/class used for project-management workflow must be version-controlled, registered in policy/build-manifest workflow, and represented in the project-state audit path before completion handoff.
- Human authority:
  - Human instruction is authoritative for prioritization and policy evolution, except where it would directly conflict with higher-priority system safety constraints.

## Policy Governance
- Changes to `AI-POLICY.md` require explicit human approval.
- AI agents must not autonomously redefine, relax, or remove policy requirements in this file.
- If a policy update is needed, propose the change and wait for human confirmation before applying it.
- If a showstopper blocks progress (auth, permissions, service limits, missing prerequisites), escalate immediately to the human owner with exact blocker details and the smallest unblock action required.
- Mutable operational rule tables should be maintained in SQL (`management/pm/policy/policy-rule-tables.sql`) and synchronized into SQLite state, instead of repeated structural edits to this policy document.
- Workflow sequencing should be maintained in normalized manifest form (`management/pm/workflow/workflow-manifest.json`) with toolchain adapters (Gradle, etc.) consuming that contract.

## Core Arbiter Derivation Model
- `pzAiCore` is the base environment arbiter model.
- This repository is a derivative project that operates under that core model.
- The core arbitration hierarchy is:
  - Human Lead Developer
  - Core Policy Realm (`pzAiCore`)
  - Project policy/governance
  - Project internal realms
- This project's internal realms are:
  - `application`
  - `pm`
  - `boilerplate`
- Project reasoning state must be linkable to core reasoning state so arbitration/escalation decisions remain traceable across levels.
- New projects should be instantiated as derivatives of `pzAiCore` instead of ad-hoc policy bootstraps.
- Topology contract for this model is defined in `management/pm/workflow/core-derivative-topology.json`.

## Filesystem Canonicality (Fundamental)
- Day-1 workspace shape must present a clean manifested layout with canonical realms:
  - `policy/`
  - `management/`
  - `product/`
- Root-level compatibility links/shims (especially symlinks like legacy realm aliases) are not default behavior and must not persist in normal handoff state.
- Temporary compatibility paths are allowed only when explicitly required for migration/debug and must be removed before completion handoff.
- Filesystem cleanup is mandatory after migration/debug work so the workspace returns to a just-manifested look and feel.

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
  - log/update issue in `management/docs/issues-log.csv`,
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
  - `pm-tools` (project utilities, including POI workbook updater)
  - `pm-console` (dev-only PM dashboard/command hub)
- Realm boundary:
  - Application realm: `afp-*` modules and `product/afp-converter/preview/` renderer outputs only.
  - PM realm: `management/pm/` state/report/checkpoint assets and governance/planning management/docs/workbooks under `management/docs/`.
  - PM artifacts must never be emitted under `product/afp-converter/preview/`.

## Source of Truth Files
- Project version: `VERSION`
- Project plan: `management/docs/project-plan.md`
- Plan progress tracker (editable by humans): `management/docs/project-plan-progress.xlsx`
- Progress source table: `management/docs/project-plan-progress.csv`
- Boilerplate sync triage workbook: `management/docs/boilerplate-sync-candidates.xlsx`
- Policy governance events tracker: `management/docs/policy-governance-events.csv`
- Policy governance workbook: `management/docs/policy-governance-events.xlsx`
- Issues log: `management/docs/issues-log.csv`
- Issues log template: `management/docs/issues-log-template.csv`
- Progress JSON intermediary: `management/pm/reports/project-plan-progress-data.json`
- Realm project-plan reports:
  - `management/pm/reports/project-plan-progress-application.json`
  - `management/pm/reports/project-plan-progress-pm.json`
  - `management/pm/reports/project-plan-progress-boilerplate.json`
- Project state database: `management/pm/state/project-state.sqlite`
- Boilerplate state database: `management/pm/state/boilerplate-state.sqlite`
- Policy rule SQL source: `management/pm/policy/policy-rule-tables.sql`
- Policy rule report export: `management/pm/reports/policy-rules.json`
- Workflow manifest source: `management/pm/workflow/workflow-manifest.json`
- Workstream-to-realm mapping source: `management/pm/workflow/workstream-realm-map.json`
- Plan commit-marker lint report: `management/pm/reports/project-plan-commit-marker-lint.json`
- Workstream slice evidence report: `management/pm/reports/workstream-j-slice-evidence.json`
- Active workstream presentation index: `management/pm/reports/workstream-presentation-index.json`
- Project file inventory export: `management/pm/reports/repo-file-inventory-with-context.csv`
- Boilerplate package inventory export: `management/pm/reports/boilerplate-package-files-with-context.csv`
- Excel macro module source: `management/tools/WorkbookUpdater.bas`
- Session history: `SESSION_CHANGELOG.md`
- API behavior contract: `management/docs/API.md`

## Non-Destructive Editing Rules
- Never overwrite or reset unrelated local changes.
- Treat `management/docs/project-plan-progress.xlsx` as human-owned and user-editable.
- Workbook updates must preserve user filters/sort/layout.
- `projectPlanWorkbook` may update `management/docs/project-plan-progress.xlsx` only via the guarded updater script.
- If `management/docs/project-plan-progress.xlsx.zip` exists, use it as a filter/sort template fallback when target filter nodes are missing.
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
- Default execution coupling:
  - Unless a human explicitly defines otherwise, any request to execute/run the application must include PM console launch in the same flow.
  - Environment health tasks are highest-priority and must execute before application workflow phases.
  - Standard default sequence is: environment readiness gate (`pmEnvironmentReady`) that ensures persistent PM console live daemon, verifies PID/log + visible session health (`pmConsoleVisibleCheck`), persists environment/visibility knowledge and evidence, then application execution (`previewManifest`), then PM console status (`pmConsoleStatus` / `pmconsole status`).
  - Governance mode is controlled by `ENV_GOVERNANCE_MODE` (`strict` default, `relaxed` optional).
  - Optional visible-console fallback launch is controlled by `PM_CONSOLE_AUTOLAUNCH` (`true` local default, `false` in CI/headless).

## Standard Development Cycle
When implementing any meaningful change:
0. Issues-log preflight (when applicable):
   - Review `management/docs/issues-log.csv` before editing components that may intersect known incidents.
   - If touched files overlap issue `components`, record that issue ID in `SESSION_CHANGELOG.md` notes for the change.
   - If no relevant issue exists and a new incident is observed, add an issue entry before concluding the change.
   - If an output is accepted but known to be imperfect (for example visual/layout quality), log it as a low/medium issue before handoff, even when a human intends to fix it manually.
0.25. Workstream formalization preflight (when applicable):
   - If work is clearly a bounded multi-step stream rather than a one-off fix, create/update a dedicated workstream in planning artifacts before implementation.
   - Ensure scoped tasks, status/progress, and next-step guidance are synchronized across human-readable and machine-readable planning outputs.
0.3. Plan completion marker discipline:
   - Completed project-plan rows must include explicit GitHub commit state in `notes`:
     - `[github:committed]` when the work is committed/pushed.
     - `[github:not-committed]` when completed locally but not yet committed/pushed.
   - Progress report exports must exclude only rows marked complete + `[github:committed]`.
0.35. Workstream identity and presentation discipline:
   - `workstream_uid` is the authoritative persistent identifier and must remain unique across project history.
   - User-facing workstream letters (`Workstream A..Z`) are presentation aliases for active rows only and may be reassigned between cycles.
   - Publish active alias mapping in `management/pm/reports/workstream-presentation-index.json` and include internal UID when reporting progress.
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
- `prodBuild` must remain application-only (`afp-api`, `afp-engine`, `afp-cli`) and must not package PM modules (`pm-tools`, `pm-console`).
- `qualityGate` is the canonical readiness command.
- `pmEnvironmentReady` is the canonical environment gate and must run before non-environment workflow phases.
- `pmConsoleVisibleCheck` enforces visible desktop console verification locally and writes `management/pm/reports/environment-health.json` (CI-safe visibility behavior).
- `pmConsoleVisibleLaunch` is the explicit manual recovery command to open a visible desktop PM console session.
- `pmConsoleCleanupStaleSessions` detects/optionally cleans stale duplicate background `pmconsole live` sessions and writes `management/pm/reports/pm-console-stale-cleanup.json`.
- `pmConsoleCleanupStaleSessionsApply` is the explicit cleanup command for stale duplicates.
- stale-session policy:
  - `PM_CONSOLE_STALE_POLICY=report|clean` (default `report`)
  - `PM_CONSOLE_STALE_WARN_THRESHOLD=<n>` controls warning trigger for consecutive stale cycles.
- `environmentHealthSchemaCheck` must validate `management/pm/reports/environment-health.json` before publication/quality gates.
- `staleHistorySchemaCheck` must validate `management/pm/state/pm-console-stale-history.json` before publication/quality gates.
- `environmentPolicyDefaultsLint` must enforce env var default/policy consistency.
- `pmEnvironmentDashboardReport` must publish `management/pm/reports/environment-dashboard.json` for compact triage status.
- `enforceProjectBoundaries` is mandatory in `qualityGate` and must fail when `afp-api`, `afp-engine`, or `afp-cli` reference project-management tooling/state (`pm-tools`, governance/project tracker sources, or management SQLite paths).
- `enforceManagedTooling` is mandatory in `qualityGate` and must fail when project-management tooling files under `management/tools/` or `management/pm-management/tools/src/main/java/solutions/pointzero/symphony/management/pm/tools` are untracked.
- `enforcePmApplicationRealmSeparation` is mandatory in `qualityGate` and must fail when PM artifacts are written under `product/afp-converter/preview/` or PM databases are written outside `management/pm/state/`.
- `enforceBoilerplateRollupForFrameworkChanges` is mandatory in `qualityGate` and must fail when framework/process/policy/build mutations occur without an accompanying update package under `product/boilerplate/update-packages/pz-boilerplate-intelliJ/`.
- `enforceNamespaceRoot` is mandatory in `qualityGate` and must fail when active source/build/policy scope includes legacy root namespaces (`com.upland.connect`).
- Root namespace standard:
  - All first-party Java package stubs must start with `solutions.pointzero.symphony`.
- `documentationManifest` must include current management/docs/changelog/plan artifacts.
- `projectPlanNextStep` must reflect the current immediate plan instruction.
- `stateInventoryCsv` must export project/boilerplate file inventories from SQLite state.
- `policyRulesReport` must export SQL-backed policy rules from SQLite state into `management/pm/reports/policy-rules.json`.
- `pmWorkflowRun` and related Gradle interface tasks must resolve PM workflow phases from `management/pm/workflow/workflow-manifest.json` instead of duplicating orchestration logic.
- `projectPlanWorkbook` updates `management/docs/project-plan-progress.xlsx` via Apache POI updater by default.
- `policyGovernanceWorkbook` updates `management/docs/policy-governance-events.xlsx` from `management/docs/policy-governance-events.csv` and should be used to track policy/governance execution events.
- `projectStateDb` is the default project-management state sync path and must populate `management/pm/state/project-state.sqlite` from:
  - `management/docs/issues-log.csv`
  - `management/docs/project-plan-progress.csv`
  - `product/afp-converter/preview/fidelity-report.json`
- `boilerplateStateDb` is the default boilerplate-governance state sync path and must populate `management/pm/state/boilerplate-state.sqlite` from:
  - `product/boilerplate/update-packages/pz-boilerplate-intelliJ`
- State separation rule:
  - project execution/reporting state belongs only in `project-state.sqlite`,
  - boilerplate sync/package governance state belongs only in `boilerplate-state.sqlite`.
- Database-backed tooling rule:
  - prefer SQLite-backed `pm-tools` tasks over ad-hoc scripts for project/workflow automation outputs.
- `projectPlanWorkbook` must be change-driven:
  - run when `management/docs/project-plan-progress.csv` is newer than workbook or workbook is missing,
  - skip when workbook is newer (to preserve human cosmetic updates),
  - allow explicit override with `AFP_FORCE_WORKBOOK_UPDATE=true`.
- Set `AFP_WORKBOOK_MODE=excel` for Excel-native scripting path using `management/pm/reports/project-plan-progress-data.json` + `management/tools/WorkbookUpdater.bas`.
- For non-Excel/headless environments, set `AFP_WORKBOOK_MODE=xml` to use guarded XML fallback.
- Workbook updates must remain cell-level on managed sheets and must not rewrite non-managed sheets.
- Excel-native refresh must be timeout-bounded and must emit `management/pm/reports/workbook-refresh-status.json`.
- XML fallback writes to `management/docs/project-plan-progress.xlsx` are **disabled by default** for corruption control; only run XML fallback with explicit human instruction for that specific operation.
- On any workbook-corruption signal, apply restore-first protocol:
  - restore `management/docs/project-plan-progress.xlsx` from `management/docs/project-plan-progress.xlsx.zip`,
  - validate archive integrity,
  - log the event in `management/docs/issues-log.csv`,
  - then continue with Excel-native or POI-managed update paths only.
- `issuesLogTickle` enforces issue-log hygiene: if files referenced by `management/docs/issues-log.csv` change, the issues log must be updated in the same change.
- `governanceAlerts` generates `management/pm/reports/governance-alerts.json` from SQLite governance state and should be reviewed for active governance-breach visibility.
- `projectPlanProgressJson`, `issuesLogTickle`, and `issuesEffectivenessReport` should execute from SQLite-backed state generated by `projectStateDb`.
- `boilerplateSyncWorkbook` maintains `management/docs/boilerplate-sync-candidates.xlsx` from `product/boilerplate/update-packages/pz-boilerplate-intelliJ` and must preserve manual triage columns (`decision`, `state`, `owner_notes`).
- `issuesEffectivenessReport` provides mechanized debug reasoning signals from:
  - issue triggers (`management/pm/reports/issues-log-tickle.json`)
  - task progress (`management/docs/project-plan-progress.csv`)
  - fidelity metrics (`product/afp-converter/preview/fidelity-report.json`)
  and writes `management/pm/reports/issues-effectiveness.json`.
- Pitfall guardrail:
  - Issues-log coupling must guide troubleshooting, not block urgent fixes; apply issue-linked context first, but do not force unrelated updates to closed/stable incidents.
  - Do not suppress "known imperfection" logging due to low severity; low-severity issues still must be captured to prevent repeated rediscovery.
- External update package workflow:
  - For frozen/external sibling repos, store transfer bundles under `product/boilerplate/update-packages/<target-repo>/<package-name>/`.
  - If the required package folder does not exist, create a new one (do not block on missing folder state).
  - When a shared process/policy/build workflow change should be mirrored to `pz-boilerplate-intelliJ`, create or update the corresponding transfer package in the same working turn before handoff.
  - When multiple candidates are approved for promotion in the same cycle, consolidate them into one outbound package and mark earlier package folders as superseded in the consolidated package manifest.
  - After the target repo has merged the package, the package folder may be removed by a human; future updates should generate a fresh package.
  - If any boilerplate sync candidate remains in `review` state, include that outstanding item list in user-facing status/closure responses.

## Plan-Driven Execution Policy
- Work should follow `management/docs/project-plan.md`.
- After completing a planned step:
  - update `management/docs/project-plan.md` immediate next step,
  - update `management/docs/project-plan-progress.csv`,
  - update `management/docs/project-plan-progress.xlsx` via `projectPlanWorkbook`,
  - append `SESSION_CHANGELOG.md`,
  - regenerate manifests.
- For project-level workflow/process updates, also record:
  - checkpoint label used before change,
  - validation commands run,
  - rollback command that would be used if reversion is required.

## Response Closure Policy
- Every user-facing response must end with a `Todo List` section.
- The `Todo List` section must reflect the current actionable backlog, including `none` when no tasks remain.
- Do not omit this section, even for brief status/checkpoint replies.

## Fidelity and Regression Discipline
- Primary KPI file: `product/afp-converter/preview/fidelity-report.json`.
- Quality gate enforces minimum thresholds; do not relax without explicit direction.
- If metrics do not improve, keep diagnostics-rich changes and move to the next highest-signal plan step.

## Artifact Expectations
After a normal cycle, these should be current:
- `product/afp-converter/preview/afp-output.pdf`
- `product/afp-converter/preview/afp-output.html`
- `product/afp-converter/preview/afp-meta.json`
- `product/afp-converter/preview/afp-diag.json`
- `product/afp-converter/preview/fidelity-report.json`
- `management/pm/reports/project-plan-next-step.json`
- `management/pm/reports/documentation-manifest.json`
- `product/afp-converter/preview/ci-artifacts/latest/*`

## Environment Metadata Change Policy
If environment/process metadata changes (new task, new file, renamed plan path, new gate, new artifact):
1. Update this `AI-POLICY.md`.
2. Update `README.md` if user-facing workflow changed.
3. Update `SESSION_CHANGELOG.md`.
4. Ensure build tasks/manifests include the new metadata source.

## Agent Handover Prompt (for a fresh AI)
If you are a new agent on this repo:
- Read: `AI-POLICY.md`, `management/docs/project-plan.md`, `management/docs/project-plan-progress.csv`, `SESSION_CHANGELOG.md`.
- Continue from `management/pm/reports/project-plan-next-step.json`.
- Maintain strict native rendering defaults.
- Keep edits non-destructive, especially workbook/history updates.
- Run `./gradlew qualityGate` before concluding substantive work.
- Update plan/progress/changelog/manifests as part of completion.

## Conversation Resumption Prompt
Use this prompt verbatim when chat history is missing and you must resume this exact workflow:

`Resume the workbook JSON/VBA flow for project-plan tracking. Treat AI-POLICY.md as the operating contract. Rebuild management/pm/reports/project-plan-progress-data.json from management/docs/project-plan-progress.csv, then continue with management/tools/WorkbookUpdater.bas as the Excel-native update path. Do not use low-level XLSX chart/drawing XML mutation. Preserve user filters/sort and avoid touching non-managed sheets. Before changing implementation, review SESSION_CHANGELOG.md and management/docs/project-plan.md for the last checkpoint and pending follow-up to review restored workbook content against CSV/JSON sources.`
