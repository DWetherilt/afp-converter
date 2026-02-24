## Summary

- 

## Scope

- [ ] Application realm
- [ ] PM realm
- [ ] Boilerplate realm

## Recovery Evidence

- [ ] `management/tools/recovery_quick_check.sh` PASS
- [ ] `management/tools/recovery_doctor.sh` PASS (if full recovery path was touched)
- [ ] `management/tools/realm_drift_verify.sh` PASS
- [ ] `management/tools/check_critical_db_hashes.sh check` PASS
- [ ] `management/pm/reports/sql-drift-summary.json` updated

## SQL Authority

- [ ] Managed text changes were upserted to the correct realm DB(s)
- [ ] No unintended SQLite `-shm/-wal` files are included

## CI/Build

- [ ] CI workflow updates validated
- [ ] `qualityGate` status noted (pass or blocked reason)

## Boilerplate Promotion

- [ ] Boilerplate package created/updated when framework/process changes are present
- [ ] Package validated (`management/tools/validate_boilerplate_package.sh <id>`)
