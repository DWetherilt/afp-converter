# Policy/README snippets

## AI-POLICY.md additions
- Source of truth files:
  - `management/docs/boilerplate-sync-candidates.xlsx`
- Build tasks section:
  - `boilerplateSyncWorkbook` maintains this workbook from `product/boilerplate/update-packages/pz-boilerplate-intelliJ`
  - must preserve manual triage columns (`decision`, `state`, `owner_notes`)

## README.md additions
- Document `management/docs/boilerplate-sync-candidates.xlsx`:
  - purpose: review potential boilerplate merges
  - task: `boilerplateSyncWorkbook`
  - force env: `AFP_FORCE_BOILERPLATE_SYNC_UPDATE=true`
