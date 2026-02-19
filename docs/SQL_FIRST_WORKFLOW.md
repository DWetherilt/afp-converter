# SQL-First Code Workflow

This project supports SQL-first source management across three realms:

- `application` -> `pm/state/application-realm.sqlite`
- `pm` -> `pm/state/pm-realm.sqlite`
- `boilerplate` -> `pm/state/boilerplate-realm.sqlite`

Managed roots are defined in:

- `pm/workflow/realm-managed-manifest.json`

## First Principles

- Code changes for managed files should be applied to SQL first.
- Filesystem endpoints are materialized from realm DBs before build.
- Drift checks fail in strict mode when managed filesystem files differ from SQL.

## Core Commands

Use helper:

```bash
tools/sql_code_workflow.sh upsert <realm> <endpoint> <source-file>
tools/sql_code_workflow.sh upsert-manifest <realm> <manifest-json>
tools/sql_code_workflow.sh export <realm>
tools/sql_code_workflow.sh verify <realm>
tools/sql_code_workflow.sh search <realm|all> <keyword>
```

Or Gradle:

```bash
./gradlew realmCodeDatabases
./gradlew sqlCoverageReport
./gradlew sqlReconstructionCheck
./gradlew realmSourcesExport
./gradlew realmTokenSearch -Prealm=application -Pkeyword=render
./gradlew crossRealmTokenSearch -Pkeyword=token
```

## Build Integration

- `build` and `prodBuild` run `realmSourcesExport` first.
- `realmSourcesExport` runs:
  - rollback checkpoint hook (`sqlMaterializationCheckpoint`)
  - drift guard (`sqlAuthorityDriftCheck`)
  - per-realm materialization tasks
- `qualityGate` runs `sqlReconstructionCheck`.

Strict mode is controlled by:

- `AFP_SQL_AUTH_STRICT=true|false` (default true)

## Multi-file SQL Patch Manifest

Example manifest for `upsert-code-files-manifest`:

```json
{
  "files": [
    { "endpoint": "pm/example/A.java", "source": "/tmp/A.java" },
    { "endpoint": "pm/example/B.java", "source": "/tmp/B.java" }
  ]
}
```

Apply:

```bash
tools/sql_code_workflow.sh upsert-manifest pm /tmp/pm-patch.json
tools/sql_code_workflow.sh export pm
```

## Minimal Snapshot And Recovery

Use this when you want the smallest possible project footprint and later full regeneration from SQL realm state.

### Snapshot to Bare Minimum

```bash
tools/minimal_snapshot.sh
```

Keep-set is defined in:

- `pm/workflow/minimal-snapshot-keep.txt`

Critical realm/state DB integrity baseline:

```bash
tools/check_critical_db_hashes.sh baseline
tools/check_critical_db_hashes.sh check
```

### Re-manifest Filesystem from SQL

```bash
tools/manifest_from_sql.sh
```

Then verify SQL authority drift:

```bash
tools/realm_drift_verify.sh
```

### Binary Artifact Policy

Text/code is SQL-managed. Binary artifacts are tracked separately and restored from canonical source (typically Git/user-managed outputs):

- `pm/workflow/binary-artifact-policy.txt`

Restore binary artifacts from policy:

```bash
tools/restore_binary_artifacts.sh
```

### Recovery Doctor

Run the end-to-end recovery health check:

```bash
tools/recovery_doctor.sh
```

Require conditional binaries during recovery (strict mode):

```bash
RECOVERY_STRICT_CONDITIONAL_BINARIES=true tools/recovery_doctor.sh
```

The recovery doctor:

- creates a local checkpoint of PM reports before regeneration
- regenerates PM core reports
- emits SQL drift summary at `pm/reports/sql-drift-summary.json`
- refreshes critical DB checksum baseline and validates it

Optional flags:

- `RECOVERY_STRICT_CONDITIONAL_BINARIES=true` to enforce conditional binaries as required
- `RECOVERY_CHECKPOINT_RETENTION_COUNT=<n>` to cap local checkpoint count
- `RECOVERY_SKIP_BASELINE_REFRESH=true` to keep prior critical DB baseline and verify only

### Optional Git Hook

Template hook:

- `tools/git-hooks/pre-commit`

Install locally if wanted:

```bash
cp tools/git-hooks/pre-commit .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

Or use helper:

```bash
tools/install_git_hooks.sh
```

### Restore Recovery Checkpoint

Restore the latest local recovery-doctor checkpointed PM reports:

```bash
tools/restore_recovery_checkpoint.sh
```

Restore a specific checkpoint:

```bash
tools/restore_recovery_checkpoint.sh 20260219T110503Z
```

### SQL Upsert Helper

Upsert one or more text files directly to a realm DB:

```bash
tools/sql_upsert_text_files.sh pm docs/SQL_FIRST_WORKFLOW.md tools/recovery_doctor.sh
```

### Boilerplate Package Helpers

Validate a boilerplate package:

```bash
tools/validate_boilerplate_package.sh 2026-02-19-recovery-ci-sql-integrity
```

Validation rejects transient SQLite artifacts (`*.sqlite-wal`, `*.sqlite-shm`) inside package directories.

Export a boilerplate package zip + checksum:

```bash
tools/export_boilerplate_package.sh 2026-02-19-recovery-ci-sql-integrity
```

Export excludes transient SQLite artifacts (`*.sqlite-wal`, `*.sqlite-shm`) from the archive.

Generate boilerplate promotion status report:

```bash
./gradlew --no-daemon boilerplatePromotionReport
```

### Decision Queue (Cross-Realm)

Record a decision in a realm database (granular scope including sub-boilerplate):

```bash
java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
  solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
  upsert-decision \
  --db pm/state/boilerplate-realm.sqlite \
  --realm boilerplate \
  --decision-id BP-DEC-001 \
  --title "Promote checksum guard into boilerplate CI" \
  --scope-level sub_boilerplate \
  --scope-ref pz-boilerplate-intelliJ \
  --sub-scope-ref .github/workflows/ci.yml \
  --status in_progress \
  --risk-score 4.0 \
  --blast-radius 3.0 \
  --unblock-factor 4.5 \
  --confidence 4.0 \
  --value-density 4.5
```

Export the cross-realm decision priority queue:

```bash
./gradlew --no-daemon decisionPriorityReport
```

### Knowledge Base

Create/update a knowledge entry in project-state:

```bash
java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
  solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
  upsert-knowledge \
  --db pm/state/project-state.sqlite \
  --knowledge-id KB-2026-02-19-001 \
  --realm application \
  --scope-level component \
  --scope-ref afp-engine \
  --title "Graphics parity observations" \
  --reasoning "Residual visual drift clusters around graphics object semantics." \
  --context-snapshot "Compared preview/afp-output.pdf with sampleOutput/sample.pdf after strict mode pass." \
  --outcome-status open \
  --outcome-summary "Needs targeted renderer update." \
  --confidence 4.0 \
  --impact-score 4.5 \
  --change-ref docs/project-plan-progress.csv
```

Attach evidence artifact:

```bash
java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
  solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
  add-knowledge-evidence \
  --db pm/state/project-state.sqlite \
  --knowledge-id KB-2026-02-19-001 \
  --artifact-path preview/fidelity-report.json \
  --type report \
  --notes "Baseline fidelity metrics used for this reasoning snapshot."
```

Link knowledge entry to a decision:

```bash
java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
  solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
  link-knowledge-decision \
  --db pm/state/project-state.sqlite \
  --knowledge-id KB-2026-02-19-001 \
  --realm application \
  --decision-id APP-RENDER-001 \
  --relation supports
```

Export knowledge base report:

```bash
./gradlew --no-daemon knowledgeBaseReport
./gradlew --no-daemon realmKnowledgeSync
```

Realm-mirrored knowledge sync report:

- `pm/reports/realm-knowledge-sync.json`
- `pm/reports/realm-knowledge-link-gate.json`

Run unresolved-link gate in strict mode:

```bash
AFP_KNOWLEDGE_LINK_STRICT=true ./gradlew --no-daemon realmKnowledgeLinkGate
```

### Action Tracking And Realm Governance

Ingest actionable prompts from assistant inbox and export action ledger:

```bash
./gradlew --no-daemon actionItemsReport
./gradlew --no-daemon actionOwnerSummaryReport actionSlaTrendReport
```

Direct action upsert:

```bash
java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
  solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
  upsert-action-item \
  --db pm/state/project-state.sqlite \
  --action-id ACT-2026-02-19-001 \
  --realm pm \
  --title "Implement realm governance export pipeline" \
  --status in_progress \
  --priority high \
  --implied-by human-question
```

Link an action item to a decision:

```bash
java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
  solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
  link-action-item-decision \
  --db pm/state/project-state.sqlite \
  --action-id ACT-2026-02-19-001 \
  --realm pm \
  --decision-id PM-DEC-001 \
  --relation implements
```

Export per-realm policy governance reports:

```bash
./gradlew --no-daemon applicationRealmPolicyReport pmRealmPolicyReport boilerplateRealmPolicyReport
```

Generated outputs:

- `pm/reports/action-items.json`
- `pm/reports/action-decision-suggestions.json`
- `pm/reports/action-owner-summary.json`
- `pm/reports/action-sla-trend.json`
- `pm/reports/action-ingest-strict.json`
- `pm/reports/realm-policy-sync.json`
- `pm/reports/realm-policy-diff.json`
- `pm/reports/application-realm-policy.json`
- `pm/reports/pm-realm-policy.json`
- `pm/reports/boilerplate-realm-policy.json`

Presentation naming convention in report payloads:

- action rows include `actionRef` (`Action | YYYY-MM-DD | #NNN`) alongside internal `actionId`
- decision rows include `decisionRef` alongside internal `decisionId`
- knowledge rows include `knowledgeRef` alongside internal `knowledgeId`
- governance event rows include `event_ref` alongside internal `event_id`
- `pm/reports/version.json` includes `releaseName` and `releaseRef`

Migration note:

- see `docs/PRESENTATION_ID_MIGRATION.md` for compatibility guidance

Cross-realm action token search from SQL:

```bash
./gradlew --no-daemon crossRealmActionTokenSearch -Pkeyword=governance
```

### PM Console Reporting

Persistent live dashboard daemon (default check in execute-application phase):

```bash
tools/pm_console_live_daemon.sh start
tools/pm_console_live_daemon.sh status
pm-console/build/install/pmconsole/bin/pmconsole live
```

Stop/restart daemon:

```bash
tools/pm_console_live_daemon.sh stop
tools/pm_console_live_daemon.sh restart
```

Default normalized execution now ensures live daemon is running before status:

- `previewManifest`
- `pmConsoleEnsureLive`
- `pmConsoleStatus`

Intent-style report output:

```bash
pm-console/build/install/pmconsole/bin/pmconsole report --request "current workstream status"
pm-console/build/install/pmconsole/bin/pmconsole report --request "reasoning drive"
pm-console/build/install/pmconsole/bin/pmconsole report --request "action status"
```

JSON payload output (exchange format `pm-console-screen@1`):

```bash
pm-console/build/install/pmconsole/bin/pmconsole report \
  --request "current workstream status" \
  --format json \
  --output pm/reports/workstream-status-screen.json
```

Granular refresh examples:

```bash
pm-console/build/install/pmconsole/bin/pmconsole refresh --phase pm_refresh_and_reports
pm-console/build/install/pmconsole/bin/pmconsole refresh --tasks projectStateDb,decisionPriorityReport,knowledgeBaseReport
```

Action view filters:

```bash
pm-console/build/install/pmconsole/bin/pmconsole actions --top 20 --priority high --stale-only
pm-console/build/install/pmconsole/bin/pmconsole actions --top 20 --owner ai-agent --open-only
pm-console/build/install/pmconsole/bin/pmconsole apply-suggestion --action-id action::db59f0535ec0
pm-console/build/install/pmconsole/bin/pmconsole report --request "realm knowledge status"
```

Authorized DB aliases:

```bash
pm-console/build/install/pmconsole/bin/pmconsole db --list
```

Reasoning databases and report are rebuilt in PM refresh:

- `pm/state/reasoning-experience.sqlite`
- `pm/state/reasoning-derived.sqlite`
- `pm/reports/reasoning-drive.json`

### CI Quick Check

Run the CI-equivalent SQL integrity checks locally:

```bash
tools/recovery_quick_check.sh
```
