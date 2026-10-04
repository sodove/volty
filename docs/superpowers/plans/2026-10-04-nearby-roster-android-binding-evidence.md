# Android roster binding evidence

Thin VoiceRosterAccountFactory delegates to the tested authenticated owner, preserving
account, tag, borrowed HTTP and supplied scheduling/clock/pulse source. Lazy Android DI
supplies the existing named bounded OkHttp client, v2 HTTPS root, queued Dispatchers.Main,
SystemClock.elapsedRealtime and a cold per-subscription one-second tick source. No new
Job, account singleton, eager collection or global credential supplier is introduced.

Fresh full2846 tests, zero failures/errors/skips59s35executed, rerun/no build/configuration
caches and exact XML asserted. Actual Android compilation validates provider types.
Configuration-only change; no mirrored constructor tests or mutation sweep. Parent
manual review, no independent Luna PASS (quota exhausted earlier).

JVM suite does not resolve the native Android container or prove timer delivery while
backgrounded. Cold source is collected/cancelled by each roster runtime; elapsed clock
preserves conservative lease freshness after sleep. Foreground/session ownership, UI
cutover, audio routing and explicit SDK/remote cleanup remain separate work.
