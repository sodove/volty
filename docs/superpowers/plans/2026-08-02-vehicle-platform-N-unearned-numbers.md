# Part N — Numbers A Connection Has Not Earned: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nothing is displayed, and nothing is alarmed on, until the thing behind it has
actually reported. Today the first six seconds of every Begode connection show a state of
charge that is **half the truth**, and the alarm engine believes it.

**Architecture:** One reported symptom, four sites. A pack publishes a placeholder as a
measurement; the profile can never give that pack what it needs to be estimated; the aggregate
folds the placeholder into a mean; and the dashboard keeps the previous session's numbers on
screen through the reconnect gap. Each is fixed where it lives, and the alarm consequence is
pinned at every one of them.

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform, Decompose, Koin,
Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

**Evidence base:** the rider on a Begode ET Max — *"при реконнекте (колесо перезапустили) оно
показывало что в батке 40%, после переподключение аппа уже нормальные ~80% на 161В"* — and the
trace behind it, summarised below.

---

## The mechanism, with its arithmetic

This is not a plausible story; it predicts the number.

`BegodeProtocol.rebuild` publishes a branch as a complete `BmsData` the moment its `0x01`
telemetry frame lands — **seconds before any cell frame** — and that sample carries `soc = 0f`
with `socKnown = true`, both by data-class default. The SoC estimator cannot rescue it: its
no-cells fallback needs a per-pack `cellCount`, and **branch 1 never has one**, because the
auto-fill writes `cellCount` to index 0 only and the synthesised branch-1 slot is built without
one. `PackAggregator` then takes a plain mean of a real branch and a fabricated zero.

```
truth after reconnect   161 V / 40 cells = 4.025 V      → (4.025−3.30)/0.90 = 80.6 %   ✔ matches "~80 % at 161 В"

during the window
  branch 0  frame-field voltage ≈ 161/1.009 = 159.6 V   → 76.6 %   (the 0.1009-vs-0.1 scale)
  branch 1  cellCount null, sample untouched            →  0 %
  aggregate mean                                        → 38.3 %   → renders "38 %"
```

**38 % predicted, ~40 % reported by eye.** Replayed against the real 13-second ET Max capture,
the window is **≈5.2 s of halved SoC**, then ~1.8 s of a mildly wrong one — because the BMS
boots with all-zero cell payloads and cell packets then arrive one per second, five per branch,
branches alternating.

### Why this is not cosmetic

- **It fires on every connect and every reconnect**, not on the one the rider happened to watch.
- **The alarm engine believes it.** SoC alerts are gated on `socKnown`, which the aggregate
  reports as `true`. With the default low threshold, a rider connecting at a true 29 % gets a
  spurious low-charge alarm every single time; a rider with a cutoff configured gets "stop now"
  on a half-full pack.
- **On a wheel's first-ever connection it is worse.** Before the cell-count auto-fill has run,
  pack 0 has no count either, both branches return untouched, and the aggregate is a confident
  **`0 %` on a full pack.**
- **A lost packet costs a whole cycle.** `contiguousCells` needs cell 0 specifically, this
  format has no checksum, and at the default MTU every frame straddles two notifications — so
  one dropped packet-0 extends the window by a full ~10 s branch cycle.

### The contrast that settles the design

Fifty lines above the offending code, the **synthetic** no-BMS pack sets `socKnown = false`
explicitly and gets this exactly right. The fallback path is honest; the real path is the one
that lies. And on the motion side, `dutyPercent` and `inputVoltageV` both travel with
`has*` flags that consumers honour — **`socKnown` was built for precisely this, and the branch
decode simply does not use it.**

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.** Every mutation killed by some
   assertion, **and** every assertion killable by some implementation.
2. **A zero-failure mutation run is not evidence unless the build compiled *and* the test count
   is non-zero and exactly right.** Reuse the audited harness at
   `.superpowers/sdd/2026-07-30-vehicle-platform-I-field-fixes/t11sweep.ps1`.
3. **Sweep your own additions.**
4. **A fixture where every contributor is complete cannot see an incompleteness bug.** **This
   part exists because of exactly that.** The test that should have caught this feeds *both*
   branches full cell lists, so both take the cell path and neither halves anything. The
   empty-cell window it never exercises is the defect. Every task here needs a fixture with a
   branch that has reported telemetry and **no cells**.
5. **Never write to Begode's FFE1 characteristic.**
6. **The battery path must not change behaviour** except where a task names it — and this whole
   part is the battery path, so each task must say exactly what it changes and prove the rest
   is untouched.
7. **`runTest` hazard:** an unbounded delayed loop wedges the build instead of failing.
8. **Compose UI is not unit-testable here.**
9. Russian UI strings in **both** `values/` and `values-ru/`.
10. Run `.\gradlew.bat :composeApp:testDebugUnitTest` green before every commit.

---

### Task 1 — a branch with no cells does not know its charge

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/BegodeProtocol.kt`
  (`rebuild`)
- Test: `BegodeProtocolTest`

**The fix is one field, and the argument for it is already written in the same file** — the
synthetic pack fifty lines up sets `socKnown = false` for the same reason.

- [ ] **Step 1: Write the failing tests.** A branch that has reported telemetry but no cells
      publishes `socKnown = false`; the same branch once its cells complete publishes
      `socKnown = true`; **the deliberately incoherent case** — a branch carrying a non-zero
      `soc` with no cells — still reports unknown, so the flag is proven to follow the *cells*
      rather than the value.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(begode): a branch that has produced no cells does not know its charge"
```

---

### Task 2 — the second branch can never be estimated, and nothing said so

**Files:**
- Modify: `domain/model/Vehicle.kt` (`withCellCount`), `domain/model/Pack.kt` (the synthesised
  branch slot)
- Test: the vehicle-model tests, `VoltageSocEstimator` tests

**Why this is separate from Task 1.** Task 1 stops the lie. This removes the reason it was
reachable: the auto-fill writes a derived cell count to **index 0 only**, so branch 1's
`cellCount` is `null` permanently, on every launch, forever — and the estimator's no-cells
fallback therefore returns its input untouched **for that pack and no other**. Even with Task 1
in place, branch 1 would sit at "unknown" for the whole window instead of being estimated from
its own voltage, which it could be.

**Ruling — the derived count applies to every branch of the same wheel, and that needs saying
where it is written.** A Begode's branches are the same pack split in two; a count derived from
one is the count of the other. That is a claim about the hardware, so it goes in the code as a
sentence, not as an unremarked loop bound.

- [ ] **Step 1: Write the failing tests.** A derived count reaches **every** branch slot, not
      index 0; a branch with a count and a voltage but no cells is estimated rather than left
      unknown; a rider's typed count reaches every branch the same way; a vehicle whose packs
      are genuinely different (not a wheel's two branches) does not get one pack's count
      applied to another.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(vehicle): a wheel's second branch never got the cell count it needed"
```

---

### Task 3 — an unknown pack must not be averaged as if it were a measurement

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/PackAggregator.kt`
- Test: `PackAggregatorTest`

**Why, even after Tasks 1 and 2.** Those two fix the producer for one wheel on one path. This
fixes the fold for every producer that will ever exist. The KDoc **already names this hazard** —
"a single unknown pack pollutes the number under both topologies" — and then relies on
`socKnown` to catch it, which the branch decode defeated. A guard whose safety depends on every
upstream producer being correct is the guard this project keeps finding broken.

**Ruling — filter, do not blank.** Fold the SoC over the packs that *know* theirs, exactly as
Part I Task 7 did for voltage and duty on the motion side, and report the aggregate as unknown
only when **no** pack knows. A vehicle where one of two branches is still booting has a
perfectly good state of charge from the other one; blanking the whole vehicle would replace a
wrong number with a needless blank.

**And the series case is not the same question.** Under `SERIES`, charge, capacity and SoC are
taken as the **minimum**, because a string delivers no more than its weakest pack — an unknown
pack there must not be treated as a zero-charge weakest link. Handle both topologies explicitly
and say why they differ.

- [ ] **Step 1: Write the failing tests.** Parallel: a known 80 % beside an unknown pack
      aggregates to 80 %, not 40; all-unknown aggregates to unknown, not 0. Series: an unknown
      pack does not become the minimum. Both: the aggregate's `socKnown` follows the *inputs*,
      not the arithmetic. Use the incoherent fixture — an unknown pack carrying a non-zero
      `soc` — so a fix that merely filters zeros cannot pass.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Pin the alarm consequence at this level**, because it is the part that matters
      most and no test at the aggregator currently reaches it: a vehicle whose aggregate SoC is
      unknown must not raise a low-charge alarm, and one whose known branch reads 80 % must not
      raise one either.
- [ ] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "fix(packs): one booting branch halved the whole vehicle's charge"
```

---

### Task 4 — the reconnect gap shows a dead session's numbers as live

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt`
  (`rebuildPipelineLocked`)
- Test: the repository tests

**Why.** The pipeline rebuild does **not** clear the active vehicle data, so through the whole
reconnect gap the dashboard displays the previous session's last sample as though it were
current. The explicit clears exist on user disconnect and on device switch — this path simply
missed them.

On the reported ride that stale value was the *correct* 80 %, which is why it did not show up
as the complaint. That is luck, not design: after a ride it would be the charge from before the
wheel was restarted, indefinitely, with nothing marking it stale.

**Ruling — clear, do not freeze-and-flag.** The orchestrator already has a staleness sweep and
an offline presentation for packs; adding a second concept ("shown but old") would be a third
spelling of an idea that already has two. Clear it and let the existing offline path own the
gap.

- [ ] **Step 1: Write the failing tests.** A reconnect clears the active data; the dashboard
      during the gap has nothing to render rather than something wrong; a *successful* reconnect
      repopulates it; the paths that already clear explicitly are unchanged.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(ble): a rebuilt pipeline kept the dead session's numbers on screen"
```

---

## The observation that confirms the diagnosis before any of this is built

Power-cycle the wheel, reconnect, and open the **per-pack view within the first ten seconds**.
The prediction is specific and falsifiable:

- **Pack 2 reads exactly `0 %` with an empty cell grid**, while Pack 1 reads ~76 % with an empty
  grid;
- the vehicle tile reads ~38 %;
- about six seconds in, Pack 2's grid fills eight cells at a time and its SoC jumps to ~80 %;
  about two seconds later Pack 1's grid fills and the vehicle tile settles at ~80 %.

**If Pack 2 shows `—` or a non-zero SoC instead, this diagnosis is wrong** and the tasks above
are aimed at the wrong thing.

## Out of scope

- **The ~0.9 % frame-field voltage error** (0.1009 V/unit decoded at 0.1), which costs ~4
  percentage points of SoC during the same window and is why the surviving branch reads 76 %
  rather than 80 %. Real and confirmed, but it is a scale question about one field, not an
  honesty question about a flag — and fixing it inside this part would let a scale change hide
  behind an honesty fix.
- **Marking a provisional voltage as provisional.** Same window, same cause; wants the same
  treatment the SoC gets here, but it is a second contract and this part is already four tasks.
