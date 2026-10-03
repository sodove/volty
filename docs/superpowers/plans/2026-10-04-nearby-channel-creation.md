# Owner channel creation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task; narrow Luna/xhigh review only.

**Goal:** An owner can create additional voice channels with stable replay and no implicit voice/position action.

**Architecture:** UPDATE-fenced JDBC transaction authorizes current active owner, checks an immutable creation receipt keyed by channel UUID, enforces at most128 active channels, writes channel/receipt/common outbox atomically. V10 adds creation receipts; V1..V9 stay immutable. Deleted channel IDs cannot be reused; default-channel collision does not count as caller-owned creation.

**Tech Stack:** Kotlin/JDBC/PostgreSQL.

**Spec:** docs/superpowers/specs/2026-10-03-nearby-voice-spaces-design.md, owner channel management.

## Constraints

- No UI or production deployment. No implicit membership/voice/sharing creation.
- Access UPDATE fence before revision, no network under locks; active owner checked within the transaction.
- Names normalized and bounded80/no ISO controls. Stable UUID and actor/community/name payload hash; exact replay adds no revision, conflict never mutates.
- Fresh actual PostgreSQL, backend/Android exact counts before commit. No mutation-sweep or device claim.

## Task1

- [ ] Author six JdbcSessionChannelCreationTest cases before APIs: creation/no voice mutation; stable replay/conflicting payload/foreign IDs; member/former-owner denial; cap128 including default with exact replay allowed; outbox rollback; deleted parent/deleted actor/invalid name rejection.
- [ ] Observe missing API RED; implement result/repository and V10 receipt schema, count migrations and update three historical count expectations.
- [ ] Run exact PG6, then PG36 (creation6 + repository21 + historical migration4 + startup retirement5); the owned disposable harness accepts multiple --tests filters without changing count/cleanup checks. Review identified default+matching-receipt incoherence: add seventh test, observe actual PG1 RED, reject is_default replay, run final PG7. Fresh full unit suites and commit evidence.

Next: exact channel deletion using V9 receipts/cleanup, authenticated HTTP/client
commands, private invitation lifecycle, authorized realtime delivery and v2 sharing.
