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
- **mileage / trip** → `odometerKm` (total), `tripKm` (**session** — see §9.3:
  the wheel's own since-power-on counter is decoded but must NOT be published
  here; the trip is the odometer minus a per-connection baseline, as VESC does)
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
| 2..3 | **battery current** | negated, then stored in the field WheelLog keeps in hundredths of an amp | 65, 54, 20, 37, 89, 82 → 0.2–0.9 A idle, live |
| 6..7 | **motor temperature** | scaled by 100 into its hundredths-of-a-degree temperature field | `0x0014` = 20 °C |
| 8..9 | **true hardware PWM** | any non-zero value latches its `truePWM` flag; the value is scaled by 100 into an output field which its PWM accessor then divides by 10 000 to a 0..1 fraction | `0x0002` = **2 %**, a balancing wheel |

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

- **Speed** is bytes 4..5 signed BE. **⚠️ The scale stated here was wrong and is
  superseded by §8.6: it is `raw × 0.036` km/h, raw in cm/s.** The paragraph is
  kept because the mistake is instructive, not because it is right. What it said:
  *"WheelLog computes `round(raw × 3.6)` into a field stored in tenths of km/h, so
  km/h = raw × 0.36, the raw unit being 0.1 m/s."* The field is stored in
  **hundredths**, not tenths — see §8.6 for the four independent statements that
  settle it.
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

### 8.6 Correction to §8.2 — the speed scale is ×0.036, not ×0.36

Checked against the source while implementing Task 1, because §8.2's own
instruction was to verify rather than trust. §8.2 says WheelLog stores speed in
**tenths** of km/h, giving `km/h = raw × 0.36`. It stores **hundredths**:

- `WheelData` declares a riding-speed constant of 200 units and annotates it as
  2 km/h, and a commented-out debug block sets the same field to 5000 for 50 km/h
  alongside a current field set to 10000 for 100 A — one convention across three
  fields. Its double accessor divides by 100.
- `GotwayAdapter` reads bytes 4..5 as a signed big-endian short, multiplies by
  3.6, rounds, and stores the result in that field.
- The frame-layout note at the foot of `GotwayAdapter.java` says the same thing
  in words: bytes 4-5 are a fixed-point big-endian speed of 3.6 × value / 100
  km/h.

So **`km/h = raw × 3.6 / 100 = raw × 0.036`**, and the raw unit is cm/s, not
0.1 m/s. `BegodeProtocol.SPEED_KMH_PER_UNIT` ships `0.036f`.

What misleads here is `WheelData.getSpeed()`, which returns
`round(mSpeed / 10.0)` and disagrees with the rest of its own file; it is used
only in an `== 0` comparison, so nothing in WheelLog depends on it. Every reading
of this field so far has been wrong in a different direction — 0.01 (3.6× low),
then 0.36 (10× high) — which is the argument for the moving capture in §8.5
rather than a fourth reading of the same source.

---

## 9. Whole-branch review, and the three seams no task could see (2026-07-27)

Six tasks, each reviewed and approved on its own. A review of the whole branch
found three defects that live **between** tasks — each one invisible from inside
any single task because it needed two of them side by side. Recorded here
because they are decisions about shared contracts, not about Begode.

### 9.1 The picker built a pack-less wheel, so the cell count could never arrive

Task 4 landed the picker branch and the cell-count lookup together, on the
argument that shipping the pick without the count is *"a correctness bug, not an
untidiness"* — the Ride dashboard renders an unknown voltage as a confident
**"0.0 kW"** and "0.0 Wh/km", never as a blank. It landed for the shape §4
describes and missed the shape the picker actually creates: `controllerVehicle`
produces **zero packs**, so the link's only pack slots are the derived ones
`planLinkPacks` synthesises (never persisted, no cell count), and
`createProtocol`'s lookup runs against `vehicle.packs`, which is empty.

There was **no recovery path**: `Vehicle.withCellCount` is `packs.mapIndexed`, a
silent no-op on an empty list; the pack auto-fill only appends behind an address
the profile already names a pack on; and the edit screen deliberately keeps a
controller-only vehicle pack-less and never edits a cell count.

**Fixed by changing the SHAPE the pick creates, not the lookup**
(`pickedControllerVehicle` → `wheelVehicle`): a BEGODE Controller pick now builds
one stored Begode pack beside the controller at the wheel's one address — §4's
archetype exactly, and byte-for-byte what the BATTERY pick has always created for
a Begode. The count then arrives through the auto-fill that already exists, and
the second connect scales the rail voltage with it.

**This also removes the Begode/VESC divergence the review flagged.** A VESC
*derives* a battery from its own telemetry, which is why `controllerVehicle` sets
`providesDerivedBattery = true` and why the VESC arm of `controllerMotionProtocol`
honours `deriveBattery`. **A wheel derives nothing** — it decodes two real
branches off the same frames — so its controller carries
`providesDerivedBattery = false` and the BEGODE arm ignoring `deriveBattery` is
correct rather than an omission. The arm now says so, and also states the
limitation that a link owning two Begode controllers is a gateway the BEGODE arm
cannot serve (unreachable today: no screen can create one).

**Still open, and unchanged by this fix:** a wheel with **no smart BMS** never
reports cells, so nothing auto-fills its count and its voltage stays absent — on
the Battery tab as much as on Ride. That is the pre-existing behaviour of every
Begode battery profile, not a regression of this part; closing it needs an
editable cell count, which belongs to whichever part opens the edit screen to
pack configuration.

### 9.2 Duty is a magnitude, and the decoder is where that is established

`0x07` bytes 8..9 are **signed** and unbounded, and the only capture reads a
constant `0x0002` — so nothing in it pins the sign in either direction. The
decode passed the value through untouched while `dutyPercent()`'s own KDoc
claimed "(0..100)" and `rebuildMotion` *reasoned from* "every consumer treats
duty as a non-negative magnitude compared against UPPER thresholds". A wheel
reporting negative hardware PWM under regen would have graded `DutyBands` level
0, filled the dial backwards, and left **the ШИМ alarm silent exactly when a
EUC's duty peaks**.

**`abs`, then clamp to 100, in `parseMotionFrame`** — not folded into
`rebuildMotion`. That is where `VescValues` establishes the same contract
(`abs(duty) * 100`), and this field's meaning is cross-protocol: one convention,
stated once per decoder, is what stops the two disagreeing. The `truePWM` latch
reads the RAW value first, so a wheel that only ever reports negative PWM still
proves it reports duty. The clamp fails **loud** on purpose — garbage becomes
100 % and raises the alarm, because duty only escalates upwards and over-firing
is the safe error.

### 9.3 `tripKm` means the SESSION, on every protocol

VESC publishes `odometerKm - baseline`, distance since this connection started
(B1's decision). Begode published the wheel's own since-power-on counter, u16
metres. `RideMetrics.sessionWhPerKm` — named *session* — was therefore dividing a
session's Wh by a non-session distance, and a rider connecting mid-ride at km 30
saw a one-second-old session reading **30.0 km**, then a silent reset to 0.0 at
km 65.5 when the 16-bit field wrapped.

Begode now derives `tripKm` from the lifetime odometer against a baseline taken
at the connection's first genuine `0x04` frame — the same shape VESC uses, which
also disposes of the wrap. The wheel's own counter is still decoded, renamed
`powerOnDistanceMeters()` so it cannot be mistaken for the trip again. **The
contract is now written on the shared field itself** (`ControllerData.tripKm`),
which is where it was missing: it had only ever been documented on VESC's private
baseline field.

### 9.4 The `0x04` and `0x07` frames now take the same boot gate the `0x00` does

`parseLiveFrame` has been gated since Task 2 (`liveVoltageRaw > 0`) because the
format has **no checksum** — only a `5A5A5A5A` tail. `parseOdometerFrame` and
`parseMotionFrame` rebuilt unconditionally, so a zero-padded boot `0x04`
republished 8 565 km of mileage as **0.0 km** and reset the trip baseline, and a
zero-padded `0x07` fed the two ONE-WAY latches it owns.

Both now skip a frame whose **whole 16-byte payload is zero** — the discriminator
`parseBmsTelemetry` already uses, and the only one this frame can support, since
every individual field has a legitimate zero (an odometer of 0 is a new wheel, an
alert byte of 0 is a healthy one, 0 °C is a real winter reading). A false
positive costs nothing: every field is left at exactly the value it would have
published anyway.

**What it does not catch, stated rather than papered over: corruption.** Garbled
bytes are non-zero by nature, so no gate here can tell a corrupted `0x07` from a
real one, and a corrupted frame can still close `truePWM` /
`sawMotorTempEvidence` for the connection. Closing that needs a checksum the
wheel does not send.
