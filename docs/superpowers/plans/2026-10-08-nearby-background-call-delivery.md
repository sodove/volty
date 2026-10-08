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

## Device registry contract (step2)

- Installation UUID plus a locally generated32-byte capability; store only the
  capability SHA256 on the server. An account UUID alone cannot take over another
  installation. The Android implementation must persist the installation identity
  and increment its generation before queuing a registration/logout request.
- Generation is strictly increasing per installation across account switches,
  token changes and logout. A binding UUID identifies that exact request. An
  exact replay is harmless; an older or conflicting equal-generation request is
  rejected. Logout keeps a tombstone, including when it arrives before the first
  registration. It cannot revoke a different account's newer binding.
- The current JWT actor/issued-at is validated against the locked user row,
  following the existing inclusive `tokens_revoked_at <= issuedAt` auth contract.
  This does not change the existing same-second JWT revocation semantics. Target
  enumeration rechecks deletion and current revocation; expired access tokens
  must not be used to register, but registration is not limited to access-token TTL.
- One active FCM token belongs to one installation. A conflicting installation
  cannot silently steal it; the eventual client must rotate its token after a
  conflict. Tokens remain opaque, bounded printable strings, not parsed IDs.
- A transport failure can retire only the exact actor/installation/generation/
  binding/token-hash it sent to. Retirement keeps the generation; replay must not
  revive it. Target pagination is bounded and exposes tokens only to the internal
  delivery adapter. No token/capability in logs, DTOs or errors.
- Use the existing access fence before user/device locks, no network in the
  transaction. Registration, deletion and revocation must not cross that lock
  order. The HTTP mutation route is now wired; client lifecycle remains a later
  wiring step.

## Current implemented boundary

Step1 journal and step2 registry/API are implemented locally. Migration19 is
additive, does not backfill device tokens and keeps installation generation
tombstones after logout or invalid-token retirement. No production migration or
deployment has been performed for these two steps.

`PUT /v2/nearby/push/device` requires current JWT authentication. JSON fields:
`installationId`, `installationKey`, `generation`, `bindingId`, `token` (null for
logout). Actor and issued-at come exclusively from the verified JWT/current user.
The request is capped8192 bytes, including streams without Content-Length.
Only generic errors or204 are returned, with no-store.409 codes distinguish
`push_binding_stale` from `push_token_conflict`; the latter requires token renewal.
Target enumeration and conditional retirement are backend-module internal and
have no HTTP listing endpoint. PostgreSQL exception details are redacted before
the global exception logger because failing rows may contain raw tokens.

Storage verification includes actual9-test RED/GREEN, then an injected SQL
failure privacy test RED1/GREEN10. Route verification includes actual5-test
RED/GREEN, JWT actor/issued-at binding, revoked authority, logout, malformed and
oversized fixed-length/streamed bodies, private response fields and distinct
conflict codes. The client, OAuth credential consumer, delivery worker and actual
delivery remain pending.

## HTTPv1 adapter boundary (step3, not delivery wiring)

The local adapter performs one bounded attempt using a borrowed HTTP client.
Production targets the fixed Google HTTPS endpoint; test injection is limited to
loopback. Redirects are forbidden. It accepts an already acquired bearer token;
service-account loading, OAuth minting/caching and worker ownership are not wired.

Only type/call UUID/revision/binding UUID/generation appear as string-valued data.
Android priority is HIGH, TTL explicitly1..30 seconds and package restriction is
ru.sodovaya.volty. No notification payload, names, GPS or media credentials. The
eventual worker must cap TTL by current ring credit and recheck binding/call.

Response buffering is bounded16KiB; total deadline covers the body after headers,
with cancellation/abort and interrupted-thread restoration. Result types cannot
carry remote error text or private capabilities. HTTP200 plus matching nonempty
message name means API acceptance only. Only404 with a typed FcmError/UNREGISTERED
can retire the exact device target. Generic404, bad payload or wrong sender never
retires it.429 uses at least60s;5xx uses at least1s and respects Retry-After seconds
or RFC1123 date. Huge numeric delay saturates instead of retrying early. The worker
must compare delay with hint lifetime before adding it to wall time.

Actual local HTTP tests cover data shape, error classification, malformed/oversized
response, Retry-After, no request for invalid input, no redirect and a deliberately
unfinished body after headers. Initial6-test RED/GREEN; huge-delay test separately
failed when40 digits fell back to1s, then parser was corrected. No Google sends,
real credentials, registration on phones or production deployment occurred.

References checked2026-10-08:
[HTTPv1 authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api),
[FCM error codes](https://firebase.google.com/docs/cloud-messaging/error-codes),
[message REST schema](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages).

References read2026-10-08: [FCM Android setup](https://firebase.google.com/docs/cloud-messaging/android/get-started),
[receiving](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
[token management](https://firebase.google.com/docs/cloud-messaging/manage-tokens),
[HTTPv1](https://firebase.google.com/docs/cloud-messaging/send/v1-api).
