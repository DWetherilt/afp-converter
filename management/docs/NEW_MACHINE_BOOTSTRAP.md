# New Machine Bootstrap

Use this when a fresh environment or AI session needs to resume the project safely.

## 1. Repository and Tooling

```bash
git clone <repo-url>
cd afp-converter
```

Optional local hook install:

```bash
management/tools/install_git_hooks.sh
```

## 2. Restore from SQL Authority

```bash
management/tools/manifest_from_sql.sh
management/tools/realm_drift_verify.sh
```

## 3. Binary and Integrity Validation

```bash
management/tools/restore_binary_artifacts.sh
management/tools/check_critical_db_hashes.sh check
```

## 4. Recovery Quick Check

```bash
management/tools/recovery_quick_check.sh
```

## 5. Full Recovery (when needed)

```bash
management/tools/recovery_doctor.sh
```

Strict conditional binary mode:

```bash
RECOVERY_STRICT_CONDITIONAL_BINARIES=true management/tools/recovery_doctor.sh
```

## 6. Build and Quality Gate

In a network-enabled environment:

```bash
./gradlew --no-daemon qualityGate
```

If blocked (e.g. Gradle download DNS), record the blocker in:

- `management/pm/reports/recovery-status.json`

## 7. Boilerplate Package Handling

Validate package:

```bash
management/tools/validate_boilerplate_package.sh <package-id>
```

Export package zip + checksum:

```bash
management/tools/export_boilerplate_package.sh <package-id>
```
