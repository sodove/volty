# FarDriver telemetry decoder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read/notify-only FarDriver BLE protocol that decodes the two reverse-engineered 16-byte frame families into honest motion telemetry and the existing derived-battery path.

**Architecture:** `FarDriverProtocol` owns framing, CRC/checksum validation, nullable evidence state, and `ControllerData`/`BmsData` publication. The existing `controllerMotionProtocol` remains the sole wiring point. No BLE writes are emitted; the protocol exposes FFE0/FFEC and empty handshake/poll commands.

**Tech Stack:** Kotlin Multiplatform commonMain/commonTest, `kotlin.test`, existing `BmsProtocol`, `MotionSource`, `ByteArrayAccumulator`, `ControllerData`, `BmsData`, and `MotorConfig`.

## Recorded correction: scalar byte order (2026-08-20)

The original Task 1 decoder and fixtures used a big-endian helper for ordinary
16-bit FarDriver values. That was wrong. Public reverse references from
`jackhumbert/fardriver-controllers` and
`bobecek79/ESP32-Fardriver-BLE-Reader` confirm little-endian encoding for the
E2/E8/D6/F4 telemetry scalars, which matches the reported 1638.7 V and extreme
temperature symptoms. The implementation and fixtures were corrected to
little-endian; the EE 24-bit phase-current fields remain explicitly
big-endian. The prior assumption is retained here as a correction record rather
than silently rewritten. The original no-write/read-only constraint remains in
force.

## Global Constraints

- Never send FarDriver configuration, login, password, time, firmware, or keepalive commands in this first slice.
- Unknown or incomplete fields must remain unavailable; never replace missing evidence with a confident zero.
- Preserve `ControllerData.speedKmh` as a non-negative magnitude and use `SpeedSource.NONE` when geometry is unavailable.
- Use both `values/` and `values-ru/` only if user-visible strings are added; this feature adds none.
- Run `./gradlew.bat :composeApp:testDebugUnitTest` before completion.

---

### Task 1: Build the FarDriver parser and telemetry state

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/FarDriverProtocol.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/FarDriverProtocolTest.kt`

**Interfaces:**
- Consumes: arbitrary BLE notification chunks through `BmsProtocol.onNotification`.
- Produces: FFE0/FFEC `BmsUuids`, empty command lists, one `MotionSource` controller, optional derived pack, and `reset()` semantics.

- [x] **Step 1: Write failing tests.** Added exact UUID, frame, checksum, availability, split-chunk, resynchronisation, and reset fixtures.
- [x] **Step 2: Verify RED.** Confirmed the focused test failed before the protocol existed.
- [x] **Step 3: Implement minimally.** Added read-only framing, CRC/additive checksums, both frame families, register mappings, explicit evidence flags, RPM speed derivation, fault labels, and conditional derived battery publication.
- [x] **Step 4: Verify GREEN.** Focused FarDriver tests pass.
- [x] **Step 5: Refactor after green only.** Kept checksum, decoding, fault-map, and snapshot helpers private; no writes were added.
- [x] **Step 6: Commit.** Included in the implementation commit.

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/FarDriverProtocol.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/FarDriverProtocolTest.kt
git commit -m "feat(fardriver): decode read-only telemetry frames"
```

### Task 2: Wire FarDriver into controller protocol coverage

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ControllerProtocols.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryVescTest.kt` (existing controller coverage location)

- [x] **Step 1: Add failing coverage.** Added factory, MotionSource, derived-pack, and no-write coverage.
- [x] **Step 2: Verify RED.** Confirmed the coverage failed while the factory returned null.
- [x] **Step 3: Wire the exhaustive arm.** FarDriver now constructs `FarDriverProtocol`; battery-only arms are unchanged.
- [x] **Step 4: Verify GREEN.** Coverage tests pass.
- [x] **Step 5: Commit.** Included in the implementation commit.

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ControllerProtocols.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryVescTest.kt
git commit -m "feat(fardriver): wire controller protocol"
```

### Task 3: Pin alert availability to actual FarDriver evidence

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/alert/MotionAlertAvailability.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/alert/MotionAlertAvailabilityTest.kt`

- [x] **Step 1: Change the pinned FarDriver duty expectation first.** The alert fixture now expects FarDriver duty to be unavailable.
- [x] **Step 2: Verify RED.** Confirmed the old optimistic table failed the new expectation.
- [x] **Step 3: Set the single FarDriver table arm to false** and documented the missing verified field.
- [x] **Step 4: Verify GREEN.** Alert tests pass.
- [x] **Step 5: Commit.** Included in the implementation commit.

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/alert/MotionAlertAvailability.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/alert/MotionAlertAvailabilityTest.kt
git commit -m "fix(fardriver): do not arm unverified duty alert"
```

### Task 4: Full verification and documentation ledger

**Files:**
- Modify: `docs/superpowers/specs/2026-07-24-vehicle-platform/E-fardriver.md`

- [x] **Step 1: Run `git diff --check` and all focused FarDriver, controller coverage, and alert tests.**
- [x] **Step 2: Run `./gradlew.bat :composeApp:testDebugUnitTest --no-build-cache --rerun-tasks --no-configuration-cache --console=plain`; fresh result: 1857 tests, 0 failures.**
- [x] **Step 3: Record in E-fardriver.md that the read-only decoder is implemented from static APK evidence, no session writes are sent, and live capture remains required before enabling handshake/keepalive or claiming firmware-wide compatibility.**
- [x] **Step 4: Commit the documentation update.**

```powershell
git add docs/superpowers/specs/2026-07-24-vehicle-platform/E-fardriver.md
git commit -m "docs(fardriver): record decoder implementation boundary"
```
