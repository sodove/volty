# Firebase provisioning for Nearby incoming calls

Application package verified from `composeApp/build.gradle.kts`:
`ru.sodovaya.volty`. On2026-10-08 the user supplied Android configuration; its
single matching client and required project fields were validated. The local
copy is `composeApp/google-services.json`, ignored by Git. Background delivery
is not implemented or tested yet; Android configuration is not proof of delivery.

## User-side setup

1. Create a project in the [Firebase console](https://console.firebase.google.com/).
2. Add an Android application with package `ru.sodovaya.volty`.
   Analytics is optional for this incoming-call feature.
3. Download that application's `google-services.json`. Place the local file at
   `composeApp/google-services.json`; do not add it to repository history.
4. In project settings, confirm the Cloud Messaging HTTPv1 API is enabled.
   Server authorization will use a service account configured on the backend;
   its private key belongs in a private server mount, never the APK or Git.

No SHA fingerprint is required solely for messaging. Do not configure Firebase
authentication or replace Volty accounts/friends for this feature. The Android
project configuration and the backend private credential are separate artifacts.
The server credential consumer and its private mount still need implementation.
The Android JSON does not contain the server service-account private key.
The backend now has a locally implemented account/installation-owned token
registry and authenticated mutation API; it is not yet deployed. Android token
registration and HTTPv1 sending are not wired, so this does not enable push calls.
A bounded HTTPv1 adapter is implemented and tested against local HTTP fixtures;
its bearer credential provider and delivery worker remain pending. No actual
Firebase message was sent or accepted during these fixture tests.

## Current Android configuration boundary

Gradle generates `google_app_id`, `google_api_key`, `gcm_defaultSenderId` and
`project_id` string resources from the matching client when the local JSON exists.
This is Firebase's documented alternative to the Google-services plugin, not a
second account system. Missing JSON does not fail configuration; malformed or
mismatched supplied JSON fails without printing its contents. Messaging auto-init
is disabled until registration has an authenticated account owner. No messaging
service or token registration is wired yet.

An earlier attempt attributed unavailable Google Maven artifacts to the plugin
version. Retraction: the same official BoM35.0.0 URL returns200 from the server
while local requests return404. This is a network-dependent fetch failure, not
evidence that the documented version is absent. Verification used a temporary
SSH SOCKS tunnel, now closed; no mirror or TLS verification bypass is configured.
The official SDK resolved successfully: BoM35.0.0 selects Messaging26.0.0.
The full Android suite passed3407 tests across336 fresh XML suites with zero
failures/errors/skips. The four generated resource values matched the supplied
JSON without exporting their contents. A separate offline configuration check
with the JSON temporarily absent also passed; the original file was restored.
That configuration check proves optional provisioning, not a second test run.

## Acceptance

Actual registration, FCM acceptance and notification display are different
observations. Test foreground, background and normal process death, an old
cancelled/accepted call, duplicates, account switch/logout and disabled call
notifications. Existing call verification remains mandatory. Only explicit
acceptance in Activity may start the microphone/voice foreground service.
Force-stop, absent Google Play services and OEM restrictions remain device
conditions to observe, not guarantees made by the backend.

Official references read2026-10-08:
[Android setup](https://firebase.google.com/docs/cloud-messaging/android/get-started),
[data-message handling](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
[HTTPv1 authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api).
Direct resource configuration:
[Firebase multiple-project initialization](https://firebase.google.com/docs/projects/multiprojects).


## Server consumer implemented2026-10-09

Retraction of the earlier pending-consumer/worker statements above: the local
backend now loads a strictly validated service-account key, acquires bounded
OAuth credentials with an early-refresh monotonic cache, and wires the durable
queue/HTTPv1 sender into the separate application-owned worker. This has not
been deployed or tested with a real Google key/message.

Configure the existing voice_spaces backend with both settings:

```text
VOLTY_FCM_PROJECT_ID=<the Firebase project ID>
VOLTY_FCM_SERVICE_ACCOUNT_FILE=/run/secrets/volty-firebase-service-account.json
```

Mount the matching service-account JSON read-only at that container path, with
read permission for the backend user; store the host file outside the checkout.
Obtain it from Firebase project settings → Service accounts → Generate new
private key. It is separate from the Android JSON already supplied. The key
consumer accepts only the matching project, Google OAuth endpoint/default
universe and messaging scope; missing/invalid configured credentials fail before
DB startup. Both variables absent intentionally keeps transport disabled.

Real service-account provisioning is pending the user's file path. Native
registration/FCM service and deferred phone acceptance remain next. Fixtures used
only ephemeral RSA keys, loopback OAuth/send servers and the existing owned
scratch PostgreSQL; none authorized a Google message or changed production.

## Private key provisioned2026-10-09

Retraction of the pending-file statement: the user supplied a directory containing
one service-account JSON and authorized copying it. The project matches Android
configuration. The actual production typed loader accepted the private copy
without network access. Local `backend/.secrets/firebase-service-account.json`
is ignored by Git and restricted to the current user/System. Server copy is
`/home/sodovaya/.config/volty/firebase-service-account.json`, directory700/file600;
transfer integrity verified. Neither copy is APK input. No private contents were
printed or committed. Container mount/settings and actual OAuth/message delivery
are still pending; the running production process was not changed.

Later verification on the server used the final immutable compiled production
loader/provider with this real key and the fixed Google OAuth endpoint. Google
accepted authorization; a second provider call reused the exact cached bearer.
No bearer/key material was printed. Retraction of "actual OAuth pending": OAuth
is now observed. This proves neither FCM send permission/API enablement nor phone
delivery. No FCM message was sent and production runtime remains unchanged.

The account-owned client HTTP registration boundary is now implemented: fixed
HTTPS PUT, explicit null token retirement, bounded10s request and8KiB response,
typed stale-binding/token-conflict outcomes, repository-owned bearer refresh,
and live-account checks before request and before response publication. Actual
issued-response cancellation and borrowed-resource ownership are tested. Fresh
Android3414 tests337 suites passed. Persisted installation state, token owner,
FCM receive service and physical acceptance remain pending.

## Native wiring implemented2026-10-09

Retraction of the previous pending-installation/owner/service status: durable
encrypted installation state, account-owned SDK token registration, Firebase
messaging service and bounded WorkManager receiving are assembled in the app.
Cold startup reuses the repository's existing application-owned session restore.
Hints require an acknowledged current actor/binding/generation and fresh backend
verification; the worker waits through verification, and notification publication
rechecks the binding. No microphone, media connection or GPS capture starts from
push. Logout/replacement suppress old work without cancelling borrowed roots.

API31+ high-priority messages request expedited scheduling. Older Android/quota
fallback are ordinary work; one retry is explicitly10s rather than the default30s.
Neither route guarantees completion within the backend30s ring window. Native
callbacks, WorkManager timing, killed-process/OEM behavior and actual FCM delivery
remain deferred phone acceptance. No Google message was sent by this stage.
Production backend container credential mount/settings are still pending.

## Native build and private mount readiness2026-10-09

The signed0.7.14/code37 candidate contains the native push lane. Fresh3450 Android
unit tests/342XML suites passed; release and unchanged SQLDelight migration check
both executed successfully. APKv2 signature matches the preceding candidate; R8
preserves the Firebase service and reflected Worker constructor class. This proves
packaging/compilation, not device delivery or Xiaomi audibility.

An optional docker-compose.fcm.yml override now supplies required project and
host-key inputs, a fixed absolute container path and a read-only bind with
create_host_path:false. Docker Compose fixtures verified disabled base, enabled
configuration and rejection of each missing input; no service was started by the
config check. Compose accepts relative local host paths, so absolute host paths
remain deployment guidance, not a YAML-enforced claim.

The original private user-owned server key is retained. A separate identical copy
at /home/sodovaya/.config/volty/backend-secrets/firebase-service-account.json is
UID10001:10001/file600 inside a700directory. An ephemeral network-none/read-only
container using the current immutable backend image actually read this mounted
file as UID10001 and could not write it. App/SFU container identities and start
stamps stayed unchanged. No helper container or temporary plaintext copy remains.
The actual production credential mount/settings and new server release remain
pending. Neither provisioned key nor contents are committed or APK inputs.

## Production FCM rollout and API validation 2026-10-09

Retraction of the preceding pending-production-mount status: the new backend
image has been deployed with the Firebase project settings and a private,
read-only service-account mount. The runtime runs as UID10001. The database and
LiveKit containers retained their identities and start times. HTTPS health and
schema1..20 were checked after rollout; an unauthenticated push-registration
request is refused. No old groups were imported into new servers.

Before rollout, an isolated restore of a fresh production snapshot preserved
all twelve account/friend/community tables by comparing full-row fingerprints
without printing private rows. The previous backend image also started against
the upgraded snapshot. A separate quiescent backup was verified immediately
before replacing only the application container. Protected frozen old/new Compose
models and the backup remain in the private release directory; these models
contain environment credentials and must never be committed or printed. Future
application recreation must retain the new FCM settings/mount rather than use
the older deployment configuration that omits them.

The actual Google FCM API accepted a `validate_only=true` request with Android
metadata using the production credential loader and OAuth provider. This confirms
project API/IAM authorization; it sends no message and proves no handset delivery.
See the [Firebase send API](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send).
Physical notification delivery, Xiaomi voice and independent microphone/output
routing remain device acceptance checks. Cold-process Android receiver ownership
is undergoing additional regression verification; the0.7.14 candidate predates
that adjustment.

## Cold-process receiver regression verification 2026-10-09

The receiver now awaits account-owned registration and fresh verified incoming-call
discovery before evaluating the push binding. Cancellation of the receiver cannot
cancel the account producer. A failed first cold-start registration triggers one
explicit wake retry; STALE/token-conflict repairs are bounded to three immediate
requests per reconciliation. Logout/replacement/close release waiters without
joining held transport requests. Fresh3457 tests across342 suites passed with
zero failures, errors or skips. The signed0.7.15/code38 build is in progress;
physical delivery and Android process scheduling remain unverified.

## Cancelled cold wake correction 2026-10-09

Retraction of the earlier caller-owned initial-failure retry: accepting a cold
wake now records its retry on the account-owned entry before awaiting anything.
The runner performs one additional reconciliation after initial failure, even
when the receiver is cancelled. Close or account replacement fences it out.
The held-HTTP cancellation regression was observed failing before the fix.
Fresh 3458 tests in 342 suites pass with zero failures, errors or skips. The
narrow lifecycle review reports no remaining blockers. The final 0.7.15/code38
candidate is being rebuilt from this corrected source.

## Signed candidate verified 2026-10-09

Retraction of the preceding rebuild-in-progress status: final 0.7.15/code38 was
built after the cancelled-cold-wake correction. Release assembly and SQLDelight
migration verification passed with all 63 tasks executed. APK v2 signing and the
existing signer identity were verified. Every archive entry was scanned for the
supplied private key in PEM, JSON-escaped PEM and DER forms; none was present.
The immutable local acceptance artifact is `volty-nearby-c941iz-release.apk`,
69,654,142 bytes, SHA256
`65410b6ccd1a370a71cfcadd812fd2072996bc4450f4db23470548fa325562ef`.
This candidate supersedes the intermediate 0.7.15 build and the 0.7.14 candidate.
Physical delivery, Xiaomi audio and microphone/output routing remain unverified.

## Emulator correction and retraction — 2026-10-09

The 0.7.15 candidate is retired: actual Android startup exposed a native DI crash
despite the earlier build/signature checks. Corrected 0.7.16/code39 starts; the
replacement signed artifact and actual emulator acceptance are recorded in
`2026-10-09-nearby-emulator-acceptance.md`. Incoming notification acceptance was
observed with a background, live process. Process-death delivery and physical
audio remain unverified, and remote direct-call completion has an open UI defect.
