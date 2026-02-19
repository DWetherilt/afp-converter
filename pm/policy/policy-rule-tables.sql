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
  ('PM-EXEC-001', 'pm', 'execution-defaults', 'Execute app flow first, ensure persistent PM console live daemon is running, then PM console status unless human overrides.', 'build.gradle:pmConsoleEnsureLive;AI-POLICY.md#Default execution coupling', 'human', 1, current_timestamp),
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
  ('PM-COMMS-001', 'pm', 'communication-governance', 'Assistant responses must end with a concise list of the next 10 prioritized tasks.', 'pm/policy/policy-rule-tables.sql', 'human', 1, current_timestamp),
  ('PM-SQLCODE-001', 'pm', 'sql-code-index', 'Maintain separate SQL code-index/token-dictionary databases for application, pm, and boilerplate realms.', 'build.gradle:realmCodeDatabases', 'human', 1, current_timestamp),
  ('PM-SQLCODE-002', 'pm', 'sql-code-index', 'Cross-realm keyword swatch changes must use token-dictionary/code-token search workflows.', 'build.gradle:realmTokenSearch', 'human', 1, current_timestamp),
  ('PM-SQLCODE-003', 'pm', 'sql-code-index', 'Build flows must materialize realm-managed source files from SQL before compilation.', 'build.gradle:realmSourcesExport', 'human', 1, current_timestamp),
  ('PM-SQLCODE-004', 'pm', 'sql-code-index', 'Direct filesystem drift from SQL-managed content must be blocked by SQL authority checks in strict mode.', 'build.gradle:sqlAuthorityDriftCheck', 'human', 1, current_timestamp),
  ('PM-SQLCODE-005', 'pm', 'sql-code-index', 'Each realm must publish SQL coverage reports proving managed file index completeness.', 'build.gradle:sqlCoverageReport', 'human', 1, current_timestamp),
  ('PM-SQLCODE-006', 'pm', 'sql-code-index', 'Quality gate must verify SQL reconstruction into a clean temp root before release.', 'build.gradle:sqlReconstructionCheck', 'human', 1, current_timestamp),
  ('PM-CI-001', 'pm', 'ci-governance', 'CI must run SQL-first pre-build integrity checks (via tools/recovery_quick_check.sh or equivalent) before Gradle build steps.', '.github/workflows/ci.yml', 'human', 1, current_timestamp),
  ('PM-CI-002', 'pm', 'ci-governance', 'CI should publish recovery/integrity artifacts for troubleshooting and rollback traceability.', '.github/workflows/ci.yml', 'human', 1, current_timestamp),
  ('PM-CI-003', 'pm', 'ci-governance', 'CI and package workflows must block transient SQLite artifacts (.sqlite-wal/.sqlite-shm) from tracked files and exported payloads.', '.github/workflows/ci.yml;tools/validate_boilerplate_package.sh;tools/export_boilerplate_package.sh', 'human', 1, current_timestamp),
  ('PM-BOILER-001', 'boilerplate', 'promotion-governance', 'Boilerplate package promotion state must be exported from SQL to pm/reports/boilerplate-promotion-status.json for review sequencing.', 'build.gradle:boilerplatePromotionReport', 'human', 1, current_timestamp),
  ('PM-DECISION-001', 'pm', 'decision-governance', 'Cross-realm prioritization must be derived from SQL-backed decision logs in each realm database and exported to pm/reports/decision-priority-queue.json.', 'build.gradle:decisionPriorityReport', 'human', 1, current_timestamp),
  ('PM-DECISION-002', 'boilerplate', 'decision-governance', 'Boilerplate reasoning must support sub-boilerplate granularity using scope_level/scope_ref/sub_scope_ref in decision_log.', 'pm-tools:StateDatabaseTool/decision_log', 'human', 1, current_timestamp),
  ('PM-KB-001', 'pm', 'knowledge-governance', 'Project reasoning outcomes must be captured in the SQL knowledge base with linked evidence artifacts and decision references.', 'pm-tools:StateDatabaseTool/knowledge_entries', 'human', 1, current_timestamp),
  ('PM-KB-002', 'pm', 'knowledge-governance', 'PM refresh pipeline must publish pm/reports/knowledge-base.json from project-state SQLite.', 'build.gradle:knowledgeBaseReport', 'human', 1, current_timestamp),
  ('PM-KB-003', 'pm', 'knowledge-governance', 'PM refresh pipeline must mirror project knowledge into each realm DB and publish realm knowledge sync diagnostics.', 'build.gradle:realmKnowledgeSync', 'human', 1, current_timestamp),
  ('PM-REASON-001', 'pm', 'reasoning-governance', 'Reasoning experience and derived-knowledge databases must be rebuilt from project-state knowledge during PM refresh.', 'build.gradle:reasoningDatabases', 'human', 1, current_timestamp),
  ('PM-REASON-002', 'pm', 'reasoning-governance', 'PM refresh pipeline must publish pm/reports/reasoning-drive.json as current reasoning signal output.', 'tools/reasoning_sync.py', 'human', 1, current_timestamp),
  ('PM-CONSOLE-001', 'pm', 'console-governance', 'Console screens must emit standard payload envelopes using exchange format pm-console-screen@1.', 'docs/PM_CONSOLE_EXCHANGE_FORMAT.md', 'human', 1, current_timestamp),
  ('PM-CONSOLE-002', 'pm', 'console-governance', 'pmconsole live must be command-driven (REPL style) and should not auto-refresh without explicit command invocation.', 'pm-console:PmConsoleMain.LiveCommand', 'human', 1, current_timestamp),
  ('PM-SEC-001', 'pm', 'security-governance', 'Console database federation must be constrained to aliases in pm/security/authorized-databases.json.', 'pm/security/authorized-databases.json', 'human', 1, current_timestamp),
  ('PM-SEC-002', 'pm', 'security-governance', 'Mutating authorized database aliases requires explicit human approval token.', 'pm-console:DbCommand --human-approved', 'human', 1, current_timestamp),
  ('PM-REC-001', 'pm', 'recovery-governance', 'Recovery-doctor local checkpoints must be retained with bounded count to prevent unbounded workspace growth.', 'tools/recovery_doctor.sh#RECOVERY_CHECKPOINT_RETENTION_COUNT', 'human', 1, current_timestamp),
  ('PM-REC-002', 'pm', 'recovery-governance', 'Recovery baseline refresh may be skipped only via explicit environment flag when preserving a prior checksum baseline is required.', 'tools/recovery_doctor.sh#RECOVERY_SKIP_BASELINE_REFRESH', 'human', 1, current_timestamp),
  ('PM-ACTION-001', 'pm', 'action-governance', 'Human prompts that imply actionable work must be ingested into action_items and reported via pm/reports/action-items.json.', 'build.gradle:actionInboxIngest;build.gradle:actionItemsReport', 'human', 1, current_timestamp),
  ('PM-ACTION-002', 'pm', 'action-governance', 'Action items should link to decision IDs for traceable execution sequencing.', 'pm-tools:StateDatabaseTool/action_item_decision_links', 'human', 1, current_timestamp),
  ('PM-ACTION-003', 'pm', 'action-governance', 'PM refresh must publish action owner summary and SLA trend reports for prioritization and drift visibility.', 'build.gradle:actionOwnerSummaryReport;build.gradle:actionSlaTrendReport', 'human', 1, current_timestamp),
  ('PM-REALM-GOV-001', 'application', 'realm-governance', 'Each realm database should publish governance policy exports using realm_policy_catalog report outputs.', 'build.gradle:applicationRealmPolicyReport', 'human', 1, current_timestamp),
  ('PM-REALM-GOV-002', 'pm', 'realm-governance', 'Each realm database should publish governance policy exports using realm_policy_catalog report outputs.', 'build.gradle:pmRealmPolicyReport', 'human', 1, current_timestamp),
  ('PM-REALM-GOV-003', 'boilerplate', 'realm-governance', 'Each realm database should publish governance policy exports using realm_policy_catalog report outputs.', 'build.gradle:boilerplateRealmPolicyReport', 'human', 1, current_timestamp);

insert into pm_data_dictionary(object_name, object_type, realm, definition, source_ref, naming_pattern, updated_at) values
  ('issues', 'table', 'pm', 'Issue log records loaded from docs/issues-log.csv for governance tickle/effectiveness workflows.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('plan_tasks', 'table', 'pm', 'Project plan task rows loaded from docs/project-plan-progress.csv.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('governance_events', 'table', 'pm', 'Policy/governance event history loaded from docs/policy-governance-events.csv.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('repo_vcs_snapshot', 'table', 'pm', 'Repository-level VCS state mirror (head/branch/dirty counts).', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('repo_file_state', 'table', 'pm', 'Per-path repository state mirror used for file-discipline reporting.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('policy_rule_catalog', 'table', 'pm', 'SQL-backed mutable policy rule catalog exported to pm/reports/policy-rules.json.', 'pm/policy/policy-rule-tables.sql', 'snake_case', current_timestamp),
  ('pm_data_dictionary', 'table', 'pm', 'Data dictionary of PM governance/state objects and naming conventions.', 'pm/policy/policy-rule-tables.sql', 'snake_case', current_timestamp),
  ('code_files', 'table', 'pm', 'Realm-scoped file index for SQL-managed code search and traceability.', 'pm-tools:StateDatabaseTool/initCodeIndexSchema', 'snake_case', current_timestamp),
  ('code_file_content', 'table', 'pm', 'Canonical realm-managed source content for SQL-first materialization to filesystem endpoints.', 'pm-tools:StateDatabaseTool/initCodeIndexSchema', 'snake_case', current_timestamp),
  ('code_tokens', 'table', 'pm', 'Token postings list mapping indexed tokens to files and occurrence counts.', 'pm-tools:StateDatabaseTool/initCodeIndexSchema', 'snake_case', current_timestamp),
  ('token_dictionary', 'table', 'pm', 'Realm-scoped token dictionary derived from indexed source files for keyword swatch search.', 'pm-tools:StateDatabaseTool/initCodeIndexSchema', 'snake_case', current_timestamp),
  ('decision_log', 'table', 'pm', 'Realm-local prioritized decision ledger with scope granularity and impact scoring for smallest-change/biggest-reward execution.', 'pm-tools:StateDatabaseTool/initCodeIndexSchema', 'snake_case', current_timestamp),
  ('decision_dependency', 'table', 'pm', 'Dependency edges between decisions in the same realm database.', 'pm-tools:StateDatabaseTool/initCodeIndexSchema', 'snake_case', current_timestamp),
  ('knowledge_entries', 'table', 'pm', 'Knowledge base entries that capture reasoning context, outcomes, and change references for decisions.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('knowledge_evidence', 'table', 'pm', 'Artifact evidence linked to knowledge entries with path, hash, and capture metadata.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('knowledge_decision_links', 'table', 'pm', 'Cross-reference map linking knowledge entries to decision IDs across realms.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('realm_knowledge_sync_report', 'report', 'pm', 'Cross-realm knowledge mirroring diagnostics showing per-realm knowledge/evidence/link counts and decision-link resolution.', 'tools/realm_knowledge_sync.py', 'snake_case', current_timestamp),
  ('action_items', 'table', 'pm', 'Actionable work ledger with status/priority and optional knowledge/decision linkage.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('action_item_decision_links', 'table', 'pm', 'Links action items to one or more decision IDs for traceable execution.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('action_inbox_events', 'table', 'pm', 'Deduplicated event capture for prompt-derived action ingestion.', 'pm-tools:StateDatabaseTool/initProjectSchema', 'snake_case', current_timestamp),
  ('action_owner_summary_report', 'report', 'pm', 'Owner-centric action rollup exported to pm/reports/action-owner-summary.json.', 'tools/action_owner_summary.py', 'snake_case', current_timestamp),
  ('action_sla_trend_report', 'report', 'pm', 'Rolling SLA breach trend exported to pm/reports/action-sla-trend.json with history under pm/state/action-sla-history.json.', 'tools/action_sla_trend.py', 'snake_case', current_timestamp),
  ('realm_policy_catalog', 'table', 'pm', 'Realm-local governance rules exported from each realm database for governance visibility.', 'pm-tools:StateDatabaseTool/initCodeIndexSchema', 'snake_case', current_timestamp),
  ('experience_events', 'table', 'pm', 'Empirical reasoning event store derived from knowledge outcomes and decision link counts.', 'tools/reasoning_sync.py', 'snake_case', current_timestamp),
  ('heuristic_scores', 'table', 'pm', 'Derived heuristic scores computed from experience events for prioritization guidance.', 'tools/reasoning_sync.py', 'snake_case', current_timestamp),
  ('derived_insights', 'table', 'pm', 'Second-order insights synthesized from experience heuristics and support signals.', 'tools/reasoning_sync.py', 'snake_case', current_timestamp),
  ('insight_edges', 'table', 'pm', 'Directed relationships between derived insights for traceable reasoning chains.', 'tools/reasoning_sync.py', 'snake_case', current_timestamp),
  ('pm_workflow_manifest', 'manifest', 'pm', 'Normalized PM workflow phase/adapter mapping manifest.', 'pm/workflow/workflow-manifest.json', 'snake_case', current_timestamp),
  ('pm_workflow_phase_task_map', 'adapter_map', 'pm', 'Gradle adapter phaseTaskMap binding phase IDs to executable task lists.', 'pm/workflow/workflow-manifest.json#adapters.gradle.phaseTaskMap', 'snake_case', current_timestamp),
  ('pm_workflow_defaults', 'adapter_defaults', 'pm', 'Default workflow phase selection for normalized PM execution.', 'pm/workflow/workflow-manifest.json#defaults', 'snake_case', current_timestamp),
  ('package_candidates', 'table', 'boilerplate', 'Boilerplate update package candidate registry synced from boilerplate/update-packages.', 'pm-tools:StateDatabaseTool/initBoilerplateSchema', 'snake_case', current_timestamp),
  ('package_files', 'table', 'boilerplate', 'Per-package file inventory for boilerplate payload audit/reporting.', 'pm-tools:StateDatabaseTool/initBoilerplateSchema', 'snake_case', current_timestamp);
