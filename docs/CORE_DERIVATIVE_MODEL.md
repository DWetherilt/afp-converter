# Core Derivative Model

## Purpose

Define the canonical hierarchy where `ai-core-3` is the base environment arbiter and projects are derivative instances under that core.

## Authority Chain

1. Human Lead Developer
2. Core Policy Realm (`ai-core-3`)
3. Derivative project policy
4. Derivative project internal realms

## AFP-Converter Placement

- Core: `ai-core-3`
- Derivative project: `afp-converter`
- Internal realms:
  - `application`
  - `pm`
  - `boilerplate`

## Reasoning Contract

- Core and derivative reasoning are separate stores.
- Derivative decisions should carry core-link metadata when escalation/arbitration occurs.
- Core-level arbitration outcomes should be referenceable from derivative decision/action records.

## Implementation Contract

- Machine-readable topology and link contracts:
  - `pm/workflow/core-derivative-topology.json`
- Bootstrap helper for new derivative projects:
  - `tools/bootstrap_core_derivative_project.sh`

## Notes

- This model does not replace project-local SQL-first workflows.
- It establishes shared constitutional and arbitration lineage across multiple projects.
