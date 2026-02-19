# Update Packages

This folder stores portable patch bundles intended for applying changes to external/frozen sibling repos (for example `pz-boilerplate-intelliJ`).

Workflow rule:
- If a required package folder does not exist, create a new one.
- Use:
  - `tools/init_update_package.sh <target-repo-name> <package-name>`
- You may remove package folders after successful merge into target repo.
- Regenerate a fresh package for future updates rather than reusing stale bundles.
- If multiple candidate packages are approved in the same cycle, produce one consolidated package and list superseded package IDs in the consolidated package manifest.

Fresh-instance convergence handoff:
- `boilerplate/update-packages/pz-boilerplate-intelliJ/2026-02-19-fresh-instance-convergence-eval`
  - contains a master handoff manifest and comparison checklist for validating that a new AI instance converges on the same boilerplate outcome.

Package release checklist:
- Validate package structure and manifest:
  - `tools/validate_boilerplate_package.sh <package-id>`
- Export zip payload and checksum for transfer:
  - `tools/export_boilerplate_package.sh <package-id>`
