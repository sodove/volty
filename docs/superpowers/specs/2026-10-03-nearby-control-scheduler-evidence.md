# Nearby control scheduler evidence

One scope-owned collector consumes injected pulses and an injected monotonic
clock. Every pulse ticks the reducer. Inclusive expiry, a sleep-sized time jump,
negative time and clock rollback cannot earn local lease credit. Heartbeat uses
the exact authorized session tag; pending join retry uses the original operation
and generation. The owner checks those identities when handling queued commands.

Remote cleanup retries retain original targets after a replacement joins. Local
cleanup retries retain exact failed engine generations. Continuous pending cleanup
uses one cooldown so changes to its set cannot postpone old work indefinitely.
Cancelling scheduling neither disconnects nor acknowledges resource release.

Six initial cases produced missing-API compile RED33914; clock rollback added a
seventh. Initial targeted28 PASS50457. Source inspection then identified that
queued heartbeat/retry commands could bind a replacement rather than their pulse
snapshot. Two owner cases produced missing-overload compile RED61838 before
identity-bound commands were added. Final targeted30 PASS21923.

Fresh full Android2673 passed in session98901: exact2673 tests, zero failures,
errors or skips, all35 tasks executed, rerun-tasks and both caches disabled.
The narrow Luna source review passed expiry, stable retry identity and exact
cleanup cadence; it did not assess Android wakeup, DI or account retirement.
These are ordinary tests, not an audited mutation sweep. Backend143 and real
PostgreSQL131 evidence remain unchanged by this client-only slice.

Production pulse source, including-sleep Android clock, foreground lifetime,
account retirement and durable cleanup are still pending. This policy does not
prove device wakeup, Xiaomi audibility or independent capture/render routing.
