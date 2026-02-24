# afp-converter

Proof-of-concept Java 21 Gradle multi-module project for:

- AFP input
- text-based PDF output where possible
- JSON metadata + diagnostics output

## Modules

- `afp-api`: stable public API (`solutions.pointzero.symphony.afp.api`)
- `afp-engine`: converter implementation + adapter SPI (`solutions.pointzero.symphony.afp.engine`)
- `afp-cli`: thin Picocli wrapper command `afp2pdf` (`solutions.pointzero.symphony.afp.cli`)
- `pm-tools`: utility tooling including Apache POI workbook updater (`solutions.pointzero.symphony.pm.tools`)
- `pm-console`: dev-only PM dashboard/command hub (`solutions.pointzero.symphony.pm.console`)
- Root namespace standard for first-party Java code: `solutions.pointzero.symphony.*`

## Core Derivative Topology

- Base environment arbiter: `pzAiCore`
- This repo (`afp-converter`) is a derivative project under the core Product realm.
- Internal project realms:
  - `application`
  - `pm`
  - `boilerplate`
- Project reasoning databases should be used with core reasoning state for escalations and arbitration lineage.
- Machine-readable topology contract: `management/pm/workflow/core-derivative-topology.json`
- Bootstrap helper for new derivatives: `management/tools/bootstrap_core_derivative_project.sh`

API documentation:

- `VERSION`:
  - canonical project semantic version used by Gradle for all modules
- `AI-POLICY.md` (agent operating policy and development-cycle rules)
- `management/docs/CORE_DERIVATIVE_MODEL.md`:
  - formal definition of the `pzAiCore` base arbiter model and derivative-project hierarchy
- `management/docs/API.md`
- `management/docs/project-plan.md`
- `management/docs/project-plan-progress.csv` (task-level completion tracker)
  - completed rows explicitly marked as GitHub committed in `notes` (`[github:committed]`) are excluded from generated progress reports
- `management/docs/boilerplate-sync-candidates.xlsx`:
  - summary workbook for potential boilerplate merges from `product/boilerplate/update-packages/pz-boilerplate-intelliJ`
  - generated/updated by `boilerplateSyncWorkbook`
  - preserves manual triage columns (`decision`, `state`, `owner_notes`) across refreshes
  - set `AFP_FORCE_BOILERPLATE_SYNC_UPDATE=true` to force refresh
- `management/docs/policy-governance-events.csv`:
  - source table for policy/process governance events (checkpoints, sequencing corrections, compliance notes)
- `management/docs/policy-governance-events.xlsx`:
  - spreadsheet tracker generated from `management/docs/policy-governance-events.csv` by `policyGovernanceWorkbook`
  - set `AFP_FORCE_GOVERNANCE_WORKBOOK_UPDATE=true` to force refresh
- `management/docs/project-plan-progress.xlsx`:
  - updated by `projectPlanWorkbook` using Apache POI by default (managed-sheet cell-level updates)
  - update is change-driven: task runs when CSV is newer than workbook (or workbook missing), and skips when workbook is newer to preserve cosmetic/user edits
  - set `AFP_FORCE_WORKBOOK_UPDATE=true` to force a workbook refresh
  - set `AFP_WORKBOOK_MODE=excel` for Excel-native scripting path
  - set `AFP_WORKBOOK_MODE=xml` to use the guarded XML updater fallback
  - XML fallback can use `management/docs/project-plan-progress.xlsx.zip` to restore filter nodes when missing
  - `Current Progress` sheet mirrors current task tracker (including `priority`)
  - `Status History` sheet logs update timestamp + previous/new status, percent, and priority for task changes
  - `Completion Trend` sheet tracks per-task completion estimate changes by iteration and includes formula-driven graph columns for stable workbook compatibility
  - `Progress Graph` sheet provides per-iteration line-graph data plus task-level trend-line summaries
- `management/docs/issues-log.csv`:
  - issues register for cross-cutting incidents and known workflow hazards
  - each issue lists referenced components that should trigger issue-log review when modified
- `management/docs/issues-log-template.csv`:
  - template row for consistently logging new issues and component bindings
- `management/pm/reports/project-plan-progress-data.json`:
  - JSON intermediary exported from SQLite project state for workbook automation workflows
- `management/pm/reports/project-plan-progress-application.json`:
  - realm-filtered project-plan report for `application`
- `management/pm/reports/project-plan-progress-pm.json`:
  - realm-filtered project-plan report for `pm`
- `management/pm/reports/project-plan-progress-boilerplate.json`:
  - realm-filtered project-plan report for `boilerplate`
- `management/pm/reports/workstream-presentation-index.json`:
  - active workstream presentation aliases (`Workstream A..Z`) mapped to persistent internal `workstream_uid` values
  - generated from active rows only (status not in `complete/completed/done`)
- `management/pm/workflow/workstream-realm-map.json`:
  - source mapping used to assign `workstream_uid` values to realm-specific project-plan reports
- `management/pm/state/project-state.sqlite`:
  - SQLite state store for project execution/reporting workflows (issues, plan progress, fidelity signals)
- `management/pm/state/boilerplate-state.sqlite`:
  - SQLite state store for boilerplate governance/sync workflows (package candidates and file inventory)
- `management/pm/policy/policy-rule-tables.sql`:
  - SQL source of truth for mutable operational PM rule tables
- `management/pm/workflow/workflow-manifest.json`:
  - toolchain-neutral PM workflow phase contract
  - adapters (for example Gradle) map phases to concrete execution tasks
- `management/pm/workflow/core-derivative-topology.json`:
  - machine-readable core/project hierarchy contract (`pzAiCore` -> `afp-converter`)
  - defines internal project realms and core-link reference fields used on escalations
- `management/pm/reports/policy-rules.json`:
  - generated export of SQL-backed operational PM rules from `management/pm/state/project-state.sqlite`
- `management/pm/reports/governance-alerts.json`:
  - generated governance/trust alert report sourced from `management/pm/state/project-state.sqlite`
  - includes active governance-breach visibility and trust-event tracking for handoff/status reporting
- Presentation-facing PM reports now include human-friendly references alongside internal IDs:
  - `actionRef`, `decisionRef`, `knowledgeRef`, `event_ref`, `releaseRef`
  - reference format: `<What> | <YYYY-MM-DD> | #<occurrence>`
  - internal token IDs (`actionId`, `decisionId`, `knowledgeId`, `event_id`) remain authoritative for storage/joins
- `management/pm/reports/presentation-ref-lint.json`:
  - generated lint output that verifies presentation reference presence/format across key PM reports
  - enforced by `presentationRefLint` and included in `qualityGate`
- `management/pm/reports/version-control-ledger.json`:
  - generated VCS/file-discipline ledger sourced from `management/pm/state/project-state.sqlite`
- `management/pm/reports/identifier-registry.json`:
  - generated cross-discipline identifier ledger sourced from `identifier_registry` in `management/pm/state/project-state.sqlite`
  - persists identifiers from project-management, policy-management, code, and version-control workflows
- `management/pm/reports/repo-file-inventory-with-context.csv`:
  - generated project file inventory with DB provenance/maintenance context sourced from `management/pm/state/project-state.sqlite`
- `management/pm/reports/boilerplate-package-files-with-context.csv`:
  - generated boilerplate package-file inventory with DB provenance/maintenance context sourced from `management/pm/state/boilerplate-state.sqlite`
  - mirrors Git `HEAD`/branch/dirty state and per-path tracked/status/hash metadata for audit workflows
  - Git remains the canonical version-control source
- `management/pm/reports/workbook-refresh-status.json`:
  - records whether workbook refresh used Excel-native path or XML fallback, including Excel error details if fallback was required
- `management/pm/reports/issues-log-tickle.json`:
  - generated by `issuesLogTickle` and indicates whether changed files touched issue-referenced components
  - build enforcement requires `management/docs/issues-log.csv` to be updated when referenced components are modified
- `management/pm/reports/issues-effectiveness.json`:
  - generated mechanized signal report combining issue triggers, plan progress, and fidelity metrics for debug reasoning
- `management/tools/WorkbookUpdater.bas`:
   - VBA module for Excel that refreshes `Current Progress`, appends `Status History`, and rebuilds `Completion Trend` from the JSON intermediary
- `management/tools/bootstrap_core_derivative_project.sh`:
  - bootstrap helper for instantiating new derivative projects from an `pzAiCore` source path
- `management/tools/run_excel_workbook_refresh.py` + `management/tools/run_excel_workbook_refresh.applescript`:
  - native Excel runner used by `projectPlanWorkbook` when `AFP_WORKBOOK_MODE=excel`
  - if required managed sheets are missing after Excel macro execution, auto-falls back to guarded XML updater to keep workbook structure complete
  - set `AFP_EXCEL_REQUIRED=true` to fail instead of fallback when Excel automation does not succeed

## Prerequisites

- Java 21 (Windows, macOS, or Linux)
- Gradle Wrapper (already included)

Quick check:

```bash
java -version
./gradlew --version
```

Both should report Java 21 for this repo.

Windows equivalents:

```powershell
java -version
.\gradlew.bat --version
```

## Build and Test

From repo root:

```bash
./gradlew clean test
```

Windows:

```powershell
.\gradlew.bat clean test
```

Build with preview-manifest regeneration:

```bash
./gradlew build
```

This now includes `previewManifest` and regenerates:
- `product/afp-converter/preview/afp-output.pdf`
- `product/afp-converter/preview/afp-output.html`
- `product/afp-converter/preview/afp-meta.json`
- `product/afp-converter/preview/afp-diag.json`
- `product/afp-converter/sampleOutput/*.pdf|*.html|*.meta.json|*.diag.json` for every `*.afp` in `product/afp-converter/sampleData/`
- `management/pm/reports/documentation-manifest.json` (via `documentationManifest`)

Run the full quality gate (tests + fidelity thresholds + management/docs/changelog manifest):

```bash
./gradlew qualityGate
```

`qualityGate` includes `enforceProjectBoundaries`, which fails if product modules (`afp-api`, `afp-engine`, `afp-cli`) reference project-management tooling/state.
`qualityGate` also includes `enforceManagedTooling`, which fails if project-management tooling files under `management/tools/` or `management/pm-management/tools/.../tools` are untracked.
`qualityGate` includes `enforcePmApplicationRealmSeparation`, which fails if PM artifacts appear under `product/afp-converter/preview/` (including `preview/ci-artifacts`) or PM databases appear outside `management/pm/state/`.
`qualityGate` includes `enforceBoilerplateRollupForFrameworkChanges`, which fails if framework/process files change without a same-change update under `product/boilerplate/update-packages/pz-boilerplate-intelliJ/`.
`qualityGate` includes `enforceNamespaceRoot`, which fails if active source/build/policy scope still references legacy namespace roots (`com.upland.connect`).
`documentationManifest` now runs `stateInventoryCsv`, which rebuilds the two inventory CSVs above from SQLite state on each run.
`documentationManifest` also runs `policyRulesReport`, which exports SQL-backed PM rule tables into `management/pm/reports/policy-rules.json`.
`publishCiArtifacts` now emits realm-ringfenced artifact indices only under `management/pm/reports/realm-artifacts/{application,pm,boilerplate}/latest/index.json` plus parent `management/pm/reports/realm-artifacts/index.json`.

Production build (no test compilation/execution; assumes working runtime/resources):

```bash
./gradlew prodBuild
```

Development PM console:

```bash
./gradlew pmConsoleStatus
./gradlew pmDevAttach
./gradlew :pm-console:run --args="tools"
./gradlew :pm-console:run --args="refresh --with-preview"
./gradlew pmConsoleInstallPath
./management/pm-console/build/install/pmconsole/bin/pmconsole status
```

Normalized PM workflow interface:

```bash
./gradlew pmWorkflowList
./gradlew pmWorkflowRun
./gradlew pmWorkflowRun -PpmPhase=execute_application_with_pm_console
python3 management/tools/pm_workflow.py list --adapter gradle
python3 management/tools/pm_workflow.py run --adapter gradle --phase pm_refresh_and_reports
```

Default execution behavior:
- Unless explicitly overridden, app execution is coupled with PM console status in the same run flow (app first, PM status second).
- Use `./gradlew pmDevAttach` as the default development execution entrypoint.

Run preview generation only:

```bash
./gradlew previewManifest
```

Run preview generation plus documentation/changelog manifest update:

```bash
./gradlew documentationManifest
```

Build/refresh SQLite state stores directly:

```bash
./gradlew projectStateDb boilerplateStateDb
```

## Versioning and Rollback

Current version:

```bash
./gradlew -q versionInfo
```

Bump version:

```bash
VERSION_PART=patch ./gradlew versionBump
VERSION_PART=minor ./gradlew versionBump
VERSION_PART=prerelease VERSION_PREID=beta ./gradlew versionBump
```

Create release-grade checkpoint:

```bash
CHECKPOINT_LABEL="before-font-work" ./gradlew createRollbackCheckpoint
```

List checkpoints:

```bash
./gradlew listRollbackCheckpoints
```

Rollback dry-run:

```bash
CHECKPOINT_ID=<checkpoint-id> ./gradlew rollbackCheckpoint
```

Apply rollback:

```bash
CHECKPOINT_ID=<checkpoint-id> ROLLBACK_APPLY=true ./gradlew rollbackCheckpoint
```

One-shot release snapshot (gate + checkpoint):

```bash
CHECKPOINT_LABEL="release-candidate" ./gradlew releaseSnapshot
```

Rollback expectation for project-file writes:
- All project-file mutations are expected to be reversible via git history and/or rollback checkpoints.
- For high-risk mutation sets, create checkpoint first.
- If rollback is requested:
```bash
./gradlew listRollbackCheckpoints
CHECKPOINT_ID=<checkpoint-id> ./gradlew rollbackCheckpoint
CHECKPOINT_ID=<checkpoint-id> ROLLBACK_APPLY=true ./gradlew rollbackCheckpoint
```

Project/process mutation safety flow (policy/build/workbook/gates):

```bash
# 1) pre-change checkpoint
CHECKPOINT_LABEL="process-change-<short-name>" ./gradlew createRollbackCheckpoint

# 2) apply changes, then validate
./gradlew documentationManifest

# 3) verify rollback availability
./gradlew listRollbackCheckpoints
```

Generate PDF fidelity comparison report against `product/afp-converter/sampleOutput/sample.pdf`:

```bash
./gradlew :afp-engine:fidelityReport
```

This writes:
- `product/afp-converter/preview/fidelity-report.json`

## Run the CLI

Main command:

```bash
./gradlew :afp-cli:run --args="--help"
```

Example conversion call (default in-process engine):

```bash
./gradlew :afp-cli:run --args="\
  --in /path/to/input.afp \
  --out /path/to/output.pdf \
  --meta /path/to/meta.json \
  --diag /path/to/diag.json \
  --html /path/to/output.html \
  --metadataMode strict \
  --timeoutSeconds 120 \
  --textFallback SUBSTITUTE"
```

Optional repeatable resources:

```bash
--resources /path/to/resourcesA --resources /path/to/resourcesB
```

Optional external engine mode:

```bash
./gradlew :afp-cli:run --args="\
  --engineMode CLI \
  --engineExe /path/to/poc-engine \
  --in /path/to/input.afp \
  --out /path/to/output.pdf \
  --meta /path/to/meta.json \
  --diag /path/to/diag.json"
```

## Exit Codes (`afp2pdf`)

- `0` success
- `2` conversion failed
- `3` timeout or cancelled
- `4` invalid args or I/O/runtime failure

## Notes

- In-process mode is the primary/default engine path.
- In-process conversion performs structured-field parsing and semantic text extraction (including AFPLib-assisted interpretation).
- PDF output now defaults to a native AFP-oriented PDFBox renderer, with HTML renderer fallback if native rendering fails.
- Native renderer now applies coded-font (`CFI`) hints for font family/style and point size where available.
- `--html` persists the semantic HTML view from `AfpHtmlRenderer` for debugging/inspection.
- Metadata style output mode is configurable via `--metadataMode inferred|strict`:
  - `strict` (default): preserve conservative style metadata without inferred weight/ratios.
  - `inferred`: include inferred style hints/ratios.
- External engine execution (`--engineMode CLI`) is optional compatibility mode.
- Current output quality is best-effort and document-layout fidelity is still a work in progress.
- Engine tests include:
  - fingerprint determinism
  - cache invalidation when input changes
  - cache invalidation when options change
  - temp workspace cleanup behavior

## Developer Quickstart (Primary In-Proc Path)

Quick end-to-end run (macOS/Linux):

```bash
mkdir -p .tmp
printf 'FAKE_AFP_CONTENT\n' > .tmp/sample.afp

./gradlew :afp-cli:run --args="\
  --in .tmp/sample.afp \
  --out .tmp/output.pdf \
  --meta .tmp/meta.json \
  --diag .tmp/diag.json \
  --cacheDir .tmp/cache \
  --tempDir .tmp/temp \
  --timeoutSeconds 30 \
  --textFallback SUBSTITUTE"
```

Inspect outputs:

```bash
ls -lh .tmp/output.pdf .tmp/meta.json .tmp/diag.json
cat .tmp/meta.json
cat .tmp/diag.json
```

### Windows Quickstart (PowerShell, default in-process)

```powershell
New-Item -ItemType Directory -Force .tmp | Out-Null
Set-Content -Path .tmp/sample.afp -Value "FAKE_AFP_CONTENT"

.\gradlew.bat :afp-cli:run --args="--in .tmp/sample.afp --out .tmp/output.pdf --meta .tmp/meta.json --diag .tmp/diag.json --cacheDir .tmp/cache --tempDir .tmp/temp --timeoutSeconds 30 --textFallback SUBSTITUTE"

Get-Item .tmp/output.pdf, .tmp/meta.json, .tmp/diag.json | Format-Table Name,Length
Get-Content .tmp/meta.json
Get-Content .tmp/diag.json
```

## External Mode Demo (Optional)

The following fake backend executables are only for exercising `--engineMode CLI`:

- `management/tools/fake-afp-engine.sh`
- `management/tools/fake-afp-engine.ps1`

They accept the same CLI adapter arguments and emit:

- `output.pdf` (tiny text-based PDF)
- `meta.json`
- `diag.json`

Windows external-engine mode using packaged fake backend:

```powershell
$engineExe = Join-Path $PWD "management/tools/fake-afp-engine.ps1"
.\gradlew.bat :afp-cli:run --args="--engineMode CLI --engineExe $engineExe --in .tmp/sample.afp --out .tmp/output.pdf --meta .tmp/meta.json --diag .tmp/diag.json --cacheDir .tmp/cache --tempDir .tmp/temp --timeoutSeconds 30"
```

## Deployment Package

Build the deployable ZIP:

```bash
./gradlew :afp-cli:distZip
```

```powershell
.\gradlew.bat :afp-cli:distZip
```

Artifact path:

- `afp-cli/build/distributions/afp2pdf-0.2.0-beta.1.zip`

## Sandbox Run

After copying `afp2pdf-0.2.0-beta.1.zip` to a sandbox host:

macOS/Linux:

```bash
unzip afp2pdf-0.2.0-beta.1.zip
cd afp2pdf-0.2.0-beta.1

mkdir -p .tmp
printf 'FAKE_AFP_CONTENT\n' > .tmp/sample.afp

bin/afp2pdf \
  --in .tmp/sample.afp \
  --out .tmp/output.pdf \
  --meta .tmp/meta.json \
  --diag .tmp/diag.json \
  --cacheDir .tmp/cache \
  --tempDir .tmp/temp \
  --timeoutSeconds 30 \
  --textFallback SUBSTITUTE
```

Windows (PowerShell):

```powershell
Expand-Archive .\afp2pdf-0.2.0-beta.1.zip -DestinationPath .
Set-Location .\afp2pdf-0.2.0-beta.1

New-Item -ItemType Directory -Force .tmp | Out-Null
Set-Content -Path .tmp/sample.afp -Value "FAKE_AFP_CONTENT"

.\bin\afp2pdf.bat --in .tmp/sample.afp --out .tmp/output.pdf --meta .tmp/meta.json --diag .tmp/diag.json --cacheDir .tmp/cache --tempDir .tmp/temp --timeoutSeconds 30 --textFallback SUBSTITUTE
```
