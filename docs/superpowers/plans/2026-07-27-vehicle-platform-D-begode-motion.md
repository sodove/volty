# Plan — Part D: Begode wheel as a controller

Spec: `docs/superpowers/specs/2026-07-24-vehicle-platform/D-begode-controller.md`
(§7 is binding — it carries two contracts inherited from Part F.)

Branch: `feat/begode-motion`, forked from `main` at Part F's merge (`c5912c9`).

---

## What already exists

`BegodeProtocol` (538 lines) already accumulates, frames and validates the
wheel's 24-byte stream, and decodes the **battery**: two branches, smart-BMS
frames `0x01`/`0x02`/`0x03`, plus a synthetic pack from the live `0x00` frame
for wheels with no smart BMS. It never writes to FFE1 — that is Begode's command
channel and a stray write could reconfigure a wheel under its rider.

From the live `0x00` frame it already keeps `liveVoltageRaw`, `phaseCurrentA`
and `boardTempC`. Its own comment says *"Speed/trip/PWM are ignored in this
task."* That is the gap Part D closes.

`BegodeDumpFixture` is a **real capture from the user's Begode ET Max**
(GotWay_75042, 40S, 148.4 V of cells), 228 notifications at the real MTU-23
chunk boundaries.

## What the wheel actually sends — read from WheelLog, not guessed

> **This section replaces an earlier version of the plan that was wrong on its
> central point.** That version reasoned only from the stationary capture, decided
> duty was unobtainable, and instructed Part D to ship `dutyPercent = 0f` with
> `reportsDuty[BEGODE] = false`. The product owner asked why we were not simply
> reading WheelLog — which this protocol already cites — and the answer overturned
> the part. Spec §8 carries the full correction; nothing from the old reasoning
> survives. If you find a claim below contradicted by spec §8.6, §8 wins.

The authority is WheelLog's `GotwayAdapter.java` + `WheelData.java` (GPL-3.0).
**Take the facts, not the code** — this repo's existing note says "layout only"
and that boundary holds.

**Frame `0x07`, which `BegodeProtocol` used to dismiss as "undocumented, and
WheelLog does not decode it either" — a claim that was simply false:**

| Bytes | Field | Scale | ET Max capture |
|---|---|---|---|
| 2..3 | battery current | negated, 0.01 A | 0.2–0.9 A idle, varies per frame |
| 6..7 | motor temperature | whole °C | 20 °C |
| 8..9 | **true hardware PWM** | whole % | **2 %** — a balancing wheel |

**Frame `0x00`:** voltage at 2..3 on the 67.2 V scale (×2.5 for this 40S wheel);
**speed at 4..5, `raw × 0.036` km/h** with the raw unit cm/s; **trip at 8..9**,
u16 metres; phase current at 10..11; board temperature at 12..13.

Three things that repeatedly caught people out, recorded so they do not catch the
next reader:
- **Bytes 6..7 are not read by WheelLog at all.** The `0x003d` = 61 sitting there
  is not a distance. An earlier draft of this plan called it a verified 61 m trip;
  it is not.
- **Bytes 14..15 are WheelLog's *fallback* PWM**, for firmware without hardware
  PWM. On this wheel they read a constant 169 → 16.9 % while `0x07` reads 2 %.
  **Do not implement it** — that is a fabricated safety number, exactly what spec
  §7.2 forbids.
- **The speed scale was read wrong three times** — `×0.01`, then `×0.36`, before
  `×0.036` was settled from `WheelData` storing hundredths and WheelLog's own
  frame comment. Only `×0.036` makes the raw unit physical (cm/s; 50 km/h → raw
  1389).

## What the capture still cannot tell us

The capture is 13 seconds of a **stationary** wheel, so **speed reads zero in
every frame**. Its offset and signedness are pinned by clearly-labelled synthetic
frames and its scale rests on WheelLog's source rather than on measurement. Trip
and odometer units are plausibility-only. Duty is exercised at 1–2 % but never
under load, so the high end of the ШИМ alarm's range is unmeasured.

**What unblocks all of it:** a `:dumper` capture of a real ride — accelerating hard
enough to push PWM up, ideally to a tiltback. Recorded in §6 as the hand-off.

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.** Every mutation
   killed by some assertion, **and** every assertion killable by some
   implementation; delete any that no implementation could falsify and say so.
   Part F shipped several that passed under every possible implementation.
2. **A zero-failure mutation run is not evidence unless the build compiled**, and
   stale or cached results score as passes. A comment-only control is served from
   the build cache; an identical control value reused in a session is served
   `UP-TO-DATE`. Use `--rerun-tasks`, assert the test task executed, never run two
   sweeps concurrently, and **sweep your own additions**.
3. **The battery path is not to change behaviour.** Part D adds motion to a
   protocol whose battery decode riders already depend on. Any change there must
   be argued, not incidental.
4. **Never write to FFE1.** Empty command lists are a requirement, not an
   oversight — it is the wheel's command channel.
5. **Absent sensors take the sentinel, not zero** (§7.1). `hasEscTemp` is computed
   as `escTempC > -50f`, so a `0f` default claims a sensor that does not exist and
   arms `ESC_TEMP` against a constant. `hasMotorTemp` is an explicit flag — set it
   honestly (a wheel has one board temp, so it is `false`).
6. **`runTest` hazard:** a test starting an unbounded delayed loop makes virtual
   time advance forever and **wedges the build instead of failing**.
7. Structural claims about frame layout must cite the fixture bytes that support
   them, or say plainly that they are unverified.

---

## Tasks

### Task 1 — decode the motion fields of the `0x00` frame

Extend `parseLiveFrame` to also read speed, and add a `0x04` handler for the
total odometer (u32 BE at 2..5, metres — currently dropped with a comment).

- Speed at bytes 4..5, and the **scale must be stated with its evidence**. Gotway
  reports speed in 0.01 km/h units on most firmware; the capture cannot confirm
  it, so the constant carries a comment saying it is unverified and what would
  verify it.
- Distance: work out the byte order from the fixture (`00 3d 00 00` should read
  as a small metre count, not millions) and pin it with a test that would fail
  under the naive big-endian reading.
- Keep every existing battery assertion green.

### Task 2 — `MotionSource` on `BegodeProtocol`

`controllerCount = 1`; `latestMotion(0)` returns a `ControllerData` carrying
speed (`speedSource = REPORTED` — a wheel reports ground speed, so no
`MotorConfig` is involved), `batteryCurrentA`, `escTempC`, `odometerKm`,
`tripKm`, `inputVoltageV`.

- **`hasMotorTemp = true`** for this wheel — frame `0x07` bytes 6..7 carry a real
  motor temperature (20 °C in the capture, against 27.5 °C on the board), so spec
  §2's prose is wrong here. Set the flag from what the wheel actually sends, and
  keep constraint 5 for the case where it sends nothing: the sentinel, never `0f`.
- **`dutyPercent` from frame `0x07`** (Task 1 decoded it). The not-yet-known
  window — before `truePWM` latches on a first non-zero — must be handled
  explicitly, not published as a confident 0 %.
- **Voltage:** the protocol deliberately does not know the 67.2-V scale factor,
  which is why the synthetic pack publishes `voltage = 0`. Decide what
  `inputVoltageV` reports — the honest options are the sentinel-free raw scale
  with a documented meaning, or zero with the reason. Do **not** invent a
  multiplier from the frame. State the choice and its consequence for the
  dashboard's voltage readout.

### Task 3 — duty, faults and tiltback from what the wheel actually sends

**Planned as "turn `reportsDuty[BEGODE]` off". That premise is void** — see spec
§8. The wheel reports true hardware PWM in frame `0x07` (2 % on the stationary
capture, exactly what a balancing wheel draws), so the entry stays `true` **on
evidence**, and Part F's headline wheel alarm is real. What this task delivers
instead:

- **Do not implement WheelLog's `0x00` fallback PWM.** On this wheel bytes 14..15
  read a constant 169 → 16.9 % while `0x07` reads 2 %. That fallback exists for
  firmware without hardware PWM and here it would be a fabricated safety number —
  precisely what spec §7.2 forbids.
- **Faults from frame `0x04` byte 14** → `ControllerData.faults`: speed-alarm ×2,
  low voltage, over voltage, over temperature, hall-sensor error, transport mode.
  Name them in the rider's words, not as bit indices.
- **Tiltback speed** from `0x04` bytes 10..11 (WheelLog treats ≥ 100 as unset).
- The greyed-with-reason path still needs verifying end to end for the alerts a
  wheel genuinely cannot supply — Part F built it and this is its first real
  consumer.

### Task 4 — let a Begode vehicle carry a controller

`controllerMotionProtocol` currently maps `ProtocolKind.BEGODE → null`, and G1
refused it **on purpose**: Begode maps to a real `BmsType`, so returning a
battery decoder would have put a dead dashboard in front of a rider. That reason
expires the moment `BegodeProtocol` is a `MotionSource`.

- Return the Begode motion protocol for `ProtocolKind.BEGODE`.
- A wheel is **one link owning `controllers = [0]` + `packs = [0,1]`** at one
  address (spec §4, `01-linking §3` archetype 3). Confirm `planLinks` already
  produces that once the vehicle config carries a Begode controller beside its
  Begode packs; if it does not, that is this task's work.
- The picker's support gate is *derived* from the protocol factory, so it should
  follow automatically — assert that it does rather than assuming.

### Task 5 — the funnel, end to end

A wheel connection yields one controller and two packs over the same link, with
motion and battery interleaving. Extend the `VehicleConnection` test with the
real fixture rather than a synthetic stream, so the MTU-23 straddling is
exercised. Confirm the Ride dashboard is reachable for a wheel and that
aggregation is unaffected (single controller).

### Task 6 — demo and dashboard sanity

The demo simulator emits a synthetic ride curve; make sure a wheel-shaped demo
vehicle renders sensibly on the Classic and Clean dashboards with **no duty** —
the dials must degrade honestly rather than showing a confident zero. Say in the
report what a duty-less wheel actually looks like on both styles.

---

## Out of scope

Other EUC brands (KingSong / Inmotion / Veteran are future adapters behind the
same interface); any duty derivation; controller writes of any kind; the
`VehicleConnection` staleness gap (`F §14`).
