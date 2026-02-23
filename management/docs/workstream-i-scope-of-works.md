# Workstream I Scope Of Works

## Title
AI-Core Governance, Tooling, and Hierarchy Manifest Hardening

## Date
2026-02-23

## Purpose
Create a dedicated AI-core-level execution stream to ensure tooling behavior is policy-conformant, realm-segmented, SQL-derived, and manifested through a consistent core->project->product hierarchy and file structure.

## Policy-Aligned Position
- Realms may extend code and policy within their scope, but must not directly mutate single-source-of-truth facts they do not own without an explicit owner-negotiation path.
- This is consistent with the existing constitutional layers (`AI-POLICY.md`) and SQL-first governance model (`management/docs/SQL_FIRST_WORKFLOW.md`).
- Goal state is that each managed tool has a deterministic, safe default execution output; this must be implemented and verified by gates, not assumed.
- Core environment identity is `pzAiCore`, which is the canonical owner/arbitration root for derivative manifestations.

## In Scope
- Inventory all managed PM/application/boilerplate tools and map each to a realm owner.
- Audit runtime command arguments and config sources.
- Eliminate hardcoded critical runtime paths where policy requires SQL-backed derivation.
- Introduce owner-negotiation/write-control mechanics for protected SSoT records.
- Perform complete hierarchy/file-structure migration needed to enforce the canonical chain:
  - `pzAiCore` core realms (`policy`, `management`, `product`)
  - derivative project realms (`policy`, `management`, `product`)
  - derivative product instances with inherited realm structure.
- Add build/gate enforcement for:
  - prohibited hardcoded critical references,
  - cross-realm writes,
  - hierarchy/structure drift from topology contract,
  - unmanaged tool execution paths.
- Validate with evidence artifacts and publish compliance reports.

## Out Of Scope
- Functional feature redesign of AFP conversion behavior.
- Rewriting non-managed local developer scripts that are outside PM governance scope.

## Deliverables
1. Tool inventory and ownership map (realm segmented).
2. Argument-source compliance matrix (DB-derived, constant, or violation).
3. Refactored managed tooling for SQL-derived critical arguments.
4. Owner-negotiation guardrails for protected SSoT writes.
5. New/updated quality gates and policy checks.
6. Canonical hierarchy/structure migration package with topology-aligned manifests.
7. Validation evidence pack and closure report.

## Acceptance Criteria
1. Every managed tool has an identified owner realm and registry entry.
2. Critical runtime arguments are DB-derived or explicitly policy-whitelisted.
3. Unauthorized direct SSoT mutation attempts fail with actionable errors.
4. Cross-realm mutation violations are blocked by automated checks.
5. Managed tools execute with deterministic default outputs.
6. Workspace and derivative manifests conform to the declared topology chain rooted at `pzAiCore`.
7. `qualityGate` passes with conformance checks enabled and evidence published.

## Execution Sequence
1. Stage 1: Core identity and topology convergence.
2. Stage 2: Tooling governance refactor.
3. Stage 3: Enforcement and closure validation.

## Stage Scopes
### Stage 1: Core Identity And Topology Convergence
- Complete `pzAiCore` identity convergence across policy/topology/bootstrap/state records.
- Finish canonical core->project->product hierarchy and file-structure migration.
- Publish topology/structure drift baseline artifacts.
- Exit: no unresolved legacy identity references and topology contract is materially aligned with workspace structure.

### Stage 2: Tooling Governance Refactor
- Convert critical hardcoded DB/path arguments to resolver-backed or SQL-derived sources.
- Enforce realm segmentation boundaries for managed tooling execution/writes.
- Implement owner-negotiation gate for protected SSoT mutation paths.
- Standardize deterministic default outputs for managed tools.
- Exit: refactored tooling set passes governance checks in report mode with no critical unresolved violations.

### Stage 3: Enforcement And Closure Validation
- Promote report-mode checks to strict gates:
  - hardcoded critical paths,
  - cross-realm writes,
  - topology/structure drift.
- Run full validation cycle and publish evidence pack.
- Produce closure handoff and residual backlog.
- Exit: `qualityGate` passes with strict enforcement active and closure artifacts published.

## Risks And Controls
- Risk: false positives from strict path checks.
  Control: phase in with report-only mode, then enforce.
- Risk: legacy tools without clear ownership.
  Control: assign provisional realm owner before refactor.
- Risk: conformance work introduces workflow friction.
  Control: provide deterministic defaults and explicit override mechanics.
- Risk: hierarchy migration introduces temporary path/tooling breakage.
  Control: execute migration with topology contract checks and staged gate enablement.

## Traceability
- Plan tracker: `management/docs/project-plan-progress.csv` (Workstream I)
- Policy contract: `AI-POLICY.md`
- SQL-first model: `management/docs/SQL_FIRST_WORKFLOW.md`
- State sync target: `management/pm/state/project-state.sqlite`
