# Energy model: a charge that does not jump, a learned capacity, and a range estimate

Date: 2026-10-24. Status: approved in conversation with the rider, pending a plan.

## Why

The rider's Begode T4 shows **60 % → 15 % when they lean hard on it**, and back when they ease off.
The reason is `VoltageSocEstimator`: for any pack whose BMS reports no SoC
(`BmsType.reportsStateOfCharge == false` — every Begode, and any controller-only vehicle) it maps the
*live* average cell voltage linearly between the configured cell bounds. Under a 200 A pull a 24S
wheel sags ~10 V, which is ~45 % of that window, so the number on the dashboard — and every SoC alert
— follows the sag. The rider's scooter (VESC + ANT BMS) does not have this problem: the ANT
coulomb-counts, so its percentage creeps down one point at a time under any load. **That behaviour is
the target.**

Second, the rider's own head unit (`E:\sodovaya\nyxdash\firmware`,
`targets/p4_43inch/main/screens/scr_dashboard.c`, the "Range estimate" block, ~line 498) shows a range
estimate next to its battery symbol that "works very well". Port it — but it works there because the
ANT hands it an honest remaining energy, which wheels do not have. The energy model below is what
gives every vehicle that half of the formula.

What the head unit does, for the builder's reference (read the source, it is short):
- range = remaining Wh / Wh-per-km;
- Wh-per-km from the VESC's own counters (`wh_used − wh_charged` over trip distance) when present,
  otherwise from integrating pack `V × I` over distance travelled;
- the integration window resets when remaining energy rises by > 15 Wh (a charge);
- a BMS link gap of ≥ 2 s is not integrated; the learned window survives a BMS disconnect.
- Its own comment records the trap we must not repeat: the ANT's `wh_remaining` is
  `ah_remaining × live V`, so it sags under load. **Remaining energy must be Ah × nominal voltage,
  never × live voltage.**

## Goals

1. **A state of charge that moves only as charge actually leaves the pack** for vehicles without a
   coulomb-counting BMS: integrate battery current, correct slowly towards a load-compensated voltage,
   correct harder at rest. A 200 A pull costs a fraction of a percent per second, not 45 %.
2. **It keeps moving during a long non-stop ride.** The rider can ride 30 min without stopping; the
   counter must not need a stop to make progress, and must not drift unchecked either.
3. **Pack capacity**: optional, rider-entered (Wh or Ah) per vehicle; **learned automatically** when
   not entered; the learned figure shown to the rider.
4. **Range estimate ("~42 км")** on all three Ride dashboards, from remaining energy and a blended
   consumption.
5. **Slightly larger battery numbers** on the Light (map) dashboard.

## Non-goals

- Do not touch the SoC of a pack whose BMS reports one (ANT, JK, JBD, Daly, Begode smart-BMS…).
  `withEstimatedSoc`'s identity guard (by device type, not by value) stays exactly as it is.
- No retroactive re-smoothing of ride history. `HistoryEstimatedSoc` keeps its voltage estimate; old
  rides stay as recorded. (Possible later work, not this.)
- No temperature / ageing model, no per-cell modelling.
- No new BLE writes of any kind. **Global Constraint 5: never write to a Begode's FFE1.** This
  feature is read-only on every link.

## Anti-goals (would make this a failure even if it "works")

- A range number shown before it is earned. Hidden until earned; no "--км" placeholder on the dash.
- A percentage that is smoother but wrong in a way the rider cannot see: the learned capacity and
  the resistance must be visible (see the Energy block), so a bad learn is noticeable.
- A capacity learned from a ride too short to mean anything overwriting a good one.
- A SoC alert that fires (or stays silent) because of sag. The alerts must run on the new SoC.

## The parts

### 1. Battery current: which one to integrate

Integrate **battery current**, never phase/motor current (phase current at low speed and high torque
is many times battery current).

- Begode: `BegodeProtocol.batteryCurrentA()` from the 0x07 frame (already separate from the live
  frame's phase current, bytes 10..11). The rider believes the T4's battery current is "a little
  synthetic" while the ET Max reports a real one. A *proportional* error is harmless — capacity is
  learned in the same current's units, so the error cancels in both SoC and range. A *load-dependent*
  error would not cancel; see Verification. **Confirmed on the T4** (rider's screen recording,
  2026-10-24): 0x07 arrives and battery current updates together with phase current (+26.8 A battery
  vs 55 A phase at 33 km/h), so the counter-first order stands for the wheel that motivated this.
- VESC without its own SoC: the controller's battery (input) current.
- Packs with per-pack current (`BmsData.current`, `hasCurrent`) and no SoC: sum for PARALLEL, any one
  for SERIES.
- Precedence when several exist is the builder's call; document it.
- **No measured battery current → no counter.** Fall back to the load-compensated voltage estimate
  (part 3). Never integrate a current that was not reported (`hasCurrent == false`, pre-0x07 Begode).

### 2. The charge counter

- State: estimated charge in Ah (or fraction), per vehicle.
- Each sample: `charge −= I_discharge × dt`. Gaps ≥ 2 s are not integrated (head-unit rule). Regen
  integrates negative naturally; clamp to [0, capacity].
- **Slow correction in motion**: blend towards the SoC implied by the load-compensated voltage
  (`V_ocv ≈ V + I·R`, part 3) with a time constant long enough that a 10 s pull moves nothing and a
  30 min ride cannot drift more than a few percent. The number (order of "fractions of a percent per
  minute") is the builder's to tune and test.
- **Stronger correction at rest**: when |I| has been near zero for several seconds the terminal
  voltage is honest; correct faster (still not a jump — the display is rate-limited).
- **Displayed value is rate-limited** and monotone-ish while discharging (no upward ticks from noise;
  upward movement only from regen, rest-correction, or charging).
- **Initialisation on connect**: the wheel may have been ridden or charged without the app. Seed from
  the voltage SoC when the wheel is at rest at connect (the normal case); otherwise from the
  load-compensated estimate. A persisted counter value may be used only if it agrees with that seed
  within a tolerance — otherwise the seed wins.
- **Must not seed from an unearned sample.** Part N (`docs/superpowers/plans/…-N-unearned-numbers.md`)
  documents a Begode reading half its true charge for the first seconds of a connect; check what of
  Part N has landed and seed only from samples the model considers earned.
- **Without a capacity** (none entered, none learned yet) there is nothing to divide by: the SoC is
  the load-compensated voltage estimate, low-pass filtered and rate-limited. Clearly better than
  today, worse than the counter.

### 3. Load compensation (internal resistance)

- Estimate pack resistance R online from how voltage drops on current steps (ΔV/ΔI over short
  windows, robust to noise; reject windows with small ΔI). Clamp to a plausible range.
- Persist per vehicle; show it in the Energy block in mΩ.
- `V_ocv = V + I_discharge·R` feeds parts 2 and 4.

### 4. Voltage → SoC curve

Replace the linear map with a Li-ion open-circuit-voltage curve (piecewise table per `Chemistry`),
stretched so the rider's configured `AlertConfig.cellLowV/cellHighV` (or the chemistry defaults)
remain exactly 0 % and 100 % — "the bounds they configured ARE their 0 % and 100 %" stays true. Only
the shape between them changes. LiFePO4's flat curve makes voltage nearly useless mid-range; there the
counter matters even more and the voltage correction weight should be lower. (Builder's call on the
tables; cite sources in the KDoc.)

### 5. Capacity: entered or learned

- **Entered**: per vehicle, Wh or Ah. Ah ↔ Wh through nominal pack voltage = series cell count ×
  chemistry nominal cell voltage. Entered always wins over learned.
- **From the BMS**: packs that report capacity (`BmsData.capacity`) supply it; nothing to learn.
- **Learned**: over a ride, `capacity ≈ ∫I dt / (SoC_rest_start − SoC_rest_end)` using **at-rest**
  voltage SoC at both ends. Accept a learn only when the SoC drop is large enough (order of ≥ 20 %;
  builder tunes) and the current was measured throughout. Combine learns (weighted average, more
  weight to bigger drops); keep the count. One bad ride must not swing it far.
- Learning is in the current's own units (Ah as the wheel reports them), which is what makes a
  proportionally-wrong current harmless.

### 6. Remaining energy and range

- Remaining Wh = SoC × capacity Ah × **nominal** voltage. Never × live voltage.
- Consumption Wh/km: reuse what `BmsNavigationEnergySource` already does (controller counters first,
  power integral second), but **blend** trip average with a recent window (last few km), so 10 km of
  gentle riding does not hide a climb at 60. Blend weights are the builder's call.
- Range shown once ~1 km and a minimum sample count are behind it (lower than navigation's 2 km /
  5 min, so the first minutes are not blank); hidden before that.
- **One source of truth**: `ArrivalEnergyEstimator` today computes energy as
  `aggregate.capacity × aggregate.voltage` (live voltage — the ANT sag trap) and requires a reported
  capacity. Move it onto this model's remaining energy so navigation and the dashboard agree and
  wheels get arrival estimates too.

### 7. Where the model applies (multi-pack)

- The model lives at **vehicle level** — the battery as assembled — not per pack. The rider's
  example: 2×2 packs of 72 V. Pack cards remain about hardware and their BMS.
- If every pack reports SoC: existing aggregation, untouched; the model only supplies range.
- If no pack reports SoC: the model's SoC is the vehicle's SoC; PARALLEL no-SoC packs (Begode
  branches) display it too.
- Mixed vehicles: the builder rules on the cleanest honest behaviour and documents it (likely: model
  only for the no-SoC packs' share, or model off with the reporting packs' SoC — prefer whichever
  never presents a guess as a measurement).
- The SoC alerts read the vehicle SoC the dashboard shows, so they inherit the smoothing.

### 8. The Energy block (vehicle editor, battery page)

On the same page as the pack cards, **a separate block, not inside a pack card**:
- Capacity: field (Wh / Ah toggle), with "learned: ~N Wh from K rides" beside it, or "learning…"
  until a learn is accepted. Clearing the field returns to the learned value.
- Resistance: "~N mΩ" (read-only).
- Average consumption: "~N Wh/km" (read-only).
- A way to reset the learned values (they can go wrong; the rider must be able to start over).
Strings in both `values/` and `values-ru/`. Follow the redesigned editor's existing row styles.

### 9. Dashboards

- **Light (map dash)**, battery line (`LightRideDashboard.kt`, `LightBatteryLine`): voltage left
  10 → 12 sp; on the right "72 % · ~42 км" — SoC 11 → 13 sp bold battery colour, range 13 sp in a
  muted colour, "~" prefix. Range absent until earned (the SoC alone, as now).
- **Clean** (my call): when the hero ring's centre is BATTERY, "~42 км" small under the percentage;
  otherwise a range readout next to SoC in the bottom metric row.
- **Classic** (my call): a "~42 км" sub-label under the battery dial's value; no ninth dial.
- Units follow the app's unit setting (km / mi).

## Persistence

Per vehicle: entered capacity (+ unit), learned capacity Ah + learn count/weight, resistance, last
counter value + timestamp. DB is at v24 per the last migration in `volty-nearby`
(`composeApp/src/commonMain/sqldelight/.../23.sqm` is the newest file — **count before writing one**:
`N.sqm` migrates `N.db` to `(N+1).db`). Column vs separate table is the builder's call; removal later
would need a table rebuild (SQLite 3.19 floor, no DROP COLUMN).

## Constraints (from CLAUDE.md, binding)

- Pure decisions in pure functions / components; Compose renders only. Compose is not unit-tested in
  this repo — the estimator, learner, resistance fit, consumption blend and the "is range earned"
  rule are all pure and fully tested.
- Fixtures must include deliberately incoherent cases (`hasCurrent = false` with a non-zero
  `current`; a reported capacity of 0; a pre-0x07 Begode sample) — the contract concerns absent data.
- Each test must be shown to fail under the mutant it exists to kill.
- No `runTest` with an unbounded delayed loop.
- Record retractions, don't delete them (e.g. `VoltageSocEstimator`'s "linear follows the user's
  mental model" KDoc gets a marked revision, not a silent rewrite).

## Verification the builder cannot do alone

Field check with the rider, stated plainly in the plan:
1. T4, hard pull: displayed SoC moves by ≤ ~1 % during the pull and does not rebound by tens after.
2. 30 min non-stop ride: SoC decreases steadily; at the next stop, rest-voltage SoC and the counter
   agree within a few percent. A large disagreement means the T4's battery current is
   load-dependently wrong — then the counter's weight must drop for that device (worth a plan step).
3. After two or three rides with ≥ 20 % drop: learned capacity is in the right ballpark of the wheel's
   spec.
4. Range on the dash appears after ~1 km and is in the same neighbourhood as the head unit's on the
   scooter (a direct comparison is possible there — both read the same VESC + ANT).

## Decisions after the builder check (designer's calls unless marked)

1. **Capacity means nameplate.** What the rider enters is the spec-sheet figure, because that is what
   a rider knows. The model works internally in the usable window: usable Ah = nameplate ×
   (OCV-curve SoC at `cellHighV` − OCV-curve SoC at `cellLowV`), so the counter's 0–100 % and the
   voltage SoC's 0–100 % are the same window and the slow correction never fights a fixed factor.
   Learned capacity is measured in the window and shown converted back to nameplate terms, so the
   field and the "learned" figure are comparable. Range uses the usable window.
2. **Invalidation.** Changing series cell count or chemistry resets learned capacity and resistance
   (they describe a different battery). Changing the cell bounds does not (point 1 makes the learn
   bound-independent). Plus the manual reset.
3. **Owner and lifetime.** The model runs where samples are already enriched today
   (`KableBmsRepository`, application scope — the same place `withEstimatedSoc` is applied), for as
   long as the BLE link lives, independent of any screen. The ride is already kept alive in the
   background by the history foreground controller. A gap ≥ 2 s is not integrated; after a gap
   longer than ~30 s the counter re-seeds as on connect; a learn is rejected if gaps cover more than
   a small fraction (~5 %) of the ride.
4. **Part N is not a prerequisite.** Nothing of Part N has landed. The model carries its own minimal
   seeding rule: seed only once the Begode has delivered complete cells / a 0x07 frame and the
   voltage has been stable for a few seconds. If Part N lands later, its notion replaces this one.
5. **Mixed vehicles and alerts.** An all-SoC-reporting vehicle keeps its existing aggregate and
   alerts; the model only adds range. No-SoC packs get one vehicle-level counter whatever their
   topology.
6. **Charging and full.** No charge current is assumed. Charge done without the app is caught by the
   seed on connect; at rest, a voltage at the high bound snaps the display to 100 % (allowed
   exception to the rate limit).
7. **Range needs a capacity.** Without an entered, reported or learned capacity, no range is shown —
   a range built on no capacity is a number the app has not earned. The rider can enter the
   capacity to get range from the first ride.

## Left to the builder

Filter constants, correction time constants, rate limits, OCV tables, resistance-fit method,
consumption blend, learn-acceptance thresholds, mixed-vehicle ruling, persistence shape — each
decided, tested, and written into the plan with its reason.
