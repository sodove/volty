# Nearby client HTTP/reducer owner foundation

`VoiceControlOwner` serializes intent and HTTP completion through a bounded
mailbox. It stores the destination with the request generation, publishes reduced
state before dispatching effects, and launches HTTP requests without blocking
stop. The existing HTTP adapter now implements its fakeable control port.

Stop during joining cancels the request locally and also sends durable operation
cancellation. A reply that survives cancellation retains its original context;
an old credential grant disconnects exactly its old session without starting the
engine or changing a replacement's state. Pending retry preserves the operation
and destination. Old heartbeat replies cannot renew a new generation. Engine
release cannot clear the separate set of unconfirmed server stop requests.

Credentials are handed once to the injected nonblocking engine dispatcher, never
stored in a state flow. External events cannot forge admission or lease credit.
Port exceptions map to safe unavailability, while coroutine cancellation remains
cancellation. Completion delivery waits for mailbox capacity or owner termination;
an earlier intermediate implementation had an arbitrary1s delivery timeout, which
was removed because it could discard an exact cleanup identity under load.

Verification:

- Missing API compile RED, then initial6 issued-command tests passed.
- Added3 tests; runtime RED2/9 showed uncaught port exceptions leaving a wanted
  attempt and a stuck cleanup request. Safe wrapping corrected both paths.
- Final targeted9 tests passed with zero failures/errors/skips after mailbox
  completion refinement, Gradle35 executed tasks in25s.
- Fresh full Android2604 passed, exactly2595 prior plus9 owner tests, zero
  failures/errors/skips. Build and configuration caches disabled, tasks rerun.
- Narrow Luna source review passed only late old-grant targeting and local+
  durable cancellation of the joining operation. Reviewer did not run tests.

This is not a product controller enabled in DI. Engine dispatch still needs an
owned SDK adapter with preference reconciliation, Android monotonic timing,
heartbeat/pending/retry scheduling, lifetime shutdown and durable offline cleanup.
Explicit tick/retry commands avoid hidden delayed loops in these tests. An Ended
HTTP response confirms server terminal intent, not an SFU revocation ACK; no UI
may claim full remote cleanup from `unconfirmedStops` becoming empty. No device,
Xiaomi, audio routing, capture/render audibility, realtime roster or UI claim.
