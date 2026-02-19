# Namespace Root Standardization

## Purpose
Mandate `solutions.pointzero.symphony` as the root namespace stub for Java package structures and runtime class references.

## Key outcomes
- Legacy root stubs (`com.upland.connect`) are disallowed.
- Build gate `enforceNamespaceRoot` is required via `qualityGate`.
- Boilerplate and project policy/docs reference the new namespace standard.

## Included files
- `package-manifest.json`
- `apply-checklist.md`
- `fresh-session-prompt.md`
