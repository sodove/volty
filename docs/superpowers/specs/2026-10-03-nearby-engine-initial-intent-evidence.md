# Nearby engine initial intent

An earlier driver revision published with the mute value captured before connect.
Android rejects `setMuted` before a track/publication exists, so pending intent
could fail and then be overwritten. This revision stores latest tagged mute in
the command even before allocation and publishes initially muted. It applies
latest mute only after publication, serializes SDK mute calls per handle, and
reconciles intent changed during an awaited call. Control waiting is bounded5s;
parent cancellation remains cancellation.

Deafen had the same pre-allocation gap when old resource release delayed the new
stack. The command now stores it too, and allocation samples it before connect.
Only the synchronous native speaker-mute setter runs under the ownership mutex;
suspended SDK work does not prevent stop from invalidating ownership.

Fresh verification, tasks rerun and caches disabled:

- New initial2 tests produced runtime RED2/10, then10 passed after the mute fix.
- Added4 invariants: one failure was an async fixture assertion before draining
  cancellation, corrected with `runCurrent` while preserving issued SDK assertions.
- New deafen fixture produced runtime RED1/15 with old resource release blocked;
  command intent storage corrected the missing allocation path.
- Final targeted15 passed, zero failures/errors/skips, Gradle35 executed tasks.
- Full Android2611 passed, exact2604 prior plus7 new tests, zero failures/errors/
  skips. Fixtures use finite deferred gates, no unbounded virtual delayed loops.
- Narrow Luna source review covered pre-publication mute storage, initially-muted
  publication, latest command sampling and wrong-tag rejection only. Deafen and
  SDK/device behavior were not part of that review.

This validates issued SDK calls and ownership, not native capture, routing,
audibility or Xiaomi. HTTP-owner engine binding, monotonic scheduling, durable
cleanup and product/DI cutover are still pending. Production backend remains
disabled while independent resource-deletion PostgreSQL131 is running.
