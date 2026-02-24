# SQLite State Tooling Sync Package

## Purpose
Promote the SQLite-backed workflow state model introduced in `afp-converter` into `pz-boilerplate-intelliJ` so project-management automation defaults to database-backed tooling instead of ad-hoc scripts.

## Scope
- Add split state databases:
  - `product/afp-converter/preview/state/project-state.sqlite`
  - `product/afp-converter/preview/state/boilerplate-state.sqlite`
- Add Java tooling class for state sync/export:
  - `boilerplate-management/tools/src/main/java/com/example/product/boilerplate/management/tools/StateDatabaseTool.java`
- Wire Gradle tasks:
  - `projectStateDb`
  - `boilerplateStateDb`
  - database-backed `projectPlanProgressJson`, `issuesLogTickle`, `issuesEffectivenessReport`
- Update policy/docs to codify state separation and database-first workflow automation.

## Contents
- apply-checklist.md
- fresh-session-prompt.md
- package-manifest.json
- patches/
