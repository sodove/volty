# Foreground admission

Native environment admission is mandatory in the global voice account coordinator. Android
provides an exact-runtime foreground gate, not a global bool or Activity lifetime. A process
registry issues unique canonical opaque tickets for explicit user requests; no credentials,
account IDs, location or server names go into service intents. Only successful platform
startForeground followed by current microphone permission and matching ticket/service instance
can acknowledge readiness. Gate methods run on the same serial Main owner.

Replacing the runtime, notification stop, service destruction and permission loss fence
positive admission before dispatching the original owner's nonblocking disconnect. Old ticket
or old service callbacks cannot affect the new runtime. Duplicate requests for the same runtime
reuse the ticket. Invalid/reused IDs earn no new authority. Readiness is permission/service
evidence only, never actual native resource close, remote membership or audibility.

Common tests first: pending/missing permission denies; exact-owner admission; stale destruction/
stop rejected after replacement; permission loss fences before callback; malformed/reused nonce;
actual repository/coordinator/Mock/held SDK denies until ready and closes after gate loss even
while old connect remains noncooperative. Observe missing API + signature RED, then implement
and fresh full suite exact XML/manual review. Bind Android gate using live RECORD_AUDIO check
and UUID tickets; service/visible launcher integration follows, no UI cutover yet.

Platform references checked 2026-10-04: Android microphone foreground services require while-in-use
eligibility; checkSelfPermission alone does not establish launch eligibility. The upcoming
launcher requires a resumed visible Activity and successful platform startForeground.
Sources: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
and https://developer.android.com/develop/background-work/services/fgs/service-types.

Evidence: missing API RED24s/33 tasks; signature runtime RED10 tests/5 failures/0 errors in54s/35executed, exact XML (four policy cases plus original account/coordinator/Mock/held SDK). Final full rerun/no caches 2891 tests, zero failures/errors/skips,1m3s/35executed. Parent manual review only: exact runtime callback mandatory, post-builder entry registration, live permission failclosed, canonical unique nonce, owner/service identity, fence-before-cleanup and stale events, explicit Android bound supplier. No service start/visible launcher/UI/device proof yet.
