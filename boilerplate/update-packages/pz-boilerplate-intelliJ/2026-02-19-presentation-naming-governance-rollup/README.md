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
