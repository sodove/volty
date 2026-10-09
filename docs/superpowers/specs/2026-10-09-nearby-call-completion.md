# Nearby: normal call completion (2026-10-09)

## Confirmed problem and behavior

The Stage188 emulator check found that when one participant hung up, the other participant retained a failure panel. Two paths classified normal closure as failure: a heartbeat terminal CANCELLED receipt and LiveKit's explicit room closure event.

A matching CANCELLED heartbeat now requests normal shutdown. Android SDK 2.25.3 explicit CLIENT_INITIATED, PARTICIPANT_REMOVED, ROOM_DELETED and ROOM_CLOSED reasons produce a tagged EngineClosed event. Other disconnect reasons and expired leases remain failures. The reducer still rejects stale session/generation events. Local media termination does not acknowledge outstanding remote cleanup; each cleanup operation still requires its own receipt.

## Verification

- Regression RED: 32 filtered tests, three expected failures reaching normal terminal paths.
- Final fresh suite: 3,466 tests in 343 reports, zero failures/errors/skips; all 143 Gradle tasks executed with cache disabled. Both signed APK variants and SQLDelight migration verification passed.
- Tests cover every SDK disconnect reason, exact cleanup effects, remote cleanup pending after media stops, expired heartbeat failure and stale terminal receipts.
- A pre-existing navigation concurrency test failed once during the full build: its default MockEngine dispatcher raced a virtual timeout. Its fixture now uses the same test scheduler and asserts that both requests were actually issued before either response is released. Production navigation code is unchanged.
- Signed 0.7.19 on emulator-5556 established a real LiveKit connection. The owned API peer ended the call; the native dock disappeared without a failure panel. A subsequent call connected and native Disconnect also closed normally; the API reported ENDED. Both synthetic accounts were deleted using fresh login tokens after their initial access tokens expired. Original emulator-5554 account data was retained.
- Narrow Luna review found no blocking issue. No new Astra invocation.

## Artifact and limits

Version 0.7.19 / code 42. ARM APK SHA-256: `5aad183014a1c0fbb38b786f32274714990773c9db2e9111a3ceba4efcf44c7b`. Existing signing certificate retained; SMTP and Firebase private material absent from all APK entries.

The second participant was an API-controlled fixture, not another native microphone. This proves call lifecycle behavior, not two-phone audibility. Physical Xiaomi/headphone routing checks remain deferred. This client-only change does not require another backend deployment; Stage190's recovery-enabled backend remains deployed.
