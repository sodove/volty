# Authentication ownership — restore/refresh stage

Startup captures its authentication generation before dispatch. Credential reads,
refresh, verified-profile retrieval, failures and final commits remain owned by that
generation. All credential writers now use one serialized commit helper with stale
write cleanup. Restore persists the token only after retrieving the profile for that
exact token; it does not publish half-authenticated credentials before profile lookup.
Authorized expiry/401 refresh captures its original generation and cannot adopt the
current global generation after waiting.

Repository root CoroutineScope may be injected for deterministic lifecycle ownership;
default remains Default + SupervisorJob. DI explicitly constructs the two-argument
default to avoid asking Koin for an arbitrary CoroutineScope. This root scope is not
the per-authenticated-account Job needed by Nearby and is not cancelled on logout.

Three tests: actual held restore profile after logout; actual held 401 refresh after
logout cannot issue a fresh-token friends retry or write; startup queued before an
explicit login cannot perform another credential read/profile request on first dispatch.
Held producers return typed results before their return marker; finally releases gates.

Missing API RED36s33executed, expected third constructor scope argument. Initial runtime
attempt was a nullable-list inference compile error42s33executed, not runtime RED;
fixed explicit element type. Actual refined RED3tests3fail56s35executed with exact XML
count before ownership guards. Fresh full2822 exact0failures/errors/skips43s35executed,
rerun/no build/config caches. Narrow Luna source/fixture review PASS.

Next: generic normal request/profile/sharing/delete callbacks and legacy live-stream
ownership before trusted Nearby auth Job/factory integration. No full auth isolation,
production Nearby UI cutover, group migration, native storage/power-loss or audio proof
claimed. Ordinary test evidence, not mutation evidence.
