# Firebase monitoring

Approved in chat: add Analytics, Crashlytics and Performance Monitoring. Collection starts by default with no in-app switch or consent screen. Analytics is for active-user counts, not navigation/click tracking.

## Scope

- Reuse the existing Firebase project and BoM 35.0.0. Add the main Android Analytics, Crashlytics and Performance artifacts (not retired KTX artifacts).
- Use automatic Analytics sessions/engagement for active users. Disable automatic screen views; add no custom events, screen hooks, user IDs, or user properties. Disable advertising-ID collection, ad storage and advertising personalization. No new analytics settings UI.
- Crashlytics collects fatal crashes/ANRs automatically. No custom exception/log upload layer in this task. Apply its official Gradle plugin so R8 mappings and build IDs are correct.
- Performance SDK collects its built-in app lifecycle/startup metrics. Deliberately omit the network-instrumentation Gradle plugin and custom HTTP traces: navigation URL paths can contain coordinates. No feature/button traces.
- Keep FCM's existing account-owned auto-init disabled until its existing authenticated startup enables it. Monitoring must not enable call push registration while signed out.
- Replace the earlier manual Firebase resource generation with the official Google Services plugin, conditionally when the git-ignored config exists. Keep production config checks; config-less debug/test builds remain possible.
- Update store disclosure and dependency credits. Prepare explicit privacy-policy wording; the existing canonical website file is untracked work in another repository, so do not overwrite or publish it as part of this change.
- Verify actual merged/APK manifests, SDK runtime startup and full unit suite. Distinguish local SDK/upload verification from Firebase console/GA property visibility.

References checked: [screen collection](https://firebase.google.com/docs/analytics/screenviews), [Analytics collection controls](https://firebase.google.com/docs/analytics/android/configure-data-collection), [Crashlytics setup](https://firebase.google.com/docs/crashlytics/android/get-started), [Performance setup](https://firebase.google.com/docs/perf-mon/get-started-android).
