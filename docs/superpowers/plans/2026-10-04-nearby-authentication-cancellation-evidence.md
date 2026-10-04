# Authentication cancellation evidence

Pending auth tracks caller cancellation until verified commit. A caller-owned finite
guardian begins undispatched and withdraws the expected generation even while transport
waits noncooperatively. Normal completion cancels the guardian without logging out;
replacement cleanup cannot clear another generation. No Job is allocated for a missing
caller Job. Store rollback stays serialized and NonCancellable, including a suspending
clear after a write finished before cancellation was delivered.

Refresh runs under the established account's actual Job. Cancellation of one request
cannot discard already-rotated tokens while that account remains active. The cancelled
caller cannot issue a retry or publish a response. Account retirement still cancels
refresh; active callers receive Unauthorized for a retired generation. Refresh failure
cleanup is NonCancellable because it cancels its own account lifetime.

Three initial fixtures: runtime RED3tests1fail28s35tasks. Retraction: synchronous clear
concealed cancellation; refined clear yields before mutation. Refined RED3tests2fail
27s35tasks, exact XML. Initial full2835 passed32s35tasks, not committed: parent review
found consumer cancellation could discard rotated credentials. Fourth actual held-write
fixture RED4tests1fail1m8s35tasks before account-owned refresh fix. Final fresh full2836,
zero failures/errors/skips,1m1s35executed, rerun/no build/config caches, exact XML totals.

Luna reviewer failed with usage limit; no independent review PASS claimed. Parent manual
source/driver review found and verified the refresh issue above; ledger records this
workflow ruling. Astra was not recalled. Ordinary tests, no mutation evidence.

No native storage/power-loss, backend rollback, audio or UI proof. Shared refresh mutex
across generations is the next blocking-isolation check before the Nearby factory.
