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

## The capture is stationary, and that decides the shape of this part

I decoded a `0x00` frame from the fixture by hand:

```
55 aa | 17 04 | 00 00 | 00 3d 00 00 | fe b6 | f4 06 | 00 a9 | 00 01 | 00 | 18 | 5a5a5a5a
        volt    speed    distance     current  temp     ?       ?     type
```

- `0x1704` = 5892 → 58.92 V on Begode's 67.2 V scale; the wheel is 40S/148.4 V,
  so the multiplier is ~2.5 (168 / 67.2), consistent with the existing comment.
- **speed = `0x0000` in every frame of the capture.** It is the first 13 seconds
  of a session with the wheel standing still.
- distance = `00 3d 00 00`, ~61 m under Gotway's middle-endian word order.
- current `0xfeb6` = −3.30 A idle draw; temp `0xf406` → 27.5 °C. Both match what
  the protocol already decodes.
- bytes 14..15 are `0x00a9` **constant across every frame** while current varies
  — which is what a version/config constant looks like, not PWM.

**Consequence: nothing in this repository can validate a moving-wheel field.**
Speed reads zero everywhere, so a speed decode can only be checked structurally.
Duty cannot be checked at all.

## The duty decision, made deliberately (spec §7.2)

Spec §3 calls duty *"the safety number"* and §7.2 says it must be **real or
absent, never a placeholder**, because `MotionAggregator` folds duty as
`maxOf { dutyPercent }` and Part F's headline wheel alarm fires on the result.

Two derivations were available and both fail here:
- **A frame field.** The only candidate (bytes 14..15) is constant while the
  wheel's other numbers move. Asserting it is duty would be a guess.
- **WheelLog-style derivation** from speed against a voltage-dependent maximum.
  It needs per-model constants this repo does not have, and a speed reading that
  is never non-zero in the only capture we own.

**Therefore Part D ships `dutyPercent = 0f` and sets
`MotionAlertAvailability`'s static `reportsDuty[BEGODE] = false`.** That entry is
currently `true`, inferred from spec prose rather than hardware, and pinned by a
test precisely so this part has to decide it on purpose. The rider then sees the
ШИМ alarm greyed out **with its reason stated** — Part F §10's whole design — not
an alarm armed against a fabricated number.

This is the difference between a wheel dashboard that is honest about what it
measures and one that lies quietly. Flipping it later needs one constant, one
test expectation and a moving capture.

**What unblocks it:** a `:dumper` capture of a real ride — accelerating hard
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

- **`hasMotorTemp = false`** and, per constraint 5, a motor temperature **below
  the −50 °C sentinel** rather than `0f`.
- **`dutyPercent = 0f`** with a KDoc stating why, pointing at the availability
  entry that keeps it from being alarmed on.
- **Voltage:** the protocol deliberately does not know the 67.2-V scale factor,
  which is why the synthetic pack publishes `voltage = 0`. Decide what
  `inputVoltageV` reports — the honest options are the sentinel-free raw scale
  with a documented meaning, or zero with the reason. Do **not** invent a
  multiplier from the frame. State the choice and its consequence for the
  dashboard's voltage readout.

### Task 3 — turn `reportsDuty[BEGODE]` off, deliberately

One constant in `MotionAlertAvailability`, its pinning test's expectation, and a
KDoc paragraph recording *why* (the constant candidate field, the stationary
capture, what would flip it). The rider must get a greyed ШИМ row **with the
reason in words** — verify that path end to end, since Part F built it and this
is its first real consumer.

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
