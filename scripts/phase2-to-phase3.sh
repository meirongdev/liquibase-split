#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
phase2_compose="${repo_root}/docker-compose-phase2.yml"
phase3_compose="${repo_root}/docker-compose-phase3.yml"
log_dir="${TMPDIR:-/tmp}/liquibase-split-phase3-logs"
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

run_liquibase_update() {
  local module_name="$1"
  local jdbc_url="$2"
  local schema_name="$3"
  local service_schema="$4"

  mvn -q -pl "${module_name}" "org.liquibase:liquibase-maven-plugin:${liquibase_plugin_version}:update" \
    -Dliquibase.changeLogFile=src/main/resources/db/changelog/db.changelog-master.xml \
    -Dliquibase.url="${jdbc_url}" \
    -Dliquibase.username=postgres \
    -Dliquibase.password=postgres \
    -Dliquibase.defaultSchemaName="${schema_name}" \
    -Dliquibase.liquibaseSchemaName="${schema_name}" \
    -Dservice_schema="${service_schema}"
}

copy_csv_between_databases() {
  local source_query="$1"
  local target_service="$2"
  local target_database="$3"
  local target_copy_clause="$4"

  docker compose -f "${phase2_compose}" exec -T postgres-shared \
    psql -U postgres -d shopdb -c "\\copy (${source_query}) TO STDOUT WITH CSV" \
    | docker compose -f "${phase3_compose}" exec -T "${target_service}" \
      psql -U postgres -d "${target_database}" -c "\\copy ${target_copy_clause} FROM STDIN WITH CSV"
}

reset_sequence() {
  local target_service="$1"
  local target_database="$2"
  local table_name="$3"

  docker compose -f "${phase3_compose}" exec -T "${target_service}" \
    psql -U postgres -d "${target_database}" \
    -c "SELECT setval(pg_get_serial_sequence('${table_name}', 'id'), COALESCE((SELECT MAX(id) FROM ${table_name}), 1), true);"
}

run_sql() {
  local target_service="$1"
  local target_database="$2"
  local sql_statement="$3"

  docker compose -f "${phase3_compose}" exec -T "${target_service}" \
    psql -U postgres -d "${target_database}" -c "${sql_statement}"
}

start_service() {
  local module_name="$1"
  local profiles="$2"
  local log_file="${log_dir}/${module_name}.log"
  local pid_file="${run_dir}/${module_name}-phase3.pid"
  local mvn_args=( -q -pl "${module_name}" spring-boot:run )

  if [[ -n "${profiles}" ]]; then
    mvn_args+=( "-Dspring-boot.run.profiles=${profiles}" )
  fi

  (
    cd "${repo_root}"
    nohup mvn "${mvn_args[@]}" -Dspring-boot.run.arguments=--spring.liquibase.enabled=false >"${log_file}" 2>&1 &
    echo "$!" >"${pid_file}"
    echo "Started ${module_name} (pid=$!, log=${log_file})"
  )
}

stop_managed_service_if_running() {
  local pid_file="$1"

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

stop_managed_service_if_running "${run_dir}/user-service-phase2.pid"
stop_managed_service_if_running "${run_dir}/product-service-phase2.pid"
stop_managed_service_if_running "${run_dir}/order-service-phase2.pid"
stop_managed_service_if_running "${run_dir}/user-service-phase3.pid"
stop_managed_service_if_running "${run_dir}/product-service-phase3.pid"
stop_managed_service_if_running "${run_dir}/order-service-phase3.pid"

docker compose -f "${phase3_compose}" up -d
wait_for_pg "${phase3_compose}" user-db userdb
wait_for_pg "${phase3_compose}" product-db productdb
wait_for_pg "${phase3_compose}" order-db orderdb

run_liquibase_update user-service "jdbc:postgresql://localhost:5433/userdb" public public
run_liquibase_update product-service "jdbc:postgresql://localhost:5434/productdb" public public
run_liquibase_update order-service "jdbc:postgresql://localhost:5435/orderdb" public public

run_sql user-db userdb "TRUNCATE TABLE users RESTART IDENTITY CASCADE"
run_sql product-db productdb "TRUNCATE TABLE products RESTART IDENTITY CASCADE"
run_sql order-db orderdb "TRUNCATE TABLE payments, order_items, orders RESTART IDENTITY CASCADE"

copy_csv_between_databases \
  "SELECT id, username, email, password_hash, created_at FROM public.users ORDER BY id" \
  user-db userdb \
  "users (id, username, email, password_hash, created_at)"

copy_csv_between_databases \
  "SELECT id, name, description, price, created_at FROM public.products ORDER BY id" \
  product-db productdb \
  "products (id, name, description, price, created_at)"

copy_csv_between_databases \
  "SELECT id, user_id, username, status, total_amount, created_at FROM public.orders ORDER BY id" \
  order-db orderdb \
  "orders (id, user_id, username, status, total_amount, created_at)"

copy_csv_between_databases \
  "SELECT id, order_id, product_id, quantity, unit_price FROM public.order_items ORDER BY id" \
  order-db orderdb \
  "order_items (id, order_id, product_id, quantity, unit_price)"

copy_csv_between_databases \
  "SELECT id, order_id, amount, status, paid_at FROM public.payments ORDER BY id" \
  order-db orderdb \
  "payments (id, order_id, amount, status, paid_at)"

reset_sequence user-db userdb users
reset_sequence product-db productdb products
reset_sequence order-db orderdb orders
reset_sequence order-db orderdb order_items
reset_sequence order-db orderdb payments

start_service user-service ""
start_service product-service ""
start_service order-service "phase2,migration"

cat <<EOF
Phase 3 preparation is complete.

- User service now points at userdb on localhost:5433
- Product service now points at productdb on localhost:5434
- Order service is running with phase2,migration profiles so the shared DB stays available while orderdb is attached as the target datasource

Logs are under: ${log_dir}
EOF
