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

## Source of Truth Files
- Project plan: `docs/project-plan.md`
- Plan progress tracker (editable by humans): `docs/project-plan-progress.xlsx`
- Progress source table: `docs/project-plan-progress.csv`
- Session history: `SESSION_CHANGELOG.md`
- API behavior contract: `docs/API.md`

## Non-Destructive Editing Rules
- Never overwrite or reset unrelated local changes.
- Treat `docs/project-plan-progress.xlsx` as human-owned and user-editable.
- Workbook updates must preserve user filters/sort/layout.
- `projectPlanWorkbook` may update `docs/project-plan-progress.xlsx` only via the guarded updater script.
- If `docs/project-plan-progress.xlsx.zip` exists, use it as a filter/sort template fallback when target filter nodes are missing.
- Never force-template overwrite filter/sort nodes when target nodes already exist unless explicitly requested by a human.
- Any workbook sheets beyond `Current Progress` and `Status History` must remain untouched unless a human explicitly requests updates to those sheets.
- Avoid destructive git/file operations unless explicitly requested.

## Default Rendering and Behavior
- Strict native renderer is the default for PDF output.
- HTML renderer is retained for fallback and future usage.
- Diagnostics and metadata are first-class outputs and must be kept in sync with renderer behavior.

## Standard Development Cycle
When implementing any meaningful change:
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
- `qualityGate` is the canonical readiness command.
- `documentationManifest` must include current docs/changelog/plan artifacts.
- `projectPlanNextStep` must reflect the current immediate plan instruction.
- `projectPlanWorkbook` updates `docs/project-plan-progress.xlsx` via guarded updater preserving existing filter/sort nodes.

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
