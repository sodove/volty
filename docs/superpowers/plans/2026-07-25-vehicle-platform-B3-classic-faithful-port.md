# Part B3 — Classic dashboard: a faithful port of VESC Tool's gauge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Classic renderer shipped in Part B2 with a faithful port of VESC Tool's own `CustomGauge`. The B2 version is unreadable on a device — the product owner's verdict was blunt and correct. This is a rewrite of the drawing, not a tuning pass.

**Why the B2 version failed.** Its acceptance criterion was self-defined and narrow ("tick numbers must not collide with the centre block"). It passed that and still looked broken, because the things that actually make VESC's dial legible were never specified: the needle is a short blade at the rim (ours ran from the centre through the readout), the value is `0.3×R` (ours was `0.15×R`), the face is the theme's dark background so overlapping dials merge into one cluster (ours drew light grey plates on a light background, so eight separate discs floated apart), and VESC has no red danger wedge at all (we invented one).

**The reference is the source, not a screenshot.** Read it directly:
- `C:\Users\sodovaya\Desktop\Software\vesc_tool_free_windows\vesc_tool-master\mobile\CustomGauge.qml` — the gauge itself, 463 lines, every constant below is from it.
- `C:\Users\sodovaya\Desktop\Software\vesc_tool_free_windows\vesc_tool-master\mobile\RtDataSetup.qml` — the eight-gauge cluster we mirror. **This is the screen, not `RtData.qml`** (that one is a different 2×2 grid — do not use it).
- Second opinion, already a Kotlin/Compose port of the same Qt component:
  `C:\Users\sodovaya\Desktop\kelly\kelly-connect\composeApp\src\commonMain\kotlin\com\kelly\app\presentation\dashboard\DashboardScreen.kt` (`VescGauge`, ~line 373). Useful for Compose idiom; **it is not authoritative** — where it differs from the QML, the QML wins.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform (Android target), Material 3.

## Global Constraints

- Branch `feat/classic-faithful-port`, off `main` **after Part G1 merges**.
- Package root `ru.sodovaya.volty`; tests `kotlin.test`. The Classic renderer lives in `presentation/ride/` and `presentation/ride/gauge/`.
- **The Clean renderer must not change at all.** It is signed off. Every existing test stays green.
- **This repo has no Compose UI test harness.** Do not add one. All geometry goes in pure, tested functions; the Canvas layer is compile-verified plus a device pass. Never claim UI coverage.
- **Colour rule, and it is the one B2 got wrong.** VESC's face is `darkBackground` in dark mode and `normalBackground` in light — i.e. *the theme's own background*, which is why its dials merge with the screen instead of sitting on it as plates. Do the same: face = `MaterialTheme.colorScheme.surface`, bezel from `surfaceVariant`/`surface`, ticks from `onSurface`/`onSurfaceVariant`. Material You enters through **`nibColor` (the needle) and its trace glow only.** Do not tint the face.
- **No red danger wedge.** VESC has none. Severity is expressed by recolouring the needle, per the thresholds in §4. Delete `dangerSweep` usage from the Classic path.
- `./gradlew :composeApp:testDebugUnitTest` and `:composeApp:compileDebugKotlinAndroid` must pass before each commit. Commit after every task.

## The numbers (all from `CustomGauge.qml`, `R` = outerRadius)

| Element | Value |
|---|---|
| Face | circle of radius `R`, theme background colour |
| Bezel | two arcs, stroke `0.035R`, at radii `R − 0.0175R` and `R − 0.0525R + 1`, diagonal linear gradients between `lightestBackground` and `darkBackground`, gradient stops mirrored between the two rings |
| Major tick | `0.02R` wide × `0.1R` long, outer end at `R − 0.07R` |
| Minor tick | `0.015R` wide × `0.07R` long, same outer end, **4 between each pair of majors** |
| Tick colour | passed by the needle → `lightText`; not yet → `disabledText` |
| Tick label | font `0.12R`, centred at radius `R − 0.34R` |
| Value | font `0.3R`, bold, centred on the dial centre |
| Type caption | font `0.12R`, ALL CAPS, anchored to the **top edge** of the value, growing upward; supports `\n` |
| Unit | font `0.12R`, ALL CAPS, anchored to the **bottom edge** of the value |
| **Needle** | a blade `0.22R` tall × `0.12R` wide whose **top edge sits at `0.05R` from the rim**; rotation pivot is the dial centre. It does **not** reach the centre — that is why nothing covers the readout and why no hub cap is needed |
| Needle fill | two mirrored quadratic paths, linear gradients ending white at the outer edge |
| Trace glow | arc at radius `R − 0.12R`, stroke `0.2R`, from `angleFor(0)` to the needle angle, radial gradient transparent → `lighter(nibColor, 1.5)`; drawn the short way round when the value is negative |
| Value animation | ease-out-circ, 100 ms |

**Draw order** (`CustomGauge.qml` z-values): face → ticks → tick labels → centre text → needle (`z:2`) → trace + bezel (`z:3`). The needle is above the text but never overlaps it, because of where the blade sits.

## Per-gauge configuration (from `RtDataSetup.qml`)

| Gauge | min…max | minAngle…maxAngle | labelStep | unit | caption |
|---|---|---|---|---|---|
| Current | −60…60 | −210…15 | 10 (20 if max>60) | A | CURRENT |
| Duty | −100…100 | 210…−15 (mirrored) | 25 | % | DUTY |
| Power | −10000…10000 | −140…140 (default) | 1000 (2000 if max>6000), `tickmarkScale 0.001`, suffix `k` | W | POWER |
| Speed | 0…60 | −225…45 | 10 (20 if max>60) | km/h \| mph | SPEED |
| Battery | 0…100 | −225…45 | — | — | `centerTextVisible = false`, custom overlay (see §4) |
| ESC temp | 0…100 | −195…30 | 20 | °C | TEMP\nESC |
| Motor temp | 0…100 | 195…−30 (mirrored) | 20 | °C | TEMP\nMOTOR |
| Consumption | −50…50 | −127…127 | 10 (20 if max>60) | Wh/km \| Wh/mi | CONSUMP. |

**Mirroring is real**: Duty and Motor-temp have `minAngle > maxAngle`, so they sweep the other way and their labels run counter-clockwise. `isInverted` in the QML exists for this. Keep it.

## Cluster composition (from `RtDataSetup.qml`)

Two sizes: `g` (speed, the big one) and `g2` (everything else). Offsets are relative to the parent gauge, in units of `g2`:

- **Top trio**, container height `1.1 × g2`: Current at `(−0.675, +0.1)`; Duty nested on Current at `(+1.35, 0)`; Power nested on Duty at `(−0.675, −0.1)`, size `1.05 × g2`.
- **Middle**: Speed at size `g`, x-offset `(g/4 − g2)/2`; Battery at size `g2`, x-offset `g/4 + g2/2`.
- **Bottom trio**, container height `1.1 × g2`, **vertical offsets inverted vs the top**: ESC at `(−0.675, −0.1)`; Motor nested at `(+1.35, 0)`; Consumption nested at `(−0.675, +0.1)`, size `1.05 × g2`.

## Colour thresholds for `nibColor` (from `RtDataSetup.qml`)

- ESC / Motor temp: `> 70` red, `> 40` orange, else the accent. Animated between colours over 1000 ms (`InOutSine`).
- Consumption: `> 45` red, `> 25` orange, else accent.
- Battery: `> 50` green, `> 20` orange, else red.
- Current / Duty / Power / Speed: fixed accents.

Map "red"/"orange"/"accent" onto `MaterialTheme.colorScheme.error` / a warning colour / `primary`-family, keeping our existing `DutyBands`/`TempBands` as the single source of the *thresholds* where they already agree. Where our bands and VESC's numbers disagree, **our bands win** — they are shared with Part F's alarms.

---

### Task 1: `VescDialGeometry` — the pure core, rewritten to the QML

**Files:** create `presentation/ride/gauge/VescDialGeometry.kt`; test `commonTest/.../gauge/VescDialGeometryTest.kt`.

Replace `DialGeometry`'s assumptions (fixed 135°/270°, danger sweep) with the QML's model: arbitrary `minAngle`/`maxAngle` including **inverted** ranges, `valueToAngle`, `isCovered(value)`, major/minor tick angles and values, label positions at `R − 0.34R`, and the needle's rotation angle.

- [ ] **Step 1: Write the failing test** — pin against values computed by hand from the QML: a normal range (Speed −225…45), an inverted range (Duty 210…−15), `isCovered` on both signs (the QML's rule: for a positive value, ticks in `0…value` are covered; for negative, `value…0`), the 4-minors-between-majors sequence, and clamping outside min/max.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Prove non-vacuity** — invert one angle range and show the mirrored-gauge tests fail; restore. Record the output.
- [ ] **Step 4: Tests + compile.**
- [ ] **Step 5: Commit** — `feat(classic): dial geometry ported from VESC CustomGauge`

---

### Task 2: `VescDialGauge` — the Canvas, drawn in the QML's order

**Files:** create `presentation/ride/gauge/VescDialGauge.kt`.

Face, bezel rings, ticks with covered/uncovered colouring, tick labels, centre text stack (caption / value / unit), the rim needle, the trace glow. Draw order exactly as §"Draw order". `centerTextVisible` parameter for the Battery gauge.

- [ ] **Step 1: Implement**, compile-verified. Every dimension expressed as a fraction of radius — no fixed `sp`, no fixed `dp`. That was B2's other structural mistake.
- [ ] **Step 2: Write the geometry off to Task 1** — this file should contain no arithmetic that a test could have checked. If you find yourself computing positions here, move them.
- [ ] **Step 3: Compile + full suite.**
- [ ] **Step 4: Commit** — `feat(classic): VESC-faithful dial canvas`

---

### Task 3: The cluster

**Files:** rewrite `presentation/ride/gauge/ClusterLayout.kt` (or replace it) to the nesting/offset model in §"Cluster composition".

Note this is *nested* positioning in the original — each gauge is anchored relative to its parent gauge, not to a shared grid. Reproduce the resulting geometry; you may flatten it into computed absolute offsets if that is cleaner in Compose, but the rendered result must match the offsets given.

- [ ] **Step 1: Failing test** on the pure placement function: the eight positions and sizes for a given container, including the inverted vertical offsets on the bottom trio.
- [ ] **Step 2: Implement.** — [ ] **Step 3: Tests + compile.** — [ ] **Step 4: Commit** — `feat(classic): cluster geometry matching RtDataSetup`

---

### Task 4: Bind live state, retire the B2 renderer

**Files:** rewrite `presentation/ride/ClassicDialSpecs.kt` + `ClassicRideCluster.kt`; delete `DialGeometry.kt`/`DialGauge.kt` and their tests once nothing references them.

Per-gauge config from §"Per-gauge configuration", `nibColor` thresholds from §"Colour thresholds". Keep the localized labels (B2's one good outcome) and keep `SecondaryGauge` emphasis working. The hero's runtime max and its round-tick divisor logic survive from B2 — do not regress imperial.

- [ ] **Step 1: Failing tests** for the spec table and the `nibColor` thresholds.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Confirm the deletions are safe** — no references left, suite green.
- [ ] **Step 4: Commit** — `feat(classic): bind the faithful cluster, retire the B2 renderer`

---

### Task 5: Device pass — and this time the criterion is legibility

**Files:** none expected.

- [ ] **Step 1:** Full suite + `assembleDebug`.
- [ ] **Step 2:** Install on an emulator, Classic style, demo vehicle, **light and dark**. Screenshot every state.
- [ ] **Step 3: Compare against the original side by side.** The product owner's screenshot of VESC Tool's RT DATA screen is the target. Report, per gauge: is the value legible at a glance; does the needle read as a blade at the rim; do the eight dials read as one cluster or as separate discs; is there any place a needle or tick crosses text.
- [ ] **Step 4:** State plainly anything that still looks wrong. **A narrow pass is what shipped B2 — do not repeat it.** "No collisions" is not the criterion; "looks like the reference" is.

---

## Self-Review

- [ ] Face colour comes from the theme background, so overlapping dials merge — not tinted plates.
- [ ] Needle is a rim blade; it cannot reach the centre readout.
- [ ] Value font is `0.3R`.
- [ ] No red danger wedge anywhere in the Classic path.
- [ ] Ticks light up as the needle passes them.
- [ ] Mirrored gauges (Duty, Motor temp) sweep the correct way.
- [ ] Clean renderer byte-identical.
- [ ] No fixed `sp`/`dp` in the gauge — everything is a fraction of radius.
