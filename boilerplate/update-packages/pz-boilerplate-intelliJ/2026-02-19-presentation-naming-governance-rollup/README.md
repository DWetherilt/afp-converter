# Update Package: Presentation Naming Governance Rollup

## Purpose
Standardize human-friendly presentation references for governance/decision/action/knowledge/release outputs while preserving internal IDs as storage/join keys.

## Includes
- New PM naming governance rules (`PM-NAME-001`, `PM-NAME-002`)
- New lint gate for presentation references (`presentationRefLint`)
- Console support for `friendly|raw` identifier display modes
- Governance status report intent in PM console
- Migration guidance for payload consumers

## Validation
- Run `./gradlew --no-daemon qualityGate`
- Confirm `pm/reports/presentation-ref-lint.json` is PASS

## Additional governance extension
- Persist all created identifiers across PM/policy/code/version-control workflows into SQL `identifier_registry`.
- Publish `pm/reports/identifier-registry.json` for audit visibility.

## Validation drill note
- Executed controlled SQL managed-file delete-and-remanifest drill on 2026-02-19.
- Restored managed endpoints directly from realm SQL stores and re-validated with reconstruction + quality gates.
