# Update Package: Project-Level Rollback Governance

## Purpose
Apply the same project-level rollback discipline added in `afp-converter` to `pz-boilerplate-intelliJ`.

## Scope
- Tighten `AI-POLICY.md` so project/process mutations use issue linkage + checkpoint + validation + rollback readiness.
- Add rollback safety flow snippet to `README.md`.
- Add changelog/process note in the target repo (recommended).

## Package Contents
- `patches/0001-policy-and-readme-rollback-governance.patch`
  - Source diff from this repo for `AI-POLICY.md` and `README.md`.
- `fresh-session-prompt.md`
  - Prompt to give a fresh AI session.
- `apply-checklist.md`
  - Step-by-step apply and validation sequence.
- `package-manifest.json`
  - Metadata and integrity details.

## Notes
- The patch was generated from `afp-converter`; context may differ slightly in `pz-boilerplate-intelliJ`.
- If `git apply` rejects hunks, apply changes manually using `apply-checklist.md`.
