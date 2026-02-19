-- PM operational rule tables.
-- Mutable operational rules belong here instead of repeatedly mutating AI-POLICY.md.

create table if not exists policy_rule_catalog (
  rule_id text primary key,
  realm text not null default '',
  category text not null default '',
  rule_text text not null default '',
  source_ref text not null default '',
  mutable_by text not null default '',
  enabled integer not null default 1,
  updated_at text not null
);

create table if not exists pm_data_dictionary (
  object_name text primary key,
  object_type text not null default '',
  realm text not null default '',
  definition text not null default '',
  source_ref text not null default '',
  naming_pattern text not null default '',
  updated_at text not null
);

delete from policy_rule_catalog;
delete from pm_data_dictionary;

insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values
  ('PM-EXEC-001', 'pm', 'execution-defaults', 'Execute app flow first, then PM console status by default unless human overrides.', 'AI-POLICY.md#Default execution coupling', 'human', 1, current_timestamp),
  ('PM-REALM-001', 'pm', 'realm-separation', 'PM state databases must be under pm/state only.', 'build.gradle:enforcePmApplicationRealmSeparation', 'human', 1, current_timestamp),
  ('PM-REALM-002', 'pm', 'realm-separation', 'PM reports must be under pm/reports and must not be emitted into preview/.', 'build.gradle:enforcePmApplicationRealmSeparation', 'human', 1, current_timestamp),
  ('PM-GATE-001', 'pm', 'quality-gates', 'qualityGate must include enforceProjectBoundaries.', 'build.gradle:qualityGate', 'human', 1, current_timestamp),
  ('PM-GATE-002', 'pm', 'quality-gates', 'qualityGate must include enforceManagedTooling.', 'build.gradle:qualityGate', 'human', 1, current_timestamp),
  ('PM-GATE-003', 'pm', 'quality-gates', 'qualityGate must include enforcePmApplicationRealmSeparation.', 'build.gradle:qualityGate', 'human', 1, current_timestamp),
  ('PM-PROD-001', 'application', 'release-boundary', 'prodBuild packages application modules only and excludes PM modules.', 'build.gradle:prodBuild', 'human', 1, current_timestamp),
  ('PM-DOC-001', 'pm', 'documentation-cycle', 'documentationManifest must include PM state/report artifacts.', 'build.gradle:documentationManifest', 'human', 1, current_timestamp);
insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values
  ('PM-ROLLUP-001', 'pm', 'boilerplate-promotion', 'Any framework/process/policy/build mutation must include an updated boilerplate roll-up package under boilerplate/update-packages/pz-boilerplate-intelliJ in the same change set.', 'build.gradle:enforceBoilerplateRollupForFrameworkChanges', 'human', 1, current_timestamp),
  ('PM-REALM-003', 'boilerplate', 'realm-separation', 'Boilerplate transfer packages must be maintained under boilerplate/update-packages and tracked through boilerplate-state SQL tables as a dedicated third realm.', 'pm-tools:StateDatabaseTool/package_candidates.realm', 'human', 1, current_timestamp);
insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values
  ('PM-WF-001', 'pm', 'workflow-normalization', 'PM process sequencing should be defined in pm/workflow/workflow-manifest.json and consumed by adapter interfaces.', 'pm/workflow/workflow-manifest.json', 'human', 1, current_timestamp),
  ('PM-WF-002', 'pm', 'workflow-normalization', 'Gradle adapter must expose normalized PM workflow interface tasks that resolve phases from workflow manifest.', 'build.gradle:pmWorkflowRun', 'human', 1, current_timestamp),
  ('PM-NS-001', 'pm', 'namespace-governance', 'First-party Java package roots must use solutions.pointzero.symphony and must not use com.upland.connect in active source/build/policy scope.', 'build.gradle:enforceNamespaceRoot', 'human', 1, current_timestamp),
  ('PM-COMMS-001', 'pm', 'communication-governance', 'Assistant responses must end with a concise list of the next 10 prioritized tasks.', 'pm/policy/policy-rule-tables.sql', 'human', 1, current_timestamp);

insert into pm_data_dictionary(object_name, object_type, realm, definition, source_ref, naming_pattern, updated_at) values
  ('issues', 'table', 'pm', 'Issue log records loaded from docs/issues-log.csv for governance tickle/effectiveness workflows.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('plan_tasks', 'table', 'pm', 'Project plan task rows loaded from docs/project-plan-progress.csv.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('governance_events', 'table', 'pm', 'Policy/governance event history loaded from docs/policy-governance-events.csv.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('repo_vcs_snapshot', 'table', 'pm', 'Repository-level VCS state mirror (head/branch/dirty counts).', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('repo_file_state', 'table', 'pm', 'Per-path repository state mirror used for file-discipline reporting.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('policy_rule_catalog', 'table', 'pm', 'SQL-backed mutable policy rule catalog exported to pm/reports/policy-rules.json.', 'pm/policy/policy-rule-tables.sql', 'snake_case', current_timestamp),
  ('pm_data_dictionary', 'table', 'pm', 'Data dictionary of PM governance/state objects and naming conventions.', 'pm/policy/policy-rule-tables.sql', 'snake_case', current_timestamp),
  ('pm_workflow_manifest', 'manifest', 'pm', 'Normalized PM workflow phase/adapter mapping manifest.', 'pm/workflow/workflow-manifest.json', 'snake_case', current_timestamp),
  ('pm_workflow_phase_task_map', 'adapter_map', 'pm', 'Gradle adapter phaseTaskMap binding phase IDs to executable task lists.', 'pm/workflow/workflow-manifest.json#adapters.gradle.phaseTaskMap', 'snake_case', current_timestamp),
  ('pm_workflow_defaults', 'adapter_defaults', 'pm', 'Default workflow phase selection for normalized PM execution.', 'pm/workflow/workflow-manifest.json#defaults', 'snake_case', current_timestamp),
  ('package_candidates', 'table', 'boilerplate', 'Boilerplate update package candidate registry synced from boilerplate/update-packages.', 'pm-tools:StateDatabaseTool/initBoilerplateSchema', 'snake_case', current_timestamp),
  ('package_files', 'table', 'boilerplate', 'Per-package file inventory for boilerplate payload audit/reporting.', 'pm-tools:StateDatabaseTool/initBoilerplateSchema', 'snake_case', current_timestamp);
