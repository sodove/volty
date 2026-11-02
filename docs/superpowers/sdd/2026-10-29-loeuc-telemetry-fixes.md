# loeuc telemetry corrections — 2026-10-29

Implemented the three corrections approved after the
[upstream audit](../specs/2026-10-29-loeuc-latest-audit.md), following the
[plan](../plans/2026-10-29-loeuc-telemetry-fixes.md).

- KingSong A9 owns ESC temperature; B9 owns motor temperature. Each sensor retains
  its value independently, but only the fields carried in the current packet are
  published as fresh. Zero is valid, and reset clears the motor-presence latch.
  A9 is no longer also published as a virtual battery thermistor; actual F1/F2
  BMS thermistors remain available per pack.
- Exact 36-byte legacy Sherman frames use their own torque-axis convention:
  equal speed/current signs mean draw, opposite signs mean regeneration. Modern
  CRC frames retain their convention. Original framing is passed through CRC
  stripping; the decision does not depend on model name/version. Raw phase-current
  signs and BmsData current/power availability are preserved.
- A complete nested legacy header before the declared legacy boundary restarts
  reassembly after a lost tail. CRC-frame payloads keep header-like bytes as data.

Old KingSong sensor and Veteran polarity/framing claims are explicitly marked as
retracted in source comments. The MIT notice for loeuc material is preserved in
`THIRD_PARTY_NOTICES.md`.

## Verification

Tests were added/updated first. Actual focused RED runs observed five failures
for KingSong sensor ownership, four for Veteran polarity/reassembly, and three
for the later virtual-pack temperature correction. The final review found that
last leak; scoped re-review confirmed it fixed and found no further issue.

Final command:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest :composeApp:verifyCommonMainVoltyDatabaseMigration --rerun-tasks --no-build-cache --max-workers=4 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=4'
```

Result: **5,975 tests, zero failures/errors/skips**, 583 fresh XML reports,
36 tasks executed; database migration verification passed. The exact expected test
count and XML modification times were asserted. The test count increased by 15.
`git diff --check` passed. No mutation sweep was claimed or run.

Two older tests used legacy 36-byte synthetic frames while asserting modern Lynx
power signs. Their fixtures now use valid modern CRC framing; their expected
modern values remain unchanged.

## Practical limits

No physical wheel was connected or written to. The legacy parser cannot prove
whether a literal header sequence inside a checksum-less payload is data or a
new frame. A header not yet complete when a candidate is emitted also cannot
always be recovered without delaying otherwise valid frames. These ambiguities
remain documented; no new global physical-value filters were introduced.

The existing release APK/AAB were not rebuilt for these changes. Backend logging,
native alignment, manifest and release-version edits already in the checkout
belong to earlier work and were preserved.
