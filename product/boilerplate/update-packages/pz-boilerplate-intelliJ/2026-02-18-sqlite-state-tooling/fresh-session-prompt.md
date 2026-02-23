Apply package `2026-02-18-sqlite-state-tooling` into `pz-boilerplate-intelliJ`.

Required outcomes:
1. Add SQLite-backed state tooling class (`StateDatabaseTool`) under `boilerplate-tools`.
2. Split state into `product/afp-converter/preview/state/project-state.sqlite` and `product/afp-converter/preview/state/boilerplate-state.sqlite`.
3. Rewire project-management Gradle tasks to use the SQLite tooling by default.
4. Update `AI-POLICY.md` and `README.md` to codify database-first workflow and state separation.
5. Validate with:
   - `./gradlew :boilerplate-tools:compileJava`
   - `./gradlew documentationManifest`
