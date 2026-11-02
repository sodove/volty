# Credits and licenses implementation evidence

Plan: `docs/superpowers/plans/2026-10-29-credits-licenses.md`.

## Result

Settings → «Благодарности и лицензии» uses the existing Material theme and one
alphabetical list. Vescape and loeuc-core have the same rows/details as every
other project. Search includes project names, authors and licenses. Unknown
authors/versions are omitted; all applicable license buttons remain available.
Texts and copyright notices are local and selectable.

The checked-in catalog has **206 projects, 20 license texts**, from **201 actual
release runtime artifact coordinates** plus five copied-source/resource entries.
AboutLibraries 13.2.1 is a build-only plugin, without Kotlin/Compose/AGP upgrades
or a new runtime UI dependency. Local archive content hashes and original notice
inputs are tracked. `preReleaseBuild` runs `verifyCreditsCatalog`.

Manual inputs include Vescape, loeuc-core, RNNoise, AudioSwitch, BRouter, Noto
Sans and OpenFreeMap styles. WebRTC wrapper/native terms and JAIN SIP API/NIST
terms remain distinct. See `third-party/credits/README.md` for provenance and
remaining upstream/model/native audit questions. This is not a full legal audit.

## Automated checks

- Model/navigation RED: stubs compiled, **15 tests ran and failed**. A prior
  compilation failure was corrected first and was not counted as semantic RED.
- Model/navigation GREEN: **21 focused tests** including existing SettingsPages.
- Generator: **12 tests pass**. Review added three regressions: stale metadata
  versions, empty graph, and removed local artifacts. All three failed before
  their fixes, then passed.
- Full app suite: results directory removed before execution, build cache
  disabled; **585 fresh XML reports, 5990 tests, 0 failures/errors/skips**.
  Gradle ran the test task; the full run took 25 seconds.
- `verifyCommonMainVoltyDatabaseMigration`, `verifyCreditsCatalog`,
  `preReleaseBuild`, and `assembleDebug` succeeded with four workers and the
  required `-XX:ActiveProcessorCount=4` cap.
- Negative gate control: inserted an extra coordinate into the generated
  provenance graph; verification rejected it with `Credits dependency graph
  changed`. Original bytes restored; positive verification executed and passed.
- Additional fresh controls changed an original glyph NOTICE and appended a
  unique nonce to the local AudioSwitch AAR. `preReleaseBuild` rejected both with
  the expected source/archive hash errors; original bytes restored, positive
  gate executed again and passed. Referenced files are declared Gradle inputs;
  validation is explicitly unconditional.
- Packaged APK catalog bytes compared equal to the generated resource.

Independent final code review found no remaining actionable issue. An initial
claim that omitted input declarations could skip validation was withdrawn after
checking Gradle's no-output-task behavior; the task already ran every time. Input
declarations were nevertheless made complete and unconditional validation made
explicit. The reviewer did not run builds; checks above were run by the primary.

## Emulator checks

Installed signed debug APK on `emulator-5554`, Android API 34. Verified real UI:

- Settings entry and initial alphabetical list load without a scroll constraint
  crash; search finds loeuc-core/Vescape and unmatched query shows empty state.
- MIT and GPL texts open with airplane mode enabled.
- System Back and toolbar Back navigate license → project → catalog; query is
  retained. Opening a scrolled row and returning preserves the exact first row
  and offset.
- 320 dp with font scale 1.3, 390 dp dark, and 840 dp light layouts render and
  wrap their text. Screenshots are under `.artifacts/credits/`.
- Emulator size/density, system font setting, night mode and airplane mode
  restored to their pre-check values.

TalkBack speech and physical-device rendering were not tested. This repository
has no Compose UI test infrastructure; these are emulator smoke checks.

The APK is `composeApp/build/outputs/apk/debug/volty-0.7.63-debug.apk`. Existing
Play Console release APK/AAB files were not replaced by this task. No commit,
push or deployment was performed.
