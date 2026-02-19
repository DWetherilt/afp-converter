# PM Console Exchange Format

Schema: `pm-console-screen@1`

This defines a standard payload envelope for console-generated screens so request/response output can be rendered in terminal, exported to file, or consumed by external tooling.

## Envelope

```json
{
  "schemaVersion": "1",
  "exchangeFormat": "pm-console-screen@1",
  "generatedAt": "2026-02-19T17:00:00Z",
  "screenId": "workstream_status",
  "request": "current workstream status",
  "sourceAliases": ["project", "pm_realm", "boilerplate_realm"],
  "data": {}
}
```

## Supported Screen IDs

- `workstream_status`: Aggregated workstream/task status from `project` DB, merged with decision signals from authorized realm DBs.
- `unsupported_request`: Returned when intent parsing does not recognize the request.

## Security Model

- DB federation is allowlisted only via `pm/security/authorized-databases.json`.
- Console report generation will only query enabled aliases in that file.
- Alias mutations require explicit human approval token: `--human-approved I_HAVE_EXPLICIT_HUMAN_APPROVAL`.
- Console report generation is read-only by design.

## Commands

- `pmconsole report --request "current workstream status"`
- `pmconsole report --request "current workstream status" --format json --output pm/reports/workstream-status-screen.json`
- `pmconsole db --list`
- `pmconsole db --authorize --alias external_audit --path /path/to/db.sqlite --description "Read-only audit DB" --human-approved I_HAVE_EXPLICIT_HUMAN_APPROVAL`

