# No legacy group transfer Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task; only narrow Luna/xhigh source review, no additional Astra.

**Goal:** Preserve accounts/friends and make users create new servers, without exposing historical V7 group imports as active servers.

**Architecture:** Keep immutable V1..V9 migrations. An UPDATE-fenced startup transaction tombstones active communities with null immutable create_request_hash and their channels, closes exact affected voice claims, retains cleanup work and historical owner-membership FK rows. Native v2 servers and legacy tables are preserved. HTTP dependency creation must run this step after schema migration and fail closed on errors.

**Tech Stack:** Kotlin/JDBC/PostgreSQL, existing session journal/outbox.

**Spec:** docs/superpowers/specs/2026-10-03-nearby-voice-spaces-design.md, user scope retraction 2026-10-04.

## Constraints

- No rewriting historical migrations, deletion of accounts/friends/legacy groups, production deployment or UI work.
- All actor locks sorted before any revision; UPDATE access fence before actor locks; no network under SQL locks.
- Historical null-hash imports differ from native v2 nonnull-hash creation; names and current legacy membership are not origin evidence.
- Actual PostgreSQL and fresh Android/backend suites; no device or mutation-sweep claims.

## Task 1: Retire imports before HTTP startup

- [ ] Author five actual-PG cases in JdbcSessionLegacyRetirementTest: fresh V1 bootstrap preserves legacy/friends but no active imports; native v2 preservation; exact old-claim cleanup and unrelated voice; outbox-failure rollback; repeated bootstrap/late legacy creation do not transfer groups.
- [ ] Observe missing API compile RED, then implement JdbcLegacyImportRetirement and BackendSchemaBootstrap.
- [ ] Connect AppDependencies.create to the tested bootstrap before constructing HTTP dependencies.
- [ ] Run exact PG5, narrow source review, full backend and Android, record evidence and commit.

## Following work

Owner channel create/delete and private invitation foundations, authorized realtime
delivery, v2 sharing, Android integration, then UI assembly and direct calls.
Final legacy-group backfill/reconciliation is cancelled; old write upgrade boundary
and deliberate termination of legacy live sessions remain rollout requirements.
