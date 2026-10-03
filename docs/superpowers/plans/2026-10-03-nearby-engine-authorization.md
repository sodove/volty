# Nearby engine admission after suspended work

Extend the owned driver with a REQUIRED synchronous nonblocking authorization
predicate; no default permit-all production construction. Check exact current
tag/wanted and earned monotonic lease again before allocation, connect, microphone
publication and mute/deafen controls, and after suspended SDK work. Ownership
alone does not prove a lease remains valid while old resource release/connect/
publication waits. Denial during start closes only that stack and reports a safe
typed failure; no credentials or exception details are emitted.

Provide a pure state predicate: matching generation/session, wanted, eligible
voice phase, earned request ordinal/deadline and now strictly before expiry.
Unknown credit never admits. Production runtime will inject state.value plus
Android elapsedRealtime including sleep. Tests/legacy diagnostic explicitly
provide their own predicate; diagnostics check its active tag and connecting/
running intent and make no v2 lease proof. Driver cannot implement continuous
expiry while a native operation is suspended; owned expiry timers/stop remain
required. A guard check is not an atomic transaction with native SDK execution.

RED/green cases: lease expires during old release (no allocation); during connect
(no publication); during publication (no unmute, close); before later unmute
(no SDK unmute); before allocation; pure unknown/incoherent lease/current intent/
inclusive deadline. Assert entered/issued SDK paths. Finite deferreds only.
Fresh full Android before commit and narrow source review; no device/audio claim.
