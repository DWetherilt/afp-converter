# Core Derivative Model

## Purpose

Define the canonical hierarchy where `pzAiCore` is the base environment arbiter and projects are derivative instances under that core.

## Authority Chain

1. Human Lead Developer
2. Core Policy Realm (`pzAiCore`)
3. Derivative project policy
4. Derivative project internal realms

## AFP-Converter Placement

- Core: `pzAiCore`
- Derivative project: `afp-converter`
- Core realms:
  - `policy`
  - `management`
  - `product`
- AFP-converter inherited realms:
  - `policy`
  - `management`
  - `product`
- AFP-converter products:
  - `boilerplate`
  - `project-management`
  - `application`
- Each AFP-converter product instance inherits the same three realms:
  - `policy`
  - `management`
  - `product`

## Reasoning Contract

- Core and derivative reasoning are separate stores.
- Derivative decisions should carry core-link metadata when escalation/arbitration occurs.
- Core-level arbitration outcomes should be referenceable from derivative decision/action records.

## Implementation Contract

- Machine-readable topology and link contracts:
  - `management/pm/workflow/core-derivative-topology.json`
- Distributed distilled workstream knowledge stores:
  - `management/pm/state/core-realm.sqlite`
  - `management/pm/state/application-realm.sqlite`
  - `management/pm/state/pm-realm.sqlite`
  - `management/pm/state/boilerplate-realm.sqlite`
- Bootstrap helper for new derivative projects:
  - `management/tools/bootstrap_core_derivative_project.sh`

## Notes

- This model does not replace project-local SQL-first workflows.
- It establishes shared constitutional and arbitration lineage across multiple projects.
