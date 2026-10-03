# Nearby backend session lifecycle Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Primary owns complex
> implementation. Only bounded Luna/xhigh checks with complete context. No Astra.
> User authorized continuous local execution; no production deployment here.

**Goal:** Build a durable, cancellable v2 channel-session backend foundation before
exposing server/channel UI or replacing legacy voice endpoints.

**Architecture:** A pure operation policy feeds PostgreSQL transactions locking
an account's claim and immutable operation. Terminal cancellation persists even
before join. External SFU actions run outside SQL locks and are reconciled through
durable intent; tokens appear only after current lease registration is confirmed.
Roster is a revisioned backend snapshot, independent of SDK tracks/membership.

**Tech Stack:** Existing Kotlin/JVM21, Ktor3.3.1, JDBC/PostgreSQL, kotlin.test/JUnit5.

**Spec:** `docs/superpowers/specs/2026-10-03-nearby-foundation-contracts.md`, approved
voice-spaces design and single-node SFU admission evidence of the same date.

## Constraints

- No deployment, remote dirty-file edits, BLE changes, old group deletion or v1
  cutover in this plan. No credentials in logs/toString; no LiveKit stock revoke.
- Membership persists across disconnect; active session/claim is separate.
- Immutable operationId belongs to one actor and destination; mismatched replay
  is rejected. Terminal operations do not resurrect. New action uses a new id.
- Server deadlines use server clock; finite pending and active windows. Heartbeat
  cannot renew after expiry. Local core independently stops after unconfirmed lease.
- SQL commit precedes externally issued effects; rollback does not publish tokens.
  SFU ACK arriving after cancel must trigger cleanup, not activation.
- SFU node readiness/epoch is authenticated and checked; restart loses registry
  and requires reconciliation of durable current state. Stock node fails closed.
- Single-node prototype is not a production readiness guarantee. Capacity and
  cleanup failure must be observable, retryable and unable to enable admission.
- Tests assert issued effects/state, not sleeps. Fresh Android suite before commit.

### Task 1: Pure immutable operation policy

Files: `backend/src/{main,test}/kotlin/ru/sodovaya/volty/backend/nearby/SessionOperation*.kt`.

- [x] Write failing tests for cancel-before-join, actor/destination replay mismatch,
  pending timeout, late admission ACK, exact-deadline heartbeat rejection,
  duplicate begin/ACK preserving deadline, terminal stop and old-session ACK.
- [x] Run backend targeted test RED; implement explicit begin, cancel, admit,
  heartbeat and expire transitions. Return an explicit rejection or next immutable
  state. Keep pure policy separate from IO/auth/transactions.
- [x] Fresh targeted GREEN and backend suite; record exact tests. No API wiring
  or assertion that in-memory policy is durable.

### Task 2: PostgreSQL operation/claim journal

Files: `nearby/SessionRepository.kt`, `nearby/JdbcSessionRepository.kt`, migration
`V2__nearby_session_journal.sql`, additive `SchemaMigrator` wiring and integration tests.

- [x] Inspect current migration count and test baseline before adding version.
- [x] Persist actor-scoped operation tombstones, destination, session/claim id,
  pending/active deadlines, node epoch and cleanup intent. Use per-account locking
  in one transaction; no network call under DB lock.
- [x] Test concurrent cancel/join in both lock orders, lost-response replay,
  replacement cleanup ownership, rollback and restart replay on disposable DB.
  Preserve existing accounts/groups/friends; migration runs once and rolls back.

### Task 3: Durable coordinator and SFU integration

Files: `nearby/SessionService.kt`, `nearby/SfuLeaseClient.kt`, bounded reconciler/tests.

- [ ] Versioned authenticated readiness plus node epoch/capacity health must be
  implemented in the SFU candidate before enablement; missing readiness rejects.
- [ ] Register only committed pending intent, recheck operation/claim after ACK,
  then activate and issue identity=sessionId JWT. Late ACK dispatches revoke.
- [ ] Cancel/stop persist terminal intent first, revoke outside transaction, retain
  retryable cleanup until ACK; release only matching claim. Heartbeat renewal
  rechecks active state after external response. Sweep pending/expired independently.
- [ ] Test offline/backend restart/SFU restart, lost ACK, partial cleanup, timeout,
  raced replacement and original/refreshed token admission through isolated runtime.

### Task 4: v2 API and authoritative roster boundary

Files: `nearby/SessionRoutes.kt`, `nearby/SessionDtos.kt`, route contract tests.

- [ ] Authenticated operation join/status/cancel, session heartbeat/disconnect;
  require channel membership through destination resolver (no arbitrary room names).
- [ ] Revisioned roster snapshots reflect committed active sessions. Duplicate
  join/reconnect does not duplicate people; revisions increment on actual change.
- [ ] Keep v2 unavailable until repository/SFU/resolver readiness passes. No v1
  mutations of v2 state. Public catalog/channel CRUD is a separate additive plan;
  direct calls build on this policy with their own accept/cancel transaction.
- [ ] Full backend and Android suites; no UI/device claims; record release limits.

## Execution order

Task 1 can run now against local backend. Tasks 2–4 require disposable PostgreSQL
and isolated fork runtime; inspect existing local tools first. Remote production
Docker availability does not authorize using production DB as a test fixture.
