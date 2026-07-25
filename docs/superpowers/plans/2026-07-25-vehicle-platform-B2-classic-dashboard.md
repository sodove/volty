# Part B2 — Classic VESC dashboard renderer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the second Ride renderer — the **Classic VESC** skeuomorphic dial cluster — so a rider can pick it per vehicle and get the familiar VESC-tool instrument panel, drawn in Material You colours instead of the original grey/white.

**Architecture:** A pure geometry layer (`DialGeometry`) computes tick, label, needle and arc positions and is fully unit-tested; a `DialGauge` Canvas composable renders from it; a `ClusterLayout` custom `Layout` positions eight dials in the overlapping fan/nest composition, scaling to available width; `ClassicRideCluster` binds live state to those dials. `RideDashboardScreen` picks the renderer off `state.style`, which is already plumbed and persisted.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform (Android target), Material 3.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-24-vehicle-platform/B-vesc-dashboard.md` §7 (design **locked and signed off**). Visual intent: `docs/design/ride-dashboard-mockup.html`, the **"Classic VESC"** view — directional only; **do not port its SVG math**, which is a static hand-authored approximation. The mockup's own known flaw is exactly what this part must beat: numbers colliding with the centre readout.
- Branch `feat/classic-dashboard`, off `main` (B1 merged at `147958d`).
- Package root `ru.sodovaya.volty`; tests `kotlin.test`. New UI under `presentation/ride/gauge/`.
- **The Clean renderer must not change behaviourally.** Every existing test stays green (481 at branch point).
- **Material You only** — every colour comes from `MaterialTheme.colorScheme` or is passed in by the caller. No hardcoded hex, no grey/white VESC palette. Semantic colour (`DutyLevel` → primary/tertiary/error) stays reserved for safety metrics, exactly as the Clean renderer does it.
- **Reuse, don't fork:** `DutyBands`/`TempBands` for severity and danger zones, `UnitFormatter` for speed/distance, `RideDashboardComponent.State` as the only data source. No new thresholds, no new state.
- This repo has **no Compose UI test harness** (the picker and Clean screens are in the same position). Therefore: all non-trivial maths lives in pure functions with real tests; composables are compile-verified plus a device check in the final task.
- `./gradlew :composeApp:testDebugUnitTest` and `./gradlew :composeApp:compileDebugKotlinAndroid` must pass before each commit.
- Commit after every task with the message shown in its final step.

## File Structure

**New:**
- `presentation/ride/gauge/DialGeometry.kt` — pure geometry + label layout (the testable core).
- `presentation/ride/gauge/DialGauge.kt` — the Canvas dial composable.
- `presentation/ride/gauge/ClusterLayout.kt` — custom `Layout` for the overlapping cluster.
- `presentation/ride/ClassicRideCluster.kt` — binds `State` to the eight dials.
- Tests under `commonTest/.../presentation/ride/gauge/`.

**Modified:**
- `presentation/ride/RideDashboardScreen.kt` — switch on `state.style`; Graph link restyle (Task 6).
- `presentation/settings/SettingsScreen.kt`, `presentation/vehicle/VehicleEditScreen.kt` — drop the "coming soon" captions.
- `composeResources/values/strings.xml` + `values-ru/strings.xml`.

---

### Task 1: DialGeometry — the pure core

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/gauge/DialGeometry.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/gauge/DialGeometryTest.kt`

**Interfaces:**
- Produces: `data class DialScale(val min: Float, val max: Float, val majorTicks: Int, val minorPerMajor: Int = 4)`; `object DialGeometry` with `fun angleFor(value: Float, scale: DialScale): Float`, `fun fraction(value: Float, scale: DialScale): Float`, `fun majorValues(scale: DialScale): List<Float>`, `fun tickAngles(scale: DialScale): List<TickAngle>`, `data class TickAngle(val degrees: Float, val isMajor: Boolean)`, `fun dangerSweep(from: Float, scale: DialScale): Pair<Float, Float>?` (start angle + sweep, null when `from` is outside the scale).
- Constants: `START_ANGLE = 135f`, `SWEEP = 270f` (same 270° dial opening the Clean gauge uses).

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialGeometryTest {

    private val zeroToHundred = DialScale(min = 0f, max = 100f, majorTicks = 5)
    private val bipolar = DialScale(min = -60f, max = 60f, majorTicks = 6)

    @Test fun the_scale_minimum_sits_at_the_dial_opening() {
        assertEquals(DialGeometry.START_ANGLE, DialGeometry.angleFor(0f, zeroToHundred))
    }

    @Test fun the_scale_maximum_sits_at_the_end_of_the_sweep() {
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP, DialGeometry.angleFor(100f, zeroToHundred))
    }

    @Test fun the_midpoint_sits_halfway_round() {
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP / 2f, DialGeometry.angleFor(50f, zeroToHundred))
    }

    @Test fun a_bipolar_scale_puts_zero_in_the_middle() {
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP / 2f, DialGeometry.angleFor(0f, bipolar))
    }

    @Test fun values_beyond_the_scale_are_clamped_to_the_dial() {
        assertEquals(DialGeometry.START_ANGLE, DialGeometry.angleFor(-999f, zeroToHundred))
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP, DialGeometry.angleFor(999f, zeroToHundred))
        assertEquals(0f, DialGeometry.fraction(-999f, zeroToHundred))
        assertEquals(1f, DialGeometry.fraction(999f, zeroToHundred))
    }

    @Test fun a_degenerate_scale_does_not_divide_by_zero() {
        val flat = DialScale(min = 5f, max = 5f, majorTicks = 2)
        assertEquals(0f, DialGeometry.fraction(5f, flat))
        assertTrue(DialGeometry.angleFor(5f, flat).isFinite())
    }

    @Test fun major_values_span_the_scale_inclusive() {
        val majors = DialGeometry.majorValues(zeroToHundred)
        assertEquals(6, majors.size)               // majorTicks = 5 ⇒ 6 labelled values
        assertEquals(0f, majors.first())
        assertEquals(100f, majors.last())
        assertTrue(abs(majors[1] - 20f) < 0.001f)
    }

    @Test fun tick_angles_include_minors_and_flag_the_majors() {
        val ticks = DialGeometry.tickAngles(zeroToHundred)
        assertEquals(5 * 4 + 1, ticks.size)        // majors × minorPerMajor + 1
        assertTrue(ticks.first().isMajor)
        assertTrue(ticks.last().isMajor)
        assertEquals(6, ticks.count { it.isMajor })
        assertEquals(DialGeometry.START_ANGLE, ticks.first().degrees)
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP, ticks.last().degrees)
    }

    @Test fun tick_angles_increase_monotonically() {
        val degrees = DialGeometry.tickAngles(bipolar).map { it.degrees }
        assertEquals(degrees.sorted(), degrees)
    }

    @Test fun the_danger_band_runs_from_its_threshold_to_the_scale_end() {
        val (start, sweep) = DialGeometry.dangerSweep(from = 90f, scale = zeroToHundred)!!
        assertEquals(DialGeometry.angleFor(90f, zeroToHundred), start)
        assertTrue(abs(sweep - DialGeometry.SWEEP * 0.10f) < 0.01f)
    }

    @Test fun a_danger_threshold_above_the_scale_yields_no_band() {
        assertNull(DialGeometry.dangerSweep(from = 150f, scale = zeroToHundred))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.gauge.DialGeometryTest"`
Expected: FAIL — unresolved `DialGeometry`, `DialScale`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package ru.sodovaya.volty.presentation.ride.gauge

/**
 * One dial's value range and tick density.
 *
 * [majorTicks] is the number of INTERVALS, so a scale with `majorTicks = 5`
 * carries six labelled values (both ends inclusive).
 */
data class DialScale(
    val min: Float,
    val max: Float,
    val majorTicks: Int,
    val minorPerMajor: Int = 4
)

/** One tick's angular position, and whether it is a labelled major. */
data class TickAngle(val degrees: Float, val isMajor: Boolean)

/**
 * Pure geometry for the Classic dial. Everything the Canvas needs to draw a
 * dial is computed here so it can be tested without a UI harness — the
 * hand-authored mockup's numbers collided with its centre readout precisely
 * because that arithmetic lived inline in the drawing code.
 *
 * The dial opens at [START_ANGLE] and sweeps [SWEEP] degrees clockwise, the
 * same 270° opening the Clean renderer's RadialGauge uses, so the two styles
 * read as the same instrument.
 */
object DialGeometry {

    const val START_ANGLE: Float = 135f
    const val SWEEP: Float = 270f

    /** Position of [value] on the dial as 0..1, clamped. Degenerate scales yield 0. */
    fun fraction(value: Float, scale: DialScale): Float {
        val span = scale.max - scale.min
        if (span <= 0f) return 0f
        return ((value - scale.min) / span).coerceIn(0f, 1f)
    }

    fun angleFor(value: Float, scale: DialScale): Float =
        START_ANGLE + SWEEP * fraction(value, scale)

    /** The labelled values, both ends inclusive. */
    fun majorValues(scale: DialScale): List<Float> {
        val steps = scale.majorTicks.coerceAtLeast(1)
        val span = scale.max - scale.min
        return (0..steps).map { i -> scale.min + span * i / steps }
    }

    /** Every tick around the dial, majors flagged. */
    fun tickAngles(scale: DialScale): List<TickAngle> {
        val majors = scale.majorTicks.coerceAtLeast(1)
        val minors = scale.minorPerMajor.coerceAtLeast(1)
        val total = majors * minors
        return (0..total).map { i ->
            TickAngle(
                degrees = START_ANGLE + SWEEP * i / total,
                isMajor = i % minors == 0
            )
        }
    }

    /**
     * Start angle and sweep of the red band running from [from] to the top of
     * the scale, or null when the threshold sits outside the dial (a gauge
     * whose scale never reaches its danger level shows no band rather than a
     * misleading zero-width one).
     */
    fun dangerSweep(from: Float, scale: DialScale): Pair<Float, Float>? {
        if (from >= scale.max || from < scale.min) return null
        val start = angleFor(from, scale)
        val end = angleFor(scale.max, scale)
        return start to (end - start)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.gauge.DialGeometryTest"`
Expected: PASS (11/11)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/gauge/DialGeometry.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/gauge/DialGeometryTest.kt
git commit -m "feat(classic): pure dial geometry — angles, ticks, danger bands"
```

---

### Task 2: DialGauge composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/gauge/DialGauge.kt`

**Interfaces:**
- Consumes: `DialGeometry`, `DialScale`, `TickAngle`.
- Produces: `data class DialColors(val face: Color, val rim: Color, val tickMinor: Color, val tickMajor: Color, val label: Color, val value: Color, val needle: Color, val arc: Color, val danger: Color)`; `@Composable fun rememberDialColors(accent: Color = MaterialTheme.colorScheme.primary): DialColors`; `@Composable fun DialGauge(value: Float, scale: DialScale, label: String, unit: String, valueText: String, modifier: Modifier = Modifier, colors: DialColors = rememberDialColors(), dangerFrom: Float? = null, showValueArc: Boolean = true)`.

- [ ] **Step 1: Build it** (no unit test — Canvas; the maths it depends on is covered by Task 1, and Task 7 does the device check)

Draw order, all sized from `size.minDimension` so the dial scales with its box:
1. **Face** — radial-ish depth using two concentric `drawCircle`s (a `face` fill plus a slightly inset `rim` stroke). No hardcoded colours: everything from `DialColors`.
2. **Value arc** — a thin `drawArc` from the scale minimum to the current value, `colors.arc`, `StrokeCap.Round`, drawn only when `showValueArc`.
3. **Danger band** — `DialGeometry.dangerSweep(dangerFrom, scale)?.let { (start, sweep) -> drawArc(colors.danger, start, sweep, …) }`.
4. **Ticks** — `DialGeometry.tickAngles(scale)`, majors longer/thicker in `colors.tickMajor`, minors in `colors.tickMinor`. Compute endpoints with `cos`/`sin` off the dial centre.
5. **Numbers** — `DialGeometry.majorValues(scale)` rendered on a radius INSIDE the ticks, formatted with `formatFixed(v, if (abs(scale.max) < 10f) 1 else 0)` so a ±4 kW dial reads "1.0" not "1". Use `drawContext.canvas.nativeCanvas`-free Compose text: take a `TextMeasurer` (`rememberTextMeasurer()`) and `drawText`, centring each label on its computed point. **This is the mockup's failure mode — the numbers must sit on a radius that leaves the centre readout clear.**
6. **Needle** — a filled triangle from a small hub to `angleFor(value)`, in `colors.needle`; plus a hub `drawCircle`.
7. **Centre readout** — `label` (small, letter-spaced, `colors.label`), `valueText` (large, bold, `colors.value`), `unit` (small, `colors.label`), stacked and centred. Draw these with `TextMeasurer` too, and lay them out from measured heights rather than guessed offsets.

Guards (mirroring `RadialGauge`'s reviewed fix): if `size.minDimension <= 0f` or the computed dial radius is `<= 0f`, return without drawing.

Animate the needle with `animateFloatAsState(targetValue = DialGeometry.fraction(value, scale), tween(220))` and derive the drawn angle from the animated fraction, so the needle sweeps rather than jumps.

`rememberDialColors` maps Material You: `face` = `colorScheme.surfaceContainerHighest`, `rim` = `colorScheme.outlineVariant`, `tickMinor` = `colorScheme.outlineVariant`, `tickMajor` = `colorScheme.onSurfaceVariant`, `label` = `colorScheme.onSurfaceVariant`, `value` = `colorScheme.onSurface`, `needle`/`arc` = the passed `accent`, `danger` = `colorScheme.error`.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid` then `./gradlew :composeApp:testDebugUnitTest`.
Expected: BUILD SUCCESSFUL; suite unchanged and green.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/gauge/DialGauge.kt
git commit -m "feat(classic): Material You skeuomorphic dial gauge"
```

---

### Task 3: ClusterLayout — the overlapping composition

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/gauge/ClusterLayout.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/gauge/ClusterPlacementTest.kt`

**Interfaces:**
- Produces: `enum class ClusterSlot { TOP_LEFT, TOP_CENTRE, TOP_RIGHT, HERO, HERO_INSET, BOTTOM_LEFT, BOTTOM_CENTRE, BOTTOM_RIGHT }`; `data class SlotBox(val xFraction: Float, val yFraction: Float, val sizeFraction: Float, val zIndex: Float)`; `object ClusterPlacement { val slots: Map<ClusterSlot, SlotBox>; fun place(slot: ClusterSlot, width: Int, height: Int): IntRect }`; `@Composable fun ClusterLayout(modifier: Modifier = Modifier, content: @Composable ClusterScope.() -> Unit)` with `ClusterScope.slot(slot: ClusterSlot)` modifier.

> Placement is expressed in **fractions of the cluster's width**, never absolute px, so the cluster scales from a small phone to a tablet. `ClusterPlacement` is pure and tested; the `Layout` just applies it.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterPlacementTest {

    private val w = 1000
    private val h = 1400

    @Test fun every_slot_has_a_placement() {
        assertEquals(ClusterSlot.entries.size, ClusterPlacement.slots.size)
    }

    @Test fun the_hero_is_the_largest_dial() {
        val hero = ClusterPlacement.slots.getValue(ClusterSlot.HERO)
        val others = ClusterPlacement.slots.filterKeys { it != ClusterSlot.HERO }.values
        assertTrue(others.all { it.sizeFraction < hero.sizeFraction })
    }

    @Test fun the_centre_dials_sit_above_their_neighbours() {
        val slots = ClusterPlacement.slots
        assertTrue(slots.getValue(ClusterSlot.TOP_CENTRE).zIndex > slots.getValue(ClusterSlot.TOP_LEFT).zIndex)
        assertTrue(slots.getValue(ClusterSlot.TOP_CENTRE).zIndex > slots.getValue(ClusterSlot.TOP_RIGHT).zIndex)
        assertTrue(slots.getValue(ClusterSlot.BOTTOM_CENTRE).zIndex > slots.getValue(ClusterSlot.BOTTOM_LEFT).zIndex)
    }

    @Test fun the_battery_inset_overlaps_the_hero_and_sits_on_top_of_it() {
        val hero = ClusterPlacement.place(ClusterSlot.HERO, w, h)
        val inset = ClusterPlacement.place(ClusterSlot.HERO_INSET, w, h)
        assertTrue(inset.left < hero.right && inset.top < hero.bottom) { "inset must overlap the hero" }
        assertTrue(
            ClusterPlacement.slots.getValue(ClusterSlot.HERO_INSET).zIndex >
                ClusterPlacement.slots.getValue(ClusterSlot.HERO).zIndex
        )
    }

    @Test fun every_dial_stays_inside_the_cluster_bounds() {
        for (slot in ClusterSlot.entries) {
            val r = ClusterPlacement.place(slot, w, h)
            assertTrue(r.left >= 0 && r.top >= 0) { "$slot starts outside: $r" }
            assertTrue(r.right <= w) { "$slot overflows width: $r" }
            assertTrue(r.bottom <= h) { "$slot overflows height: $r" }
        }
    }

    @Test fun placement_scales_with_width() {
        val small = ClusterPlacement.place(ClusterSlot.HERO, 500, 700)
        val large = ClusterPlacement.place(ClusterSlot.HERO, 1000, 1400)
        assertEquals(small.width * 2, large.width)
    }

    @Test fun the_top_row_sits_above_the_hero_and_the_bottom_row_below_it() {
        val hero = ClusterPlacement.place(ClusterSlot.HERO, w, h)
        assertTrue(ClusterPlacement.place(ClusterSlot.TOP_CENTRE, w, h).top < hero.top)
        assertTrue(ClusterPlacement.place(ClusterSlot.BOTTOM_CENTRE, w, h).bottom > hero.bottom)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.gauge.ClusterPlacementTest"`
Expected: FAIL — unresolved `ClusterPlacement`, `ClusterSlot`.

- [ ] **Step 3: Write minimal implementation**

`ClusterPlacement` holds the fractions (tune so the tests above pass and the composition reads like the mockup's Classic view: a top fan of three with the centre one larger and forward, a hero speed dial with a battery dial overlapping its lower-right, and a bottom fan of three). Suggested starting fractions, adjust until the bounds test passes:

```kotlin
val slots: Map<ClusterSlot, SlotBox> = mapOf(
    ClusterSlot.TOP_LEFT     to SlotBox(0.02f, 0.03f, 0.32f, 1f),
    ClusterSlot.TOP_CENTRE   to SlotBox(0.31f, 0.00f, 0.36f, 2f),
    ClusterSlot.TOP_RIGHT    to SlotBox(0.66f, 0.03f, 0.32f, 1f),
    ClusterSlot.HERO         to SlotBox(0.10f, 0.22f, 0.56f, 0f),
    ClusterSlot.HERO_INSET   to SlotBox(0.52f, 0.40f, 0.36f, 3f),
    ClusterSlot.BOTTOM_LEFT  to SlotBox(0.02f, 0.72f, 0.32f, 1f),
    ClusterSlot.BOTTOM_CENTRE to SlotBox(0.31f, 0.75f, 0.36f, 2f),
    ClusterSlot.BOTTOM_RIGHT to SlotBox(0.66f, 0.72f, 0.32f, 1f)
)
```
`place(slot, width, height)` returns `IntRect(left = (x*width).roundToInt(), top = (y*height).roundToInt(), size = (sizeFraction*width).roundToInt())` — square dials, sized off WIDTH so they stay circular.

`ClusterLayout` is a `Layout` that measures each child with fixed constraints from `place(...)` and places it at that offset, ordering placement by `zIndex` so later-drawn dials overlap earlier ones. `ClusterScope.slot(...)` attaches the slot via `ParentDataModifier`. The cluster's own height comes from its width × the aspect the slots imply (roughly 1.4); expose that ratio as a constant so the screen can size it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.gauge.ClusterPlacementTest"` then `./gradlew :composeApp:compileDebugKotlinAndroid`.
Expected: PASS (7/7); BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/gauge/ClusterLayout.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/gauge/ClusterPlacementTest.kt
git commit -m "feat(classic): width-scaled overlapping dial cluster layout"
```

---

### Task 4: Dial specs — binding live state to eight dials

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/ClassicDialSpecs.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/ClassicDialSpecsTest.kt`

**Interfaces:**
- Consumes: `ControllerData`, `BmsData`, `UnitSystem`, `DutyBands`, `TempBands`, `DialScale`, `UnitFormatter`, `formatFixed`.
- Produces: `data class DialSpec(val slot: ClusterSlot, val label: String, val valueText: String, val unit: String, val value: Float, val scale: DialScale, val dangerFrom: Float?, val severity: DutyLevel)`; `object ClassicDialSpecs { fun build(motion: ControllerData, battery: BmsData, units: UnitSystem, maxSpeedKmh: Float): List<DialSpec> }`.

The eight dials, mirroring the mockup's Classic view and reusing the shared bands:

| Slot | Metric | Scale | Danger |
|---|---|---|---|
| TOP_LEFT | Current (battery A) | −150..150 | — |
| TOP_CENTRE | Power (kW) | −2..8 | — |
| TOP_RIGHT | Duty % | 0..100 | `DutyBands.DEFAULT_CRITICAL_PERCENT` |
| HERO | Speed | 0..`maxSpeedKmh` | — |
| HERO_INSET | Battery % | 0..100 | — |
| BOTTOM_LEFT | ESC °C | 0..120 | `TempBands.ESC_CRITICAL_C` |
| BOTTOM_CENTRE | Consumption Wh/km | 0..50 | — |
| BOTTOM_RIGHT | Motor °C | 0..140 | `TempBands.MOTOR_CRITICAL_C` |

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.presentation.ride.gauge.ClusterSlot
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassicDialSpecsTest {

    private val motion = ControllerData(
        speedKmh = 47f, speedSource = SpeedSource.REPORTED, dutyPercent = 76f,
        batteryCurrentA = 52.4f, inputVoltageV = 78.2f, powerW = 4098f,
        escTempC = 52f, motorTempC = 68f, hasMotorTemp = true, hasEscTemp = true,
        consumedWh = 980f, tripKm = 58f, isConnected = true
    )
    private val battery = BmsData(voltage = 78.2f, soc = 84f, socKnown = true, isConnected = true)

    private fun specs(m: ControllerData = motion, b: BmsData = battery, u: UnitSystem = UnitSystem.METRIC) =
        ClassicDialSpecs.build(m, b, u, maxSpeedKmh = 70f).associateBy { it.slot }

    @Test fun all_eight_slots_are_filled_exactly_once() {
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f)
        assertEquals(ClusterSlot.entries.size, built.size)
        assertEquals(built.size, built.map { it.slot }.distinct().size)
    }

    @Test fun the_hero_is_speed_and_honours_the_unit_setting() {
        assertEquals("47", specs()[ClusterSlot.HERO]!!.valueText)
        assertEquals("29", specs(u = UnitSystem.IMPERIAL)[ClusterSlot.HERO]!!.valueText)
        assertEquals("mph", specs(u = UnitSystem.IMPERIAL)[ClusterSlot.HERO]!!.unit)
    }

    @Test fun an_unknown_speed_reads_as_a_dash() {
        val stopped = motion.copy(speedSource = SpeedSource.NONE)
        assertEquals("—", specs(m = stopped)[ClusterSlot.HERO]!!.valueText)
    }

    @Test fun duty_carries_its_shared_severity_and_danger_band() {
        val duty = specs()[ClusterSlot.TOP_RIGHT]!!
        assertEquals("76", duty.valueText)
        assertEquals(DutyLevel.WARN, duty.severity)
        assertEquals(90f, duty.dangerFrom)
    }

    @Test fun temperatures_use_the_shared_bands_and_dash_when_unreported() {
        val hot = motion.copy(motorTempC = 105f)
        assertEquals(DutyLevel.CRITICAL, specs(m = hot)[ClusterSlot.BOTTOM_RIGHT]!!.severity)
        val noSensor = motion.copy(hasMotorTemp = false)
        assertEquals("—", specs(m = noSensor)[ClusterSlot.BOTTOM_RIGHT]!!.valueText)
        assertNotNull(specs()[ClusterSlot.BOTTOM_LEFT]!!.dangerFrom)
    }

    @Test fun battery_dashes_when_the_state_of_charge_is_unknown() {
        val unknown = battery.copy(socKnown = false)
        assertEquals("—", specs(b = unknown)[ClusterSlot.HERO_INSET]!!.valueText)
    }

    @Test fun consumption_dashes_while_standing_still() {
        val stopped = motion.copy(speedKmh = 0f, consumedWh = 0f, tripKm = 0f)
        assertEquals("—", specs(m = stopped)[ClusterSlot.BOTTOM_CENTRE]!!.valueText)
    }

    @Test fun non_safety_dials_stay_neutral() {
        assertEquals(DutyLevel.NORMAL, specs()[ClusterSlot.TOP_LEFT]!!.severity)   // current
        assertEquals(DutyLevel.NORMAL, specs()[ClusterSlot.TOP_CENTRE]!!.severity) // power
        assertEquals(DutyLevel.NORMAL, specs()[ClusterSlot.HERO_INSET]!!.severity) // battery
        assertNull(specs()[ClusterSlot.TOP_CENTRE]!!.dangerFrom)
    }

    @Test fun the_hero_scale_never_collapses_to_zero() {
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, maxSpeedKmh = 0f)
        val hero = built.first { it.slot == ClusterSlot.HERO }
        assertTrue(hero.scale.max > hero.scale.min)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.ClassicDialSpecsTest"`
Expected: FAIL — unresolved `ClassicDialSpecs`.

- [ ] **Step 3: Write minimal implementation**

Build the eight `DialSpec`s per the table. Rules to honour, all mirroring the Clean renderer so the two styles can never disagree:
- Unknown values (`!speedKnown`, `!socKnown`, `!hasMotorTemp`, `!hasEscTemp`, consumption null) render `"—"` and pass their raw value as `0f` so the needle rests at the scale minimum.
- Severity: duty from `DutyBands.level(...)`, ESC/motor from `TempBands.escLevel(...)`/`motorLevel(...)`, everything else `DutyLevel.NORMAL`.
- Consumption uses `RideMetrics.instantWhPerKm(powerW, speedKmh) ?: RideMetrics.sessionWhPerKm(consumedWh, tripKm)`.
- Speed uses `UnitFormatter.speed/speedUnit`; the hero scale max is `maxSpeedKmh.coerceAtLeast(10f)` so it can never be degenerate.
- Power is `powerW / 1000f` with one decimal; current is `batteryCurrentA` rounded.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.*"` then the full suite.
Expected: PASS — including the existing `SecondaryGaugeMapperTest` and `RideDashboardComponentTest`.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/ClassicDialSpecs.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/ClassicDialSpecsTest.kt
git commit -m "feat(classic): eight dial specs bound to live state"
```

---

### Task 5: ClassicRideCluster + renderer switch

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/ClassicRideCluster.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt`

**Interfaces:**
- Consumes: `ClassicDialSpecs`, `ClusterLayout`, `DialGauge`, `RideDashboardComponent.State`.
- Produces: `@Composable fun ClassicRideCluster(state: RideDashboardComponent.State, maxSpeedKmh: Float, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Build it**

`ClassicRideCluster` calls `ClassicDialSpecs.build(...)`, then inside `ClusterLayout` emits one `DialGauge` per spec with `Modifier.slot(spec.slot)`, passing `colors = rememberDialColors(accent = severityColor(spec.severity))` so a WARN duty dial turns its needle and arc amber and a CRITICAL one red — the same semantic mapping the Clean renderer uses. Pass `dangerFrom = spec.dangerFrom`.

In `RideDashboardScreen`, replace the hero+cluster section with a switch:
```kotlin
when (state.style) {
    DashboardStyle.CLEAN -> { /* existing hero + 2×2 cluster + consumption card, unchanged */ }
    DashboardStyle.CLASSIC -> ClassicRideCluster(state = state, maxSpeedKmh = vehicleMaxSpeed, modifier = Modifier.fillMaxWidth())
}
```
The `VehiclePill`, the odometer/trip/uptime strip and the Graph link stay OUTSIDE the switch — both styles share them. **Do not otherwise touch the Clean branch**; it is signed off and its behaviour must not change.

Reuse the existing `severityColor(level)` helper already in the screen rather than defining a second one.

- [ ] **Step 2: Verify**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid` and `./gradlew :composeApp:testDebugUnitTest`.
Expected: BUILD SUCCESSFUL; full suite green (the Clean path is untouched, so `RideDashboardComponentTest` and friends stay green).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/
git commit -m "feat(classic): render the Classic cluster when the vehicle selects it"
```

---

### Task 6: Ship the choice — drop "coming soon", restyle the Graph link

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/vehicle/VehicleEditScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/common/GraphLinkButton.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` + `values-ru/strings.xml`

- [ ] **Step 1: Make the changes**

1. **Drop the "coming soon" captions.** Both the Settings app-default style row and the Vehicle Edit per-vehicle style row show a caption saying Classic isn't implemented. It is now. Remove those captions and delete the now-unused string keys from **both** locale files. Leave the pickers themselves alone.
2. **Restyle the Graph link.** `GraphLinkButton` currently renders a filled, primary-tinted chip. On the Ride screen it is the only saturated element in an otherwise calm body and competes with the hero gauge — it deviates from the signed-off mockup, whose header row is two muted labels. Change it to a **text-only link**: the label in `MaterialTheme.colorScheme.primary` with no filled background and no border, keeping the existing `minimumInteractiveComponentSize()` and `role = Role.Button` so the 48dp touch target and semantics survive. Do not move it; only the styling changes.

- [ ] **Step 2: Verify**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid` and `./gradlew :composeApp:testDebugUnitTest`.
Expected: BUILD SUCCESSFUL; suite green. Grep both `strings.xml` files to confirm the removed keys are gone from BOTH and that no `stringResource` still references them.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ composeApp/src/commonMain/composeResources/
git commit -m "feat(classic): Classic is selectable for real; Graph link becomes a text link"
```

---

### Task 7: Device check — both styles, live

**Files:** none expected (fix whatever the run exposes)

- [ ] **Step 1: Run the app and exercise both renderers**

Build and install: `./gradlew :composeApp:assembleDebug`, then install on an emulator or device and launch. Tap **Try demo** — the demo vehicle has a controller, so it lands on Ride.

Verify, and report exactly what you saw:
- **Clean** renders as before (regression check).
- Switch the demo-adjacent saved vehicle's style to **Classic** (Settings → Dashboard style changes the app default; that is the reachable lever for the demo, which is never persisted and has no edit screen). Confirm the Classic cluster renders: eight dials, overlapping fan/hero/fan composition, needles sweeping with the simulated ride.
- **The mockup's failure mode is fixed:** scale numbers do NOT collide with the centre readout on any dial, at the phone's actual width.
- Duty dial shows its red danger band and turns amber/red as the simulated duty climbs past 75/90.
- Both light and dark themes are legible (toggle the system theme) — this is a Material You surface, not a fixed dark panel.

- [ ] **Step 2: Fix anything the run exposes**

Most likely candidates: number radius still too close to the centre on small widths, dial overlap hiding a needle, or a colour with poor contrast in light theme. Fix, rebuild, re-verify, and say what changed.

- [ ] **Step 3: Full verification and commit**

Run: `./gradlew :composeApp:testDebugUnitTest` and `./gradlew :composeApp:assembleDebug`.
```bash
git add -A
git commit -m "fix(classic): device-verified dial rendering"
```
(If the run exposed nothing, say so and skip the commit rather than inventing a change.)

---

## Self-Review

**Spec coverage (`B-vesc-dashboard.md` §7 → task):**
- §7 "Classic VESC — skeuomorphic overlapping dial cluster, Material-You-tinted" → Tasks 2, 3, 5 ✓
- §7.1 `DialGauge` (ticks, collision-free numbers, needle+hub, swept arc, danger segment, clean centre readout) → Tasks 1, 2 ✓
- §7.1 `ClusterLayout` (custom Layout, overlapping fan/nest, scales to width not absolute px) → Task 3 ✓
- §7.2 secondary-gauge choice → already shipped in B1; Classic emphasises via `severityColor` on the relevant dial (Task 5) ✓
- §7.4 duty bands shared with Part F → Task 4 reuses `DutyBands`/`TempBands`, no new thresholds ✓
- §11 decision 1 "both ship in Part B; Clean first, Classic second" → this plan is the Classic half ✓
- The signed-off design's own defect (numbers colliding with the centre) is an explicit acceptance criterion in Tasks 2 and 7 ✓

**Placeholder scan:** Tasks 2, 5, 6, 7 describe composable construction and a device run rather than carrying complete Compose source. That is deliberate and disclosed: this repo has no Compose UI test harness, the drawing code's testable maths is fully specified and pinned in Tasks 1/3/4, and inventing exact Canvas source in a plan document would be guesswork about a rendering surface that must be tuned by eye. Every task carrying real logic (1, 3, 4) has complete test + implementation code.

**Type consistency:** `DialScale(min,max,majorTicks,minorPerMajor)` identical across Tasks 1/2/4. `DialGeometry.START_ANGLE/SWEEP/angleFor/fraction/majorValues/tickAngles/dangerSweep` identical across Tasks 1/2. `ClusterSlot`/`SlotBox`/`ClusterPlacement.place` identical across Tasks 3/4/5. `DialSpec(slot,label,valueText,unit,value,scale,dangerFrom,severity)` identical across Tasks 4/5. `DialColors`/`rememberDialColors` identical across Tasks 2/5.

**Scope:** one coherent slice — after Task 7 a rider can choose Classic per vehicle and get a working, legible, Material You instrument cluster. Nothing else in the app changes.
