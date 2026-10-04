# Atomic accepted-call session pair

Prerequisite 7bd01886. Implement a SQL-only accepted pair, never standalone issuance.
The new repository remains unbound to DI/routes/SFU. Generic session stop/expiry,
account retirement of ACCEPTED and typed call access must be integrated next before
any production call activation. The current channel access deliberately denies call media.

Use the UPDATE access fence for accepted transitions, then both actor rows in stable
order, original call row and friendship proof. Only the original callee may accept. Check
live accepted friendship, original caller reservation, server expiry/revision and the
callee's exact voice slot. A callee outgoing reservation or another accepted call is busy;
a confirmed channel replacement closes that exact channel and revokes both actors' old sharing.
Original caller operation and supplied callee operation cannot reuse any existing row.
A terminal caller-operation tombstone cancels the pending ring rather than resurrecting it.

Persist two fresh PENDING DIRECT_CALL session rows with original operation bindings and
one canonical volty-call-ID room/node epoch, conservative 15-second pending deadlines,
and both exact actor claims. Pending is not audible/registered and grants no credentials.
V14 adds a unique actor/call destination index, preserving all existing data. Candidate
session UUIDs are generated locally inside SQL. Duplicate accepted replay preserves both
original rows; mismatched callee operation or epoch cannot replace them.

stop(callId) closes RINGING and ACCEPTED, retaining both original media descriptors for
durable cleanup. Cancel-after-accept must end both sessions; old stop must preserve any
newer independently owned claim. No SFU effect executes in SQL. Admission later must
drain all older cleanup, register/verify SFU and reauthorize exact pair before issuance.

Tests before source: accepted pair replaces channel; stop after accept; stop before accept;
callee reserved outgoing busy; failure on second claim rolls back whole replacement;
duplicate accepted payload cannot rebind. Then cancellation tombstone, expiry/revision,
outsider and superseded claims review. Fresh real PostgreSQL, backend unit, Android suites
and parent manual review before commit. No native/media activation claim.

Review additions: the accepted call row alone cannot prove live sessions. Accept replay and
stop persist elapsed original media leases as ENDED/MEDIA_TIMEOUT, closing both descriptors
without inventing a caller/callee stop cause. Trusted pure expireMedia requires repository
evidence and preserves terminal outcomes. V14 additively extends V12's outcome CHECK (its
generated name was measured from the PostgreSQL catalog), retaining null rejection.
Original caller-operation cancellation before acceptance dominates; unrelated preexisting
operations cannot be reused. Outsiders cannot expire/mutate calls, and deliberately
superseded caller claims survive old accepted stop. Old groups remain unchanged.
