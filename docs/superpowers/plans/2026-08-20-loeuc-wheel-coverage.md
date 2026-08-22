# loeuc-core wheel coverage implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Execute one task at a time with a fresh implementer, focused RED/GREEN tests, read-only review, and a fix loop. Do not commit or push without explicit user authorization.

**Goal:** port confirmed read-only telemetry behavior from `loeuc-core` into Volty for Begode, KingSong, InMotion, Leaperkim/Veteran, Nosfet, and Ninebot. Solowheel Xtreme and SKAT/CAN-Control remain excluded.

**Source/spec:** `docs/superpowers/specs/2026-08-20-loeuc-wheel-coverage-design.md` and `docs/superpowers/specs/2026-08-20-loeuc-wheel-audit-matrix.md`.

## Non-negotiable constraints

- Never write Begode FFE1 or Veteran/Leaperkim FFE1.
- Keep all wheel protocols notify-only in this plan: `handshakeCommands()` and `pollCommands()` remain empty unless a separate hardware-approved task changes that contract.
- Preserve Volty's known-value flags. A missing field is not zero.
- Use raw fixtures from the checked-out loeuc tests where the matrix cites them; do not replace wire evidence with guessed layouts.
- Keep `minSdk 26`, common code platform-neutral, and Russian strings in both resource trees if UI changes are necessary.
- Do not add Solowheel Xtreme, SKAT/CAN-Control, charger, settings, light, shutdown, STR-mode, or field-weakening commands.
- Work in the existing dirty tree; preserve unrelated changes and do not reset or commit.

## Task 1 — audit matrix and provenance

**Deliverable:** `docs/superpowers/specs/2026-08-20-loeuc-wheel-audit-matrix.md`.

Record every reviewed family/field as `equivalent`, `port`, `deferred`, or `out of scope`, with exact loeuc source/test evidence, the Volty target field, and the unknown/write rationale. Add `THIRD_PARTY_NOTICES.md` only if implementation copies a non-trivial code block rather than independently reimplementing from fixtures.

**Acceptance:** no row without source/test evidence, target/decision, and safety rationale; no excluded protocol appears in production scope.

## Task 2 — KingSong safe telemetry reconciliation

**Write scope:**

- `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/KingSongProtocol.kt`
- `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/KingSongProtocolTest.kt`

Add failing tests first, then implement:

1. protocol-specific A9 distance byte order and trip delta;
2. B9 temperature latch over later A9 frames;
3. F5 byte-15 duty with `hasDuty`;
4. BMS summary cycle count into existing `BmsData.numCycles`;
5. page-06 eighth BMS temperature only after a valid page.

Keep `FFE1` read-only. Do not add the loeuc initial commands/heartbeat, speed-limit writes, F6 energy mapping, F3/F4 mixing, or fields that do not exist in `BmsData`.

**Focused verification:** `KingSongProtocolTest`, then all existing protocol tests.

## Task 3 — Begode earned-value fixes

**Write scope:**

- `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/BegodeProtocol.kt`
- matching Begode tests and, only if needed by a failing regression, `VoltageSocEstimator.kt` tests/implementation.

Add failing incomplete fixtures before production edits:

1. a live-only `0x00` frame must expose phase current only as `motorCurrentA`, with battery current and power unknown;
2. partial cell pages must not make pack SoC known or feed `VoltageSocEstimator` as a complete pack;
3. preserve ET Max `0x00/01/02/03/04/07`, fault filtering, duty, fragmentation, and FFE1 no-write behavior.

Do not narrow cell voltage bounds or port the estimated legacy PWM current without a real capture-backed fixture.

**Focused verification:** Begode protocol tests and estimator/pack aggregation tests.

## Task 4 — Leaperkim/Veteran decoder correctness

**Write scope:**

- `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/VeteranProtocol.kt`
- `VeteranProtocolTest.kt`

Add failing raw-fixture tests for:

1. stripping the 4-byte CRC before payload offsets, including a short Patton page-2 frame;
2. 24-bit bytes 28..30 hardware/firmware decoding and Nosfet `5010` profile recognition;
3. page 0/4 being main telemetry, not smart-BMS pages;
4. valid BMS page topology and complete-cell gating;
5. reported page-2 SoC taking precedence over the voltage estimate and staying known;
6. battery current/power being published only where the source proves them, while motor temperature remains unknown.

Leave STR/co-stream/settings/light/shutdown/field-weakening commands deferred and all command lists empty.

## Task 5 — Leaperkim battery integration and Nosfet identity

**Write scope:**

- `BmsType.kt`, `Controller.kt`, `LinkPlan.kt`, `ControllerProtocols.kt`, `BmsTypeDetector.kt`;
- vehicle builders/repository wiring required by compilation and focused tests;
- matching detector, link-plan, builder, and protocol-factory tests.

Add failing integration tests first. Add a shared `BmsType.LEAPERKIM` (not `BmsType.NOSFET`) for the two-pack side of a Veteran/Nosfet wheel, add `ControllerType.NOSFET` as identity, and route both `VETERAN` and `NOSFET` to the same passive `VeteranProtocol`. Ensure the configured wheel shape has one controller and the two Leaperkim pack sources at one address, without creating a second protocol or any write path.

Preserve address-specific identity precedence; FFE0/FFE1 alone must not classify a device as Nosfet.

## Task 6 — InMotion model-aware decoder

**Write scope:**

- `InMotionProtocol.kt` and `InMotionProtocolTest.kt`;
- existing controller/BMS model fields only where they can represent the source honestly.

Add failing raw-fixture tests from loeuc for model IDs 61, 71/72/73/111, 81/82, 91/92, and 131. Implement:

1. escaped AA AA framing/reassembly and XOR validation;
2. model-routed offsets for V11/V12/V13/V14/P6;
3. explicit speed/duty/power/current fields with known flags;
4. zero/absent sensor handling without `-176°C` or fabricated power;
5. P6 BMS realtime only if it fits existing pack slots without conflating direct diagnostic replies.

Keep diagnostics, TPMS, angles, extra sensor models, settings, and all BLE read/write commands deferred when no Volty contract exists. Do not add state-changing commands.

## Task 7 — Ninebot validation and unknown semantics

**Write scope:**

- `NinebotProtocol.kt`, `NinebotLegacyProtocol.kt` only when a focused failing test proves the change;
- matching protocol tests.

Add failing tests for CRC-valid wrong-command/wrong-destination frames, zero live pages, zero BMS cells, cells-only pages, signed speed magnitude, and the public BMS1/BMS2 index translation. Keep Volty's stricter command/destination validation, legacy `55 AA` support, empty command lists, and unknown current/power. Do not port loeuc settings/read/write APIs.

## Task 8 — cross-family integration and review

Add exhaustive tests for every changed identity and link shape:

- detector output;
- `ProtocolKind`/controller factory;
- Leaperkim battery/controller address grouping;
- pack/controller counts;
- empty command lists;
- unknown-value behavior;
- explicit exclusion of Solowheel and SKAT.

Run a fresh read-only review after implementation. Resolve findings with the owning worker and rerun the focused tests.

## Task 9 — final verification

Run with fresh results and no build cache:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --no-build-cache --rerun-tasks --no-configuration-cache --console=plain
.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration --no-build-cache --rerun-tasks --no-configuration-cache --console=plain
.\gradlew.bat :composeApp:assembleRelease --no-build-cache --rerun-tasks --no-configuration-cache --console=plain
git diff --check
```

Before claiming success, assert the fresh XML test count exactly and report any device-only limitations separately from fixture-backed verification.
