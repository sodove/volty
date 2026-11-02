# loeuc telemetry fixes implementation plan

> Use superpowers:subagent-driven-development and TDD. Scope approved by the rider's
> «Давай» after the three prioritized corrections in the latest loeuc audit.

Spec: `docs/superpowers/specs/2026-10-29-loeuc-latest-audit.md`.

Work in the existing feature checkout, preserving unrelated release/backend edits.
Never write to any wheel. No new global value filters, calibration, hardware models,
dependency, UI, firmware update, or release-version change in this task.
Keep Gradle workers and ActiveProcessorCount capped at four. No commit/push required.

- [x] Task 1 — KingSong: A9 always owns ESC temperature; B9 owns motor temperature.
  Files: KingSongProtocol.kt and KingSongProtocolTest.kt. Retain both across ancillary
  updates, publish only fields fresh in the current packet, preserve unknown until
  observed, reset both on disconnect, accept genuine zero motor temperature.
  Verify WheelLog source before applying; mark the previous B9 claim as retracted.
  Do not also publish the A9 ESC sensor as a virtual-pack battery temperature;
  only real F1/F2 thermistors populate battery temperatures.
  Add failing tests first, then fix and run focused tests.
- [x] Task 2 — Veteran: carry original CRC/legacy framing into controller decoding.
  Files: VeteranProtocol.kt and VeteranPowerFlowTest.kt. Agreement of torque/speed
  means draw only on exactly 36-byte legacy frames; modern retains opposite rule.
  Test both directions, braking, zero speed/current, and CRC frames whose stripped
  payload length could otherwise be mistaken for a legacy frame. Battery signs and
  raw phase current remain unchanged. Add failing tests, then minimal fix.
- [x] Task 3 — Veteran legacy reassembly: recover from a lost tail without emitting
  the next DC 5A 5C 20 header as old telemetry. Files: VeteranProtocol.kt and
  VeteranLegacyReassemblyTest.kt. Do not change CRC-frame payload handling.
  Test lost-tail streaming, split header/length, both frames complete, CRC payload
  containing a header-like pattern, and reset. Define nested-header ambiguity in
  comments rather than hiding it behind new absolute physical limits.
  Task 2 must finish before Task 3 edits the shared production file.
- [x] Review each task and final combined diff, run fresh full app unit suite and
  database migration verification, inspect fresh XML results and exact test count.
  Record results and remaining hardware limitations in the ledger.
