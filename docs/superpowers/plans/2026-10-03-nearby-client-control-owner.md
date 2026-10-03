# Nearby client control owner

Continue the approved voice reducer and bounded HTTP boundary with one mailbox
owner. This slice binds HTTP results to reducer effects; engine dispatch,
Android scheduling/DI, durable offline cleanup and UI remain separate tasks.

1. Define a fakeable session control port implemented by the existing HTTP
   adapter. Preserve its ordinal/start/receipt contract unchanged.
2. Add bounded mailbox commands for join(channel, stable operation, replace),
   stop, retry pending join, heartbeat, explicit tick and SDK/user events. Store
   destination with the request generation. Reject external Join/SessionGranted/
   LeaseAck: only the owned HTTP completion can issue admission/lease events.
3. Publish reducer state before dispatching effects. Launch HTTP child jobs so a
   slow join cannot block stop. Cancel an in-flight join locally AND issue its
   durable operation cancellation, since cancellation can lose a committed reply.
   Keep reply context in its closure; a late retired grant must issue disconnect
   for that exact session and must never start the engine or overwrite state.
4. Deliver engine/sharing effects through an injected nonblocking dispatcher;
   StartEngine receives credentials only for the exact admitted tag. No replaying
   credentials through state flows. Clear credentials after dispatch. Engine
   integration will own serialized SDK controls, initial preference reconciliation
   and callbacks; this slice does not claim those are implemented.
5. Pending joins have no capture/lease authority. Explicit retry reuses the same
   operation/destination; status cannot mint credentials. Heartbeat cannot renew
   another generation. Failed heartbeat leaves existing credit until an explicit
   monotonic tick expires it. No delayed loop in tests or automatic reconnect.
6. Track unconfirmed stop requests separately from engine release. A terminal HTTP
   reply confirms server intent only; it is NOT SFU revocation acknowledgement.
   Unavailable responses remain retryable, keyed to the original operation/tag,
   never the current new session. Persistence/retry scheduler and explicit SFU ACK
   DTO are prerequisites before production exit UX can claim full completion.
7. RED tests assert issued requests: stop during blocked join; ignored cancellation
   and late grant; replacement with old heartbeat completion; pending retry same
   operation; failed cleanup retry after new join; expired receipt no engine;
   explicit expiry. Then fresh targeted and full Android checks, exact counts.

No backend edits/builds while its PostgreSQL run is active. No product enablement,
audio routing/Xiaomi proof, automatic timing or durable cleanup claim from this
mailbox foundation.
