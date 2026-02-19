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
