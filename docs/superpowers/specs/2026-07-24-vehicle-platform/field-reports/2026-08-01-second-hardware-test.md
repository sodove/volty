# Field report — second hardware test, 2026-08-01

**Build:** signed release off `feat/vehicle-composer` at `997bc50` (all eleven Part I tasks).
**Vehicles:** the rider's electric scooter (nyxdash VESC Express head unit + uBox over CAN
forwarding + ANT pack behind the head unit's bridge) and — for the first time — their
**bicycle**: a plain VESC on a stock Nordic-UART BLE module advertising as `VESC BLE UART`,
single motor, no CAN, no head unit, plus an ANT smart BMS on its own separate BLE link.

**This is the first time a plain (non-gateway) VESC link has ever met hardware.** Every VESC
test before today went through the head unit, which is `isGatewayLink` and therefore runs
`VescGatewayProtocol`. The bicycle runs `VescProtocol`, a code path with no field history at
all.

---

## §1 What the rider reported, verbatim

> не хватает нормального конструктора, при попытке добавления велосипеда пришлось лезть в
> nrf connect копировать mac бмски. также ощущение что на самокате через приборку не работает
> скорость (бмс, кстати, заработала). также по ощущениям вел не особо хочет работать - бмс
> видно, а вот контр под вопросом. nrf connect показывал постоянные реконнекты к vesc ble uart

and, on the speed:

> скорость - на привязанном контре по can скорость есть, притом калиброванная по gps,
> vesctool показывает её идеально, да и приборка тоже гетает параметры мотора с контров.
> аппка показывает не прочерк, а 0. хотя приборка с удовольствием рапортует 1-2 когда я качу
> самокат по дому. может конечно у нас скорость опроса низкая, но звучит как полная шиза

on the observer:

> а по поводу nrf - он был в фоне и отключен, там сам сервис просто видит кто и когда
> пытается подключаться

and on the composer:

> а, кнопку >(найти рядом) я не увидел. заныкано жестко. надо нормальный многоуровневый
> конструктор, а не этот хлам

---

## §2 What this test SETTLES

### S1 — Part I Task 5 worked. CONFIRMED by the rider.

*"бмс, кстати, заработала"*. The head unit's ANT bridge had never been sent
`COMM_BMS_GET_VALUES` at all, because the gateway branch dropped `deriveBattery` and allocated
no pack slot. Task 5 wired it; the pack now reports. This is the half of the rider's
2026-07-30 *"это ничем не помогло"* that was diagnosed purely from firmware sources with no
way to test it.

### S2 — the `COMM_GET_VALUES_SETUP` decoder is correct. Verified field-by-field against the firmware.

Checked `VescValues.decodeSetupValues` against `bldc/comm/commands.c`'s writer (`case
COMM_GET_VALUES_SETUP`, the `mask = 0xFFFFFFFF` path), in order:

| # | firmware | scale | ours |
|---|---|---|---|
| 1 | `temp_fet_filtered` | f16 1e1 | `d16(10f)` ✓ |
| 2 | `temp_motor_filtered` | f16 1e1 | `d16(10f)` ✓ |
| 3 | `val.current_tot` | f32 1e2 | `d32(100f)` ✓ |
| 4 | `val.current_in_tot` | f32 1e2 | `d32(100f)` ✓ |
| 5 | `get_duty_cycle_now` | f16 1e3 | `d16(1000f)` ✓ |
| 6 | `get_rpm` | f32 1e0 | `d32(1f)` ✓ |
| 7 | `get_speed` | f32 1e3 | `d32(1000f)` ✓ |
| 8 | `get_input_voltage_filtered` | f16 1e1 | `d16(10f)` ✓ |
| 9 | `battery_level` | f16 1e3 | `d16(1000f)` ✓ |
| 10-13 | `ah_tot`, `ah_charge_tot`, `wh_tot`, `wh_charge_tot` | f32 1e4 | `d32(1e4f)` ×4 ✓ |
| 14-15 | `get_distance`, `get_distance_abs` | f32 1e3 | `d32(1000f)` ×2 ✓ |
| 16 | `get_pid_pos_now` | f32 1e6 | `d32(1e6f)`, discarded ✓ |
| 17 | `get_fault` | u8 | `i8()` ✓ |

Order, widths and scales all agree. The firmware writes five further fields after `fault`
(controller id, `num_vescs`, `wh_batt_left`, odometer, uptime) which we do not read; the
length guard is `>=`, so a longer frame is accepted. **A field-misalignment explanation for
the zero speed is refuted.**

### S3 — the bicycle's reconnect loop is ours, not the radio's, and not the observer's.

The rider force-checked the observer question: nRF Connect was **backgrounded and
disconnected**, its service log merely records who attempts to connect. So the repeated
connection attempts it logged were Volty's own.

`ConnectionSession`'s watchdog declares a link stale when `lastSampleAtMs == 0` and more than
`noSampleEverMs` (10 s) has passed since connect, and `lastSampleAtMs` advances **only on a
successful decode** — not on traffic, not on a healthy GATT link. It then tears the link down
and redials immediately, then every 3 s. **A perfectly healthy connection that never decodes a
frame is churned about every 12 s, forever.**

---

## §3 What this test OPENS

### O1 — the composer is unreachable when it is needed, and unusable when it is found.

Three separate defects compound:

1. **`canComposeSources = isEditing`.** The BLE scan sheet, both add buttons, the CAN section
   and the whole source band are gated on editing an already-created vehicle. A rider building
   a vehicle from scratch lands on a create form with a read-only address and no scan at all;
   three of the five entry points lead there.
2. **The scan button is not findable.** The rider's own words — *"кнопку я не увидел, заныкано
   жестко"*. It is one unlabelled button among three, with no explanation, while the CAN
   section beside it gets a full explanatory sentence.
3. **The device picker never shows an address.** `DeviceRow` renders the name, or `BMS` plus
   the last four characters of the address. The composer's own scan sheet *does* show the full
   address, with a comment saying it is what the rider would otherwise have to type. So on the
   screen where an unnamed BMS must be identified, the one disambiguating fact is truncated —
   **which is a standing reason to open nRF Connect even when no typing is involved.**

Part G's spec describes the rider's exact vehicle as flow 2 — *"one controller + one BMS
(typical custom scooter/bike)"* — and `§7` says the picker *"routes a discovered device into
the composer"*. That routing was never built: the picker creates a new single-source vehicle,
and the composer is what you land on afterwards.

Compounding it, `BmsTypeDetector` mislabels this rider's own hardware: an ANT that does not
advertise an `ANT…` name falls through `0xFFE0` to the `JK_BMS` fallback, and
`detectController` refuses to look at anything `detect` already matched. Diagnosed and planned
as Part K; not built.

### O2 — the scooter shows a confident `0` for speed, and only one thing can produce it.

`reportedSpeedSource(speedMs, rpm)` is `if (speedMs != 0f || rpm == 0f) REPORTED else NONE`.
Since the decode is correct (S2), a **displayed** `0` — rather than the `—` that Part I
installed everywhere — requires `speedSource == REPORTED` with a zero value, which requires
**both `speedMs` and `rpm` to have arrived as zero**. Not "we do not know": "the controller
said it is stationary".

The rider's evidence makes the obvious explanations unavailable: the uBox's speed is
GPS-calibrated, VESC Tool displays it perfectly, and the head unit's own display reports
1-2 km/h while the scooter is pushed by hand. So the number exists on the bus.

**Discriminating observation, one push down a corridor:**

- `0` at a standstill, `—` while rolling → the uBox has no wheel geometry in its mcconf, and
  the app is behaving correctly (Part J is then the answer);
- `0` both at a standstill **and** while rolling → zeros are reaching us where VESC Tool sees
  numbers, and the question is whether the SETUP request reaches the uBox at all.

Not yet settled. **Do not design a fix until this is observed.**

### O3 — the plain VESC path asks exactly one question and cannot ask another.

`VescProtocol.pollCommands()` sends `COMM_GET_VALUES_SETUP` (47) and nothing else;
`useSetupFrame` is a constructor parameter **no caller ever sets**, and `decodeValues`
(opcode 4) is unreachable on a plain link. There is no probe, no downgrade, no back-off and
no logging: a VESC that does not answer 47 is asked five times a second until the watchdog
kills the link.

**This is consistent with the rider's report that VESC Tool serves the same module reliably**:
VESC Tool's realtime view polls `COMM_GET_VALUES` (4); opcode 47 is a separate tab.

The contrast with the tested path is the finding. `VescGatewayProtocol` asks **both** opcodes
per controller and suppresses whichever pair stays silent — the machinery that would have
saved this vehicle exists only on the branch this vehicle does not use.

Runner-up cause, to be ruled out on the module rather than in code: Kable throws
`NoSuchElementException` *before any GATT traffic* if the characteristic does not advertise
`WRITE_NO_RESPONSE`, and the plain path's poll loop swallows every write exception **with no
log line at all** (the gateway branch beside it logs). A rejected write is invisible in a
release build.

### O4 — a flapping controller is invisible, and the app names it as connected.

Vehicle state is a fold: any link ONLINE → `Connected`. The healthy ANT link pins it there, so
the VESC link's RECONNECTING state and its `"No samples"` reason never surface. The pill then
labels the vehicle by the **controller** type by preference — so the screen says *"Connected
(VESC)"* while naming the one device that is not working.

`MotionResult.partial` is computed, carried all the way into ride state as `motionPartial`,
and **read by no composable**. Offline chips exist for packs only; `ControllerState.isOnline`
has no UI anywhere outside the composer's duplicate detection.

### O5 — nothing in the app can explain an unknown value.

`SpeedSource.NONE` is a flat enum with no reason attached. `derivedSpeedKmh` knows exactly why
it returned null — `wheelDiameterMm <= 0` — and discards that one line later. So the
presentation layer cannot say more than "unknown" even if it wanted to.

The one cause-stating sentence in the app, *"Этот контроллер не сообщает скорость"*, lives on
the Alerts screen and names the wrong cause for this vehicle: the controller may well be
willing to report a speed; it is Volty that cannot derive one.

The wheel-diameter field compounds it: an unset diameter renders as the literal string `0`,
indistinguishable from a deliberate value, with no placeholder, no caption and **no
`ComposerIssue`** — `validate()` never reads `motor` at all.

---

## §4 Latent defects this test did NOT cause, found while tracing it

- **The SETUP overlay would refuse the obvious fix.** `publishController` copies `speedKmh`
  and `speedSource` from the overlay unconditionally. Inert today (both roads report
  `NONE`/`0`), but the moment a rider types a wheel diameter and `GET_VALUES` starts producing
  a real `DERIVED` speed, the uBox's honest `NONE`/`0` overwrites it every cycle. **The rider
  would type the correct number and see nothing change.**
- **A create form can save a vehicle that can never connect** — Settings → "+ Add" with
  nothing connected produces `bmsAddress = ""`, saves clean, and appears in the list.
- **A hand-typed address is never validated** — no trim, no case fold, no format check;
  `BlankAddress` is explicitly non-blocking, and the comparison against a scan hit is exact.
  A lower-case or space-padded MAC saves silently and fails later as a generic *"Device not
  found"*, with nothing pointing at the address.
- **`onAppResumed` forces an extra reconnect** of a never-decoding link on every resume,
  because `lastSampleMs == 0` is treated as "long stale" unconditionally.
- **A `Peripheral` is leaked per connection attempt** — `close()` is never called anywhere in
  the repo. Kable closes the underlying GATT, so no handle exhaustion, but each abandoned
  peripheral keeps a scope and a state collector alive, at ~5/minute in a reconnect loop.
- **The plain VESC path's poll races the CCCD enable.** `VescProtocol.handshakeCommands()` is
  empty, so its writes start concurrently with subscription setup rather than after it — the
  only protocol with this gap, since ANT and the gateway both have non-empty handshakes.
  Self-healing at 5 Hz, but it means the first replies are discarded and nothing counts them.

---

## §5 What each open item needs before it can be designed

| Item | Needs |
|---|---|
| O2 (speed `0`) | One push down a corridor: standstill vs rolling. Nothing else. |
| O3 (plain path) | nRF Connect on the module: RX characteristic properties, then write our framed 47 and a framed 4, and see which answers. |
| O1 (composer) | A decision from the rider on what "multi-level" should mean — a link/source tree, or a scenario-led wizard. |
| O4, O5 | Nothing external. Both are ours to design. |
