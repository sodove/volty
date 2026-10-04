# Persistent ringing and the shared voice slot

Prerequisite: direct-call lifecycle policy, 95cd246f. First SQL step covers RINGING only.
No accepted-media endpoint/FCM or production dependency is enabled yet. Accept must later
reserve both slots and retire prior voice/sharing in the same transaction; this step provides
no standalone accepted writer, and cancellation of ACCEPTED is deliberately outside its API.

V12 call rows retain immutable caller/callee/idempotency/caller-operation identity, ring deadline,
phase/outcome and revision. Add an explicit reserved_call_id slot to nearby_actor_claims, mutually
exclusive with its session fields. RINGING reserves only caller, preserving callee's existing
channel. Current production channel admission must reject a reserved call even with replace=true;
terminal original session cleanup remains allowed. Isolated access=null legacy foundation is
not production and does not gain a call path.

SQL lock order: shared access fence, both actor rows in UUID order, accepted friendship row,
call row. No network/clock reads before acquiring locks for decisions. Creation checks actual
live users and accepted friendship under transaction. Idempotent payload replay preserves ID,
original deadline and caller operation; conflicting payload cannot replace reservation. Busy
caller does not create a call. Expired outgoing ring can be persisted/released before a new
one. Stop/decline/status release only exact call reservation, never a newer session/call.
Participant-only lookup precedes locks; identities cannot change between lookup and locking.
Outsiders get no row and cannot expire/mutate it. No roster/sharing/SFU media side effects.

Tests on disposable real PostgreSQL, never production: caller-only reservation with busy callee
and blocked channel replace; friendship/busy denies; replay and elapsed old ring preserving new
slot; outsider privacy; rollback after call insert before slot reservation; two concurrent
outgoing attempts one committed winner. Migration count expectations move 11->12 and preserve
all existing rows. Start from fresh whole PG baseline, missing API/runtime RED, whole PG suite,
backend unit suite, Android suite before commit; exact XML counts and parent manual review.
Stop only the uniquely named test container/tunnel after checks; no deployment.

Manual review additions: cancellation before creation retains the original outgoing binding
as a caller-private ever_rang=false terminal row. A delayed creation cannot resurrect it;
the callee cannot see a phantom incoming call. Existing RINGING replay/status must verify
the original caller reservation, cancelling the old ring if a newer session superseded it.
Conflicting outgoing cancellation cannot change the original ring, and a caller operation
cannot be reused with another key after termination. Friendship revocation releases only
the original ring and preserves an independently active callee channel.

V12 terminal outcome validation explicitly checks outcome IS NOT NULL: SQL CHECK alone
accepts UNKNOWN, so enum comparisons with a null outcome do not enforce the invariant.
All tests ran against fresh schemas in one disposable task-owned PostgreSQL container;
the container remains temporarily available for the next typed-destination prerequisite.
