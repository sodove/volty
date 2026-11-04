# Firebase monitoring disclosure

Status: documentation for the Firebase Analytics, Crashlytics and Performance SDK configuration approved on 2026-11-05. This records the intended behavior for the Android build; verify it against the merged manifest, resolved SDKs and runtime before treating the implementation as verified.

## Actual collection and controls

- Firebase Analytics collection is on by default for signed-in and signed-out use. It automatically records `first_open`, `session_start`, `user_engagement` and other standard SDK lifecycle/update events. This supports active-user counts. Automatic `screen_view` collection is disabled. The app adds no custom events, screen/navigation hooks, user IDs or user properties.
- Advertising-ID collection, ad storage and advertising personalization are disabled. Volty has no ads.
- Crashlytics automatically collects fatal Java/Kotlin crashes and ANRs. The app adds no nonfatal-exception or log-upload layer and this work does not add NDK symbol integration.
- The Performance SDK collects built-in app startup/lifecycle metrics and may collect automatic Activity rendering traces. Those rendering metrics are performance diagnostics, not app navigation analytics. There is no Performance Gradle network-instrumentation plugin and no custom feature trace.
- These SDKs run without an account, a consent dialog or an in-app opt-out. FCM keeps its existing account-owned startup: its auto-init remains disabled until the current authenticated startup enables it.
- SDK traffic can include pseudonymous Firebase installation identifiers, device/app information, timestamps and durations. Crash reports can include stack traces, exception messages and runtime context. Google may infer approximate location from network information. The app does not deliberately add routes, vehicle/BMS addresses, names, email addresses or ride values as custom telemetry; this is not a promise that runtime context in a crash report can never contain app data.

## Privacy policy text to insert

The following Russian paragraph is ready to insert into the canonical policy at `C:/Users/sodovaya/git/sodove.ru/privacy_volty.html`. That file is outside this repository and is not changed or published here.

> Для аналитики и обеспечения работы Volty приложение автоматически передаёт Google Firebase сведения об использовании и техническом состоянии. Firebase Analytics считает первые запуски, сеансы, активность и стандартные события жизненного цикла приложения; сбор просмотров экранов и пользовательских событий отключён. Firebase Crashlytics собирает отчёты о критических сбоях и ANR, а Firebase Performance Monitoring — показатели запуска и производительности приложения, включая возможные автоматические трассировки отрисовки Activity в Android. Сбор включён по умолчанию, в том числе без входа в аккаунт; отключить его в приложении нельзя. Передаваемые сведения могут включать псевдонимный идентификатор установки, тип и версию устройства и приложения, временные метки и длительность событий; отчёт о сбое может содержать стек вызовов, сообщение об ошибке и контекст выполнения, который может включить данные приложения. Google может определить примерное местоположение по сетевой информации. Мы не добавляем маршруты, адреса транспорта/BMS, имя, email или значения поездок в пользовательскую телеметрию. Рекламный идентификатор, хранение данных рекламы и персонализация отключены. Firebase Cloud Messaging по-прежнему запускается только после входа в аккаунт для доставки уведомлений о голосовых вызовах.

## Google Play Console draft

Use these collection purposes when completing Data safety. The entries below describe SDK collection and transfer to Firebase/Google; confirm the final categories and sharing answers in the current Play Console form.

| Play data category | Collection | Purpose | Disclosure detail |
|---|---|---|---|
| App activity → App interactions | Yes, automatic | Analytics | First-open, session, engagement and standard lifecycle/update events. No automatic screen views, custom navigation/button events, user IDs or user properties. |
| App info and performance → Crash logs / Diagnostics | Yes, automatic | App functionality | Fatal Java/Kotlin crash and ANR reports may include stacks, exception messages and runtime context. No custom nonfatal reports or log uploads. |
| App info and performance → Other app performance data | Yes, automatic | App functionality | Built-in startup/lifecycle metrics and possible automatic Activity rendering traces. No network instrumentation or custom feature traces. |
| Device or other IDs | Yes, automatic | Analytics and app functionality | Pseudonymous Firebase installation identifier and related device/app information. The existing FCM token remains account-gated. Advertising ID is disabled. |

Collection is on by default in the app as shipped, does not depend on account sign-in, and has no consent prompt or app setting to turn it off. Select Analytics for usage measurement and App functionality for crash, ANR and performance diagnostics. No data is used for advertising. Do not describe the outgoing data as only an active-user count: SDK requests also contain identifiers and technical context as noted above.

## Manual verification

Use a debug build with the matching `google-services.json` and an Android device/emulator with network access. The Android application ID is `ru.sodovaya.volty`.

1. Enable verbose Analytics SDK logs, then enable DebugView for this package:

   ```sh
   adb shell setprop log.tag.FA VERBOSE
   adb shell setprop log.tag.FA-SVC VERBOSE
   adb shell setprop debug.firebase.analytics.app ru.sodovaya.volty
   adb logcat -v time -s FA FA-SVC
   ```

2. Launch the app and inspect Firebase Console → Analytics → DebugView. A fresh install can produce `first_open`; normal use should show session/engagement and standard lifecycle events. Move between screens and verify that no `screen_view` or custom navigation/button event appears. No user ID or custom user property should be present.
3. Inspect Android logs and the event stream for SDK startup, queued/sent standard events, and any `firebase_error` messages. This verifies local runtime behavior and event delivery to the selected Firebase project; it does not prove that the project is connected to the intended Google Analytics property or that standard reports are available.
4. In Firebase Console, separately verify the project’s Google Analytics integration/property and that DebugView is showing this app/project. Console/property linkage and report visibility are not established by successful local SDK initialization. Standard reports can lag behind DebugView.
5. Disable debug mode when finished:

   ```sh
   adb shell setprop debug.firebase.analytics.app .none.
   ```

DebugView instructions: [Firebase Analytics DebugView](https://firebase.google.com/docs/analytics/debugview). Debug logging instructions: [Get started with Google Analytics for Android](https://firebase.google.com/docs/analytics/android/get-started).

## Firebase references

- [Disable automatic screen-view reporting](https://firebase.google.com/docs/analytics/screenviews)
- [Configure Analytics data collection](https://firebase.google.com/docs/analytics/android/configure-data-collection)
- [Set up Crashlytics for Android](https://firebase.google.com/docs/crashlytics/android/get-started)
- [Set up Performance Monitoring for Android](https://firebase.google.com/docs/perf-mon/get-started-android)
