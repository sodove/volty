# Exact channel deletion Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task; scoped Luna/xhigh review only, no additional Astra.

**Goal:** Owner deletion closes only exact channel sessions, preserves permanent membership and never moves users to another channel.

**Architecture:** UPDATE-fenced transaction, sorted affected actor locks before revisions, channel tombstone and V9 actor/operation-owned immutable receipt/targets. Replay preserves original channel/session identities after new joins. Current active owner authorizes first mutation; receipt replay is owned by its original active actor. No schema addition or SFU network under SQL locks.

**Tech Stack:** Kotlin/JDBC/PostgreSQL.

**Spec:** docs/superpowers/specs/2026-10-03-nearby-voice-spaces-design.md.

## Constraints

- Any channel, including default/last, may be deleted. No automatic replacement/default promotion; server and membership persist.
- Exact current claim identity/epoch; no cleanup guessing on incoherence; remote cleanup remains pending until actual acknowledgement.
- V9 operation namespace shared with community deletion: conflict must not retarget either operation.
- No UI, deployment or claim that v2 sharing is wired; complete that revoke boundary before production removal enablement.

## Task1

- [ ] Author ten actual PG cases before APIs: exact channel-only cleanup; default/last empty-server policy; replay/new claim; member/unknown/foreign denial; operation conflict both resource types; outbox rollback; incoherent claim rollback; deleted UUID re-admission; issued-query credential/delete and delete/join fences.
- [ ] Observe missing API RED; implement ChannelDeletionResult, JdbcChannelDeletionRepository and exact channel claim helper.
- [ ] PG10, scoped review; add self-consistent foreign-destination/target-room incoherence regression (11th), observe PG1 RED then reject rather than skip. Final regressions creation7/community deletion8/channel deletion11 = PG26, fresh unit suites and evidence before commit.

Next: authenticated channel management HTTP/client commands and private invitations;
authorized roster delivery/sharing/Android integration remain required.
