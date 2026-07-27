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

---

## 8. §2, §3 and §6 answered from WheelLog's source (2026-07-27)

The product owner asked why we were reasoning from a stationary capture instead
of reading WheelLog, which this protocol already cites. Fair question — and the
answer overturned the part's scope.

**The comment that caused it.** `BegodeProtocol` says of frame `0x07`:
*"undocumented; WheelLog does not decode it either. Ignored deliberately."*
**That is false.** WheelLog decodes `0x07`, and it carries the three numbers this
part most needed. Everything below is from
`app/src/main/java/com/cooper/wheellog/utils/GotwayAdapter.java` and
`WheelData.java` (GPL-3.0). **Take the facts, not the code** — the existing
"layout only" note is the boundary this project keeps.

### 8.1 Frame `0x07` — battery current, motor temperature, true PWM

All signed big-endian 16-bit:

| Bytes | Field | WheelLog handling | ET Max capture |
|---|---|---|---|
| 2..3 | **battery current** | `setCurrent(-1 × value)`, hundredths of an amp | 65, 54, 20, 37, 89, 82 → 0.2–0.9 A idle, live |
| 6..7 | **motor temperature** | `setTemperature2(value × 100)` | `0x0014` = 20 °C |
| 8..9 | **true hardware PWM** | `if (abs(v) > 0) truePWM = true; setOutput(v × 100)`, then `updatePwm()` = `output / 10000` as a 0..1 fraction | `0x0002` = **2 %**, a balancing wheel |

**So duty percent is the raw value of bytes 8..9, reported by the hardware.**
§3's two-case framing collapses: the wheel reports PWM, so **no derivation is
implemented**. WheelLog's `calculatePwm()` fallback needs rider-configured
rotation-speed / rotation-voltage / power-factor constants and only runs when
hardware PWM is absent. `truePWM` latches on the first non-zero value; "never
seen a non-zero" means *not yet known*, not zero duty.

**§7.2's `reportsDuty[BEGODE]` therefore stays `true`, on evidence rather than
on prose.** The wheel's ШИМ alarm — Part F's headline feature — is real.

**§2's motor-temperature claim is wrong for this wheel.** It says wheels expose
one board temperature and `hasMotorTemp = false`. The ET Max reports both: 20 °C
motor against 27.5 °C board. Set the flag from what the wheel actually sends.

**§6.3 (current split) is answered:** `0x00` bytes 10..11 are **phase** current,
`0x07` bytes 2..3 are **battery** current. Both can be populated honestly.

### 8.2 Corrections to the live `0x00` frame

- **Speed** is bytes 4..5 signed BE, and WheelLog computes `round(raw × 3.6)`
  into a field stored in **tenths** of km/h — so **km/h = raw × 0.36**, the raw
  unit being 0.1 m/s. An earlier reading of this plan guessed hundredths of km/h;
  that is wrong by 3.6×, and the implementer independently flagged the same
  discrepancy from memory before the source was fetched.
- **Trip distance is bytes 8..9**, unsigned BE, metres. Bytes 6..7 are **not read
  by WheelLog at all** — the `0x003d` = 61 sitting there in the capture is not a
  distance and must not be decoded as one.
- Total distance from frame `0x04` is bytes 2..5 BE metres, as already commented.

### 8.3 Voltage scaling — Volty can do better than WheelLog

WheelLog does not derive the scale either: it uses a **rider-chosen setting**
with multipliers 1.0 / 1.25 / 1.5 / 1.738 / 2.0 / 2.5 / 2.25 over the 67.2 V
base. Volty already knows the vehicle's cell count, and 40S × 4.2 V = 168 V is
exactly the ×2.5 this wheel needs — so the scale can be **derived from
configuration the rider has already given us**, with no extra setting. §6.2's
"profile entry" question is answered: cell count is the profile.

### 8.4 Newly in scope — frame `0x04` carries alerts and settings

Beyond the odometer, `0x04` holds a settings word at bytes 6..7 (pedal mode,
speed alarms, roll angle, an **in-miles** flag), power-off time at 8..9,
**tiltback speed** at 10..11, LED mode at 13, an **alert byte at 14** and light
mode at 15. The alert bits are: speed-alarm ×2, low voltage, over voltage, over
temperature, hall-sensor error, transport mode.

That is a genuine `ControllerData.faults` source for a wheel, and tiltback speed
is a rider-meaningful number. Both are additions to this part's scope.

### 8.5 What still needs the wheel

Speed reads zero in every frame of the only capture, so its **offset and
signedness are pinned by synthetic frames** and its scale rests on WheelLog's
source rather than on measurement. One `:dumper` capture of a moving wheel
settles it, and would also confirm the odometer's unit (8 565 341 → 8 565 km is
plausible for an ET Max; if the wheel's display reads ~856 km, the unit is wrong).
