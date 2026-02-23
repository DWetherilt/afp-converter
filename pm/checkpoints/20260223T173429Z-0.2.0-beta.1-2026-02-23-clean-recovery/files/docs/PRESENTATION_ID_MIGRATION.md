# Presentation Reference Migration

## Purpose
This project now separates internal IDs from presentation-facing references.

## New presentation fields
- `actionRef` (with `actionId`)
- `decisionRef` (with `decisionId`)
- `knowledgeRef` (with `knowledgeId`)
- `event_ref` (with `event_id`)
- `releaseRef` (with version metadata)

Reference format:
- `<What> | <YYYY-MM-DD> | #<occurrence>`

Examples:
- `Action | 2026-02-19 | #001`
- `Decision | 2026-02-19 | #007`
- `Release | 2026-02-19 | #001`

## Compatibility guidance
- Existing consumers that parse internal IDs can continue unchanged.
- UI/presentation consumers should prefer `*Ref` fields for display.
- Never use `*Ref` values as join keys; use internal IDs for all relational operations.

## Rollout note
- During transition, payloads carry both internal IDs and presentation refs.
- Validation is enforced by `presentationRefLint`.
