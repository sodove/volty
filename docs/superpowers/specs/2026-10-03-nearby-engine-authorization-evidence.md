# Nearby engine authorization after suspended work

The engine now requires an explicit nonblocking current authorization predicate.
There is no default permit-all constructor. A denied start returns before it can
retire a currently authorized stack. Authorization is checked again after old
resource release, before allocation/connect/publication, after suspended SDK
operations and before microphone/speaker controls. Start denial releases only
its own allocated stack and reports a safe typed failure.

The pure v2 predicate requires matching generation/session, wanted live phase,
earned request ordinal/deadline and monotonic now strictly before expiry. Unknown
credit and inclusive expiry deny. Runtime must inject Android elapsedRealtime
including sleep. Legacy diagnostics explicitly use their current tag/connecting
or running intent; their old credentials do not provide v2 lease evidence.

Nine initial cases produced missing-API compile RED32054. First implementation
compile failed on recursive inferred Android controller type; explicit type
annotations corrected it. Targeted27 passed; two additional cases preserve the
current stack against denied stale start and check diagnostic active intent.
Final targeted29 PASS80238 and fresh full Android2652 PASS65930, exact zero
failures/errors/skips, all35 tasks executed with rerun-tasks and caches disabled.
No mutation-sweep claim. Issued/entered SDK paths are asserted with finite gates.

New cases cover expiry while old close waits (no new allocation), while connect
waits (no microphone publication), while publication waits (no later unmute),
before later controls (no unmute/undeafen), before first allocation, denied stale
start preserving current resources, unknown/incoherent credit, exact identity,
eligible phases and inclusive deadline. Existing ownership/preference tests stay
intact; their explicit test predicate permits their synthetic SDK scenarios.

The first narrow guard review passed but identified a boundary: generation-only
Failed could refer to another session in the same generation. That earlier engine
event design is superseded by exact Tagged EngineFailed. Driver and Android/audio
adapters now emit exact tags; accepted legacy factory failures are normalized to
the factory-owned tag after the existing ownership filter. The reducer rejects
foreign same-generation failure before changing state. HTTP operation failures
retain their separate generation-owned path. Diagnostics also check failure tag.
Two missing-API tag cases produced compile RED25944, then targeted31 PASS48779.
Added assertions for legacy callback normalization and same-generation denied
start preserving current stack; final targeted31 PASS78444, exact zero failures/
errors/skips. The earlier2652 run is pre-tag-refinement evidence only.

Final fresh full Android2654 PASS10050, exact zero failures/errors/skips and all35
tasks executed with caches disabled. Follow-up Luna source review passed exact
failure-tag fencing/normalization only; it did not execute tests or assess timers.

This guard is not atomic with native SDK execution and does not implement ongoing
expiry while native operations suspend. Owned timers/stop, runtime dispatch,
foreground-service/DI integration, durable cleanup and device routing/audibility
remain required. No Xiaomi or phone-mic/headphone-output fix is proven here.
