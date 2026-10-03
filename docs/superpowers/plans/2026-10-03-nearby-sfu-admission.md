# Nearby SFU admission prototype Implementation Plan

> **For agentic workers:** Use executing-plans. Primary owns implementation;
> Luna/xhigh may review one bounded invariant with full context. No Astra.
> User authorized continuous execution without approval handoffs.

**Goal:** Measure an SFU-internal admission/lease boundary that rejects revoked
original and refreshed JWTs and closes existing media before revoke completes.

**Architecture:** A reversible overlay on pinned LiveKit1.13.5, tested only on
loopback. Register/renew/revoke use existing verified room-admin authorization.
RTC-node StartSession and revoke serialize on the same per-session gate. Each
immutable `(room epoch, participant identity=sessionId)` has a finite deadline;
unknown, revoked and expired sessions fail closed. Empty registry after process
restart admits nobody until trusted backend registers a current session.

**Tech Stack:** Go1.26 toolchain, pinned LiveKit SFU1.13.5 and existing synthetic
RTP harness/server-sdk-go2.13.3. Pure gate stdlib module + reproducible overlay.

**Spec:** foundation-contracts and sfu-revocation-evidence under docs/superpowers.

## Global Constraints

- No production changes, SSH writes, deployment, Cloud switch, BLE/FFE1 edits.
- Preserve original audited sources; copy to this plan's ignored workspace.
- Prototype is single-node only; no claim of distributed/persistent readiness.
- No bearer/raw JWT/logged HTTP Authorization. Disposable loopback credentials.
- LiveKit current Close is asynchronous for transport. Revoke must await an
  explicit transport/subscription close completion, not IsClosed alone.
- Gate retains terminal tombstones; bounded capacity rejects rather than evicts.
  Register/renew cannot resurrect expired/revoked sessions. A new session uses a
  new identity. Backend must persist terminal operation state before registering.
- Refreshed tokens preserve room/identity, never extend the application deadline.
- No fake webhook acknowledgement. Initial admission executes at the RTC node,
  before room resume/create; expiry sweep closes current media independently.
- Go unit tests plus fresh Android suite before commits; actual RTP runtime proof
  separate. No Android audibility claim. Keep probe sources and nonce/hash evidence.

## Review Focus

1. Admission pending while revoke races: never attach after acknowledged revoke.
2. Cleanup failure keeps admission terminal and allows cleanup retry.
3. Expiry cannot be renewed/re-registered; SDK refresh cannot bypass gate.
4. No participant token/wrong-room admin can mutate leases.
5. Missing register, restart, expiry and capacity exhaustion fail closed.

### Task 1: Gate and reproducible source overlay

**Files:** tools/nearby-sfu-gate/go.mod, lease/gate.go, lease/gate_test.go;
prepare-overlay.ps1; overlay/service/voltyleases.go. Patch hashes recorded.

- [x] Write Go tests before implementation: unknown denied; active admitted;
  admission held on a channel then revoke waits/cleanup runs; revoked/expired
  register and renew denied; failed close retained and retry; capacity full
  rejects without evicting existing tombstones; expiry sweep closes media.
- [x] Run RED `go test -count=1 ./lease`; implement per-entry locks and clock
  injection. Run GREEN. Use channel preconditions, not sleeps as evidence.
- [x] Overlay validates pinned source SHA before copies/edits; adds RTC-node
  StartSession wrapper, signed admin controls, expiry worker and explicit media
  close completion. Build patched binary; original baseline binary unchanged.
- [x] Inspect alternate admission paths (WHIP/resume) and fail startup for Redis
  single-node prototype to avoid accidental distributed use. Gate auth tests.
- [x] Narrow invariant review, full fresh Android suite, diff/commit.

### Task 2: Runtime matrix on disposable SFU

**Files:** tools/nearby-sfu-gate/probe (existing harness adapted, pinned go locks);
own ignored workspace config/logs/binaries and source hashes.

- [x] Generate fresh nonce; register A/B/observer with finite leases; prove
  baseline duplex RTP and capture SFU refresh in memory through test proxy.
- [x] Revoke B; wait media close ACK; measure quiet interval and deny original,
  refreshed and full reconnect. A remains duplex with observer. Same-SID resume is unit/source checked; actual signalling resume remains unmeasured.
- [x] Race an admitted StartSession against revoke with deterministic test
  barrier in overlay only; assert cleanup precedes revoke ACK. No delayed hook.
- [x] Expire without renew and deny delayed renew; test register rejection after
  revoke, wrong-room/participant auth and unknown identity. Fresh-registry denial is unit-tested; process restart remains unmeasured.
- [x] Assert counts/baseline and every expected rejection. Failed precondition
  is failed proof. Stop only owned SFU/proxy processes and delete nonce rooms.

### Task 3: Evidence and backend readiness decision

- [x] Record exact versions, patch/harness/binary hashes, fresh nonce, RTP
  counter windows and limits; distinguish unit/source/runtime proof.
- [x] Gate conclusion must state single-node scope. No automatic claim of
  production readiness; durable backend operations and restart registration
  remain explicit integration requirements.
- [x] Update foundation readiness only if measured; full Android suite and
  Go tests before commit. Keep managed worktree and evidence.

## Autonomy ruling

The approved foundation allowed RTC-node pre-admission enforcement as the next
candidate. This tests that candidate without weakening revoke, changing hosting
or deploying. User's "continue" authorizes this reversible local prototype.
