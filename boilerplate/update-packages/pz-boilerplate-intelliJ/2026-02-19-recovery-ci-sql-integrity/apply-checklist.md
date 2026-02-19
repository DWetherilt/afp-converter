# Apply Checklist

1. Copy listed files from package manifest into target repo preserving paths.
2. Ensure executable mode for shell/python scripts and hook template:
   - `chmod +x tools/*.sh tools/*.py tools/git-hooks/pre-commit`
3. Run `tools/recovery_doctor.sh` and confirm PASS.
4. Verify `tools/realm_drift_verify.sh` and `tools/check_critical_db_hashes.sh check` both PASS.
5. Confirm `.github/workflows/ci.yml` includes SQL pre-build integrity steps and artifact upload.
6. Record adoption in target repo changelog/policy governance logs.
