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
