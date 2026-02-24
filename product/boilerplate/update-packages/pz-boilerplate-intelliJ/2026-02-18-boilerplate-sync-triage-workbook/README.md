# Update Package: Boilerplate Sync Triage Workbook Framework

## Purpose
Add the boilerplate-sync triage framework to `pz-boilerplate-intelliJ` so update candidates can be reviewed and directed via spreadsheet.

## Scope
- Add generator: `BoilerplateSyncWorkbookUpdater` (POI-based).
- Add Gradle task: `boilerplateSyncWorkbook` (change-driven, force override).
- Wire workbook into documentation manifest + artifact publishing.
- Update policy/docs for the new workbook.

## Target Files
- `afp-management/tools/src/main/java/solutions/pointzero/symphony/afp/management/tools/BoilerplateSyncWorkbookUpdater.java`
- `build.gradle`
- `AI-POLICY.md`
- `README.md`
- (recommended log update) `SESSION_CHANGELOG.md` or `management/docs/process-evolution-log.md`
