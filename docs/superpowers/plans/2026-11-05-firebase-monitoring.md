# Firebase monitoring implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development; primary owns Gradle integration, only mechanical documentation and scoped review are delegated.

**Goal:** Count active users and diagnose stability/startup without screen/click analytics.

**Architecture:** Official Android SDK auto-initializers and manifest configuration; no common telemetry interface or product event plumbing. Google Services and Crashlytics Gradle plugins provide resources/build IDs/mappings when config is present. Performance SDK only, without network bytecode instrumentation.

**Tech Stack:** Kotlin Multiplatform Android application, Firebase BoM 35.0.0, Google Services 4.5.0, Crashlytics Gradle 3.0.8.

**Spec:** `docs/superpowers/specs/2026-11-05-firebase-monitoring.md`

## Global constraints

- Preserve existing BegodeProtocolTest.kt modification and all BLE/control code.
- Gradle workers and ActiveProcessorCount remain 4.
- Never check in google-services.json or expose keys in logs.
- Analytics has no user switch and no screen/click/custom event calls.

## Review focus

- FCM auto-init remains false and separate from Analytics.
- Merged dependencies cannot silently reintroduce screen reporting or advertising ID permissions.
- Crashlytics build ID and mapping tasks exist for release.
- A checkout lacking the ignored Firebase config can still configure/test debug.
- Existing privacy text saying no analytics/crashes must be explicitly retracted.

### Task 1: SDK and build integration (primary)

**Files:** gradle/libs.versions.toml, build.gradle.kts, composeApp/build.gradle.kts, androidMain/AndroidManifest.xml.

- [x] Add managed SDKs, official Google Services/Crashlytics plugins and conditional application.
- [x] Replace manual resource emission with official generation while preserving config validation and production gate.
- [x] Configure default-on collection, disable screen tracking and ads, retain FCM auto-init policy.
- [x] Build debug APK and inspect resolved dependencies, actual merged metadata/permissions, generated resources and release mapping tasks.

### Task 2: Disclosures and credits

**Files:** docs/store/play-listing.md, docs/store/firebase-monitoring.md, existing generated credits catalog.

- [x] Update bilingual store descriptions/data-safety draft and prepare privacy wording with implementation limits.
- [x] Refresh the catalog with the established credits exporter/normalizer; run generator tests and catalog verification.

### Task 3: Acceptance

- [x] Exercise SDK initialization/session events on the API34 emulator in Analytics debug mode; confirm absence of screen_view and custom events during UI navigation.
- [x] Verify Crashlytics initializes with a build ID and Performance starts; report console validation separately.
- [x] Run the full unit suite with fresh execution and assert the exact count.
- [x] Review scoped integration; record evidence and limitations. No merge/push implied by the previous feature's integration request.
