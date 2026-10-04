# Voice account coordinator

Supply the mandatory global native admission predicate to authenticated runtimes from
a single coordinator on the same queued serial dispatcher. Only the newest selected
entry with an active exact account may admit positive work. Older runtimes must be
retired, unwanted, without pending/failed local cleanup or retirement submission failure,
and have zero native producers actually in flight. Remote stop evidence remains separate;
backend claims still authorize server admission. No synthetic media or membership ACK.

Account acquisition registers selection before suspension, immediately revoking older
positive permission. Builders run under short-lived owned SupervisorJobs beneath the
original repository root, so consumer cancellation cannot abandon a partially created
owner or cancel login. Completion closes only builder lifetime, not runtime cleanup.
Duplicate acquisition of the same pending/live exact account shares its builder/result.
No global mutex across token refresh/HTTP. Older pending builders cannot allocate SDK
before runtime is returned/registered; obsolete results retire before delivery.

Coordinator root identity is fixed to the first verified account's repository root.
Acquire validates canonical identity and queued dispatcher before selection/children.
Failed build permits retry; explicit retireAll fences all entries before suspension and
submits original retirement without joining held builders/native/account children.
Old runtimes remain retained: HTTP/native drain disposal is deliberately separate.

Tests first against actual auth/HTTP and issued fake native stack: same-actor relogin
blocked after old successful close while cancelled connect still runs, opens only after
actual producer completion; failed native close blocks until retried; duplicate acquisition
shares runtime; cancelling an acquisition waiter does not cancel authenticated runtime
construction. Finite gates/pulses, request/header/allocation markers, finally retirement.
Missing API then signature-only runtime RED, fresh exact full suite/manual review.

No production native DI/foreground/UI, process-death persistence, HTTP drain disposal,
physical SDK/internal-thread proof or actual phone/Xiaomi routing claim.
