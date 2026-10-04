# Separate autonomous media invalidation from explicit user stop

Prerequisite 4adad3cd. Review found SessionService uses its user-stop path after SFU
registration/observation rejection, missing typed room or credential issuance failure.
For accepted direct calls this falsely records CALLER_ENDED/CALLEE_ENDED. Keep explicit
cancel/disconnect as user intent; add a trusted repository media-invalidation operation
for autonomous loss. Existing channel/fake repositories retain compatible cancellation.

The SQL accepted-pair owner rechecks original operation/participants under UPDATE fence,
sorted actor locks and call lock. Actual original expiry and friendship revocation retain
precedence, terminal reasons never change; otherwise accepted pair ends MEDIA_LOST.
Both claims release exactly and both cleanup descriptors persist. No HTTP endpoint may
accept client assertions of SFU loss; only actual internal service evidence uses this path.
All network cleanup remains outside SQL and explicit stop continues to retain user cause.

Tests before source: real SQL + service register rejection, current observation rejection,
missing typed resolver on active replay, signing failure all retain MEDIA_LOST rather than
fabricated user intent. Supplement explicit stop/replay and expiry/friend precedence.
Fresh exact PG/backend/Android suites before commit. Call routes remain unbound.
