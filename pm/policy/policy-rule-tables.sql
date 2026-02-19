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

delete from policy_rule_catalog;

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
  ('PM-WF-002', 'pm', 'workflow-normalization', 'Gradle adapter must expose normalized PM workflow interface tasks that resolve phases from workflow manifest.', 'build.gradle:pmWorkflowRun', 'human', 1, current_timestamp);
