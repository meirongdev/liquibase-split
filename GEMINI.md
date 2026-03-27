# Liquibase Split POC — GEMINI Context

This project is a Proof of Concept (POC) demonstrating how to progressively split a monolithic Liquibase database into independent microservice databases with minimal downtime.

## Project Overview

*   **Goal:** Decompose a monolithic database into three microservice-owned databases using a three-phase approach.
*   **Main Technologies:** Java 25, Spring Boot 3.5, Liquibase, PostgreSQL, Docker Compose, Maven, Testcontainers.
*   **Architecture:**
    *   `monolith`: Represents the initial state (Phase 1).
    *   `user-service`: Owns the `users` table.
    *   `product-service`: Owns the `products` table.
    *   `order-service`: Owns `orders`, `order_items`, and `payments` tables.

## Three-Phase Migration Strategy

| Phase | Description | Database Setup | Changelog Strategy |
| :--- | :--- | :--- | :--- |
| **Phase 1** | Monolith | Single shared DB (`shopdb`) | Single shared changelog with FK constraints. |
| **Phase 2** | Shared DB, Split Changelogs | Single shared DB (`shopdb`) | Each service owns its changelog; `DATABASECHANGELOG` isolated via PostgreSQL schemas. |
| **Phase 3** | Independent DBs | Dedicated DB per service | Each service has its own DB; cross-service FKs removed; dual-write window for migration. |

## Key Commands

### Building and Testing
*   **Build everything:** `mvn clean install`
*   **Run all tests:** `mvn test` (uses Testcontainers)

### Running Phases
*   **Phase 1 (Monolith):**
    ```bash
    docker compose -f docker-compose-phase2.yml up -d
    mvn -pl monolith spring-boot:run
    ```
*   **Phase 2 (Split Changelogs):**
    ```bash
    ./scripts/phase1-to-phase2.sh
    ```
*   **Phase 3 (Independent DBs):**
    ```bash
    ./scripts/phase2-to-phase3.sh
    ```

## Development Conventions

### Liquibase Management
*   **Master Changelog:** Use `<includeAll path="schema/" />` and `<includeAll path="data/" />` to avoid merge conflicts.
*   **File Naming:** Use lexicographic naming (e.g., `001-create-table.xml`) to control execution order.
*   **Zero-Downtime Changes:** Follow the **Expand-Contract** pattern (Add column -> Sync data -> Drop old column).
*   **Schema Isolation (Phase 2):** Each service uses its own PostgreSQL schema for the `DATABASECHANGELOG` table to prevent collisions in the shared database.

### Codebase Structure
*   `monolith/`: Phase 1 implementation.
*   `user-service/`, `product-service/`, `order-service/`: Microservice implementations.
*   `scripts/`: Automation for migration phases.
*   `docker/`: Database initialization scripts.
*   `docs/`: Design specifications and migration guides.

## Testing Strategy
Each module includes an integration test (e.g., `MonolithLiquibaseTest`, `UserServiceLiquibaseTest`) using Testcontainers to verify:
*   Table existence and schema correctness.
*   Presence or absence of Foreign Key constraints according to the current phase.
*   Successful Liquibase migration execution.
