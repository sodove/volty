# Part D — Begode wheel as a controller (surface motion)

| Field | Value |
|---|---|
| Part | D |
| Depends on | A, B |
| Blocks | — |
| Hardware | a Begode/Gotway EUC (already partially supported in Volty for battery) |
| Reference | Volty's existing `BegodeProtocol`; WheelLog `GotwayAdapter`/`VeteranAdapter` |

> Read `00-overview.md`, `A-foundation.md`, `B-vesc-dashboard.md` first. Volty
> already decodes a Begode wheel's **battery** (two branches, `packCount = 2`).
> The *same 20-byte frames* also carry motion — speed, PWM/duty, mileage,
> temperature. Part D makes `BegodeProtocol` additionally a `MotionSource`, so a
> wheel becomes controller + batteries over its one link.

## 1. Scope
**In:** extend `BegodeProtocol` to implement `MotionSource` (`controllerCount =
1`); decode speed/PWM/current/voltage/temperature/mileage from the frames it
already parses; feed `ControllerData`; the wheel's Ride dashboard. Keep the
existing battery decode untouched.
**Out:** other EUC brands (KingSong/Inmotion/Veteran decoders are future adapters
behind the same `MotionSource` interface); the sound alarm (F) — though the
**duty/ШИМ alarm is the headline feature for wheels** and D must produce a
trustworthy duty.

## 2. What the Begode frame carries (motion)
The existing `BegodeProtocol` already locates and validates the frame types (see
its battery decode + `BegodeDumpFixture`). Extend it to also read the motion
fields from the main data frame (WheelLog is the field-scaling reference; the
exact constants differ by model/profile, which Volty's Begode profile handling
already selects for the battery side):
- **voltage** (already decoded) → `inputVoltageV`
- **speed** — reported by the wheel → `speedKmh`, `speedSource = REPORTED`
  (wheels report ground speed; **no `MotorConfig` needed**)
- **current** → `batteryCurrentA`/`motorCurrentA` (Begode reports pack current;
  set `batteryCurrentA`, and `motorCurrentA` if a separate phase value exists),
  `powerW = voltage × current`
- **temperature** → `escTempC` (mainboard temp; wheels usually expose one board
  temp — `hasMotorTemp = false`)
- **mileage / trip** → `odometerKm` (total), `tripKm` (session, from the "b"
  frame or session delta)
- **duty / PWM** — see §3

## 3. Duty / PWM — the safety number
PWM headroom is *the* reason a wheel rider needs an audible alarm (Part F). Two
cases, pinned at implementation from WheelLog:
- If the wheel's firmware reports PWM/duty in a frame field, decode it directly →
  `dutyPercent`.
- Otherwise derive it the way WheelLog does for Gotway/Begode (a function of speed
  vs the voltage-dependent max speed / motor constant) and mark it derived.
Either way `dutyPercent` must be trustworthy — it drives the Part F alarm. Add a
unit test asserting the derived PWM matches WheelLog's for known samples.

## 4. Topology
A Begode vehicle is **one link** owning `controllers = [0]` + `packs = [0,1]` at
one address (`01-linking §3`, archetype 3). `planLinks` already produces this once
the vehicle config carries a Begode controller alongside its Begode packs
(`ProtocolKind.BEGODE` for both — `A §4.4`). A dumb wheel (no smart BMS) keeps its
synthesised batteries (existing behaviour) and gains motion.

## 5. Testing
- Extend `BegodeProtocolTest` / `BegodeDumpFixture`: from the recorded frames,
  assert `latestMotion(0)` fields (speed, current, voltage, temp, mileage) and the
  duty/PWM value/derivation.
- A wheel connection yields one controller + two packs through the funnel
  (`VehicleConnection` test): motion and battery interleave from the same link.
- Demo/aggregation unaffected (single controller).

## 6. Open questions
1. **PWM source** — frame field vs WheelLog-style derivation, per Begode model.
   Confirm against a real capture from the user's wheel(s) if available.
2. **Which models** — Volty's current Begode profiles cover the user's wheel(s)?
   The motion scaling constants may need a profile entry like the battery side.
3. **Current split** — does the frame separate battery vs phase current, or only
   one? Map accordingly (`batteryCurrentA` at minimum).

---

## 7. Two contracts inherited from Part F's availability gate (2026-07-26)

Found while building `MotionAlertAvailability` (F Task 3). Both are silent
failures — nothing throws, nothing logs, the rider simply gets an alarm that is
displayed as armed and never fires.

**7.1 — no ESC sensor means the sentinel, not zero.** `ControllerData.hasEscTemp`
is computed as `escTempC > -50f`, which is VESC's "no sensor wired" sentinel
generalised into a cross-protocol predicate. A decoder that leaves `escTempC` at
its `0f` default when the wheel has no ESC thermistor therefore claims the sensor
*exists*, and `ESC_TEMP` arms against a constant zero. **Write a value below
−50 °C when the reading is absent.** The same applies to `hasMotorTemp`, which is
an explicit flag — set it honestly.

**7.2 — duty must be real or absent, never a placeholder.** `MotionAggregator`
folds duty across controllers as `maxOf { dutyPercent }`. A decoder that writes
an approximation into `dutyPercent` makes the ШИМ alarm — the headline safety
feature for wheels — fire on a number that is not a duty measurement. If Begode's
frame does not carry a trustworthy PWM (see §6.1), leave `dutyPercent` at `0f`
and set `MotionAlertAvailability`'s static `reportsDuty` entry for `BEGODE` to
`false`. That entry is currently `true`, **inferred from this spec's §2/§3 text
rather than from hardware**, and is pinned by a test so this part has to decide
it deliberately.
