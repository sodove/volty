# Part M — The Plain VESC Link: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A VESC on an ordinary Bluetooth-UART module works — which today it does not, on the
one vehicle shape that has never had field coverage and is the commonest one in the wild.

**Architecture:** Three independent faults, one vehicle. The protocol asks a single question it
cannot change; the poll loop swallows the only error that would explain silence; and a watchdog
answers "I decoded nothing" by tearing down a healthy GATT link every twelve seconds forever.
Each is fixed where it lives; none of them needs the gateway's machinery, and none of them may
disturb it.

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform, Decompose, Koin,
Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

**Evidence base:** `field-reports/2026-08-01-second-hardware-test.md` §O3 and §S3. The rider's
bicycle: a plain VESC on a stock Nordic-UART module advertising as `VESC BLE UART`, one motor,
no CAN, no head unit. Their words: *"бмс видно, а вот контр под вопросом. nrf connect показывал
постоянные реконнекты к vesc ble uart"* — and, ruling out the observer, *"он был в фоне и
отключен, там сам сервис просто видит кто и когда пытается подключаться"*.

---

## The one fact that frames the whole part

**`VescProtocol` has never met hardware.** Every VESC this project has ever tested went through
the rider's head unit, which is `isGatewayLink` and therefore runs `VescGatewayProtocol`. Eleven
tasks of Part I hardened the gateway path — two opcodes per controller, silence suppression,
re-probes, late-reply guards, a warm-up before a verdict. **The plain path got none of it**, and
it is the path an ordinary VESC rider is on.

So the risk in this part is not that the fixes are hard. It is that the plain path has no field
history at all, so **anything a task here "confirms" from code alone is a claim about untested
ground**. Where a step cannot be settled without the device, it says so.

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.** Every mutation killed by some
   assertion, **and** every assertion killable by some implementation.
2. **A zero-failure mutation run is not evidence unless the build compiled *and* the test count
   is non-zero and exactly right.** Four sweeps on this project reported false passes. Reuse the
   harness at `.superpowers/sdd/2026-07-30-vehicle-platform-I-field-fixes/t11sweep.ps1`,
   audited three times.
3. **Sweep your own additions.**
4. **Where a contract concerns absent data, the fixture must be deliberately incoherent.**
5. **Never write to Begode's FFE1 characteristic.** Task 1 adds a second thing the app may send
   on a link; the rule that a wheel's command channel is never written must survive it.
6. **The battery path must not change behaviour** except where a task names it. Task 3 touches
   the watchdog, which every link shares — this constraint binds it hardest.
7. **`runTest` hazard:** an unbounded delayed loop wedges the build instead of failing. All three
   tasks are about loops and timers.
8. **Compose UI is not unit-testable here.** Task 4's surfacing goes in the component.
9. Russian UI strings in **both** `values/` and `values-ru/`.
10. Run `.\gradlew.bat :composeApp:testDebugUnitTest` green before every commit.

---

### Task 1 — ask the other question

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/VescProtocol.kt`
- Test: `VescProtocolTest`

**Why.** `pollCommands()` sends `COMM_GET_VALUES_SETUP` (47) and nothing else. `useSetupFrame`
is a constructor parameter **no caller ever sets**, and `decodeValues` (opcode 4) is therefore
unreachable on a plain link. A VESC that does not answer 47 is asked five times a second
forever, and nothing tries anything else.

**This is consistent with the rider's report that VESC Tool serves the same module reliably:**
VESC Tool's realtime view polls opcode **4**; opcode 47 is a separate tab. A correct client
succeeding where Volty fails is exactly what this predicts.

**Design ruling — probe, do not alternate.** Send both opcodes until one of them answers, then
keep asking the one that answered and stop asking the other. Do not permanently alternate:
that halves the effective rate for every healthy VESC to fix a minority that cannot answer 47.
And do not pick by firmware version — the version frame is another request and another
assumption.

**Ruling — SETUP wins ties.** If both answer, keep SETUP: it carries ground speed, odometer and
battery level, which opcode 4 does not. Opcode 4 is a fallback, not a preference.

- [ ] **Step 1: Write the failing tests.** A link whose device answers only opcode 4 produces
      motion samples; one that answers only 47 is unchanged from today; one that answers both
      settles on 47 and **stops sending 4**; a device that answers neither does not escalate to
      sending more and more (the probe is bounded). Assert on **requests issued**, never on
      timing — a timing-only assertion on a virtual clock is the shape that passes under its
      own mutant.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement the probe** in `VescProtocol`. Keep it small: this protocol has no
      request/reply accounting and this task does not add one — that is Task 2's business.
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(vesc): a plain link could only ever ask one question"
```

---

### Task 2 — a write that never reached the wire must not look like silence

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ConnectionSession.kt`
  (the non-`SerialPollSource` burst branch)
- Test: `ConnectionSessionTest` or equivalent

**Why.** The burst poll loop is `try { … } catch (_: Exception) { }` **with no log line at
all**, while the gateway branch two lines above it logs. So the single most likely write failure
on the untested path is invisible in a release build.

And it is a real candidate, not a hypothetical: Kable resolves a characteristic **by property**
before any GATT traffic, and throws if the one you asked for is absent. The app asks for
`WriteType.WithoutResponse` everywhere. **If this module's RX characteristic advertises `WRITE`
but not `WRITE_NO_RESPONSE`, every poll throws before a byte leaves the phone** — and the app
reports nothing, retries at 5 Hz, and lets the watchdog blame the device.

**Ruling — log, count, and surface; do not "fix" the write type blindly.** Switching to
with-response on failure is a guess that could break every working link. What this task owes is
the ability to *know*: a counted, logged, retrievable failure. The device question is then one
look at the module's properties, which the report must say plainly is still needed.

- [ ] **Step 1: Write the failing tests.** A write that throws is counted and reported, not
      swallowed; N consecutive write failures are distinguishable from N cycles of device
      silence **in the state the app exposes**, because those are different faults with
      different fixes; a link whose writes all fail does not report itself as merely quiet.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.** Match the gateway branch's logging, and keep the failure on the
      link's own state rather than in a log only — a rider cannot read logcat.
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(ble): the poll loop swallowed the one error that explains silence"
```

---

### Task 3 — a watchdog that cannot tell a wrong question from a dead link

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/ConnectionSession.kt`
  (the stale-sample watchdog), `data/ble/BleConfig.kt`
- Test: the session tests

**Why.** The watchdog declares a link stale when nothing has **decoded** within
`noSampleEverMs` (10 s) of connect, and answers by tearing down the GATT link and redialling —
first attempt immediate, then every 3 s. On a link that connects cleanly and decodes nothing,
that is a churn cycle of roughly twelve seconds, forever. **That is the rider's "постоянные
реконнекты", and it is the app doing it to itself.**

Reconnecting is right when the link is dead. It is wrong when the link is healthy and we are
asking the wrong question — and after Task 1 that case shrinks but does not vanish.

**Ruling — the remedy must match the diagnosis, and the diagnosis needs one more bit.** A link
that has never decoded is in one of three states: nothing is arriving at all (reconnect is
right); notifications are arriving but nothing decodes (reconnect will not help — a different
protocol or opcode might); writes are failing (Task 2's case, and reconnect definitely will not
help). **The watchdog currently cannot see the difference, and that is the defect** — not the
timeout value. Give it the bit, then let the remedy follow: redial only when nothing is
arriving.

**Do not simply raise the timeout.** A longer wait on a genuinely dead link is a regression for
every other vehicle, and it would leave this defect intact behind a bigger number.

- [ ] **Step 1: Write the failing tests.** A link receiving *undecodable* notifications is not
      redialled but is reported as not understood; a link receiving nothing is redialled exactly
      as today; a link whose writes fail is neither, and says so; the gateway path's behaviour
      is **byte-identical** to today under every one of these (constraint 6).
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.** The notification arrival that is *not* a decode is the new signal —
      it exists already at the accumulator boundary and is currently discarded.
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(ble): a healthy link that we were asking wrongly was redialled forever"
```

---

### Task 4 — a link that is flapping must not read as connected

**Files:**
- Modify: `presentation/ride/RideDashboardScreen.kt` and its component; wherever the vehicle
  pill is composed
- Test: the ride component tests

**Why.** Vehicle state is a fold: **any** link online means `Connected`. On the bicycle the ANT
link is healthy, so the pill reads `Connected` — and it labels the vehicle by the **controller**
type by preference, so **the screen names the exact device that is not working.** The VESC
link's reconnecting state and its reason never surface anywhere.

Worse, the signal already exists and is thrown away: `MotionResult.partial` is computed, carried
all the way into ride state as `motionPartial`, and **read by no composable**. Offline chips
exist for packs only; a controller has no equivalent.

- [ ] **Step 1: Write the failing tests** at the component level: a vehicle with one online pack
      and one flapping controller exposes that mixed state distinguishably; `motionPartial`
      reaches something a renderer can bind; the pill's label does not name a source that has
      never reported.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement**, decisions in the component per constraint 8, strings in both
      locales. Say plainly in the report that the rendering itself needs a device.
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(ride): the pill named the one device that was not working"
```

---

## What still needs the device, and what to look at

Stated here rather than discovered later. **Task 1 may be the whole fix, or none of it** — these
observations decide which:

1. **In nRF Connect on `VESC BLE UART`, read the RX characteristic's properties.** If
   `WRITE NO RESPONSE` is absent while `WRITE` is present, Task 2's runner-up is the actual
   cause and Task 1 fixes nothing on this vehicle.
2. **Write our framed opcode 47 and our framed opcode 4 to it, with notifications enabled.** If
   4 answers and 47 does not, Task 1 is the whole fix and it is confirmed before it is built.
3. **`adb logcat | grep VOLTY-BLE` during a flap.** `watchdog: STALE` proves the app killed the
   link; `Disconnected event received` would prove the radio did. Note that the write failure
   prints nothing today, so its absence from the log is not evidence against it — which is
   itself Task 2's point.

## Out of scope

- **Porting suppression to the plain path.** With one controller and one opcode there is
  nothing to suppress; the watchdog is the right owner of "this link is not producing".
- **Raising the ATT MTU.** Nothing requests it anywhere, for any device including the gateway.
  A separate change with its own risk, and reassembly already works.
- **The leaked `Peripheral` per connection attempt** — `close()` is never called anywhere in the
  repo. Real, bounded, and not this part's business; it becomes visible mainly *because* of the
  reconnect loop this part removes.
