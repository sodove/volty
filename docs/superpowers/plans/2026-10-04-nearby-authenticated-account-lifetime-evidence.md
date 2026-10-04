# Authenticated account lifetime evidence

DefaultSocialRepository exposes a read-only SocialAuthenticatedAccount state, created
after profile retrieval and credential commit. Principal is immutable; its real
SupervisorJob is parented to repository scope. Login/logout remove and cancel the
previous account before storage or remote waits. A held child may keep the cancelled
Job incomplete, but bearer access is already denied. Same-actor login creates a new
lifetime; same-generation refresh retains the object and Job.

Token source is private and bound to the original authentication generation; it uses
owned token refresh, with Job checks before and after. No JWT decoding or asynchronous
token copying. Subsequent get/update profile validates the established actor under the
publication lock. Mismatch returns InvalidRequest without a 401 refresh retry.

Missing API RED46s33tasks. Signature-only null-state/null-bearer runtime RED2tests2fail
47s35tasks, exact XML. After implementing lifetime, separate principal RED2tests1fail
25s35tasks: lifetime test passed; second test actually reached foreign-profile Success
and failed before the principal guard. Fresh full2832 tests, zero failures/errors/skips,
31s35executed, rerun/build/config caches disabled, exact totals asserted. Narrow Luna
source/fixture review PASS. Ordinary tests, no mutation evidence.

Identity is legacy-neutral here; canonical v2 identity is checked by the next factory.
No production Nearby consumer, UI, voice stop ACK or native audio proof. Caller auth
cancellation and persistence exception cleanup need a separate follow-up; this evidence
does not claim full auth isolation or durable logout through physical power loss.
