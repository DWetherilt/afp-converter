# Recovery Doctor

`tools/recovery_doctor.sh` runs a full SQL-first recovery cycle.

## What it does

1. Restores binary artifacts from policy.
2. Re-materializes managed files from realm SQL databases.
3. Verifies SQL drift across realms.
4. Verifies minimal snapshot keep-set.
5. Creates a checkpoint of PM core reports and prunes old checkpoints.
6. Rebuilds PM reports and upserts them into PM realm SQL authority.
7. Generates SQL drift summary report.
8. Refreshes and verifies critical DB hash baseline.

## Environment flags

- `RECOVERY_STRICT_CONDITIONAL_BINARIES=true|false`
  - `true` treats `required=conditional` binary policy entries as required.
- `RECOVERY_CHECKPOINT_RETENTION_COUNT=<n>`
  - Number of local recovery checkpoints to keep. Default: `20`.

## Related scripts

- `tools/recovery_quick_check.sh` (CI-safe pre-build integrity checks)
- `tools/restore_binary_artifacts.sh`
- `tools/restore_recovery_checkpoint.sh`
- `tools/sql_upsert_text_files.sh`
- `tools/sql_drift_summary.py`
