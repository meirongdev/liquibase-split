# Liquibase Split POC Migration Guide

This POC demonstrates a progressive migration path from a monolith with one shared changelog to service-owned changelogs and finally to service-owned databases.

## Prerequisites

- Java 25
- Maven
- Docker with `docker compose`

The helper scripts use the Liquibase Maven plugin directly, so no standalone Liquibase CLI is required.

## Phase 1: monolith

Start the shared database:

```bash
docker compose -f docker-compose-phase2.yml up -d
```

Run the monolith:

```bash
mvn -q -pl monolith spring-boot:run
```

What to observe:

- One PostgreSQL database: `shopdb`
- One Liquibase history table in `public.databasechangelog`
- Five business tables in the `public` schema
- Cross-domain foreign keys from `orders.user_id`, `order_items.product_id`, `order_items.order_id`, and `payments.order_id`

## Phase 2: split changelogs in one shared database

Run the helper script:

```bash
./scripts/phase1-to-phase2.sh
```

The script does three things:

1. Starts `docker-compose-phase2.yml`
2. Runs `changelogSync` for `user-service` and `product-service`, then applies the `order-service` migration changelog to remove cross-service foreign keys and add the expand-contract demo column
3. Starts the three services in `phase2` mode with Spring-side Liquibase disabled, because the script has already applied the migration step explicitly

What to observe:

- The database is still `shopdb`
- Business tables still live in the shared `public` schema
- `user-service` uses `user_schema.databasechangelog`
- `product-service` uses `product_schema.databasechangelog`
- `order-service` uses `order_schema.databasechangelog`
- `order-service` no longer has cross-service foreign keys on `user_id` and `product_id`

## Phase 3: independent databases

Run the phase transition helper:

```bash
./scripts/phase2-to-phase3.sh
```

The script:

1. Starts `docker-compose-phase3.yml`
2. Applies each service changelog to its own database
3. Copies the phase2 data set from the shared `public` schema in `shopdb` into `userdb`, `productdb`, and `orderdb`
4. Starts `user-service` and `product-service` against their independent databases with Spring-side Liquibase disabled
5. Starts `order-service` with `phase2,migration` profiles so the shared DB remains attached while the target DB is available

After verification, switch `order-service` to its normal profile:

```bash
mvn -q -pl order-service spring-boot:run
```

What to observe:

- `user-service` uses `localhost:5433/userdb`
- `product-service` uses `localhost:5434/productdb`
- `order-service` target DB is `localhost:5435/orderdb`
- `orders.user_id` and `order_items.product_id` remain plain `BIGINT` values
- The expand-contract demo in `order-service` migrates from `user_name` to `username`

## Useful verification commands

Run module tests:

```bash
mvn -q -pl monolith,user-service,product-service,order-service test
```

Validate compose files:

```bash
docker compose -f docker-compose-phase2.yml config
docker compose -f docker-compose-phase3.yml config
```
