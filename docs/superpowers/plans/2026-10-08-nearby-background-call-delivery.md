# Nearby background incoming-call delivery

Implements the approved voice-spaces design's missing FCM delivery. The user
confirmed on2026-10-08 that a Firebase project can be created and subsequently
supplied its Android configuration. Server provisioning and delivery remain
pending. Existing in-process discovery/notifications continue to verify call
state; push never grants media permission or starts capture.

## Sequence and verification

1. Persist a callee hint for each committed, ever-rang call revision in the same
   PostgreSQL transaction. Migration18 is additive; historical calls are not
   backfilled. Replayed revisions and never-rang cancellation tombstones do not
   create duplicate/incoming hints. Both ringing and accepted-media writers
   share one append helper. Prove create/replay, decline, accept/end and private
   tombstone behavior against the existing owned disposable PostgreSQL.
2. Add account-owned device-token registration/revocation and conditional token
   retirement. Bind registrations to authenticated accounts, preserve other
   devices and prevent stale logout/send results retiring a newer binding.
   Tokens are private capabilities, never returned/logged. Verify transaction
   ownership, replacement and revoked-account cases before wiring routes.
3. Deliver outbox hints through a bounded HTTPv1 FCM adapter and owned worker.
   Recheck current call/recipient before dispatch, coalesce obsolete revisions,
   use short bounded TTL and idempotent completion/retry. FCM acceptance proves
   acceptance only, not notification delivery. No names, positions, media
   credentials or JWTs in payload. Missing configuration explicitly disables
   this transport; it does not disable live-app calling. Test transient failure,
   terminal tokens, late results, cancellation and restart with controlled I/O.
4. Register Android FCM service and token ownership in the actual app account
   lifecycle. Data-message handling verifies current backend call through the
   existing hint owner; only notification is permitted. Accept opens Activity
   and existing permission/FGS flow. No audio/position capture in push service.
   Test common decisions; native killed-process/Doze behavior remains physical.
5. Provision project `ru.sodovaya.volty`, Android config and server credentials
   outside Git. Validate actual sending only once configured, then include
   foreground/background/process-death/stale-message/account-switch cases in
   the deferred two-phone acceptance. Force-stop is not a delivery guarantee.

Primary owns cross-layer implementation. Narrow delegates may review concrete
boundaries with sufficient context, Luna/xhigh only. No further Astra invocation
is authorized. Do not alter production or old groups for the initial journal.

References read2026-10-08: [FCM Android setup](https://firebase.google.com/docs/cloud-messaging/android/get-started),
[receiving](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
[token management](https://firebase.google.com/docs/cloud-messaging/manage-tokens),
[HTTPv1](https://firebase.google.com/docs/cloud-messaging/send/v1-api).
