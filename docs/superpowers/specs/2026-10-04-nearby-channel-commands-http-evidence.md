# Channel commands HTTP evidence

New optional ChannelCommandsEndpointPort delegates to existing fenced JDBC creation and
deletion. PUT explicit channel UUID normalizes title; POST delete uses caller-owned stable
operation UUID. Authenticated actor comes from JWT/store; body actorId cannot override it.
Channel routes reject noncanonical UUIDs before mutation. Creation/delete conflicts409,
cap409 channel_limit_reached, generic404 for denied/unknown, optional dependency503.
All authenticated responses inherit no-store. Production dependency stays unwired.

Shared existing communityBody reader is now internal: unchanged16KiB/10s limits and strict
UTF-8, reused by channel commands. Renamed existing canonical body-ID checker to internal
communityCanonicalId for exact path validation in new routes; existing session/community
read path normalization was not changed.

Seven recording-port route cases observed missing API compile RED, then full backend152
fresh PASS9s/all6 executed tasks, exactzero. Full Android2680 fresh PASS30s/all35 executed
tasks, exactzero. Tests assert issued commands, normalized payload, replay identity,
explicit deletion, conflicts/cap/no fallback, private denial, streamed oversized/malformed
UTF-8 and deliberately incoherent terminal operation with actual cleanup flag. Responses
expose no cleanup SIDs/epochs/tokens. Existing repository PG tests are recorded separately;
this HTTP-only change did not rerun them and makes no new full-PG or device claim.

Narrow source review result is recorded after completion. Matching client commands,
private invitation HTTP/client, production wiring/sharing/realtime, new UI and device
validation remain pending. No production deployment or legacy group transfer.
Narrow Luna source review PASS; no additional findings or source changes after fresh verification.
