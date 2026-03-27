# Liquibase Split POC — Design Spec

**Date:** 2026-03-27
**Tech stack:** Spring Boot 3.5 + Java 25 + Liquibase + PostgreSQL + Testcontainers + Docker Compose
**Domain:** E-commerce order system

---

## Goal

Demonstrate how to progressively split Liquibase database migrations when decomposing a monolithic application into microservices, with zero or minimal downtime at each stage.

---

## Three-Phase Migration

| Phase | Database | Changelog | Downtime |
|-------|----------|-----------|----------|
| Phase 1 — Monolith | Single shared DB | Single changelog, all tables + FK constraints | — |
| Phase 2 — Shared DB, split changelogs | Single shared DB | Each service owns its changelog; DATABASECHANGELOG isolated per schema | Zero downtime |
| Phase 3 — Independent DBs | Each service has its own DB | Each service owns its changelog; cross-service FKs removed | Minimal (dual-write window) |

---

## Domain Model

### Phase 1 (Monolith) — with cross-domain FK constraints

```sql
users         (id, username, email, password_hash, created_at)
products      (id, name, description, price, created_at)
orders        (id, user_id → users.id, status, total_amount, created_at)
order_items   (id, order_id → orders.id, product_id → products.id, quantity, unit_price)
payments      (id, order_id → orders.id, amount, status, paid_at)
```

### Phase 2 & 3 (Microservices) — no cross-service FK constraints

| Service | Owns | Note |
|---------|------|------|
| user-service | `users` | Fully independent |
| product-service | `products` | Fully independent |
| order-service | `orders`, `order_items`, `payments` | `user_id` and `product_id` are plain BIGINT, no REFERENCES |

---

## Key Design Decisions

### 1. Phase 2: DATABASECHANGELOG isolation via PostgreSQL schemas

Each service uses a dedicated PostgreSQL schema so its `DATABASECHANGELOG` table doesn't conflict with others in the same database:

```yaml
# user-service application-phase2.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/shopdb
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
    default-schema: user_schema      # DATABASECHANGELOG lives here
    liquibase-schema: user_schema
```

Before starting services in Phase 2, run `changelogSync` to mark monolith-applied changesets as already executed (prevents duplicate table creation):

```bash
liquibase --defaultSchemaName=user_schema changelogSync
```

### 2. Changelog master structure (mixed / includeAll)

Each microservice uses a master changelog that delegates to subdirectories. Multiple developers can add files to `schema/` without editing the master file — no merge conflicts:

```xml
<databaseChangeLog>
    <includeAll path="schema/" relativeToChangelogFile="true"/>
    <includeAll path="data/"   relativeToChangelogFile="true"/>
</databaseChangeLog>
```

Files are loaded in lexicographic order, so naming convention `NNN-description.xml` controls execution order.

### 3. Expand-Contract pattern for zero-downtime schema changes

When renaming or restructuring columns during Phase 2 (while old and new code both run):

```xml
<!-- Step 1 — Expand: add new column, old code still writes old column -->
<addColumn tableName="users">
    <column name="username" type="VARCHAR(100)"/>
</addColumn>

<!-- Step 2 — Sync data -->
<sql>UPDATE user_schema.users SET username = user_name WHERE username IS NULL</sql>

<!-- Step 3 — Contract: after all traffic migrated to new code, drop old column -->
<dropColumn tableName="users" columnName="user_name"/>
```

### 4. Phase 3: Dual-write window (Spring Profile controlled)

During Phase 2→3 database separation, a `migration` Spring profile enables writing to both the shared DB and the new independent DB simultaneously:

```java
@Configuration
@Profile("migration")
public class DualWriteConfig {
    // Routes writes to both DataSources
    // Routes reads to the new independent DB
}
```

Sequence:
1. Provision new DB, run Liquibase to create schema
2. Bulk-copy data (Liquibase `<sql>` changeset or external script)
3. Enable `migration` profile — dual-write begins
4. Verify consistency
5. Switch profile to normal — writes go to new DB only
6. Run contract changesets to clean up old tables from shared DB

---

## Project Structure

```
liquibase-split/
├── pom.xml                            # parent pom (Java 25, Spring Boot 3.5)
├── monolith/                          # Phase 1
│   ├── pom.xml
│   └── src/main/resources/db/changelog/
│       ├── db.changelog-master.xml    # includeAll migrations/
│       └── migrations/
│           ├── 001-create-users.xml
│           ├── 002-create-products.xml
│           ├── 003-create-orders.xml       # FK → users
│           ├── 004-create-order-items.xml  # FK → orders, products
│           └── 005-create-payments.xml     # FK → orders
├── user-service/
│   ├── pom.xml
│   └── src/main/resources/
│       ├── application.yml            # Phase 3: independent DB (port 5433)
│       ├── application-phase2.yml     # Phase 2: shared DB + schema isolation
│       └── db/changelog/
│           ├── db.changelog-master.xml
│           ├── schema/
│           │   ├── 001-create-users.xml
│           │   └── 002-add-user-index.xml
│           └── data/
│               └── 001-seed-users.xml
├── product-service/                   # mirrors user-service structure
├── order-service/
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../order/DualWriteConfig.java
│       └── main/resources/
│           ├── application.yml
│           ├── application-phase2.yml
│           ├── application-migration.yml  # dual-write profile
│           └── db/changelog/
│               ├── db.changelog-master.xml
│               ├── schema/
│               │   ├── 001-create-orders.xml       # user_id BIGINT, no FK
│               │   ├── 002-create-order-items.xml  # product_id BIGINT, no FK
│               │   ├── 003-create-payments.xml
│               │   ├── 004-expand-add-username.xml # Expand-Contract demo
│               │   └── 005-contract-drop-old-col.xml
│               └── data/
│                   └── 001-seed-orders.xml
├── docker-compose-phase2.yml          # single PG, creates 3 schemas
├── docker-compose-phase3.yml          # 3 independent PG instances
├── scripts/
│   ├── phase1-to-phase2.sh            # changelogSync + service startup
│   └── phase2-to-phase3.sh            # data migration + dual-write steps
└── docs/
    ├── migration-guide.md             # step-by-step operator guide
    └── superpowers/specs/
        └── 2026-03-27-liquibase-split-design.md  # this file
```

---

## Verification

### Automated tests (Testcontainers, runs via `mvn test`)

| Module | Test class | What it verifies |
|--------|-----------|-----------------|
| monolith | `MonolithLiquibaseTest` | All 5 tables exist; cross-domain FK constraints exist |
| user-service | `UserServiceLiquibaseTest` | `users` table exists; no FK to other services |
| product-service | `ProductServiceLiquibaseTest` | `products` table exists |
| order-service | `OrderServiceLiquibaseTest` | `orders`, `order_items`, `payments` exist; `user_id`/`product_id` have NO FK constraints |

### Manual demo

```bash
# Phase 1
docker compose -f docker-compose-phase2.yml up -d
cd monolith && mvn spring-boot:run
# → Observe: single DATABASECHANGELOG table, all 5 tables, FK constraints

# Phase 2 (same DB, split changelogs)
cd user-service && mvn spring-boot:run -Dspring.profiles.active=phase2
# → Observe: user_schema.DATABASECHANGELOG now separate from public.DATABASECHANGELOG

# Phase 3 (independent DBs)
docker compose -f docker-compose-phase3.yml up -d
cd user-service    && mvn spring-boot:run  # connects to port 5433
cd product-service && mvn spring-boot:run  # connects to port 5434
cd order-service   && mvn spring-boot:run  # connects to port 5435
```

---

## Implementation Steps (for reference)

1. **Parent POM** — Java 25, Spring Boot 3.5, Testcontainers BOM, 4 modules ✅ DONE
2. **Monolith module** — 5 changelogs with FK constraints, JPA entities, integration test
3. **User Service** — schema + data changelogs, phase2 config, integration test
4. **Product Service** — same structure as user-service
5. **Order Service** — 3 schema changesets (no cross-FK), Expand-Contract demo, DualWriteConfig, integration test
6. **Docker Compose** — phase2 (single DB) and phase3 (3 DBs)
7. **Scripts & docs** — migration shell scripts, migration-guide.md
