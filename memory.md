# Project Memory

## liquibase-split POC project context

This is an active POC project at /Users/matthew/projects/meirongdev/liquibase-split.

**Goal:** Demonstrate 3-phase progressive Liquibase migration split (monolith → shared DB split changelogs → independent DBs), with zero/minimal downtime strategy.

**Tech stack:** Spring Boot 3.5, Java 25, Liquibase, PostgreSQL, Testcontainers, Docker Compose, Maven multi-module.

**Domain:** E-commerce (users, products, orders, order_items, payments).

**3 services:** user-service, product-service, order-service (order includes payment).

**Why:** Educational POC to show the realistic incremental path for DB decomposition, not big-bang cutover.

**Resume guidance:** When resuming this project, read the design spec at `docs/superpowers/specs/2026-03-27-liquibase-split-design.md` and the implementation plan at `/Users/matthew/.claude/plans/recursive-wibbling-otter.md` for full context.

## Implementation Progress

- Step 1 (Parent POM): ✅ DONE — committed
- Step 2 (Monolith): ✅ DONE
- Step 3 (User Service): ✅ DONE
- Step 4 (Product Service): ✅ DONE
- Step 5 (Order Service): ✅ DONE
- Step 6 (Docker Compose): ✅ DONE
- Step 7 (Scripts & docs): ✅ DONE
