# Private invitations Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task; only narrowly scoped Luna/xhigh source review, no additional Astra.

**Goal:** Authenticated people explicitly join a private server through a bounded, revocable invitation without starting voice or sharing.

**Architecture:** V11 stores invitation metadata and a SHA-256 token hash, never the raw token. Caller generates32 cryptographically random bytes (canonical unpadded base64url43 chars) and retains its stable invitation UUID/creation payload for retries. Backend derives expiry from server time, with TTL15min..7days, maxUses1..1000. Owner create/revoke and authenticated acceptance share the UPDATE access fence; acceptance receipts keyed by actor/operation prevent old retries rejoining after leave.

**Tech Stack:** Kotlin/JDBC/PostgreSQL; existing community ACL/outbox.

**Spec:** docs/superpowers/specs/2026-10-03-nearby-voice-spaces-design.md. Legacy invitation codes are not transferred.

## Constraints

- Current active owner creates/revokes; no raw token in SQL/outbox/result/error/logs. Future HTTP sends secret in bounded body, never server URL/query.
- Stable create checks original actor/community/TTL/maxUses/token hash, cannot extend expiry or revive revoked IDs. Global token hash uniqueness rejects reuse under a different invitation ID.
- Expiry is inclusive. Active user, active server, no ban and unrevoked invitation are checked in one acceptance transaction. New members consume a use; existing members consume none.
- Invitation acceptance is permanent membership only. No claim, credentials, capture, position or default-channel join.
- Actor-owned stable acceptance operation records invitation/revision and whether membership changed. Replay is a historical receipt, not a new grant; it never recreates membership after leave or consumes another use. Changed token under the same operation conflicts.
- Revocation is idempotent and cannot eject existing members. Resource tombstones/account deletion automatically deny new accepts through current ACL.
- Sender token generation/persistence and HTTP/client/UI are subsequent integration work; repository fixtures do not prove entropy.

## Task1: Schema and JDBC invitation lifecycle

- [x] Author meaningful actual-PG tests before APIs: owner create/replay/expiry stability/hash-only storage; private explicit acceptance/no voice/sharing; repeat and already-member no extra use; expired/revoked/banned/deleted denial; final-use competing accepts; owner-only idempotent revoke/current-owner transfer; create conflict/token reuse; receipt replay after departure; outbox rollback of membership/use/receipt; metadata bounds/canonical token; acceptance-vs-revoke issued SQL barriers.
- [x] Observe missing API RED; count V1..V10 and add V11, update migration count tests explicitly.
- [x] Split files: InvitationModels/InvitationTokens; JdbcInvitationRepository create/revoke; JdbcInvitationAcceptanceRepository accept; narrow shared JDBC read helpers only if needed.
- [x] Scoped reviews plus exact actual-PG tests, relevant schema/startup regressions, fresh Android/backend suites, evidence and commit. No production deployment.

Then authenticated channel/invitation HTTP and matching client commands; durable
authorized roster delivery and v2 sharing, Android runtime integration, UI assembly
and direct calls remain the approved subsequent work.
