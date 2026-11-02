# Backend logging cleanup — 2026-10-29

Added `logback.xml` with root INFO and Hikari INFO. Pool startup, warnings and
errors remain visible; per-connection DEBUG events are disabled.

Ktor CallLogging omits only successful (2xx) GET responses for the exact path
`/v2/nearby/calls/incoming`. Other methods, paths, failed responses and responses
without a known status remain eligible for logging. Filtering does not change
polling frequency or endpoint behavior.

Verification: fresh backend `test installDist --rerun-tasks --no-build-cache`,
287 tests, zero failures/errors. Integration test observes actual log events for
200/204/401/500 polling responses, POST on the same path and GET /health.
Narrow independent review found no defects.

Published image:
`sha256:cd940fc0005196cfb959ff925e42a6fbc10427101971c8aa5efa72ee131ae042`.
It extends the exact previous production image
`sha256:3f7ddf480cac0b511f431b2dcd2553fd9dd1972390ddbeeb21453af39b405226`
and replaces only `/app/lib/volty-backend-0.1.0.jar` (SHA256
`0bce8cdeb4a80077898f536c5362499d2c6e161a5d83f4108f3fe0c23c11f334`).
Dependencies, environment and schema are unchanged. Only app was recreated;
database and SFU container identities/start times were asserted unchanged.
No active SFU participants were present at rollout. No new database backup was
created for this logging-only change.

Release directory: `/home/sodovaya/volty-nearby-releases/2026-10-29-logging`.
It contains source, image context, frozen private old/new Compose models,
guarded rollout with automatic rollback on failure, and live verification script.
The old model supports image-only rollback without a database restore.

Live checks passed: backend healthy, public health and account-removal page 200,
invalid refresh 401. A short-lived token for our existing review account was used
in memory to verify incoming polling returns 200 without an access-log event.
An unauthenticated poll returned 401 and was logged; GET /health was also logged.
No DEBUG events appeared during the check. No token or secret was printed.
