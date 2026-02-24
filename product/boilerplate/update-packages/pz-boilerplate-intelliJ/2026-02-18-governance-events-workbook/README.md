# Governance Events Workbook Sync Package

## Purpose
Add explicit policy-governance event tracking (CSV + generated XLSX + build task) to the boilerplate framework so future agents must leave an auditable governance trail.

## Scope
- Add `management/docs/policy-governance-events.csv`
- Add workbook updater class for governance events in `boilerplate-tools`
- Add Gradle task `policyGovernanceWorkbook`
- Wire into `documentationManifest` and policy/readme docs
