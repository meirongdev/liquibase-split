#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
phase2_compose="${repo_root}/docker-compose-phase2.yml"
log_dir="${TMPDIR:-/tmp}/liquibase-split-phase2-logs"
run_dir="${TMPDIR:-/tmp}/liquibase-split-run"
mkdir -p "${log_dir}"
mkdir -p "${run_dir}"

liquibase_plugin_version="4.31.1"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

wait_for_pg() {
  local compose_file="$1"
  local service_name="$2"
  local database_name="$3"

  until docker compose -f "${compose_file}" exec -T "${service_name}" psql -U postgres -d "${database_name}" -c "SELECT 1" >/dev/null 2>&1; do
    sleep 2
  done
}

run_liquibase() {
  local command_name="$1"
  local module_name="$2"
  local default_schema_name="$3"
  local liquibase_schema_name="$4"
  local service_schema_name="$5"

  mvn -q -pl "${module_name}" "org.liquibase:liquibase-maven-plugin:${liquibase_plugin_version}:${command_name}" \
    -Dliquibase.changeLogFile=src/main/resources/db/changelog/db.changelog-master.xml \
    -Dliquibase.url=jdbc:postgresql://localhost:5432/shopdb \
    -Dliquibase.username=postgres \
    -Dliquibase.password=postgres \
    -Dliquibase.defaultSchemaName="${default_schema_name}" \
    -Dliquibase.liquibaseSchemaName="${liquibase_schema_name}" \
    -Dservice_schema="${service_schema_name}"
}

start_phase2_service() {
  local module_name="$1"
  local log_file="${log_dir}/${module_name}.log"
  local pid_file="${run_dir}/${module_name}-phase2.pid"

  (
    cd "${repo_root}"
    nohup mvn -q -pl "${module_name}" spring-boot:run -Dspring-boot.run.profiles=phase2 -Dspring-boot.run.arguments=--spring.liquibase.enabled=false >"${log_file}" 2>&1 &
    echo "$!" >"${pid_file}"
    echo "Started ${module_name} in phase2 mode (pid=$!, log=${log_file})"
  )
}

stop_managed_service_if_running() {
  local module_name="$1"
  local pid_file="${run_dir}/${module_name}-phase2.pid"

  if [[ -f "${pid_file}" ]]; then
    local pid
    pid="$(cat "${pid_file}")"

    if ps -p "${pid}" -o command= | grep -F "${repo_root}" >/dev/null 2>&1; then
      kill "${pid}"
      wait "${pid}" 2>/dev/null || true
    fi

    rm -f "${pid_file}"
  fi
}

require_cmd docker
require_cmd mvn

docker compose -f "${phase2_compose}" up -d
wait_for_pg "${phase2_compose}" postgres-shared shopdb

stop_managed_service_if_running user-service
stop_managed_service_if_running product-service
stop_managed_service_if_running order-service

run_liquibase changelogSync user-service public user_schema public
run_liquibase changelogSync product-service public product_schema public
run_liquibase update order-service public order_schema public

start_phase2_service user-service
start_phase2_service product-service
start_phase2_service order-service

cat <<EOF
Phase 2 is ready.

- Shared PostgreSQL: localhost:5432/shopdb
- User service:    http://localhost:8081
- Product service: http://localhost:8082
- Order service:   http://localhost:8083

Logs are under: ${log_dir}
EOF
