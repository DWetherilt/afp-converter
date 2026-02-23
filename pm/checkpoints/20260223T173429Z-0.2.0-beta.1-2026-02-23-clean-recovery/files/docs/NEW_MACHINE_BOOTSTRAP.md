# New Machine Bootstrap

Use this when a fresh environment or AI session needs to resume the project safely.

## 1. Repository and Tooling

```bash
git clone <repo-url>
cd afp-converter
```

Optional local hook install:

```bash
tools/install_git_hooks.sh
```

## 2. Restore from SQL Authority

```bash
tools/manifest_from_sql.sh
tools/realm_drift_verify.sh
```

## 3. Binary and Integrity Validation

```bash
tools/restore_binary_artifacts.sh
tools/check_critical_db_hashes.sh check
```

## 4. Recovery Quick Check

```bash
tools/recovery_quick_check.sh
```

## 5. Full Recovery (when needed)

```bash
tools/recovery_doctor.sh
```

Strict conditional binary mode:

```bash
RECOVERY_STRICT_CONDITIONAL_BINARIES=true tools/recovery_doctor.sh
```

## 6. Build and Quality Gate

In a network-enabled environment:

```bash
./gradlew --no-daemon qualityGate
```

If blocked (e.g. Gradle download DNS), record the blocker in:

- `pm/reports/recovery-status.json`

## 7. Boilerplate Package Handling

Validate package:

```bash
tools/validate_boilerplate_package.sh <package-id>
```

Export package zip + checksum:

```bash
tools/export_boilerplate_package.sh <package-id>
```
