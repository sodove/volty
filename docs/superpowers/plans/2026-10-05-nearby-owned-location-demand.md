# Native location demand ownership for Nearby channels

The existing social location adapter owns a process-wide boolean SOCIAL_SHARING demand: a late
old owner stop can remove a newer owner's demand. Give v2 Nearby its separate consumer and a
single process coordinator with opaque exact-ticket ownership. Native mutations are serialized
across awaits. Remove only an original ticket, keep MAP/NAVIGATION/legacy social demands.

Create the ticket before positive work so partial/cancelled native activation is still owned
and revocable. Release before acquire tombstones it. Failed native release remains pending for
retry; no false cleanup success. A late old release cannot stop a newer ticket. Caller carries
the ticket through root capture lifetime; don't activate DI/publisher/foreground yet.

Tests first with a recording repository and held actual native-mutator facade: multiple owners,
negative before positive, wrong coordinator ticket, held old stop/new acquisition serialization,
cancelled activation after issued side effect and original retryable cleanup, failed native
stop retains pending truth. Fresh Android suite. Android permission/audibility/device claims
remain separate; location foreground gating is required in the following capture integration.

Native request policy also needs this new consumer: request callbacks while stationary (same
1s/0m policy as navigation), and restart Android registration on effective request changes,
including adding Nearby to an already active map. Adding/removing navigation while Nearby
already needs that policy must not unnecessarily restart registration. Pure policy tests and
Android compilation verify the decision; actual callback cadence remains a device check.
