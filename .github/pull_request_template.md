## Summary

- 

## Scope

- [ ] Application realm
- [ ] PM realm
- [ ] Boilerplate realm

## Recovery Evidence

- [ ] `tools/recovery_quick_check.sh` PASS
- [ ] `tools/recovery_doctor.sh` PASS (if full recovery path was touched)
- [ ] `tools/realm_drift_verify.sh` PASS
- [ ] `tools/check_critical_db_hashes.sh check` PASS
- [ ] `pm/reports/sql-drift-summary.json` updated

## SQL Authority

- [ ] Managed text changes were upserted to the correct realm DB(s)
- [ ] No unintended SQLite `-shm/-wal` files are included

## CI/Build

- [ ] CI workflow updates validated
- [ ] `qualityGate` status noted (pass or blocked reason)

## Boilerplate Promotion

- [ ] Boilerplate package created/updated when framework/process changes are present
- [ ] Package validated (`tools/validate_boilerplate_package.sh <id>`)
