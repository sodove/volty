# Community account composition — evidence

`di.nearby.NearbyCommunityAccount` composes one guarded HTTP credential binding,
community browser, and invitation draft owner. Requires actual parent Job, matching
store actor, and valid HTTPS root before allocating account children. No global
DI registration or legacy authentication token supplier in this slice.

Parent cancellation immediately denies commands through borrowed Job guards.
An undispatched guardian closes HTTP children and clears browser state under
NonCancellable cleanup. Explicit retire fences owner and HTTP before suspension,
cancels only its guardian, and retries serialized cleanup even from a cancelled
caller. Both HTTP and browser cleanup are attempted; a safe boolean records failure
and successful retry clears it. Parent Job/shared HttpClient remain caller-owned.

Four new tests: cached private membership plus held engine/account child on parent
cancellation; explicit retire from already-cancelled caller and fresh same-actor draft
retry; cancellation before first queued dispatch; invalid construction without orphan
children. Producer return is asserted; held children released in finally. No-Job fixture
uses a custom scope because CoroutineScope factory inserts a Job. Cancelled-caller
fixture uses UNDISPATCHED launch because withContext(cancelledJob) rejects before body.
Held account child is explicit: MockEngine handler belongs to engine, not necessarily
to account Job. The exact community page envelope is `{items:[...]}`.

Missing API RED21s33executed: aggregate absent. Fresh full2816 exact0failures/errors/skips,
32s35executed tasks, rerun/no build/config caches. Narrow Luna test fixture and source
reviews PASS. Ordinary tests, not mutation evidence. No joining network children, server
rollback, native microphone release, Android preferences/Keystore field proof, or
Xiaomi/audio-route claims. Trusted auth establishment/refresh integration is next.
