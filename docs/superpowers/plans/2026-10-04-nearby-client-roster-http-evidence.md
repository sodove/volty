# Client roster HTTP evidence

Strict account-bound roster snapshot transport now preserves caller subscription tag and
server revision, validates unique actor/session rows and phase/media/SID evidence, and
assigns increasing request ordinals under a mutex even at equal monotonic ticks.
Peer lease credit is server deadline minus observed server time, capped at30s and charged
from request start. Expired rows retain persisted content without earning fresh presence.

Eight initial tests failed compilation against missing API before implementation. The
transport and lease/ordinal reviews passed except one concrete URL finding: earlier parsed
parameter/fragment checks overlooked empty `?` and `#` delimiters. This is a recorded
retraction of the initial URL validation assumption. A ninth regression actually failed
(1 test,1 failure, fresh35 tasks), then raw delimiter rejection corrected the constructor.

Fresh final Android suite:2712 tests, zero failures/errors/skips,30s,35 tasks executed.
Fresh backend suite (source unchanged):161 tests, zero failures/errors/skips,9s,6 tasks
executed. No cache-based evidence or mutation-sweep claim. Logs under ignored
`.superpowers/sdd/2026-10-04-nearby-client-roster-http/`.

No production binding, authorized realtime publication, SDK participant count, audio routing,
audibility, Xiaomi field validation or final UI claim. Later clock rollback requires a
generation owner; this stateless sample cannot remember previously observed time.
