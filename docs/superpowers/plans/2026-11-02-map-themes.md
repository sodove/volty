# Application and map themes

> **For agentic workers:** Use superpowers:subagent-driven-development for narrow tasks; primary owns tonal generation and multi-renderer integration.

**Goal:** Share a chosen color direction across the UI and all map renderers, with independently controlled map tones, and provide real Android screenshots.

**Architecture:** Read Android system accent/neutral tonal families for Material You. Generate HCT tonal families for manual colors using the standalone MaterialKolor color utilities, without another Compose dependency. Derive a serializable map palette and paint patches for the existing bright/dark OFM layers; retain source URLs, layer order, geometry, filters and zoom expressions. Apply patches to loaded native layers and bundled thumbnail JSON.

**Spec:** User conversation: manual color when adaptive disabled; map should match, including near-black/white tones unavailable as individual ColorScheme roles. User authorized implementation and screenshots.

## Global constraints
- minSdk 26; do not change database or BLE.
- Keep Gradle workers=4 and ActiveProcessorCount=4.
- Existing checkout contains unrelated edits: preserve them; no automatic commits.
- Both English and Russian strings; no Android escaped newlines.
- Preserve OFM style URLs/offline identity, semantic telemetry colors, sources and layer structure.

## Review focus
- Retained maps must recolor with unchanged dark mode, including a style load finishing after theme changes.
- Manual selection survives dynamic-color toggles, process restarts, unknown stored IDs.
- Bright/dark layer IDs differ; zoom-dependent color expressions retain stops and alpha.
- Thumbnail keys must include the entire map palette, not just route color.
- Material You works on API31+ and manual fallback works below it.

### Task 1: Persist manual palette and settings controls
**Ownership:** AppPrefs.kt, SettingsComponent.kt, SettingsScreen.kt, a new ThemeAccent.kt enum, both strings.xml, AppPrefsThemeTest.kt.
**Interface:** domain.model.ThemeAccent with stable id and seedArgb:Int; DEFAULT(0xFF5B53D4), ORANGE(0xFFC45C20), GREEN(0xFF367C44), TEAL(0xFF007F82), BLUE(0xFF2165C0), PINK(0xFFAA3F77). AppPrefs.themeAccent:StateFlow<ThemeAccent>; setThemeAccent(ThemeAccent). Settings State.themeAccent and onThemeAccentChanged default no-op for compatibility with existing fakes. Picker visible when dynamic colors disabled or unsupported.
- [x] Add failing persistence tests (default, roundtrip, unknown ID fallback, independent adaptive toggle); run red.
- [x] Implement enum/persistence, component forwarding, labelled accessible color selection with selected state, translated strings.
- [x] Run focused tests and review diff; do not change VoltyTheme or map files.

### Task 2: Tonal generation and map paint policy (primary)
**Ownership:** theme palette files, build dependency, map paint policy and tests.
- [x] Tests for independent dark/light tones, hue changes, geometry-preserving JSON transformation and semantic exclusions; observe red.
- [x] System tonal reader and seeded HCT generation; manual UI ColorScheme; LocalVoltyMapPalette.
- [x] Explicit bright/dark role mapping for neutral map geometry and thematic overlays, preserving color expressions and alpha.

### Task 3: Integrate all map renderers (primary)
- [x] Apply shared paint policy on load, same-URL reuse and live palette change; use current palette in asynchronous callbacks.
- [x] Theme vehicle snapshots and history thumbnail backgrounds, cache identity includes palette, route uses themed accents.
- [x] Preserve offline source policy, meaningful telemetry colors and lifecycle behavior.

### Task 4: Verification and screenshots
- [x] Full test suite, debug build, inspect actual counts/failures; review scoped diff.
- [x] Capture baseline and themed dark/light maps and manual color selection on emulator; inspect screenshots, fix visual issues.
- [x] Deliver image links and limitations; do not claim physical-device validation.
