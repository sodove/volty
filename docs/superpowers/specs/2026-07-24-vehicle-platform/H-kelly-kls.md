# Part H — Kelly (KLS) controller via the ETS protocol

| Field | Value |
|---|---|
| Part | H |
| Depends on | A, B |
| Blocks | — |
| Hardware | a Kelly KLS controller. Low-risk: the protocol already works in the user's `kelly-connect` app. |
| Source of truth | `C:\Users\sodovaya\Desktop\kelly\kelly-connect\protocol` (KMP `protocol/` module, ETS) |

> Read `00-overview.md`, `A-foundation.md`, `B-vesc-dashboard.md` first. Kelly is
> another `MotionSource` controller, like VESC — but its telemetry set is smaller
> (no duty/ШИМ, no battery current, no odometer). The protocol is already
> implemented and tested in the user's own repo; this part **vendors** it.

## 1. Scope
**In:** vendor the ETS `protocol/` module into Volty; a `KellyProtocol`
(`BmsProtocol + MotionSource`) that polls the 3 monitor commands and decodes
them into `ControllerData`; KLS detection in the scanner; an optional derived
battery from pack voltage.
**Out:** KLS **calibration/flash writes** (the ETS module supports them, but
Volty is read-only per `00-overview §4`); duty/ШИМ (KLS does not report it);
multi-controller CAN (KLS is a standalone BLE controller — uses the independent-
links path, `C §7`, if two are present).

## 2. Vendor the ETS module
The user's `kelly-connect/protocol` is pure `commonMain` KMP with no platform
dependencies — copy it under `ru.sodovaya.volty.data.controller.kelly` (or add it
as a Gradle module). Files needed: `EtsProtocol`, `EtsPacket`/`EtsPacketBuilder`,
`EtsCommand`, `EtsChecksum`, `ByteUtils`, `ParameterCodec`, `MonitorDefinitions`,
`ErrorCodes`, `VoltageRanges`, `ControllerModel`. Bring their tests
(`EtsPacketTest`, `EtsChecksumTest`, `ParameterCodecTest`, `ErrorCodesTest`) too.
Do **not** vendor kelly's BMS/transport/presentation — Volty has its own.

## 3. ETS transport & polling
KLS speaks request/response over a transparent BLE UART bridge (get the service /
write / notify UUIDs from kelly's `AndroidBleTransport`). The monitor read is 3
commands, each returning 16 bytes → a concatenated 48-byte buffer
(`EtsProtocol.readMonitor`).

`KellyProtocol : BmsProtocol(), MotionSource`:
- `handshakeCommands()` = optional `CODE_VERSION` (0x11) to confirm the model.
- `pollCommands()` = the 3 monitor packets `[0x3A, 0x3B, 0x3C]`
  (`MonitorDefinitions.MONITOR_COMMANDS`), spaced by `writeSpacingMs`.
- `onNotification()` accumulates responses; match each to its command via
  `EtsPacketBuilder.parseRxResponse(raw, expectedCmd)`, fill the 48-byte buffer,
  decode when all three are in.
- `controllerCount = 1`; `latestMotion(0)` = the decoded `ControllerData`.

> **Integration nuance:** ETS is strict request→response with drain+retry
> (`EtsProtocol.sendWithRetry`), unlike the fire-and-forget poll of JBD/Daly.
> Volty's session writes poll commands and collects notifications loosely; KLS
> needs each monitor command's response matched by its command byte before the
> next is sent (or a short inter-command await). Adapt in `KellyProtocol` /
> the session poll loop; this is the one real integration point.

## 4. Monitor → `ControllerData` (from `MonitorDefinitions.PARAMETERS`)
The 48-byte buffer decodes to 19 named params. Map the numeric ones (use
`ParameterCodec`'s numeric read, not the display string):

| Monitor param | → `ControllerData` | Notes |
|---|---|---|
| B+ Volt | `inputVoltageV` | scale per `ParameterCodec` / `VoltageRanges` |
| Motor Temp (°C) | `motorTempC`, `hasMotorTemp = true` | |
| Controller Temp (°C) | `escTempC` | |
| Motor Speed (RPM) | `eRpm` + speed derivation | **mechanical** RPM assumed → speed = RPM × wheelCircumference × gearRatio; `polePairs` ignored; `speedSource = DERIVED` (NONE if wheel unknown) |
| Phase Current (RMS) | `motorCurrentA` | phase current, not battery current |
| Error Status (bitmask) | `faults` | via `ErrorCodes` |
| — (no duty) | `dutyPercent = 0` | **KLS reports no duty** — Ride hides/greys the duty gauge; no ШИМ alert (F) |
| — (no battery current) | `batteryCurrentA = 0`, `powerW` | power unavailable/estimated; document as absent |
| — (no odometer) | `odometerKm/tripKm = 0` | KLS monitor has none |

## 5. Derived battery (optional)
KLS reports only pack **voltage** (B+ Volt) — no cells, no current integration.
When `providesDerivedBattery` is set and no smart BMS covers it, synthesise a
`BmsData` with `voltage = inputVoltageV`, `soc` from `VoltageSocEstimator` over
the vehicle chemistry, `socKnown = true`, no cells. Same latent-slot mechanism as
the VESC derived battery (`B §5`).

## 6. Detection
Add KLS to `BmsTypeDetector` / the scanner: match the KLS BLE service UUID (from
kelly's transport) → `ControllerType.KELLY`. Confirm by a successful
`CODE_VERSION` / monitor read. Shown as a controller in the picker.

## 7. Alerts (interaction with Part F)
KLS supports: **motor-temp**, **controller-temp**, **speed**, **phase-current**,
and **controller-fault** (Error Status) alerts. It does **not** support the
duty/ШИМ alarm (no duty telemetry) — Part F must gate the ШИМ alarm on
`speedKnown`/duty availability so a KLS vehicle never arms a duty alert that can
never fire.

## 8. Testing
- Reuse the vendored module tests (`EtsPacketTest`, `EtsChecksumTest`,
  `ParameterCodecTest`, `ErrorCodesTest`).
- `KellyProtocolTest` — decode a captured 48-byte monitor buffer (from a real KLS
  or synthesised per `MonitorDefinitions`) → assert every `ControllerData` field;
  Error Status bitmask → fault list; speed derivation from RPM + wheel config;
  duty stays 0.
- Detection test — KLS service UUID classified as a controller.
- Demo/aggregation — a KLS controller folds through `MotionAggregator` (Part A)
  with duty absent.

## 9. Open questions
1. **KLS BLE UUIDs** — pull the exact service/write/notify UUIDs from kelly's
   `AndroidBleTransport`. (Kelly also supports BT-Classic/USB; Volty is BLE-only.)
2. **Motor Speed unit** — mechanical vs electrical RPM. Assumed mechanical
   (KLS "Motor Speed"); confirm against a live capture and adjust the speed
   derivation (drop or apply `polePairs`).
3. **B+ Volt scaling** — confirm the volt scaling via `ParameterCodec` /
   `VoltageRanges` (the module already formats it for display).
4. **Power** — with only phase current (no battery current), true input power is
   unavailable. Show current/temps and omit or clearly estimate power. Confirm the
   UX with the user (his Kelly-app dashboard shows what KLS gives — reference it).

---

## 10. Two contracts inherited from Part F's availability gate (2026-07-26)

Found while building `MotionAlertAvailability` (F Task 3).

**10.1 — leave `dutyPercent` at `0f`, and rely on the static gate.** KLS reports
no duty (§7), and `MotionAlertAvailability` already encodes that permanently:
`DUTY` is `Unavailable` on a Kelly vehicle even when a live sample arrives. But
`MotionAggregator` folds duty across controllers as `maxOf { dutyPercent }`, so
on a mixed VESC + Kelly vehicle `DUTY` *is* available — one duty-reporting
controller is enough — and the aggregate takes the maximum of both. If the Kelly
decoder ever writes a non-zero approximation into `dutyPercent`, the ШИМ alarm
fires on a number that is not a duty measurement, on a vehicle where the alarm is
legitimately armed. Write nothing into that field.

**10.2 — no ESC sensor means the sentinel, not zero.** `ControllerData.hasEscTemp`
is computed as `escTempC > -50f` (VESC's "no sensor wired" sentinel, generalised).
A decoder leaving `escTempC` at its `0f` default when the reading is absent claims
the sensor exists, and `ESC_TEMP` arms against a constant zero — displayed as
armed, permanently silent. Write a sub-−50 value. Same for `hasMotorTemp`.

---

## A vehicle of this type can be locked out of its own Ride dashboard, silently

Found in Part D Task 5's review (2026-07-27).

`RootComponent.homeConfigFor` is the single rule behind every landing decision
and the Ride tab's visibility, and it is **type-agnostic** —
`vehicle?.hasControllers == true`. Every test of it used a VESC until Part D
added one for Begode.

The reviewer ran a mutant that returns the battery `Dashboard` for a
`FARDRIVER` or `KELLY` controller: **1160 tests, 0 failures.** So a vehicle of
this type can be routed away from the Ride dashboard — with no Ride tab left to
escape by — and nothing in the suite objects. A green build says everything is
fine while the rider cannot reach their own instruments.

**This part must add the test for its own controller type**, in the same shape
Part D used: a mutant that sends this type to the battery dashboard has to fail
something. One test, and it closes the quarter of the hole this part owns.

---

## 11. Kelly must set `hasDuty = false` (2026-07-27)

Part D added `ControllerData.hasDuty`, defaulting to **`true`** so no existing
decoder changed. Kelly reports no duty (§7), so its decoder must set it `false`
explicitly — the default is wrong for it.

Why it matters beyond tidiness, from Part D's final review: `MotionAlertAvailability`'s
static layer folds over **all** of `vehicle.controllers`, while the aggregate's
`hasDuty`/`dutyPercent` fold over the **online** ones only. On a Begode + Kelly
vehicle with only the Kelly online, the static layer sees the Begode and answers
DUTY `Available`, the aggregate's `hasDuty` is the Kelly's inherited `true`, and
`dutyPercent` is `0`. The rider gets a ШИМ alarm shown as armed against a constant
zero — the silent-dead-alarm shape Part F spent a task eliminating, reintroduced
by a default.

Setting the flag honestly closes it.
