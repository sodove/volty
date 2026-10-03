# Nearby owned engine effect bridge

The engine-only bridge queues SDK work on an explicitly supplied queued serial
dispatcher. Inline dispatchers are rejected. Dispatch callers and SDK jobs must
share that serial owner; this is an integration requirement, not a claim about
arbitrary dispatcher pools. An earlier plan's initial-yield proposal is retracted:
yield alone does not guarantee deferral on every immediate dispatcher.

One guarded driver samples current matching state preferences at queued start.
Duplicate exact starts do not allocate/reconnect again. Stop cancels only its
generation's start/control work; driver owns release-before-allocation. Stale
controls cannot cancel replacement preference work. Queued preferences coalesce
and remain accepted during connect before publication exists. Credentials stay
in a short-lived start closure and never enter a state flow.

Driver stop now acknowledges successful local release even when no handle was
allocated. Earlier silence left preallocation cancellation pending forever.
Failed native close returns before Stopped and preserves the exact generation
in failedCleanup for explicit retry. Local Stopped does not confirm SFU cleanup.

Seven cases first produced missing-API compile RED24532. Targeted32 PASS87842
covered runner7/driver21/diagnostic4 with exact counts. Added inline-dispatch
rejection, preference coalescing and stronger stale-control assertions: targeted34
PASS35239, initial full Android2663 PASS36432, exact zero failures/errors/skips.
One additional test binds the actual VoiceControlOwner to this bridge, stops an
issued blocked SDK connect without waiting for its gate, confirms exact backend
disconnect and local Stopped while pendingRemoteStops remains nonempty. Final
targeted35 PASS41892, exact zero failures/errors/skips.

Final fresh full Android2664 PASS52897, all35 tasks executed with rerun-tasks and
build/configuration caches disabled, exact zero failures/errors/skips. Backend143
and PostgreSQL131 source/schema evidence are unchanged by this client-only slice.

Narrow Luna source review passed exact-generation stop, failed cleanup retry and
no-allocation acknowledgement only. No builds or wider runtime/threading review
were performed by the reviewer. Tests use finite deferreds and issued SDK paths;
no unbounded delayed runTest loop or mutation-sweep claim.

No production DI/foreground service/timers/sharing/account retirement or durable
process-death cleanup is wired. Parent cancellation is not release acknowledgement;
the runtime must explicitly stop and preserve failed cleanup before retirement.
No Xiaomi, actual Android capture/render routing or audible-media proof follows.
