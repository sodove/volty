# Telemetry Plausibility — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Stop numbers the wheel never meant from reaching ride totals, the live trip, latches and fault alerts. Reject only what a witness contradicts or what the signal's own neighbours do not repeat. Count every rejection («Всё о ТС») and keep its raw bytes in the dump. Repair rides that are already recorded at read time, with no migration.

**Why (rider's T4 ride of 2026-10-27; spec `docs/superpowers/specs/2026-10-27-telemetry-plausibility.md`):**
- The ride summary showed **67.1 km in 15 min, average 254.1 km/h, top 55.1 km/h**. GPS measured about 5–7 km. The odometer chart had **one** sample at 2883.6 km on a ride that ran 2816.4 → ~2822. That is a torn 32-bit read: true value 0x2AFFxx m, published as 0x2BFFxx = true + 65 536 m. `historyRideTotals` takes `max − min` of `trip_distance_km`, then of `odometer_km` (`presentation/history/HistoryRideTotals.kt:71-77,83`). A Begode trip is `odometer − first baseline` (`data/bms/BegodeProtocol.kt:504-508`, baseline at `:1558`), so the needle becomes the distance. Average is distance / wall duration (`:89-90`) and is never checked against the peak (`:91`).
- **The wheel already sends a witness.** The live 0x00 frame's power-on counter (bytes 8..9, u16BE, `BegodeProtocol.kt:1414`) moves exactly with the 0x04 odometer. In dump (3), `odometer − powerOn` = 2 816 063 m on every one of 44 frames, over 0→81 km/h. Over the three dumps, power-on span = odometer span = 60, 242, 174 m.
- **The T4 resets its own odometer** (rider): at about 3000 km it drops to about 2600–2700 km. This has not been seen in any frame yet. A persistent discontinuity must therefore become a new baseline that adds zero distance. It must never freeze the trip.
- **"Battery −39 %"** on a pack that ended at 79 %: the tile is `max − min` of SoC estimated from instantaneous voltage (`HistoryRideTotals.kt:104-108`), so the low point is a sag. The «−» is hard-coded (`HistoryTotalsRow.kt:42`).
- **The controller-power chart is a few dots.** Every 0x00 frame records `CONTROLLER_POWER` as unknown (`data/bms/BegodeTelemetryObservation.kt:35`). The stored series alternates value/unknown at ~3 Hz, so the ≤ 600-point overview falls back to min/max/first/last (`domain/history/HistoryOverview.kt:31-47`). No energy is integrated between unknowns (`HistoryBucketAccumulator.kt:34-35,48`), so the T4 has no Energy and no Wh/km tile.
- **Peak PWM** is wanted in the ride summary and on the ride-list card. History records the estimated duty **unmarked** under `duty_percent` (`data/history/HistoryEstimatedDuty.kt:36-39`, `domain/model/TelemetryObservation.kt:36-45`). The KDoc says a marker "needs a schema migration". It does not: `metricId` is TEXT (`RideHistorySample.sq:5`) and the registry is code (`domain/history/HistoryMetricRegistry.kt`).

**Citation check (done while planning, against 2a78dea2):** almost every file:line in the spec holds. Two corrections:
- `BegodeTelemetryObservation.kt` has 47 lines. The spec's `:45,56,64` are stale. The 0x00 unknown power is at **`:35`** (`unpairedPower()`), the 0x07 join at `:43`, and the KDoc sentence to retract at `:15-16`.
- The old behaviour is pinned in `BegodeTelemetryObservationTest.kt` at **`:32-33`** (0x00 metric set contains `CONTROLLER_POWER`), **`:49`** and **`:51`** (`calibratedVoltageRequires…`), and **`:65-66`** (`t4ShapedSequence…`). That is four assertion sites in three tests, not two.

Also: `HistoryReadRepository` (`domain/history/HistoryReadRepository.kt:19-32`) does **not** expose detail samples. Only `HistoryStore.samples` (`data/history/HistoryStoreReader.kt:76`) can page them. Task 2 adds the read path.

**Repo / branch:** `C:\Users\sodovaya\git\volty-nearby`, new branch `fix/telemetry-plausibility` from `origin/main` @2a78dea2. Suite: `.\gradlew.bat :composeApp:testDebugUnitTest`. Keep `org.gradle.workers.max=4` and `-XX:ActiveProcessorCount=4` (AGENTS.md). DB stays at **v27**: no `.sqm` in this plan (see Task 11 for why).

## Global Constraints
- **Rider ruling (2026-10-27): no fixed physical thresholds.** «Токи фиксировать не вариант — самокат может скушать 600А; напряжение — вдруг другая химия; ШИМ — Sherman/SV показывают 105 % (ослабление поля).» The spec's absolute bounds are **out**: R3 (250 km/h), R6 (400 A), R7/R10 temperature windows, R8 (V/cell), R9 (300 A), R11 (|duty| > 100 rejection; the existing clamp at `BegodeProtocol.kt:1615` stays **unchanged**), R14 (cell window), and R2's fixed 75 m / `vmax`. A retraction note now sits at the top of spec §2. Only four kinds of rule are allowed:
  1. **Witness:** an in-frame or cross-frame quantity contradicts the value. Examples: R1 (power-on counter vs odometer); R13 (0x01 pack voltage vs 0x00 live voltage, two frames ~3 ms apart).
  2. **Temporal confirmation, relative to the signal's own neighbours:** a single-sample excursion that the next sample does not repeat and that returns to the prior level. Thresholds are relative, measured against the dumps, and their false-positive analysis is written into KDoc.
  3. **Encoding impossibility:** only where it holds for every device of that protocol. Leave it out when unsure. This plan uses one: a lifetime odometer field is a counter, so it does not run backwards except at a reset (handled by kind 4).
  4. **Counter re-baseline** after N = 3 agreeing samples, with trip = Σ accepted increments.
- **Live display stays raw.** Speed, currents, voltages, temperatures and duty on the dashboard are never filtered. Only these are held:
  - odometer/trip (R1 / counter gate);
  - a frame a witness contradicts (R13);
  - a new fault bit (R12);
  - one-way latches (Task 7).
- **Every rejection is counted and kept.** A per-connection counter is shown in «Всё о ТС». The raw bytes go into an always-on 32-entry ring that is exported in the BLE dump. A rejected reading is **omitted** from the history observation, never recorded as unknown, because explicit unknowns break chart segments (see Task 6).
- **Every rule states its false-positive risk** in KDoc and in its test name or comment, and says how a false positive would show (the counter grows on a healthy wheel). The rider's words: «мы тоже можем ошибаться». Keep rules narrow.
- Read-only on every device; **never write to a Begode's FFE1** (Global Constraint 5). Nothing here sends a byte.
- Compose is not unit-testable. Every decision lives in a pure function or component with tests. Tile rendering, the «~» glyph and the «Всё о ТС» layout are judged on device. Say so plainly; do not write a test that dresses it up.
- **Incoherent fixtures where data is absent.** Examples: `complete = true` stats carrying a needle; a torn **first** 0x04; calm current with unknown SoC; `hasPower = true, powerW = 4200f` on a 0x00 frame.
- **Tests:**
  - No unbounded `runTest` loops.
  - Tests that build components inject test dispatchers for every IO context (`pwmIoContext`-style parameters, `HistoryTotalsComponent`'s parent scope) and destroy component lifecycles before `Dispatchers.resetMain()`. The pattern is in 2a78dea2.
  - Assert on what was published or recorded, never on timing alone.
- **Record retractions in KDoc** ("an earlier revision … ; that was wrong because …"). Do not delete the old reasoning silently.
- **Fixtures from real dump hex** (`C:\Users\sodovaya\Downloads\Telegram Desktop\volty-ble-dump*.txt`; the parsed series and `parse.py`/`odospeed.py` are in the session scratchpad `plaus\`). Build torn or reset frames by **editing real frames**, not by inventing them. Dump (3) frames used below:

  | Frame | Hex | Decodes to |
  |---|---|---|
  | live, pwron 101 | `55 AA 19 06 F7 43 00 6A 00 65 FE 5C F1 D2 08 A0 00 05 00 18 5A 5A 5A 5A` | −80.5 km/h, power-on 101 m |
  | live, pwron 108 | `… 19 06 F7 46 00 6A 00 6C F9 70 F1 D5 08 A0 …` | |
  | live, pwron 115 | `… 19 06 F7 42 00 6A 00 73 FB 82 F1 DE 08 A0 …` | |
  | live, pwron 122 | `… 19 06 F7 45 00 6A 00 7A FD 44 F1 DE 08 A0 …` | |
  | 0x04, odometer 2 816 164 m | `55 AA 00 2A F8 A4 28 00 1C 20 00 C8 00 00 01 00 00 12 04 18 5A 5A 5A 5A` | |

  The next 0x04 frames read 2 816 171 / 2 816 178 / 2 816 185 m (bytes 4..5 `F8 AB` / `F8 B2` / `F8 B9`). Each odometer frame pairs with the live frame that has power-on = odometer − 2 816 063.
- Strings go in **both** `values/` and `values-ru/strings.xml`. No Android backslash escapes. Russian is natural and lower-case for choice values.
- Mutation checks, if any, only with the audited harness `.superpowers/sdd/2026-07-30-vehicle-platform-I-field-fixes/t11sweep.ps1` (CLAUDE.md "a green sweep is not evidence").

## Not in this plan (deliberately)
- **R4** (duty/speed alarm jump confirmation) is an **open question for the rider**. It delays a real alarm by one frame (~310 ms). Ask before building it.
- The T4 0x01 "confident 0 A / 0 W" pack current on the live state (spec §2.1, last-but-one row) is an honesty fix, not a plausibility rule. It gets its own small change.
- GPS walking-pace jitter mitigation and the tyre factor (spec §3.2 steps 2–3): inference only, not requested.
- Recording `ChargeTracker` SoC instead of the OCV estimate (spec §3.3 option).

---

### Task 1: `CounterStepGate` — a counter gate with no magnitudes
**Why:** One pure rule decides, for every counter in the app, whether a step is distance, a needle or a reset. It must handle the torn +65 536 m needle (rejected), its −65 536 m twin (rejected), and the T4's own reset 3000 → 2650 km (accepted as a new baseline that adds 0). It uses no speed limit and no metre limit (rider ruling).
**Files:** create `domain/plausibility/CounterStepGate.kt`, `domain/plausibility/CounterStepGateTest.kt` (commonTest).
**Interface:**
```kotlin
class CounterStepGate<P>(
    private val value: (P) -> Double,                  // metres
    private val agrees: (from: P, to: P) -> Boolean,   // kind-1 witness or kind-3 monotone
    private val immediate: (from: P, to: P) -> Boolean = { _, _ -> false }, // witness present → no lookahead
    private val confirmations: Int = 3,
)
fun offer(point: P): List<CounterDecision<P>>   // decisions for this and earlier held points
fun finish(): List<CounterDecision<P>>           // end of a finite series (read side only)
sealed interface CounterDecision<P> { Accepted(point, incrementM) ; Rejected(point, reference) ; Rebased(point, jumpM, creditedM) }
val acceptedSumM: Double
```
Default predicate `monotone = { a, b -> value(b) >= value(a) }`. This is a kind-3 property of a lifetime counter: it does not run backwards except at a reset.

**Rules (exact):** let `A` be the last accepted point.
- The first point becomes `A` with increment 0.
- `x` with `agrees(A, x)`:
  - If `immediate(A, x)`, accept now.
  - Otherwise hold `x` **provisionally**. It is decided by the next point `y`:
    - `agrees(x, y)` → accept `x` (credit `x − A`), then evaluate `y` against `x`;
    - else if `agrees(A, y)` → `x` is a **needle**: `Rejected(x)`, then evaluate `y` against `A`;
    - else accept `x`, and `y` starts a chain.
- `x` with `!agrees(A, x)` starts or extends a **pending chain**. A later point that agrees with `A` discards the chain (every chain point `Rejected`). A chain of `confirmations` points, each agreeing with its predecessor, **re-baselines**: `Rebased(jump = first − A, credited = Σ internal chain increments)`. The jump adds **0**; internal increments are credited; the last chain point becomes `A`.
- `finish()` accepts a held provisional point and rejects a pending chain shorter than N.

**False-positive analysis (KDoc):** every step in the three dumps is monotone, so `monotone` never fires on them; check this in a test by replaying the dump-(3) odometer series. Three identical torn reads in a row would re-baseline to the needle, but that contributes **0** distance; only the published odometer is wrong until the next re-baseline back. This is the property that makes "sum of increments" safe where "last − first" is not.

- [ ] Failing tests (values in metres, points `(t s, m)`, `monotone`, N = 3):
  - **Torn needle:** 2 816 164, 2 816 171, **2 881 707** (= 2 816 171 + 65 536), 2 816 178, 2 816 185 → exactly one `Rejected(2 881 707)`; `acceptedSumM == 21.0`.
  - **Dip needle:** 2 816 164, **2 750 635**, 2 816 171 → one `Rejected`; sum 7.
  - **Persistent reset:** …, 2 999 990, then 2 650 000, 2 650 007, 2 650 014, 2 650 021, 2 650 028 → `Rebased(jump −349 990, credited 14)` at 2 650 014. Then the sum keeps growing: +7, +7. No `Rejected`.
  - **Persistent forward jump** with a witness predicate (`agrees = |Δvalue − Δwitness| ≤ 40`): 2 999 990 (w 100), 3 350 000 (w 107), 3 350 007 (w 114), 3 350 014 (w 121) → `Rebased`, jump +350 010 contributes 0.
  - Chain broken at length 2 → both chain points `Rejected`.
  - A standing wheel (equal values) is accepted with increment 0.
  - The dump-(3) odometer series (2 816 071 → 2 816 245, 44 points) gives zero `Rejected`/`Rebased` and sum 174.
  - `finish()` with a held point accepts it; with a chain of 2 rejects both.
- [ ] Implement; suite; commit `feat(plausibility): CounterStepGate — needles rejected, persistent jumps re-baselined, no magnitudes`.

### Task 2: Ride distance from detail samples — GPS first, wheel fills, impossibility cross-check
**Why:** This is the 67 km / 254 km/h ride. The repair happens at **read time** from the stored 500 ms detail samples (first/min/max/last per window, `domain/history/HistoryDetailGate.kt:3-29`). That repairs every recorded ride, including the rider's, and needs no migration.
**Files:**
- `domain/history/HistoryReadRepository.kt`: add `suspend fun samples(rideId, key, range, after: HistoryCursor?, limit: Int): HistoryPage<HistorySample>? = null`. Null means "this reader cannot page detail" (legacy, fakes).
- `data/history/DefaultHistoryReadRepository.kt`: implement it with the same ordering, range and cursor checks as `series` (`:44-58`).
- `PreparedHistoryReadRepository.kt` and `SqlDelightHistoryReadRepository.kt`: delegate.
- Create `presentation/history/HistoryRideDistance.kt`: a pure `historyRideDistance(counter: List<HistorySample>, gps: List<HistoryGpsFix>, origin: HistoryTick, peakKmh: Double?, durationMillis: Long): HistoryRideDistance?`.
- `HistoryRideTotals.kt`: take the computed distance instead of `delta(...)`.
- `HistoryTotalsComponent.kt` `readHistoryTotals` (`:48-77`): page counter samples with a budget of `maxDetailPages = 16` × 20 000, and **always** read GPS (today it skips GPS when a counter exists, `:57`).
- Tests.

**Rules:**
1. **Counter series:** walk `trip_distance_km`, else `odometer_km`, of the motion tier through `CounterStepGate` with `monotone`, values ×1000. A deviation from spec §3.2, which says odometer first, justified as follows: after Task 4 the trip series is already the decoder's gated sum and carries no reset jumps, while the odometer series carries real resets. On old rides both series carry the same needle.
   - An unknown sample, a clock-generation boundary or a `GPS_GAP`-excluded marker ends a run: `finish()` the gate and start a new one, adding 0 across the break.
2. **GPS-first:**
   - GPS-covered intervals are consecutive fixes ≤ 30 s apart with accuracy ≤ 50 m (the existing rules, `HistoryRideTotals.kt:139-152`), on the ride axis (`historyAxisEpochMillis`).
   - GPS length = `historyGpsDistanceKm`.
   - An accepted wheel increment `[t_prev, t]` is added as **fill** only when its midpoint lies outside every covered interval.
   - Distance = GPS + fill.
3. **Cross-check** (kind-1 witness: the ride's own speed series): if the distance > `peakKmh × duration × 1.05`, the counter is not used. Show GPS alone when there is any GPS; otherwise **no distance tile** (and so no average and no Wh/km). Never a clamped number. The same check also guards the fallback path (repository returns null → today's `max − min` of stats).
4. `approximate` when any fill was used, any step was rejected or re-baselined, the page budget ran out, or the cross-check fired.

**False-positive risk:** a ride with GPS everywhere shows GPS length, which reads slightly long at walking pace (spec §3.2, inferred). The cross-check needs a genuine peak; a speed needle would only loosen it. Task 10 removes speed needles.

- [ ] Failing tests:
  - **Rider's ride reconstructed:** 950 s; odometer every 500 ms from 2816.400 km rising 6.0 m/s; at t = 272 s the true value is 2818.032 (0x2AFFF0 m), and one sample there reads **2883.568** (0x2BFFF0); speed peak 55.1.
    - No GPS: distance 5.700 ± 0.01 km, approximate, not 67.2.
    - Average below the peak.
    - Today's code gives 67.17. Keep that as a comment, not an assertion.
  - **GPS-first:** fixes every 1 s along a meridian at 6 m/s for the whole ride → 5.70 ± 0.02, exact.
  - **Pre-GPS head:** GPS from t = 120 s → fill 0.720 + GPS 4.98 = 5.70 ± 0.02, approximate.
  - **Persistent reset in a recorded ride:** at t = 500 s the series drops to 2650.000 and continues at 6 m/s → 5.697 ± 0.01; no negative step and no frozen tail.
  - **Cross-check alone:** a fake repository whose `samples` returns null and whose stats say odometer min 2816.4 / max 2883.6 (*incoherent: `complete = true`*), duration 950 s, peak 55.1:
    - no GPS → no `DISTANCE_KM`, no `AVERAGE_KMH`;
    - with GPS 5.6 → 5.6.
  - Page budget exceeded → falls back to the stats path with the cross-check, approximate.
  - `HistoryTotalsComponentTest` still passes with fakes that keep the default `samples = null`.
- [ ] Implement; suite; commit `fix(history): ride distance from plausible counter increments, GPS first, never above the top speed`.

### Task 3: Average speed over moving time, and the moving time itself
**Why:** The rider's decision is that the average is over **moving** time only, where moving means speed > 3 km/h, and that the moving time is shown too. Today the average is distance / wall duration (`HistoryRideTotals.kt:89-90`).
**Files:**
- `HistoryRideTotals.kt`: new `HistoryTotalKind.MOVING_MILLIS`, and the AVERAGE rule.
- `presentation/history/HistoryMovingTime.kt`: pure `historyMovingMillis(speed: List<HistorySample>): HistoryMovingTime(millis, approximate)`.
- `readHistoryTotals`: pages the motion tier's `speed_kmh` detail with the same budget as Task 2.
- `HistoryTotalsRow.kt`: title + text, reusing the duration format.
- Strings `history_total_moving`: «В движении» / "Moving".

**Rules:**
- An interval between consecutive **known** speed samples `[s_i, s_{i+1}]` counts as moving when `s_i > 3.0 km/h` and `Δt ≤ 5 s`. A longer interval or an unknown sample is not credited, and the result is marked `approximate`.
- Average = distance / moving time. With no speed series, keep distance / duration and mark it approximate.
- The 3 km/h moving threshold is the rider's definition, not a plausibility bound. The 5 s gap is the data-continuity rule (5× the slowest 1 Hz source). Document both.
- `historyVisibleTotals` hides MOVING_MILLIS below 1 s.

- [ ] Failing tests:
  - 950 s ride: standing 0–100 s at 0 km/h, 100–900 s at 25 km/h, 900–950 s creeping at 1.5 km/h; distance 5.556 km → MOVING_MILLIS 800 000 ± 500, AVERAGE 25.0 ± 0.1 (today 21.05).
  - *Incoherent:* speed unknown 300–400 s while the counter keeps rising → moving 700 000, AVERAGE approximate.
  - No speed series → old formula, approximate.
  - MOVING_MILLIS is listed after DURATION.
- [ ] Implement; suite; commit `feat(history): average speed over moving time; moving time tile`.

### Task 4: Begode odometer — power-on witness (R1), counter gate, trip as Σ accepted increments, rejection ledger
**Why:** The same torn read reaches the live trip tile and history. A torn **first** 0x04 offsets the live trip by ±65.5 km for the whole connection (`:1558`). The T4's own reset would today pin the trip at 0 (`coerceAtLeast(0)`, `:504-508`).
**Files:**
- `data/bms/BegodeProtocol.kt`: `parseLiveFrame` keeps the latest witness; `parseOdometerFrame` (`:1537-1561`); `sessionTripKm`; `reset`; `deviceFacts`.
- `data/bms/BmsProtocol.kt`: a rejection ledger shared by every protocol:
  - `protected fun recordRejection(frameType: Int, rule: PlausibilityRule, raw: ByteArray, reference: String?, witness: String?)`;
  - `val rejectedFrameCount: Long` (per connection, zeroed by `reset()`);
  - `val rebaseCount: Long`;
  - `fun rejectedFrames(): List<RejectedFrame>` (ring of 32, survives `reset()` because it is evidence).
- `domain/plausibility/RejectedFrame.kt` (`atFrameIndex`, `frameType`, `rule`, `rawHex`, `reference`, `witness`, `kind = REJECTED | REBASED`). No clock in the decoder; the repository stamps time when exporting.
- Tests in `BegodeOdometerWitnessTest.kt`.

**Rules:**
- A point is `(odometer m, power-on m from the newest genuine live frame, liveSeenSinceReference)`.
- **R1** `agrees(a, b)`:
  - `dW = (W_b − W_a) mod 65 536` (handles the u16 wrap);
  - `|(O_b − O_a) − dW| ≤ max(40 m, 2 × last accepted Δodo)`.
  - Measured: lock-step exact in all three dumps (`odometer − powerOn` constant). 40 m covers the ~100 ms between the 0x00 and 0x04 samples at any speed the dumps show (≤ 7 m/frame) with a ×5 margin. This is a witness tolerance, not a speed limit.
- **Witness unavailable** means either:
  - no genuine live frame since the reference; or
  - power-on reset: `W_b < 100 && dW > 1000`, meaning the wheel restarted its power-on counter.

  In that case use `monotone` **with one-frame lookahead** (Task 1's provisional path). No 75 m bound (rider ruling).
- `immediate = witness available`: with a witness the decision is instant; without one the published odometer/trip lag by one 0x04 (~310 ms).
- **Handling decisions:**
  - Accepted frame: proceeds exactly as today (odometer, tiltback, alert byte, `rebuildMotion`, observation).
  - Rejected frame: **no state write, no `rebuildMotion`, no observation**; `recordRejection(0x04, R1_ODOMETER_WITNESS, frame, "…m", "+N m")`.
  - Pending chain frame: held, not published.
  - On `Rebased`: publish the chain's last frame, record a `REBASED` ring entry, `rebaseCount++`.
- **Trip** = `acceptedSumM / 1000`. It replaces `odometer − baseline`; record a retraction note on `sessionTripKm`'s KDoc (`:495-503`). `tripBaselineMeters` goes away. A torn first frame costs the published odometer ≤ 1 s and the trip nothing.
- `reset()` clears the gate, the witness and the counter; it keeps the ring.

**False-positive risk:** R1 never fired on 44 + 105 + 118 real frames. A wheel whose power-on counter does not track the odometer (another Begode board) would show up as the counter climbing on every 0x04 and a re-baseline storm. The test pins that such a wheel still gets a trip (each re-baseline credits its chain), and the counter makes the problem visible.

- [ ] Failing tests (frames from the table in Global Constraints; feed live + 0x04 pairs through `onNotification`; assert on published `ControllerData` and observations):
  - **In step:** pwron 101/108/115/122 with odometer 2 816 164/171/178/185 → all accepted, trip 0.021 km, counter 0.
  - **Torn needle:** replace the pwron-108 pair's 0x04 byte 3 `2A → 2B` (odometer 2 881 707).
    - That frame publishes nothing: no ODOMETER/TRIP observation, `odometerKm` stays 2816.164.
    - The next frame is accepted; trip 0.014.
    - `rejectedFrameCount == 1`; the ring holds the torn hex with reference `2816164` and witness `+7`.
  - **Torn first frame** (*incoherent*): the connection's first 0x04 is torn → trip never exceeds 0.1 km. After 3 in-step frames the odometer publishes 2816.185, trip 0.014, `rebaseCount == 1`.
  - **T4 reset:** after the in-step run, 0x04 frames carry 2 650 000 / 007 / 014 / 021 (bytes 2..5 `00 28 6F 90` …) while live frames keep pwron +7 each.
    - First two: nothing published.
    - Third: odometer 2650.014, trip = previous + 0.014.
    - Fourth: +0.007.
    - Trip never decreases and never freezes.
  - **Persistent forward jump** to 3 000 000 / 007 / 014 → same shape.
  - **Witness wrap:** live pwron `FF FA` (65 530) then `00 04` (4) with odometer +10 → accepted, no rejection.
  - **Power-cycle reset:** pwron 182 then `00 00` with odometer +3 → handled by the lookahead path: accepted one frame later; no rejection.
  - **No live frame between two 0x04:** monotone lookahead; a +65 536 step followed by a return is rejected.
  - Existing `odometerBaselineIsOnlyRefreshedByRealDistanceFrames` (`BegodeTelemetryObservationTest.kt:118-127`) still passes, or is updated with a retraction comment if the trip semantics change its numbers.
- [ ] Implement; suite; commit `fix(begode): odometer witnessed by the power-on counter; trip is the sum of accepted increments`.

### Task 5: Battery used — start minus end on calm stretches, signed
**Why:** «−39 %» on a pack at 79 %. The tile is `max − min` of instantaneous-voltage SoC (`HistoryRideTotals.kt:104-108`) with a hard-coded «−» (`HistoryTotalsRow.kt:42`).
**Files:**
- `presentation/history/HistoryBatteryUsed.kt`: a pure function over the battery tier's `soc_percent` detail plus a current series. The current series is the battery tier's `current_a`, else the motion tier's `battery_current_a` (the T4's pack current is unknown, `BegodeBatteryObservation.kt:41-50`).
- `HistoryRideTotals.kt`, `readHistoryTotals` (page those series).
- `HistoryTotalsRow.kt`: the sign comes from a pure `historyBatteryUsedSign(value)` so it is testable.
- `historyVisibleTotals`: `|value| ≥ 0.5`.

**Rules:**
- `robustStart` = median of known SoC samples in the first 60 s after the first known sample. `robustEnd` = median over the last 60 s.
- In each window prefer **calm** samples: the nearest current sample within ±1 s has `|I| < 3 A`. 3 A is "not under load" for selecting a window, not a plausibility bound; justify it from the dumps (standing −0.2…−0.9 A, riding −4…−21 A).
- `used = robustStart − robustEnd`, **signed**. Display: positive → «−N %», negative → «+N %».
- `approximate` unless both windows used calm samples from the battery tier's own `current_a`. A pack without its own current is voltage-estimated: every Begode, and every `!reportsStateOfCharge` BMS (`HistoryEstimatedSoc.kt:17-29`). The source model carries no "estimated" flag; record this limitation in KDoc.
- Several battery sources: average their `used` (as today).

- [ ] Failing tests:
  - 15-min ride; SoC every 1 s: 80.0 for the first 60 s at I = −1 A, a sag dip to **40.0** at t = 400 s at I = −45 A, 79.0 for the last 60 s at I = −0.5 A → `used = 1.0`, sign «−» (today 40).
  - A charging session 50 → 62 → `used = −12`, sign «+».
  - *Incoherent:* a current series exists but every start-window sample is loaded (|I| ≥ 3 A) → falls back to all window samples, approximate.
  - No current series at all → approximate.
  - A ride shorter than 60 s → start and end windows overlap; still computed, approximate.
- [ ] Implement; suite; commit `fix(history): battery used is start minus end on calm stretches, with its real sign`.

### Task 6: Controller power — the 0x00 frame stops recording "unknown"
**Why:** The value/unknown alternation (`BegodeTelemetryObservation.kt:35` → `HistoryDetailGate.kt:19` → `HistoryOverview.kt:31-47`) empties the chart and blocks energy integration. `TelemetryObservation`'s contract already says "omitted fields were not observed in this decode" (`domain/model/TelemetryObservation.kt:24`).
**Files:**
- `data/bms/BegodeTelemetryObservation.kt`: the 0x00 branch drops `unpairedPower()`. The 0x07 stale-join unknown stays: it is honest and rare. Add a retraction note on the KDoc's `:15-16` ("The 0x00 frame still records unknown…").
- `BegodeTelemetryObservationTest.kt`: `:32-33`, `:49`, `:51`, `:65-66`, each with a retraction comment: "an earlier revision pinned PARTIAL_SOURCE here; that alternation emptied the chart overview and blocked energy (plausibility spec §4)".
- Audit the same pattern (a per-frame `PARTIAL_SOURCE` for a metric another frame of the same source supplies). Next candidate: `BegodeBatteryObservation.kt:76-79` cell summaries per page. Fix only if the same alternation is demonstrated in a test; otherwise leave a KDoc note.

- [ ] Failing tests:
  - A 0x00 observation contains **no** `CONTROLLER_POWER` reading.
  - *Incoherent:* a 0x00 decoded with `hasPower = true, powerW = 4200f` (call `observedBegodeController(0x00, data.copy(...), powerJoinFresh = true)`) still records none.
  - Replay `BegodeT4Fixture` through `BegodeProtocol(cellCount = 24)` → `historyTelemetrySamples` → `HistoryDetailGate` → `historyOverview(maxPoints = 600)`. Repeat the fixture to > 600 rows. The controller-power overview has > 4 known points and at least one segment of ≥ 2 consecutive known points. Today: ≤ 4 isolated points.
  - `HistoryBucketAccumulator` over the same rows yields a non-null `energyWh`.
- [ ] Implement; suite; commit `fix(begode): the live frame omits controller power instead of recording it unknown`.

### Task 7: Begode cross-frame witness (R13) and confirmed latches
**Why:** Of the frame rules in the spec, only witnesses and temporal confirmation survive the rider's ruling.
- A corrupt 0x01 pack voltage becomes a SoC needle on the T4.
- One corrupt 0x07 latches `sawTrueDuty` / `sawMotorTempEvidence` for the whole connection (`BegodeProtocol.kt:1590-1609`).
- One non-zero duty sample latches `PwmEvidence.REPORTS` **forever, persisted** (`domain/pwm/PwmEvidence.kt:48-58`). That would switch off the T4's estimate for good.

**Files:** `BegodeProtocol.kt` (0x01 parse at `:1676-1680` and the branch-ratio certification at `:934-951`; `parseMotionFrame`); `domain/pwm/PwmEvidence.kt`; tests.
**Rules:**
- **R13 (witness):** once the branch ratio is certified, a 0x01 whose pack voltage differs from the scaled live voltage of the newest genuine 0x00 by > **3 %** is rejected (ledger rule `R13_PACK_VOLTAGE_WITNESS`). The live frame is not touched: live display stays raw.
  - Measured: the ratio 0x01 V / live raw is **0.01500 on all 268 pairs** of the three dumps, spread 0.01 %, including the 0→81 km/h sag in dump (3). 3 % is a ×300 margin.
  - False-positive risk: a wheel whose two boards sample far apart in time under heavy sag. Shows as the counter.
- **Latch confirmation (temporal):** `sawMotorTempEvidence` latches only when `motorTempRaw != 0` in **2 consecutive** 0x07 frames. `sawTrueDuty`'s non-zero path likewise; its `sawLiveMotion` path is unchanged.
  - Cost: one frame (~310 ms) before a real sensor shows.
- **PwmEvidence:** `REPORTS` needs a non-zero measured duty in 2 consecutive samples. Record a retraction note on the reducer KDoc ("any non-zero sample latches").
  - Open question carried from spec §7: dump (3) shows the T4's duty field non-zero and rising. This task must not decide that question. It only stops one corrupt frame from deciding it.

- [ ] Failing tests:
  - R13 with the dump-(3) pair: live `55 AA 19 5D …` (raw 6493) and 0x01 `55 AA 00 50 00 00 03 CE …` (97.4 V) are accepted.
  - The same 0x01 with bytes 6..7 edited to `04 B0` (120.0 V) is rejected; SoC is unchanged and the counter is 1.
  - Before certification, R13 does not apply.
  - One 0x07 with temperature 130 and duty 37 between zero frames → `hasMotorTemp` stays false, and the duty latch and `PwmEvidence` stay as they were.
  - Two consecutive → latched.
  - The existing ET Max "duty 2 % standing" case (`REPORTS`) still latches from its real consecutive frames.
- [ ] Implement; suite; commit `fix(begode): pack voltage witnessed by the live frame; latches need two consecutive frames`.

### Task 8: Surfacing — rejection counter in «Всё о ТС», rejected frames in the dump
**Why:** The rider's decision is that every rejection is counted and visible, and that its raw bytes stay in the dump. This is also how the next torn read gets **measured** instead of inferred (spec §7).
**Files:**
- `domain/model/DeviceFact.kt`: `REJECTED_FRAMES` (count), `REJECTED_LAST_RULE` (token), `REJECTED_LAST_DETAIL` (numbers only, e.g. `+65536 m / +7 m`), `ODOMETER_REBASED` (count). Emitted from `BegodeProtocol.deviceFacts()` (`:1148`) once a 0x04 has been seen; `0` on a healthy wheel.
- `presentation/vehicle/info/VehicleInfoModel.kt` / `VehicleInfoText.kt`: labels; the rule token maps to an `InfoChoice` the way `SERIES_CELLS_PROOF` does (`VehicleInfoModel.kt:728-740`).
- `domain/model/BleDiagnosticRecord.kt` `BleDiagnosticLink`: `rejectedFrames: List<RejectedFrame>`, filled in `KableBmsRepository.kt:2710-2716`.
- `presentation/settings/DebugSettingsTab.kt` `bleCaptureFile`/`copyBundle`: a `rejected frames` section per link (rule, kind, reference, witness, raw hex).
- Strings en + ru, e.g.:
  - «Отброшено кадров (за подключение)»;
  - «Последнее отброшенное»;
  - «одометр не совпал со счётчиком с включения»;
  - «напряжение 0x01 не совпало с live-кадром»;
  - «Одометр перепривязан».

**Notes:** The ring is independent of manual capture (`BleDiagnosticBuffer.kt:43-52` records only while capturing). The «Всё о ТС» layout is Compose and is judged on device; say so in the task report.
- [ ] Failing tests:
  - After the Task 4 torn-needle fixture, `deviceFacts()` contains `REJECTED_FRAMES = "1"`, `REJECTED_LAST_RULE = "odometer_witness"`, `REJECTED_LAST_DETAIL` with both numbers.
  - A healthy replay of `BegodeT4Fixture` gives `REJECTED_FRAMES = "0"`.
  - The info model maps the token to a localized choice (no raw token on screen).
  - `bleCaptureFile` for a link with one rejected frame contains the section and the exact raw hex.
  - `reset()` zeroes the count and keeps the ring.
- [ ] Implement; suite; commit `feat(info): rejected-frame counter in «Всё о ТС» and rejected frames in the BLE dump`.

### Task 9: Fault-bit debounce (R12)
**Why:** The 0x04 alert byte 14 drives `faults` (`BegodeProtocol.kt:1551-1552`) and `wheelPwmAlarm` (`:852`). One corrupt byte raises a CRITICAL `CONTROLLER_FAULT` push (`domain/usecase/AlertEngine.kt:314-321`) and a banner row (`domain/alert/ActiveAlertTracker.kt:101-103`). It also teaches the PWM learner a false onset, because a rising edge of `wheelPwmAlarm` is a calibration point. Kept by the rider; temporal kind 2.
**Files:** `BegodeProtocol.kt` (`parseOdometerFrame` alert handling, `rebuildMotion` `:852`); tests. KingSong and Veteran publish no `faults` today (checked: no assignment in `KingSongProtocol.kt` / `VeteranProtocol.kt`), so this is Begode-only. Say so in KDoc.
**Rules:**
- A bit newly **set** becomes visible (in `faults` and `wheelPwmAlarm`) only when it is set in 2 consecutive **accepted** 0x04 frames.
- A bit that **clears** clears immediately.
- No counter entry: a one-frame bit is not a rejected frame, just an unconfirmed bit. Keep a separate `unconfirmedAlertBits` count in facts.
- False-positive cost: a genuine fault shows ~310 ms late; a genuine one-frame fault blip is not shown. The wheel's own beep is unaffected.

- [ ] Failing tests:
  - Using the 0x04 with bit 0 set from the pwm-calibration plan (`55 AA 00 2A DC AD 28 00 1C 20 00 C8 00 00 01 00 00 12 04 18 5A 5A 5A 5A`) and the same frame with byte 14 = `00`:
    - one set frame between clear ones → `wheelPwmAlarm` never true; `PwmEstimator` learns nothing; no `CONTROLLER_FAULT` alert;
    - two consecutive → true;
    - the next clear frame → false at once.
  - The same for a fault bit, giving `faults` and the AlertEngine notification.
  - `BegodeWheelPwmAlarmTest` is updated with a retraction comment where it asserted the first-frame edge.
- [ ] Implement; suite; commit `fix(begode): a new alert bit needs two consecutive frames`.

### Task 10: Robust peaks — needles excluded from history peaks
**Why:** Bucket `maximum` is the raw maximum, so one corrupt sample becomes «Макс. скорость» / «Контроллер 130 °C» / «Макс. мощность» (spec §3.4). The rider's ruling: a needle is a **single-sample excursion relative to its own neighbours**, with no absolute magnitudes.
**Files:** `domain/plausibility/HistoryNeedles.kt` (pure); `HistoryRideTotals.kt` `peak()` and MAX_POWER; `readHistoryTotals` pages the peak metrics' detail (same budget; over budget → today's stats maximum, approximate); tests.
**Rule:** an interior known sample `x_k` with known neighbours `a = x_{k−1}`, `b = x_{k+1}`, both within 2 s, is a **needle** iff all of these hold:
- `x_k` is above both or below both neighbours;
- `excursion = min(|x_k − a|, |x_k − b|)` and `|b − a| ≤ 0.25 × excursion` (the series returns to its prior level);
- `excursion > 6 × median|Δ|` over the ±8 neighbouring steps (excluding the two that touch `x_k`).

Robust peak = max over non-needle samples. `approximate` when any needle was excluded.
- **Measured on the three dumps:** 11 flags in 3 098 samples across speed, phase current, board temperature, battery current and voltage. All are standstill quantisation blips (≤ 1.4 km/h, 0.1 °C, 0.67 A). **No series' max or min changed** (script: scratchpad `plaus2\needles.py`). Put these numbers in KDoc.
- **False-positive risk:** a genuine sub-300 ms spike (a bump's current spike, a 5 kW pull lasting one frame) is excluded from the **tile**. The chart still shows it raw, and the tile shows ≈.

Applies to MAX_KMH, MOTOR_TEMP_C, CONTROLLER_TEMP_C, BATTERY_TEMP_C, MAX_POWER_W (and MAX_DUTY in Task 11). The cross-check in Task 2 uses the robust speed peak.
- [ ] Failing tests:
  - Speed `40, 41, 42, 1180, 43, 44` → peak 44, approximate.
  - A real ramp `40…81.0` from dump (3) (speeds −79.7, −80.5, −80.4, −80.6, −81.0, −79.9 km/h as magnitudes) → peak 81.0, exact.
  - Temperature `45, 45, 130, 45` → 45.
  - A two-sample plateau `45, 130, 130, 45` → 130: corroborated by its neighbour, so not a needle.
  - *Incoherent:* `complete = true` stats with maximum 1180 but detail available → 44.
  - A needle at a gap (neighbour > 2 s away) is not excluded.
- [ ] Implement; suite; commit `fix(history): peak tiles ignore single-sample needles`.

### Task 11: Peak PWM in the ride summary and on the ride-list card, estimate marked «~»
**Why:** Rider request. Today the estimate is recorded unmarked under `duty_percent` (`HistoryEstimatedDuty.kt:36-39`), so «~» cannot be shown honestly.
**Files:**
- `domain/model/TelemetryObservation.kt`: `DUTY_ESTIMATED("duty_estimated_percent", CONTROLLER)`, plus a retraction note on `:40-45` ("a marker needs a schema migration": it does not, because `metricId` is TEXT and the registry is code).
- `domain/history/HistoryMetricRegistry.kt`: a descriptor, PERCENT, CONTROLLER group, `motionSources`.
- `data/history/HistoryEstimatedDuty.kt`: under NEVER_REPORTED the DUTY reading is **replaced by** a `DUTY_ESTIMATED` reading (value, or unknown when there is no estimate). Retraction note on `:36-39`.
- Metric labels/picker (`HistoryMetricLabels.kt`), the export encoders (`HistoryStructuredExportEncoder.kt:198` takes any registry id; check any id allow-list).
- `HistoryRideTotals.kt`: `HistoryTotalKind.MAX_DUTY_PERCENT` + `estimated: Boolean` on `HistoryTotal` (or a kind flag). Robust peak from Task 10, over `duty_percent` and `duty_estimated_percent` of the motion tier; estimated when the winning series is the estimate.
- `HistoryTotalsRow.kt`: «~» prefix via a pure `historyTotalPrefix(total)`.
- Ride-list card: a sibling of `HistoryPreviewComponent` (same visible-rides / revision / cache / retry pattern, `HistoryPreviewComponent.kt:22-169`), `HistoryCardStatsComponent`. It reads cheap **bucket stats** maximum per duty key (`repository.stats`) and robust-checks it only when detail is cheap; otherwise it shows the stats maximum with ≈. `HistoryRideCardText` gets an optional third line («ШИМ ≤ ~87 %»).
- Strings `history_total_max_duty` «Макс. ШИМ» / "Max PWM", `history_card_max_duty`.

**Migration verdict:** none. A new TEXT `metricId` value needs no schema change, and the totals are read-time. Rides recorded before this task cannot be told apart and show **unmarked**: we do not know, and that is honest. Wheels whose own duty reaches 105 % (Sherman/SV field weakening, rider): no upper bound is applied, and the clamp in the decoder is unchanged.
- [ ] Failing tests:
  - NEVER_REPORTED + estimate 70.6 → the observation has `DUTY_ESTIMATED = 70.6` and **no** `DUTY`.
  - NEVER_REPORTED without an estimate → `DUTY_ESTIMATED` unknown.
  - REPORTS → raw `DUTY` untouched.
  - The registry resolves the new id; `HistoryTelemetryAdapter` records it.
  - Totals: a ride with `duty_estimated_percent` peaking at 87 → MAX_DUTY 87, estimated; with `duty_percent` 92 → 92, not estimated; an old ride with only `duty_percent` → not estimated.
  - `HistoryCardStatsComponent`: visible rides load once and are cached; a failure retries; the component test injects a test dispatcher and destroys its lifecycle before `resetMain`.
- [ ] Implement; suite; commit `feat(history): peak PWM in the ride summary and on the ride card; the estimate is its own metric, marked «~»`.

### Task 12: The counter gate for the other protocols' counters
**Why:** A torn counter is a firmware property, and a checksum computed over a torn value is valid (spec §1.2). The Veteran trip re-baselines to 0 on any backward step (`VeteranProtocol.kt:282-288`), which is exactly the T4-reset failure. VESC's `coerceAtLeast(0)` sits at 0 after a controller reboot until it passes the old baseline (`VescProtocol.kt:230-237`).
**Files:** `KingSongProtocol.kt` (`kingSongDistance`, `:429`), `VeteranProtocol.kt` (`:256`, `:282-288`), `VescProtocol.kt` (`:230-237`, both the live and the observed trip), `VescGatewayProtocol.kt`, `NinebotProtocol.kt`, `InMotionProtocol.kt` trip/odometer paths; tests per protocol.
**Rules:**
- Each protocol's session trip = `CounterStepGate(monotone)` sum, using the one-frame lookahead (no witness exists in these frames).
- Rejections go through the Task 4 ledger. Facts are emitted only where the protocol already emits facts.
- No magnitudes.
- **Port only where the counter is decoded today.** If a protocol has no trip/odometer, write nothing for it.

- [ ] Failing tests (one per protocol, from that protocol's existing test frames with the counter bytes edited):
  - a +65 536 m needle (or the protocol's unit equivalent) between in-step frames is rejected and the trip is unaffected;
  - a persistent backward reset (VESC: `tachometer_abs` reset after reboot) re-baselines after 3 frames and the trip keeps growing from where it was, instead of sitting at 0;
  - in-step frames are unchanged from today.
- [ ] Implement; suite; commit `fix(protocols): session trips are sums of plausible counter increments`.

---

## Open questions for the rider (ask; do not build blind)
1. **R4:** should a duty/speed **alarm** wait one frame (~310 ms) to confirm a jump? Spec §5.3 argues yes (a phantom 100 % at speed is itself a hazard). The ruling leaves it out until the rider says so.
2. **T4 duty:** in dump (3) the 0x07 duty field is non-zero and rises to 100 % at ~80 km/h, and live bytes 14..15 changed `0x0080 → 0x08A0`, unlike dumps (1)/(2). What changed on the wheel between 26 and 27 October? This decides whether the T4 keeps the estimate (Task 7 only stops a single corrupt frame from deciding it).
3. The «−39 %» ride: open its SoC chart. Does the minimum sit on a current spike? A one-minute check of Task 5's diagnosis.
4. When the T4 next resets its odometer, export the BLE dump. The Task 8 ring will hold the `REBASED` frames: the first measurement of the reset.
