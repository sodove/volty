# Nearby incoming-call screen increment

Version 0.7.23 / code 46. This increment implements incoming CallStyle notification and an internal Volty screen. Telecom lifecycle, ongoing-call system controls and explicit Bluetooth capture/playback split remain pending.

## Behavior

The previous notification content intent used ACCEPT. Tapping the notification body could therefore answer. It now uses a distinct OPEN address and an immutable Activity PendingIntent. Display lookup resolves only the original current published ticket under its account binding, without consuming it or issuing backend/media work. Explicit answer hands off to the existing MainActivity permission/admission gates; explicit rejection delegates to the existing account-owned producer.

The screen follows the original ticket and closes after cancellation, expiry, consumption or retirement. Caller label changes retain original ticket identity. Launch is internal/nonexported; the incoming task is excluded from recents, while answer reuses the main task.

A narrow review caught use of API 27 lock-screen methods on minSdk 26. They are now guarded at Android 8.1+, with equivalent legacy window flags on Android 8.0. No API-26 device acceptance is claimed.

Full-screen launch is optional. Android 14+ eligibility is checked separately from notification permission. A profile setting opens the package-specific system page and refreshes on resume. There is no mandatory settings redirect or onboarding gate. Without full-screen access, ordinary call notification actions and opening the screen by tapping remain available.

## Measured verification

- RED: six selected tests first fail three assertions for missing OPEN; with parsing implemented, two assertions fail on actual display lookup. Meaningful tests cover no HTTP/media/consumption, wrong action/binding/ticket, consumed/retired/expired ticket and identical ticket IDs after same-user relogin.
- Fresh full suite: 3483 tests in 344 reports, zero failures/errors/skips; timestamp freshness asserted. Signed ARM/x86 and database migration pass, 143 tasks executed.
- A later capped run exposed the existing navigation fallback fixture recording concurrent Default-dispatcher handlers in an unsynchronized mutable list. Only that fixture now uses the common test scheduler; both host and exact request-count assertions remain. Production navigation is unchanged.
- Native signed emulator: minimized receiver receives CallStyle with named caller and answer/reject. Body opens Volty incoming screen; actual backend remains RINGING. Explicit screen rejection confirms DECLINED. A second owned caller cancellation confirms CANCELLED and closes the screen. Repeated on the final app sources after adding optional full-screen settings.
- Profile full-screen control opens the actual Android package settings screen. Actual eligibility after update is allowed. Retraction: an earlier appops default/rejection history was insufficient to establish current `canUseFullScreenIntent`; use the API result instead.
- Earlier measured warm/cold FCM probes confirmed emulator delivery, including process restart and native rejection. These do not establish the Pixel-specific failure cause or physical audibility.
- Both synthetic accounts were removed after exact UUID/name proof; native test receiver explicitly logged out and temporary Demo disconnected. Original user account retained. No backend deployment change.

## Gradle CPU limit

At the user's request, project `gradle.properties` sets `org.gradle.workers.max=4` and JVM `-XX:ActiveProcessorCount=4`. Runtime assertion confirms both max workers and available processors equal four. Installed AGP 8.13.2 bytecode shows its default R8/D8 executor size uses `Runtime.availableProcessors()`, so the JVM setting also bounds that default pool. This is a parallel-work limit, not a claim that the JVM has only four total housekeeping threads.

## Remaining acceptance

Physical Pixel delivery and Bluetooth phone-mic/headset-output behavior are unproven. Telecom authority, ongoing call notification/remote controls, answering with locked device, mic denial and active-call cleanup need the later plan tasks. The signed emulator checks do not replace them.
