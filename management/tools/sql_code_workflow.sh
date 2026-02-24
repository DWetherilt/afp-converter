#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
SQL-first code workflow helper.

Usage:
  management/tools/sql_code_workflow.sh upsert <realm> <endpoint> <source-file>
  management/tools/sql_code_workflow.sh upsert-manifest <realm> <manifest-json>
  management/tools/sql_code_workflow.sh export <realm>
  management/tools/sql_code_workflow.sh verify <realm>
  management/tools/sql_code_workflow.sh coverage <realm>
  management/tools/sql_code_workflow.sh search <realm|all> <keyword>
  management/tools/sql_code_workflow.sh reconstruct-check

Realms:
  application | pm | boilerplate
EOF
}

realm_db() {
  case "$1" in
    application) echo "management/pm/state/application-realm.sqlite" ;;
    pm) echo "management/pm/state/pm-realm.sqlite" ;;
    boilerplate) echo "management/pm/state/boilerplate-realm.sqlite" ;;
    *) echo "Unsupported realm: $1" >&2; exit 2 ;;
  esac
}

if [[ $# -lt 1 ]]; then
  usage
  exit 2
fi

cmd="$1"
shift

case "$cmd" in
  upsert)
    [[ $# -eq 3 ]] || { usage; exit 2; }
    realm="$1"; endpoint="$2"; source="$3"
    db="$(realm_db "$realm")"
    ./gradlew :pm-tools:classes >/dev/null
    java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
      solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
      upsert-code-file \
      --db "$db" \
      --realm "$realm" \
      --endpoint "$endpoint" \
      --source "$source"
    ;;
  upsert-manifest)
    [[ $# -eq 2 ]] || { usage; exit 2; }
    realm="$1"; manifest="$2"
    db="$(realm_db "$realm")"
    ./gradlew :pm-tools:classes >/dev/null
    java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
      solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
      upsert-code-files-manifest \
      --db "$db" \
      --realm "$realm" \
      --manifest "$manifest"
    ;;
  export)
    [[ $# -eq 1 ]] || { usage; exit 2; }
    realm="$1"
    db="$(realm_db "$realm")"
    ./gradlew :pm-tools:classes >/dev/null
    java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
      solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
      materialize-code-realm \
      --db "$db" \
      --realm "$realm" \
      --target-root "."
    ;;
  verify)
    [[ $# -eq 1 ]] || { usage; exit 2; }
    realm="$1"
    db="$(realm_db "$realm")"
    ./gradlew :pm-tools:classes >/dev/null
    java -cp "$(./gradlew -q :pm-tools:printRuntimeClasspath)" \
      solutions.pointzero.symphony.pm.tools.StateDatabaseTool \
      verify-code-realm \
      --db "$db" \
      --realm "$realm" \
      --target-root "." \
      --output "management/pm/reports/sql-authority-${realm}.json" \
      --enforce
    ;;
  coverage)
    [[ $# -eq 1 ]] || { usage; exit 2; }
    realm="$1"
    case "$realm" in
      application) ./gradlew applicationRealmCoverageReport ;;
      pm) ./gradlew pmRealmCoverageReport ;;
      boilerplate) ./gradlew boilerplateRealmCoverageReport ;;
      *) echo "Unsupported realm: $realm" >&2; exit 2 ;;
    esac
    ;;
  search)
    [[ $# -eq 2 ]] || { usage; exit 2; }
    realm="$1"; keyword="$2"
    if [[ "$realm" == "all" ]]; then
      ./gradlew crossRealmTokenSearch -Pkeyword="$keyword"
    else
      ./gradlew realmTokenSearch -Prealm="$realm" -Pkeyword="$keyword"
    fi
    ;;
  reconstruct-check)
    [[ $# -eq 0 ]] || { usage; exit 2; }
    ./gradlew sqlReconstructionCheck
    ;;
  *)
    usage
    exit 2
    ;;
esac
