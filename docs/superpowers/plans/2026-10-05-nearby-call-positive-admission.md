# Typed positive call admission under original pair ownership

Prerequisite a3cec40e. Call REST/UI/SFU activation remains subsequent work. Add a composite
SQL access policy with explicit CHANNEL/DIRECT_CALL kind; its legacy four-argument path
continues to mean channel access. Default/custom existing policies remain channel-only.
CALL requires accepted live friendship, original caller/callee, an ACCEPTED call, exactly
two original media rows and both exact current claims, matching canonical call room/node,
nonterminal phases and unelapsed leases. Server membership in a colliding channel is not
call permission. No row/SDK success fabricates media observations or audibility.

All positive call session operations and credential CPU producers use UPDATE access fence,
both actor locks in order and original call lock. Identify immutable operation kind before
the single-actor fallback; malformed unparented call fixtures stay fail closed. Reconcile
the pair before and after mutation in that same transaction, retaining first terminal
outcome. Late admission/expired receipt cannot leave only one ended side. Never perform
SFU/network effects inside these locks. Cleanup acknowledgement may lock the pair but
does not fabricate a new clock observation.

Credential producer receives a transient authorization view with deadline bounded by the
shortest original participant lease. Persistent self lease remains unchanged. In particular,
an ACTIVE caller plus PENDING callee cannot earn thirty seconds from caller alone when the
callee owns only fifteen. Both-active transition may earn the actual shared minimum.
An EXPIRED original operation is expiry evidence even if its old retained deadline is later
than a rejected/expired conservative SFU receipt; distinguish it from generic media loss.

Tests before source: actual admissions and shortest-credit projection with incomplete peer;
typed access under deliberate call/channel UUID collision and outsider server membership;
late original admission retires whole pair; connected receipt expiry preserves timeout
reason and closes both even with stale retained deadline. Then held credential producer,
permission/ownership loss and stale callback review. Fresh full PostgreSQL/backend/Android
suites, exact XML counts, parent manual review. No production binding or physical proof.
