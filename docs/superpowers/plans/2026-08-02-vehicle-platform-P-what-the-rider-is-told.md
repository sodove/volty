# Part P — What The Rider Is Told: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fault the vehicle reported stays on the screen long enough to be read, and a value
the app cannot know says *why* instead of showing a dash.

**Architecture:** Part I made unknown values honest. It did not make them explicable, and it
left faults to a push notification that is gone by the time a rider can look. Both are the same
shortfall: the app knows something specific and shows something generic.

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform, Decompose, Koin,
Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

**Evidence base:** the rider, 2026-08-01 — *"я словил ошибку overvoltage на веске (при резком
сбросе газа на полном заряде оно чутка ток беквардит) и он сбросил газ - пуш пришел, но было бы
также неплохо показывать ошибки и на самом деше определенное время (допустим минуту или по
выбору юзера)"* — and `field-reports/2026-08-01-second-hardware-test.md` §O5.

---

## The two shortfalls

**A fault is announced once and then gone.** The rider's VESC threw an over-voltage on a sharp
throttle release at full charge — a regen spike the controller answered by cutting throttle,
which is a real event on a real ride. The app sent a push. If the phone is bar-mounted and the
rider is riding, a push is the one delivery method they cannot act on, and by the time they
stop, it is gone. The fault list exists in the sample; the dashboard does not show it.

**And an unknown value cannot say why it is unknown.** `SpeedSource.NONE` is a flat enum with no
reason attached: the decoder knows exactly why it returned nothing — no wheel diameter — and
discards that one line later. So the presentation layer *cannot* say more than "unknown" even if
it wanted to, and the one cause-stating sentence in the app lives on the Alerts screen and names
the wrong cause for the vehicle that most needs it.

The two are one design problem: **the app reduces specific knowledge to a generic symbol at the
producer, and then no amount of UI work downstream can recover it.**

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.**
2. **A zero-failure mutation run is not evidence unless the build compiled *and* the test count
   is non-zero and exactly right.** Reuse the audited harness.
3. **Sweep your own additions.**
4. **Where a contract concerns absent data, the fixture must be deliberately incoherent.**
5. **Never write to Begode's FFE1 characteristic.** A fault banner must never grow a "clear
   fault on the vehicle" action — on a wheel that is a write to the command channel.
6. **The battery path must not change behaviour** except where a task names it. Task 1 renders
   BMS faults, which already flow; it must not change what is produced.
7. **`runTest` hazard:** a fault banner with a dismissal timer is a delayed loop — this is the
   part most exposed to it.
8. **Compose UI is not unit-testable here.** Every decision — which fault is shown, for how
   long, when it clears, what an unknown value says — lives in the component or a pure function.
   The banner itself needs a device.
9. Russian UI strings in **both** `values/` and `values-ru/`. Fault names come from the vehicle
   in English and must be presented without pretending they were translated.
10. Run `.\gradlew.bat :composeApp:testDebugUnitTest` green before every commit.

---

### Task 1 — a fault stays on screen

**Files:**
- Modify: `presentation/ride/RideDashboardComponent.kt` and its screen; settings for the
  duration
- Test: the ride component tests

**The rider's own specification:** *"показывать ошибки и на самом деше определенное время
(допустим минуту или по выбору юзера)"* — on the dashboard, for a set time, a minute by default,
configurable.

**Ruling — the timer starts when the fault *clears*, not when it arrives.** A fault that is
still active must stay on screen for as long as it is active, however long that is; the
configured duration is how long it **lingers after the vehicle stopped reporting it**. Starting
the timer on arrival would hide a live fault after a minute, which is the opposite of the
point.

**Ruling — faults are a stack, not a slot.** Two faults in quick succession must both be
readable; the second must not silently replace the first. Order by arrival, newest first.

**Ruling — the same fault re-arriving does not restart the list.** A controller that reports
over-voltage on every frame for four seconds produced **one** event, not two hundred. Deduplicate
by fault identity while it is continuously present, and count the repeats rather than stacking
them.

- [ ] **Step 1: Write the failing tests.** A fault arriving is exposed to the renderer; it
      stays while present; it lingers for the configured duration after clearing and then goes;
      two distinct faults both remain readable; the same fault repeating is one entry with a
      count; a duration of zero means "only while active" rather than "never show".
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement**, with the timer in the component. Per constraint 7, drive it from
      the sample clock the rest of the ride state already uses rather than a free-running delay
      loop.
- [ ] **Step 4: The setting**, defaulting to one minute, in both locales.
- [ ] **Step 5: Sweep, full suite, commit.** Say plainly that the banner's appearance needs a
      device.

```bash
git commit -m "feat(ride): a fault the vehicle reported was gone before the rider could stop"
```

---

### Task 2 — an unknown value carries its reason

**Files:**
- Modify: `domain/model/ControllerData.kt` (`SpeedSource` and its neighbours),
  `data/bms/vesc/VescValues.kt`, `domain/stats/MotionReadings.kt`
- Test: `VescValuesTest`, `MotionReadingsTest`

**Why the producer and not the UI.** The decoder knows the reason and throws it away, so no
screen can recover it. This task moves the reason across the boundary; Task 3 spends it.

**Ruling — a reason, not a message.** The producer emits a typed cause; the presentation layer
owns the words, in both locales. A decoder that returns a Russian sentence is a decoder that
cannot be tested against firmware and cannot be re-worded without touching the wire layer.

**Ruling — start with speed, and only speed.** The same shortfall applies to duty, voltage,
power and state of charge, and doing all of them at once would be a refactor of every producer
in the app on the strength of one rider report. Speed is where the evidence is. **Design the
type so the others can join it, and say in the report which ones did not.**

- [ ] **Step 1: Write the failing tests.** A controller with no wheel geometry reports "no
      geometry configured", not merely unknown; one whose firmware answers no speed frame
      reports something distinct from that; a controller that is simply stationary reports a
      **known** zero and no reason at all; the aggregate of two controllers with different
      reasons resolves to something a renderer can use rather than a contradiction.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "fix(vesc): the decoder knew why it had no speed and discarded it"
```

---

### Task 3 — say it where the rider is looking

**Files:**
- Modify: `presentation/ride/` — the hero and the Classic dial; `presentation/vehicle/` — the
  composer's wheel-geometry field
- Test: the mapper and component tests

**Why both places.** The dash is where the rider notices; the composer is where the fix is. A
dash that says "no wheel diameter configured" and a composer field that renders an unset
diameter as the literal string `0` — indistinguishable from a deliberate value, with no
validation issue behind it — would still leave them stuck.

**Ruling — a dash is not a place for a paragraph.** The gauge shows the dash it already shows;
the reason belongs one tap away, or in a line under the readout, and must not push the number
around when it appears and disappears. **Layout that moves when a fault or a reason arrives is
worse than no reason at all** on a screen being read at speed.

- [ ] **Step 1: Write the failing tests** at the mapper level: each reason maps to its own
      string key; a known value maps to no reason; the unset wheel diameter is distinguishable
      from a deliberate zero in what the composer field is handed.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement**, strings in both locales, decisions in pure code per constraint 8.
- [ ] **Step 4: Give the composer's geometry field an issue**, so an unset diameter is visible
      before a ride rather than diagnosed during one.
- [ ] **Step 5: Sweep, full suite, commit.**

```bash
git commit -m "feat(ride): a dash that says why, and a field that admits it is empty"
```

---

## Ordering

Task 2 before Task 3 — the reason must exist before it can be shown. Task 1 is independent of
both and is the one the rider asked for directly; **it can go first**.

**Part L Task 4a interacts with Task 3**: both add an issue to a composer field. Whichever lands
second should reuse the first one's mechanism rather than adding a second spelling.

## Out of scope

- **A fault history screen.** The rider asked for a fault to be *readable*, not for a log. A
  ride recorder is a schema change and its own part.
- **Acting on a fault** — clearing it, acknowledging it to the vehicle. Constraint 5 forbids the
  Begode case outright and the VESC case is a write to a controller under a rider.
- **Reasons for duty, voltage, power and SoC.** Named in Task 2 as the ones the type must be
  able to carry, deliberately not built.
