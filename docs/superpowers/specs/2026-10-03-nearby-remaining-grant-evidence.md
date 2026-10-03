# Nearby conservative remaining lease grant

The earlier client foundation credited every grant/ACK with thirty seconds from
arrival. That was insufficient: replay and network delay could manufacture local
time beyond the authorization. The replacement carries server remaining credit
and local request provenance explicitly.

`VoiceLeaseGrant` requires positive remaining1..30000, nonnegative monotonic
request start, receipt after start, an increasing request ordinal, and checked
deadline addition. Deadline is request start plus remaining; elapsed HTTP time
is consumed. Android's future SessionPort must use elapsedRealtime for both
timestamps, including device sleep, and assign ordinals before sending requests.
There is no fallback to a fresh thirty-second credit for missing fields.

Reducer expiry is inclusive at the deadline. An expired initial response never
starts the engine and releases its session. A heartbeat received at/after the
prior deadline cannot revive intent even if no Tick ran. Same/older request
ordinals cannot overwrite credit, including requests with equal start times.
New conservative deadlines may shorten the old deadline. SDK events remain
unable to authorize lease acknowledgements; only the future backend port may.

Backend Ready/Confirmed return bounded remaining credit from current-claim
authorization under the actor lock. CPU token issuance consumes credit before
the final calculation. A credential that expires during issuance is discarded
and cleanup runs; no expired bearer is returned. Status and unconfirmed media
heartbeat do not mint JWTs or renew SFU admission. HTTP uses
`remainingLeaseMillis`; absolute server deadline remains diagnostic data.

Evidence so far: missing-API compile RED before each API, targeted client50
PASS, full fresh Android2579 PASS (35 executed tasks), backend110 fresh PASS.
Six new pure client cases cover delay, expired initial grant, equal-clock request
order/replay, late ACK, already-expired new receipt, and invalid/overflow values.
Targeted actual PostgreSQL26 PASS run0113f456-40e0-47e5-8b95-0d565c7a281a
(23 existing plus three grant cases), zero failures/errors/skips, owned fixture
cleaned. Full fresh PostgreSQL78 PASS
06d6dac1-1abe-4eb5-acc5-23ee8484c67e, zero failures/errors/skips, six executed
Gradle tasks,9m17s; owned pool shutdown and fixture cleanup confirmed.

Narrow Luna source review PASS for pure grant/reducer/driver branches, no tests
run by reviewer. It explicitly excludes future SessionPort clock capture and
ordinal assignment, production integration and other Nearby behavior.

This slice is still a protocol/core foundation: production SessionPort, HTTP
response parsing, heartbeat scheduling and product cutover are pending. It does
not prove phone routing, Xiaomi audio, audibility or community authorization.
