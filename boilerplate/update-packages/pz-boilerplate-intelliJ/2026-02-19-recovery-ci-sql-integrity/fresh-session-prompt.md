Apply package `2026-02-19-recovery-ci-sql-integrity` to align the target boilerplate with SQL-first recovery governance.

Required outcomes:
- CI runs binary restore + SQL materialization + drift verify + drift summary enforce + critical DB hash verify before Gradle build.
- Recovery tooling exists (`recovery_doctor`, `restore_binary_artifacts`, `sql_drift_summary`, hook installer, checkpoint restore, SQL upsert helper).
- Policy SQL rules include CI SQL-integrity governance entries.
- SQL-first workflow docs include recovery doctor and helper usage.
