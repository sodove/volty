# FarDriver telemetry decoder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read/notify-only FarDriver BLE protocol that decodes the two reverse-engineered 16-byte frame families into honest motion telemetry and the existing derived-battery path.

**Architecture:** `FarDriverProtocol` owns framing, CRC/checksum validation, nullable evidence state, and `ControllerData`/`BmsData` publication. The existing `controllerMotionProtocol` remains the sole wiring point. No BLE writes are emitted; the protocol exposes FFE0/FFEC and empty handshake/poll commands.

**Tech Stack:** Kotlin Multiplatform commonMain/commonTest, `kotlin.test`, existing `BmsProtocol`, `MotionSource`, `ByteArrayAccumulator`, `ControllerData`, `BmsData`, and `MotorConfig`.

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

- [ ] **Step 1: Write failing tests.** Instantiate `FarDriverProtocol(motor = MotorConfig(...))`; assert exact UUIDs, empty commands, one controller, valid new/legacy fixture decoding, split chunks, checksum rejection, resynchronisation, scaling, availability flags, and reset.
- [ ] **Step 2: Verify RED.** Run `./gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.FarDriverProtocolTest" --no-build-cache --rerun-tasks --console=plain`; expect compilation failure because the protocol is absent.
- [ ] **Step 3: Implement minimally.** Accumulate arbitrary chunks, scan for `0xAA`, parse complete 16-byte candidates, classify register frames by `(byte1 and 0xC0) == 0x80`, validate CRC-16/MODBUS (poly `0xA001`, initial `0x7F3C`, low byte first), otherwise validate legacy big-endian additive checksum, and trim one byte on invalid candidates. Decode register 232 (voltage/current), 238 (phase currents), 226 (RPM/status), 214 (controller temperature/status), 244 (motor temperature/SOC), 250 (stop/running), plus legacy commands 0/1/2/15. Build explicit availability flags, derive mechanical-RPM speed from `MotorConfig`, set duty/distance/energy unavailable, and create a derived pack only after voltage/current evidence exists.
- [ ] **Step 4: Verify GREEN.** Re-run the focused FarDriver test command; all tests must pass.
- [ ] **Step 5: Refactor after green only.** Extract private CRC, decoding, fault-map, and snapshot helpers only if tests remain green; never add writes.
- [ ] **Step 6: Commit.**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/FarDriverProtocol.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/FarDriverProtocolTest.kt
git commit -m "feat(fardriver): decode read-only telemetry frames"
```

### Task 2: Wire FarDriver into controller protocol coverage

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ControllerProtocols.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryVescTest.kt` (existing controller coverage location)

- [ ] **Step 1: Add failing coverage.** Assert `controllerMotionSupported(ControllerType.FARDRIVER)` is true and the factory returns a `FarDriverProtocol` that is also a `MotionSource`.
- [ ] **Step 2: Verify RED.** Run the focused coverage class; it must fail while the factory returns null.
- [ ] **Step 3: Wire the exhaustive arm.** Replace `ProtocolKind.FARDRIVER -> null` with `FarDriverProtocol(deriveBattery = deriveBattery, motor = motor)`; leave battery-only arms unchanged.
- [ ] **Step 4: Verify GREEN.** Re-run the coverage class.
- [ ] **Step 5: Commit.**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ControllerProtocols.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryVescTest.kt
git commit -m "feat(fardriver): wire controller protocol"
```

### Task 3: Pin alert availability to actual FarDriver evidence

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/alert/MotionAlertAvailability.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/alert/MotionAlertAvailabilityTest.kt`

- [ ] **Step 1: Change the pinned FarDriver duty expectation first.** Assert `ControllerType.FARDRIVER.reportsDuty == false` and that the duty alert is unavailable with the existing protocol-unverified reason.
- [ ] **Step 2: Verify RED.** Run the focused alert class; the current static table must fail the new expectation.
- [ ] **Step 3: Set the single FarDriver table arm to false** and update its KDoc to state that the APK did not expose a verified duty field.
- [ ] **Step 4: Verify GREEN.** Re-run the alert class.
- [ ] **Step 5: Commit.**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/alert/MotionAlertAvailability.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/alert/MotionAlertAvailabilityTest.kt
git commit -m "fix(fardriver): do not arm unverified duty alert"
```

### Task 4: Full verification and documentation ledger

**Files:**
- Modify: `docs/superpowers/specs/2026-07-24-vehicle-platform/E-fardriver.md`

- [ ] **Step 1: Run `git diff --check` and all focused FarDriver, controller coverage, and alert tests.**
- [ ] **Step 2: Run `./gradlew.bat :composeApp:testDebugUnitTest --no-build-cache --rerun-tasks --no-configuration-cache --console=plain`; require a fresh successful result and exact test count.**
- [ ] **Step 3: Record in E-fardriver.md that the read-only decoder is implemented from static APK evidence, no session writes are sent, and live capture remains required before enabling handshake/keepalive or claiming firmware-wide compatibility.**
- [ ] **Step 4: Commit the documentation update.**

```powershell
git add docs/superpowers/specs/2026-07-24-vehicle-platform/E-fardriver.md
git commit -m "docs(fardriver): record decoder implementation boundary"
```

