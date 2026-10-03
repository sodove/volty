# Nearby client v2 HTTP request provenance

The injected HTTP boundary captures a strictly increasing request ordinal and
monotonic start before sending. It reads bounded responses and records receipt
before producing VoiceLeaseGrant. Remaining credit is charged from request start,
never absolute server deadline, response arrival or a default thirty-second grant.
Generation comes from the caller; heartbeat/disconnect requires the exact expected
session. Pending/terminal responses cannot carry a grant. An expired credential
receipt discards credentials but keeps the exact tag for cleanup.

Join requires explicit credentials; status/heartbeat cannot smuggle credentials
into engine admission. Failure codes omit response/exception/token text; busy is
distinct from other conflict. The HTTP child refuses redirects, limits response
to16KiB and bounds the whole request to ten seconds. Caller cancellation propagates.
Bearer auth/refresh and monotonic clock are injected, with no wall-clock default.

Validation: missing-API compile RED, initial seven-case runtime6/7 RED caused by
MockEngine default I/O dispatcher racing the virtual ten-second timer. This was
a test fixture issue: engine and deadline now share StandardTestDispatcher, with
issued-request assertions retained. Targeted7 then PASS. Four additional cases
gave runtime3/11 RED for missing join credentials, busy mapping and network failure
classification. Corrected targeted11 PASS with fresh uncached Gradle execution.
Full fresh Android2595 PASS, zero failures/errors/skips. Narrow Luna source review
PASS only ordinal/start/receipt/grant/expired-credentials invariants; no actual
Android clock wiring or complete HTTP/auth review claim.

Tests inspect issued HTTP path/body/bearer, delayed receipt, inclusive expired
deadline, absent/zero remaining grant, equal-tick ordinal ordering, foreign-session
heartbeat, pending response, safe denied/oversized outcomes, no redirected request,
busy, network exception, finite request timeout and cancellation after issue.
No unbounded delayed loop or device capture is involved.

No production DI/controller/secure-auth wiring or actual socket/Android device
claim follows from these MockEngine tests. Android wiring must pass elapsedRealtime
including sleep. Future controller owns request cancellation on account/generation
change, expiry cleanup and heartbeats. Atomic backend ACL and UI cutover remain
prerequisites. No Xiaomi, routing or device audibility fix is claimed here.
