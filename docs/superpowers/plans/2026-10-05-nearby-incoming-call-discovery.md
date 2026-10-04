# Bounded incoming call discovery

Prerequisite 85132caf. Add SQL-only incoming pages for an authenticated callee. Only
ever-rang RINGING calls are candidates; private pre-create cancellation, another callee,
accepted conversations and terminal history cannot be advertised as incoming calls.
Before returning, reconcile each candidate's actual friendship, live accounts, deadline
and original caller reservation. Invalid rings retire and release only their original
reservation. Callee's current channel/sharing is untouched: listing is not acceptance.

Use bounded UUID keyset pagination (1..50), cursor is the last scanned candidate and
advances even when reconciliation suppresses stale candidates. Existing callee/call index
supports selection. One UPDATE access-fence transaction collects all candidate participants,
locks every actor in fixed order before call/revision writes, and returns a coherent page.
No network, credentials, notification or microphone effect. No new migration is needed.

Tests before source: only actual callee-visible ringing records; expiry/friendship/slot
loss retires invalid records preserving exact newer claims; multiple incoming pages under
intervening terminal transitions without duplicated calls; callee channel stays active.
Validate cursor/limits and fresh full PG/backend/Android exact counts before commit.
Route binding and call orchestration follow; no native/device readiness claim.
