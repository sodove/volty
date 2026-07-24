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
