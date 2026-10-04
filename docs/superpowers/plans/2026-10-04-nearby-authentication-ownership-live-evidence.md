# Authentication ownership — legacy live stream stage

A live flow captures its authentication generation at creation, before collection.
Its token callback and forced refresh retain that generation. Event acceptance and
sharing mutations occur under the finite session lock; obsolete streams terminate
with Unauthorized. An upstream ending without an event is also checked after collection.
No lock spans transport calls or downstream emit.

Four deterministic fixtures cover a cold obsolete flow, a callback held before token
lookup, a stale revoke deliberately targeting the new account after its new sharing
starts, and an old handshake rejection after replacement. Assertions cover actual
callback tokens, producer event/rejection markers, refresh requests and sharing state.
Finite gates are released in finally.

Initial compile attempt failed on invariant list assertion types (49s, 33 tasks);
explicit types corrected before runtime. Actual RED: 4 tests, 4 failures, 1m,
35 executed tasks, exact XML asserted. Fresh full: 2830 tests, zero failures/errors/
skips, 1m6s, 35 executed tasks; rerun and build/configuration caches disabled.
Narrow Luna source/fixture review PASS. Ordinary tests, no mutation evidence.

This verifies local account ownership. It does not prove immediate stream closure,
remote unsubscribe acknowledgment, atomic authorized WebSocket publication or native
audio. Next is the trusted authenticated account lifetime and Nearby factory wiring.
