# AI Policy

## Purpose
This file defines the operating policy for AI agents working in this repository so development remains consistent over long dormant periods.

Use this file as the first-read operational contract before making changes.

## Policy Governance
- Changes to `AI-POLICY.md` require explicit human approval.
- AI agents must not autonomously redefine, relax, or remove policy requirements in this file.
- If a policy update is needed, propose the change and wait for human confirmation before applying it.

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
- Issues log: `docs/issues-log.csv`
- Issues log template: `docs/issues-log-template.csv`
- Progress JSON intermediary: `preview/project-plan-progress-data.json`
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
- `qualityGate` is the canonical readiness command.
- `documentationManifest` must include current docs/changelog/plan artifacts.
- `projectPlanNextStep` must reflect the current immediate plan instruction.
- `projectPlanWorkbook` updates `docs/project-plan-progress.xlsx` via Apache POI updater by default.
- Set `AFP_WORKBOOK_MODE=excel` for Excel-native scripting path using `preview/project-plan-progress-data.json` + `tools/WorkbookUpdater.bas`.
- For non-Excel/headless environments, set `AFP_WORKBOOK_MODE=xml` to use guarded XML fallback.
- Workbook updates must remain cell-level on managed sheets and must not rewrite non-managed sheets.
- Excel-native refresh may auto-fallback to guarded XML update when required managed sheets are missing after macro execution.
- Excel-native refresh must be timeout-bounded; if Excel automation hangs/fails, use guarded XML fallback and emit `preview/workbook-refresh-status.json`.
- `issuesLogTickle` enforces issue-log hygiene: if files referenced by `docs/issues-log.csv` change, the issues log must be updated in the same change.
- `issuesEffectivenessReport` provides mechanized debug reasoning signals from:
  - issue triggers (`preview/issues-log-tickle.json`)
  - task progress (`docs/project-plan-progress.csv`)
  - fidelity metrics (`preview/fidelity-report.json`)
  and writes `preview/issues-effectiveness.json`.
- Pitfall guardrail:
  - Issues-log coupling must guide troubleshooting, not block urgent fixes; apply issue-linked context first, but do not force unrelated updates to closed/stable incidents.
  - Do not suppress "known imperfection" logging due to low severity; low-severity issues still must be captured to prevent repeated rediscovery.

## Plan-Driven Execution Policy
- Work should follow `docs/project-plan.md`.
- After completing a planned step:
  - update `docs/project-plan.md` immediate next step,
  - update `docs/project-plan-progress.csv`,
  - update `docs/project-plan-progress.xlsx` via `projectPlanWorkbook`,
  - append `SESSION_CHANGELOG.md`,
  - regenerate manifests.

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
