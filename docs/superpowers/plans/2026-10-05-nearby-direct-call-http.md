# Authenticated private direct-call HTTP contract

Prerequisite 90767f2c. Add an optional disabled-by-default call endpoint port to application
dependencies and authenticated v2 routes: create, incoming page, status, accept, prepared
resume, decline, stop and outgoing cancel-before-create. Actor always comes from live JWT
principal/store lookup. UUID payload/path/cursor and bounds validate before invoking port;
clients never choose room, node, friendship evidence, participant SID or server deadline.

Public call snapshots include call/participant IDs, phase/revision/outcome/ring deadline,
never caller's idempotency key or original operation nonce. Only accept/resume can include
the authenticated actor's SessionStateResponse. Ready means CONNECTING; current observed
ACTIVE means transport CONNECTED, never device audibility. Create/status/incoming/stop
cannot serialize credentials. No-store responses and credential toString redaction remain.

Authenticated disabled dependency returns 503; unauthenticated routes still return401.
Invisible calls return404, friends-required403, immutable conflicts/busy/revision409;
terminal decisions return their terminal snapshot instead of resurrection. Temporary SFU
failure includes the authorized current snapshot with503, no bearer. Pending media202.
Existing session handlers remain compatible; production DI remains unset until composition.

Tests before source: auth/disabled; forged actor ignored and immutable payload preserved;
private snapshot omission; original own credentials CONNECTING and remaining credit;
status/incoming no bearer; validation before port; exact decline/stop/early outgoing cancel;
stable denial/terminal/failure mapping. Fresh backend/PG/Android exact counts before commit.
