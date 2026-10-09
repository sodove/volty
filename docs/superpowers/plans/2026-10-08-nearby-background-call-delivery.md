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

## Durable delivery queue contract

- Migration20 adds a DB-written event timestamp for new journal rows; existing
  rows remain timestamp-less and are not backfilled into notifications. Each
  materialization slice seeds at most100 currently eligible installations and
  persists its pagination cursor. One pending row per event/installation.
- Each attempt snapshots the current account/binding/token and uses a fresh UUID
  claim ticket with a10s lease and target-specific attempt number. Before HTTP, verify current call revision, phase,
  recipient authority and binding, then extend the lease. No SQL lock spans HTTP.
  Late or reused tickets cannot complete a replacement attempt: match both ticket
  and attempt number against the unchanged target. Invalid-token
  retirement uses the same exact-binding helper as the device registry.
- A token rotation in the same account requeues a pending installation with its
  new binding; account switch drops the old account's delivery. The client must
  refresh incoming calls after successful registration, including a newly added
  installation after an event's device snapshot has finished. Snapshot creation
  is not a permanent subscription for future installations.
- Old call revisions, inactive authority and expired hints are completed without
  dispatch. Hint lifetime is at most30s from the event and additionally bounded
  by ring deadline while RINGING. API acceptance marks only that device attempt.
- Retry due time/backoff/attempt budget and provider cooldown survive restart.
  The queue enforces reason-specific minimum delays independently of the HTTP
  producer and rechecks provider pause in final dispatch preflight.
  Quota, configuration and service Retry-After pause the provider durably even if
  a particular hint expires. Saturating arithmetic cannot schedule an early send;
  compare delay with hint lifetime before adding it. Expired rows remain eligible
  for cleanup during cooldown. No token text in queue rows or exceptions.
- A worker slice handles one attempt; future production ownership must use a
  separate application-owned job so slow FCM cannot delay SFU lease maintenance.
  Credential acquisition precedes final recheck; interrupted/cancelled attempts
  leave a reclaimable lease rather than reporting delivery.

References read2026-10-08: [FCM Android setup](https://firebase.google.com/docs/cloud-messaging/android/get-started),
[receiving](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
[token management](https://firebase.google.com/docs/cloud-messaging/manage-tokens),
[HTTPv1](https://firebase.google.com/docs/cloud-messaging/send/v1-api).


Implemented queue boundary (2026-10-08): migration20, JDBC materialization,
claim/preflight/receipt handling and a synchronous one-attempt worker slice.
Actual PostgreSQL regression tests cover restart/reclaim, obsolete revision,
revocation/deletion, same-account rotation/account switch, late unregistration,
expiry, durable cooldown including after claim, incoherent zero quota delay,
A→B→A ticket reuse, historical NULL events, pagination101 devices and actual
worker cancellation. The final full PostgreSQL run has370 tests with zero
failures/errors/skips; this is software evidence, not handset delivery evidence.
Application-owned scheduling, OAuth credential consumer, native registration /
receive lifecycle, service-account provisioning and real sending remain pending.


Application ownership boundary (2026-10-08): an optional bounded push-tick port
now starts in the backend module on a separate application-child IO job. Pulses
are serial and500ms apart after each slice; no detached/global job and no shared
SFU maintenance lane. Close/root cancellation interrupts blocking work through
runInterruptible; producerPending/stopped wait for actual completion even if an
admitted function ignores interruption. Ordinary private errors count and retry
on the next pulse without leaking details or cancelling the borrowed root.
Actual module test holds push work while two SFU slices run, then observes real
interruption on ApplicationStopping. Default port isNULL: production assembly
still needs the credential-backed worker factory, so this does not enable real
FCM sends yet. HTTP client and database remain borrowed by the worker owner.

Credential-consumer references checked2026-10-08:
[Google service-account credentials](https://docs.cloud.google.com/java/docs/reference/google-auth-library/latest/com.google.auth.oauth2.ServiceAccountCredentials),
[HTTPv1 authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api).
Use a typed service-account credential, validate its project and Google token
endpoint before loading it; do not accept arbitrary external credential kinds or
follow credential-supplied URLs. OAuth acquisition must be bounded and precede
final queue preflight. A server key is separate from Android google-services.json.


Service-account loading boundary (2026-10-09): Google Auth Library1.54.0 typed
ServiceAccountCredentials loaded from an explicitly supplied local regular file,
no symlink/ADC lookup/network access. Read bound64KiB, exact project/type/token
endpoint and account-email domain; alternate universe rejected. Validated JSON
is normalized before SDK decoding; scope restricted to firebase.messaging.
Filesystem/JSON/SDK errors lose their private cause/suppressed chain and expose
one generic message. Wrapper toString redacted; cancellation/interruption kept.
Tests use an ephemeral RSA key and assert successful SDK decoding as well as
rejection. Exact count matters: the initial positive test accidentally returned
PrivateKey and was not discovered byJUnit; fixed toUnit, reran actual baseline
5tests/5failures and final full249backendtests42XML zero bad.

This is a local key loader only. OAuth HTTP acquisition/cache, preparation before
queue preflight, environment/mount configuration, production worker factory and
native account lifecycle are still pending; no real key or Google send tested.
Sources checked2026-10-09:
[Google typed credentials](https://docs.cloud.google.com/java/docs/reference/google-auth-library/latest/com.google.auth.oauth2.ServiceAccountCredentials),
[Firebase server authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

Final loader review added strict UTF-8 decoding: malformed bytes even in an
otherwise ignored field reject the file. Actual6-test run failed only that case
under replacement decoding, then full250backendtests42freshXML passed after
REPORT decoding. Fresh3407Androidtests336XML passed; this backend-only review fix
changes no Android sources. No OAuth request/startup enablement yet.


## Step3 server wiring implemented2026-10-09

Earlier sections describe their stage's partial boundary; retraction of current
"OAuth/worker factory pending" status: the local server now assembles the full
optional credential-backed delivery lane. Key validation precedes DB startup;
prepared graph assembly takes the preloaded key and performs no file/DB/network
I/O. App root cancellation precedes owned HTTP shutdown; borrowed database stays
outside push-runtime ownership. Missing configuration disables only this lane;
partial/mismatched settings fail explicitly. No production deployment yet.

Google Auth Library owns typed key parsing and RSA signing; JWT claims and fixed
OAuth request use the documented service-account protocol. One serial worker
caller owns the monotonic cache with60s margin, validated61..3600s lifetime and
network-time credit; oversized responses do not hide429/5xx Retry-After. Bounded
body/deadline/interruption mirror the sender. A401/configuration receipt evicts
only its exact cached bearer; late old invalidation cannot evict a newer token.
Credential preparation happens between claim and final call/binding preflight;
no SQL lock spans it. OAuth failure persists retry without issuing a media/send
request. Call ending or binding rotation during credential acquisition suppresses
the old send, with a positive next-revision/binding attempt proven separately.

Actual tests verify ephemeral-key JWT signature/claims/form, cache refresh/count,
short/expired/oversized token responses, quota/redirect/deadline/interruption,
exact-bearer invalidation and real loopback OAuth→401→reauthorize→accepted send.
PostgreSQL full373 tests passed after worker preparation changes. Production
assembly/unit shutdown tests cover wiring and owned resource cleanup. Real
Firebase credential/provisioning and native receive/register remain pending.

Sources checked2026-10-09:
[Service-account OAuth protocol](https://developers.google.com/identity/protocols/oauth2/service-account),
[Firebase HTTPv1](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

## Step4 installation persistence boundary2026-10-09

Account-owned HTTP registration and the singleton durable installation ledger
are implemented locally. Installation identity/capability survive account/token
changes; changed intent increments generation and changes binding before request
publication. Exact replay preserves acknowledgement. Logout records a null token
under its retiring actor; receiver wiring must still require the currently live
account and an acknowledged, non-null-token binding. Old A→B→A receipts cannot
acknowledge the new state. Failed writes retain the last durable state; generation
exhaustion fails instead of wrapping or replacing an existing installation.

The Android store encrypts the whole versioned snapshot using its separate
Keystore AES-GCM key. AtomicFile has process-level mutual exclusion, explicit
fd.sync, finishWrite and read-back verification. It lives in noBackupFilesDir;
corrupt/foreign/missing snapshots never yield a partial old identity. New identity
creation belongs to the ledger. Transient I/O/key failures are generic failures,
not partial restoration. Encoding is bounded16KiB/strict UTF-8/all eight fields.
The generator uses SecureRandom32-byte capabilities and UUIDs. These classes are
compiled, not yet assembled into the application lifecycle. Physical Keystore,
power-loss/process-death and OEM behavior remain unverified on phones.

Native receive/token ownership/startup restoration and server container mount
remain next. Repository logout retires the exact account before HTTP logout;
registration must not bypass its fence using a raw old bearer. On local logout,
persist the null-token state and suppress hints immediately; backend account
revocation independently suppresses old targets after successful server logout.
Cold-process receiving must arrange repository-owned session restoration, since
an app-only poller cannot assume an Activity has already restored credentials.

Platform references checked2026-10-09:
[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile),
[noBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()).
