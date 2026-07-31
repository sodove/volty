# Part J — The Controller Already Knows Its Wheel: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ask a VESC controller for the wheel geometry it is already configured with, so a
rider does not type numbers their controller has stored — and so that when they do type
them, Volty can tell them the controller disagrees.

**Architecture:** One new opcode (`COMM_GET_MCCONF_TEMP` = 91), asked once per connection
per controller, decoded into a small value type. Three consumers: the composer pre-fills a
controller's motor card from it, the eRPM fallback prefers it over the typed values, and an
unconfigured controller becomes a diagnosis the rider can act on instead of a silent dash.
No polling: a controller's configuration does not change while it is being ridden.

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform, Decompose,
Koin, Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

**Evidence base:** the VESC firmware and VESC Tool sources at
`C:\Users\sodovaya\Desktop\Software\vesc_tool_free_windows`, read on 2026-07-31. Every wire
fact below is cited to a file and line there and was read, not inferred. Related:
`docs/superpowers/specs/2026-07-24-vehicle-platform/field-reports/2026-07-30-first-hardware-test.md`
and Part I Tasks 4, 6 and 7, which this builds on.

---

## What this is actually worth, stated honestly

**It does not recover a speed the controller could not compute itself.** The firmware derives
its own ground speed from the same three parameters (`mc_interface.c:1612-1613`):

```c
const float rpm = mc_interface_get_rpm() / (conf->si_motor_poles / 2.0);
return (rpm / 60.0) * conf->si_wheel_diameter * M_PI / conf->si_gear_ratio;
```

That is arithmetically identical to Volty's `VescValues.derivedSpeedKmh`. So a controller with
no wheel configured reports `speed = 0` in its SETUP frame **and** would report zeros here —
there is nothing to inherit in exactly the case where the fallback is needed.

What it is worth is three other things:

1. **The rider stops typing what the controller knows.** A properly set-up uBox already has
   the diameter, the gear ratio and the pole count. Today Part I Task 6 makes a CAN-discovered
   controller inherit its gateway's typed geometry — a guess that is usually right. This
   replaces the guess with the controller's own answer.
2. **A disagreement becomes visible.** If the rider types 500 mm and the controller believes
   600 mm, Volty's eRPM-derived speed and the controller's own SETUP speed differ by 20 % and
   nothing today says so. Two numbers for one wheel is a bug the app can detect for free once
   it can read both.
3. **"Unconfigured" becomes a diagnosis instead of a dash.** A zero diameter is currently
   indistinguishable, from the rider's side, from a controller that is not answering. With
   this, Volty can say *this controller has no wheel diameter set — enter one here, or set it
   in VESC Tool* — which is the actual fix, and which the app currently cannot name.

**Not in scope, and why.** The same frame carries ten limit fields (`l_max_erpm`,
`l_watt_max`, `l_in_current_max`, …) that would seed a gauge's maximum at connect rather than
after a ride — which is what VESC Tool itself does (`mobile/RtDataSetup.qml:683-709`). Part G2
Task 7 has just shipped a *learned* per-vehicle range with a spike guard and a persisted
median. Two sources of truth for one scale is a design decision, not a task. Recorded as an
open question at the end of this plan.

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.** Every mutation killed by some
   assertion, **and** every assertion killable by some implementation. Delete any that no
   implementation could falsify and say so.
2. **A zero-failure mutation run is not evidence unless the build compiled *and* the test
   count is non-zero and exactly right.** Four sweeps on this project reported false passes.
   Bytecode-changing control with a **fresh nonce per run**, results directory wiped, count
   asserted. Never two sweeps at once. Reuse the harness shape from
   `.superpowers/sdd/2026-07-30-vehicle-platform-I-field-fixes/t8sweep.ps1`, which survived a
   line-by-line audit.
3. **Sweep your own additions.** Twelve implementers on this project have shipped guards
   indistinguishable from their absence, each finding it only this way.
4. **Where a contract concerns absent data, the fixture must be deliberately incoherent** — a
   combination no producer emits, which is exactly why it separates the contract from the
   producers' habits.
5. **Never write to Begode's FFE1 characteristic.** Not touched by this part; restated because
   it binds every plan in this project.
6. **The battery path must not change behaviour.** Nothing here has any business near it.
7. **`runTest` hazard:** a test starting an unbounded delayed loop makes virtual time advance
   forever and **wedges the build instead of failing**.
8. **Compose UI is not unit-testable here** (no Robolectric, no `compose-ui-test`, no
   instrumented source set). Every decision goes in pure/component code; the `@Composable`
   layer stays a thin renderer. Do not write a test that dresses up an unverifiable claim —
   say plainly what needs a device.
9. Russian UI strings in **both** `values/` and `values-ru/`. Compose Multiplatform does
   **not** process Android backslash escapes.
10. Run `.\gradlew.bat :composeApp:testDebugUnitTest` green before every commit.

---

## The wire, pinned

`COMM_GET_MCCONF_TEMP = 91` (`bldc/datatypes.h:1033`). Request is the bare opcode byte. The
reply (`bldc/comm/commands.c:995-1019`) is **fixed-layout, 50 bytes, no signature and no
version negotiation** — unlike `COMM_GET_MCCONF`, which serialises the whole configuration
behind a `MCCONF_SIGNATURE` check (`confgenerator.c:11,347`) and would tie Volty to a firmware
revision. This frame is the cheap one, and the firmware's own comment above the last three
fields reads *"Setup config needed for speed calculation"*.

| offset | bytes | encoding | field |
|---|---|---|---|
| 0 | 1 | `u8` | opcode, `91` |
| 1 | 4 | `f32auto` | `l_current_min_scale` |
| 5 | 4 | `f32auto` | `l_current_max_scale` |
| 9 | 4 | `f32auto` | `l_min_erpm` |
| 13 | 4 | `f32auto` | `l_max_erpm` |
| 17 | 4 | `f32auto` | `l_min_duty` |
| 21 | 4 | `f32auto` | `l_max_duty` |
| 25 | 4 | `f32auto` | `l_watt_min` |
| 29 | 4 | `f32auto` | `l_watt_max` |
| 33 | 4 | `f32auto` | `l_in_current_min` |
| 37 | 4 | `f32auto` | `l_in_current_max` |
| 41 | 1 | `u8` | **`si_motor_poles`** |
| 42 | 4 | `f32auto` | **`si_gear_ratio`** |
| 46 | 4 | `f32auto` | **`si_wheel_diameter`** |

`f32auto` is VESC's bit-packed float, **not** a scaled integer and **not** `Float.fromBits`.
`VescReader.f32auto()` already implements it, is already documented against
`buffer.c:123-146`, and is already tested — **do not write a second one**.

### Two unit traps, both of which will silently produce a plausible wrong speed

- **`si_motor_poles` is POLES; `MotorConfig.polePairs` is PAIRS.** The firmware divides by
  `si_motor_poles / 2.0` to get mechanical rpm. A VESC configured for a 30-pole motor must
  become `polePairs = 15`. Getting this backwards doubles or halves every derived speed, and
  both results look like a plausible speed.
- **`si_wheel_diameter` is METRES; `MotorConfig.wheelDiameterMm` is MILLIMETRES.** The
  firmware multiplies it by π directly to get circumference in metres. A 0.6 m wheel is
  `wheelDiameterMm = 600`.
- `si_gear_ratio` is motor revolutions per wheel revolution — the **same** convention as
  `MotorConfig.gearRatio`, which `derivedSpeedKmh` divides by. No conversion.

### Reachability

The request is an ordinary command in the same `commands.c` switch as opcodes 4 and 47, so it
is forwardable with `COMM_FORWARD_CAN` and a CAN slave can be asked exactly like a directly
connected node. **VESC Express does not implement it** — the rider's head unit will not
answer, the same way it answers neither 4 nor 47. Silence is therefore an expected outcome on
real hardware, not an error, and must not be logged as one.

---

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `data/bms/vesc/VescSetupConfig.kt` (new) | the value type + the opcode-91 decoder | 1 |
| `data/bms/VescProtocol.kt` | ask once per connection on a plain link | 2 |
| `data/bms/VescGatewayProtocol.kt` | ask once per controller, per connection | 2 |
| `data/bms/ControllerProtocol.kt` (or wherever the interface lives) | expose the answer to the layer above | 2 |
| `presentation/vehicle/VehicleEditComponent.kt` | pre-fill a controller's motor card | 3 |
| `presentation/vehicle/VehicleComposer.kt` | the geometry a draft carries | 3 |
| `domain/model/Controller.kt` | provenance: measured-by-controller vs typed | 3 |

---

### Task 1 — decode the frame

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescSetupConfig.kt`
- Reference: `data/bms/vesc/VescReader.kt` (`f32auto`, `u8`, `has`), `data/bms/vesc/VescValues.kt`
  (the decoder shape and its length-guard convention), `domain/model/Controller.kt` (`MotorConfig`)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/vesc/VescSetupConfigTest.kt`

**Interfaces:**
- Produces: `VescSetupConfig`, `VescSetupConfig.OPCODE_GET_MCCONF_TEMP`,
  `VescSetupConfig.decode(payload: ByteArray): VescSetupConfig?`,
  `VescSetupConfig.motorConfig: MotorConfig?`

- [ ] **Step 1: Write the failing tests.** Build the payload byte-for-byte from the table
      above with a helper that encodes `f32auto` the way the firmware does — encode, do not
      hand-copy bytes from somewhere, and assert the helper round-trips through
      `VescReader.f32auto()` first, or the whole suite is testing itself.

```kotlin
@Test
fun `a configured controller reports its own wheel geometry`() {
    val cfg = VescSetupConfig.decode(mcconfTempPayload(poles = 30, gear = 1f, diameterM = 0.6f))
    assertNotNull(cfg)
    assertEquals(15, cfg.motorConfig?.polePairs)          // POLES / 2 — the halving is the point
    assertEquals(600, cfg.motorConfig?.wheelDiameterMm)   // METRES × 1000
    assertEquals(1f, cfg.motorConfig?.gearRatio)
}

@Test
fun `an unconfigured controller offers no geometry rather than a zero one`() {
    val cfg = VescSetupConfig.decode(mcconfTempPayload(poles = 30, gear = 1f, diameterM = 0f))
    assertNotNull(cfg)                                    // the frame decoded
    assertNull(cfg.motorConfig)                           // but there is nothing to inherit
}

@Test
fun `a frame short of the geometry is not a config`() {
    assertNull(VescSetupConfig.decode(mcconfTempPayload().copyOf(45)))
}

@Test
fun `another opcode is not this frame`() {
    val wrong = mcconfTempPayload().also { it[0] = VescValues.OPCODE_GET_VALUES.toByte() }
    assertNull(VescSetupConfig.decode(wrong))
}
```

- [ ] **Step 2: Run them and watch them fail.**

```
.\gradlew.bat :composeApp:testDebugUnitTest --tests "*VescSetupConfigTest*"
```

- [ ] **Step 3: Implement.** Mirror `VescValues.decodeValues`' shape exactly: opcode check,
      then one `has(...)` length guard, then straight-line reads, returning null rather than
      throwing on anything short.

```kotlin
data class VescSetupConfig(
    val maxErpm: Float,
    val maxWattsOut: Float,
    val maxInputCurrentA: Float,
    val motorPoles: Int,
    val gearRatio: Float,
    val wheelDiameterM: Float
) {
    /**
     * The three fields as Volty spells them, or **null when the controller has
     * no usable geometry** — which is the honest reading of a zero diameter,
     * zero gear ratio or zero pole count: the firmware's own
     * `mc_interface_get_speed` produces 0 or a division by zero from exactly
     * these values, so there is nothing here to inherit.
     */
    val motorConfig: MotorConfig?
        get() = if (wheelDiameterM <= 0f || gearRatio <= 0f || motorPoles < 2) null
                else MotorConfig(
                    polePairs = motorPoles / 2,
                    wheelDiameterMm = (wheelDiameterM * 1000f).roundToInt(),
                    gearRatio = gearRatio
                )

    companion object {
        const val OPCODE_GET_MCCONF_TEMP = 91
        private const val BODY_BYTES = 49        // everything after the opcode byte

        fun decode(payload: ByteArray): VescSetupConfig? {
            val r = VescReader(payload)
            if (!r.has(1) || r.u8() != OPCODE_GET_MCCONF_TEMP) return null
            if (!r.has(BODY_BYTES)) return null
            r.f32auto(); r.f32auto()                      // l_current_min_scale, _max_scale
            r.f32auto()                                   // l_min_erpm
            val maxErpm = r.f32auto()
            r.f32auto(); r.f32auto()                      // l_min_duty, l_max_duty
            r.f32auto()                                   // l_watt_min
            val maxWattsOut = r.f32auto()
            r.f32auto()                                   // l_in_current_min
            val maxInputCurrentA = r.f32auto()
            return VescSetupConfig(
                maxErpm = maxErpm,
                maxWattsOut = maxWattsOut,
                maxInputCurrentA = maxInputCurrentA,
                motorPoles = r.u8(),
                gearRatio = r.f32auto(),
                wheelDiameterM = r.f32auto()
            )
        }
    }
}
```

- [ ] **Step 4: Add the cross-check test that makes the unit conversions load-bearing.**
      This is the assertion that catches a poles/pairs or metres/millimetres inversion, and
      neither of the two above does on its own — both halves of an inverted pair still produce
      a plausible number.

```kotlin
@Test
fun `our derived speed agrees with the firmware's own formula for the same config`() {
    val poles = 30; val diameterM = 0.6f; val gear = 2.5f; val eRpm = 3000f
    // mc_interface.c:1612-1613, transcribed:
    val firmwareMs = (eRpm / (poles / 2f) / 60f) * diameterM * PI.toFloat() / gear
    val cfg = VescSetupConfig.decode(mcconfTempPayload(poles, gear, diameterM))!!
    val ours = VescValues.derivedSpeedKmh(eRpm, cfg.motorConfig!!)!!
    assertEquals(firmwareMs * 3.6f, ours, 0.01f)
}
```

- [ ] **Step 5: Sweep, full suite, commit.** Mutate each conversion separately — `poles` not
      halved, `poles * 2`, diameter not scaled, diameter `/1000`, gear inverted (`1/gear`),
      each guard in `motorConfig` dropped in turn, and each `r.f32auto()` skip-read removed so
      a field-order error is killed.

```bash
git commit -m "feat(vesc): the controller can be asked what wheel it thinks it has"
```

---

### Task 2 — ask it once, per controller, per connection

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/VescProtocol.kt`
  (`handshakeCommands()` currently returns `emptyList()` — `:67`)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/VescGatewayProtocol.kt`
  (the serial `plan`, `:573-577`; `handshakeCommands()` at `:964`; `reset()`)
- Test: `VescProtocolTest`, `VescGatewayProtocolTest`

**Interfaces:**
- Consumes: `VescSetupConfig.decode` from Task 1.
- Produces: a per-controller accessor for the decoded config, mirroring `latestMotion`'s shape.

**Why a one-shot and not a poll.** A controller's configuration does not change while it is
being ridden, and the gateway loop is **strictly serial** — every request costs a full
round-trip that the speed and current readouts are waiting behind. Part I Task 11 exists
because that budget is already tight on the rider's own vehicle.

- [ ] **Step 1: Write the failing tests.**
      (a) the request appears exactly once per controller per connection, not once per cycle;
      (b) a reply is decoded and reaches the accessor;
      (c) **silence is not an error** — a VESC Express head unit answers neither this nor
      opcodes 4 and 47, so a no-answer must leave the accessor null, must not retry every
      cycle, and must not stall the poll loop;
      (d) `reset()` clears it, and a reconnect asks again — a rider who reconfigures in VESC
      Tool and reconnects must not be served the old geometry;
      (e) on a gateway, the ask is forwarded to each controller's own CAN id.

- [ ] **Step 2: Run them and watch them fail.**

- [ ] **Step 3: Implement.** On the plain link, `handshakeCommands()` is the natural home. On
      the gateway the serial `plan` is a fixed cycle, so the one-shot needs to be a request
      that removes itself once answered **or once refused** — model it on how `scanCanBus`
      arms and disarms a single outstanding expectation rather than inventing a second
      mechanism.

      **Do not let an unanswered ask retry forever.** The rider's head unit will never answer.
      Give up after a small fixed number of attempts and record that this node does not
      implement the opcode, the same way Part I Task 11 stops asking a node that never answers.

- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "feat(vesc): ask each controller once what it was set up with"
```

---

### Task 3 — use it, and say where the number came from

**Files:**
- Modify: `presentation/vehicle/VehicleEditComponent.kt` (the CAN-candidate add, and the
  motor card), `presentation/vehicle/VehicleComposer.kt` (`ControllerDraft.motor`,
  `MotorDraft`), `domain/model/Controller.kt`
- Test: `VehicleComposerTest`, the composer component tests

**Precedence, ruled here so the implementer is not guessing.**

1. **A controller's own geometry wins over an inherited guess.** Part I Task 6 makes a
   CAN-discovered controller inherit its gateway's typed geometry because slaves on one
   vehicle usually share a wheel. That is a guess; this is an answer. The answer wins.
2. **A rider's explicit edit wins over both, and survives a reconnect.** Someone who has
   typed a correction has told Volty the controller is wrong — most often because they
   measured the wheel and never updated VESC Tool. Overwriting that on every connect would
   make the field impossible to use.
3. **Therefore the geometry needs a provenance**, not just a value: *read from the
   controller* / *inherited from the gateway* / *typed by the rider*. Rule 2 is unimplementable
   without it, and the disagreement warning below is unstateable without it.

- [ ] **Step 1: Write the failing tests.** (a) a CAN-discovered controller that answers
      opcode 91 takes the controller's geometry, not the gateway's; (b) a rider's typed value
      survives a reconnect that reports different geometry; (c) the disagreement is reported
      when the two differ by more than a threshold, and is silent when they agree; (d) a
      controller that answers with a zero diameter yields the *unconfigured* diagnosis, which
      is a different state from *did not answer*.

- [ ] **Step 2: Run them and watch them fail.**

- [ ] **Step 3: Implement the provenance and the precedence.** Keep the decision in the
      component, not the composable (constraint 8).

- [ ] **Step 4: Surface the two messages.** Both need Russian strings in **both** `values/`
      and `values-ru/` (constraint 9): *the controller has no wheel diameter set* and *the
      controller believes X mm, you entered Y mm*. Neither is an error state — both are
      things the rider can fix, and the second may well be deliberate.

- [ ] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "fix(composer): stop asking the rider for a number the controller already has"
```

---

## Open questions, deliberately not answered here

1. **The ten limit fields.** `l_watt_max` is exactly a power gauge's natural maximum and
   `l_max_erpm` a speed gauge's, both known at connect rather than learned over a ride —
   which is what VESC Tool does (`mobile/RtDataSetup.qml:683-709`). Part G2 Task 7 has just
   shipped a learned per-vehicle range with a spike guard and a persisted median. Whether a
   configured limit should seed that range, override it, or be ignored is a design decision
   about which number a rider is better served by, and it wants its own brainstorm.
2. **Whether to offer writing the geometry back.** `COMM_SET_MCCONF_TEMP` = 48 exists and
   takes the same shape. A rider who measures their wheel in Volty could fix their controller
   from the app. That is a write to a motor controller's configuration, which is a different
   risk class from everything Volty does today, and it is not in this plan.
3. **Whether the head unit could be taught to answer.** The rider builds their own VESC
   Express firmware (`nyxdash`). Adding opcode 91 there is a firmware change, not an app one,
   and would only report the head unit's own geometry — which, having no motor, it does not
   have.
