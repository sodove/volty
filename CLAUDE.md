# Volty

A Bluetooth BMS monitor becoming a **personal-EV telemetry platform**: one app that reads a
vehicle's controllers *and* its battery packs over BLE, folds them into one dashboard, and never
shows a number it has not earned.

Kotlin Multiplatform, Android target only. Compose Multiplatform, Decompose, Koin, Kable (BLE),
SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

```bash
.\gradlew.bat :composeApp:testDebugUnitTest      # the suite — run it before every commit
.\gradlew.bat :composeApp:assembleRelease        # signed APK (keystore.properties + 123.jks present)
.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration
```

Windows + PowerShell. `minSdk 26` — which rules out SQLite features newer than 3.19; see below.

---

## The rider's hardware — every design decision traces to one of these

The user is the only field tester. Three vehicles, and knowing which is which prevents most
wrong guesses:

- **Begode ET Max / EXN** (electric unicycles) — one BLE address serving *both* a controller and
  its own battery. Checksum-less 24-byte frames, notify-only. **Never write to FFE1**: it is the
  wheel's command channel (light, pedal mode, tiltback) and a stray write reconfigures a wheel
  under its rider. This is Global Constraint 5 in every plan and it is not negotiable.
- **An electric scooter** behind a **VESC Express head unit** (`nyxdash`) the rider wrote
  themselves — a CAN gateway bridging an ANT smart BMS and forwarding to a uBox controller. It
  is **the rarest device in this project, one copy in the world**. Do not treat it as the normal
  shape; it answers neither `GET_VALUES` opcode itself.
- **A bicycle** — a plain VESC on a stock Nordic-UART module, one motor, no CAN, no head unit,
  plus its own ANT BMS. **The commonest shape in the wild and the one with no field history**:
  every VESC ever tested before 2026-08-01 went through the gateway.

External sources on the rider's machine (not in this repo): VESC firmware and VESC Tool at
`C:\Users\sodovaya\Desktop\Software\vesc_tool_free_windows` (`bldc/` is the firmware,
`vesc_tool-master/` the reference client); the head unit's firmware at
`E:\sodovaya\nyxdash\firmware`. **Read them before asserting anything about the wire.**

---

## Five things that have burned people here

1. **A green sweep is not evidence.** Four mutation sweeps on this project reported false
   passes: one never started Gradle, one scored stale XML, two were served cached results. A run
   counts only with a bytecode-changing control carrying a **fresh nonce per run**, the results
   directory wiped, and the test count asserted exactly. Reuse the audited harness at
   `.superpowers/sdd/2026-07-30-vehicle-platform-I-field-fixes/t11sweep.ps1` rather than writing
   one. `BUILD SUCCESSFUL in 1s` is a cache hit and proves nothing.
2. **A fixture where every contributor is complete cannot see an incompleteness bug.** This is
   the single most productive rule in the project's history — it wrote most of Part I and all of
   Part N. Where a contract concerns absent data, build the fixture *deliberately incoherent*
   (`powerW = 4200f, hasPower = false`): a combination no producer emits, which is exactly why
   it separates the contract from the producers' habits.
3. **Compose is not unit-testable in this repo** — no Robolectric, no `compose-ui-test`, no
   instrumented source set. Every decision lives in a component or a pure function; the
   `@Composable` layer renders what it is handed. When something can only be judged on a device,
   **say so plainly instead of writing a test that dresses it up.**
4. **`runTest` hazard:** a test that starts an unbounded delayed loop makes virtual time advance
   forever and **wedges the build instead of failing**.
5. **A test can pass under the very mutant it exists to kill**, because its driver never reaches
   the path. Found six times here; the usual shape is a precondition that itself never fires.
   Assert on what was *issued* or *published*, never on wall-clock timing alone.

Also: `DROP COLUMN` needs SQLite 3.35 and even API 31 ships 3.32, so schema removal is a table
rebuild. Migrations are `N.sqm`, snapshots `N.db`, and **`N.sqm` migrates `N.db` to `(N+1).db`** —
count before writing one.

---

## How work is done here

Plans in `docs/superpowers/plans/`, specs in `docs/superpowers/specs/2026-07-24-vehicle-platform/`,
field reports under that spec's `field-reports/`. Execution is
**superpowers:subagent-driven-development**: a fresh implementer per task, a task review, a fix
loop, ledgers in `.superpowers/sdd/<plan-name>/progress.md` (git-ignored — **read the ledger
before assuming a task is undone**).

Two cultural rules that are load-bearing, not decoration:

- **Measurement beats inference, every time they disagree.** The specs were written from one
  stationary capture and a partial reading of another app; two field tests overturned a dozen of
  their conclusions, including a firmware claim that was exactly inverted. When a comment cites
  a source, **go read the source** — one citation here proved the opposite of what it was quoted
  for and survived review because nobody checked.
- **Record retractions rather than deleting them.** A silently corrected comment reads as a lost
  edit to the next reviewer; a marked one reads as a considered reversal. Several files carry
  "an earlier revision claimed X, and here is why that was wrong".

---

## Where the branch stands

`feat/vehicle-composer`, ~1640 tests green, pushed. **`main` is far behind and contains no
composer at all** — don't reason about the app from `main`.

**Done:** Parts A, B1–B3, C, D, F, G1, and G2 Tasks 1–7. **Part I complete** — eleven tasks
fixing everything the first hardware test found, released as a signed APK and confirmed working
by the rider on two of three symptoms.

**Open, planned, not started** — seven parts, all in `docs/superpowers/plans/`:

| Part | What | Why it matters |
|---|---|---|
| **N** | numbers a connection has not earned | **highest severity** — a Begode reads exactly half its true charge for the first ~6 s of *every* connect, and the alarm engine believes it |
| **L** | the setup wizard | the rider had to copy a MAC out of nRF Connect to add a vehicle; has an **approved mockup** beside the plan |
| **M** | the plain VESC link | the bicycle does not work at all: one opcode, no fallback, and a watchdog redialling a healthy link every 12 s |
| **O** | telemetry cadence | nothing ever requested a connection priority or an MTU, on any link |
| **P** | what the rider is told | faults vanish with the push notification; an unknown value cannot say why |
| **J** | read `si_` geometry from the controller | stop asking the rider for a number the controller already has |
| **K** | device identity by address | the app re-guesses a device's type from its name on every scan |

Also open: **G2 Tasks 8 and 9** (vocabulary rename; an unsaved composer must not vanish) — Task 9
is a **prerequisite of Part L Task 2**. Then a whole-branch review and the merge.

---

## If you are starting fresh, start with Part N

`docs/superpowers/plans/2026-08-02-vehicle-platform-N-unearned-numbers.md`. It is the best first
task for four reasons: it is a **confirmed wrong number**, not a feature; the diagnosis
**predicts the rider's exact reading** (a correct 80.6 % averaged with a fabricated 0 renders as
exactly the 40 % they saw); it is **entirely pure code** — no UI, no BLE, no device needed to
know you are right; and it ends in the alarm engine, so the payoff is real.

Then **G2 Tasks 8 and 9** to close the plan already in flight, then **Part L** with its mockup.

**Before building Parts M or N, get the rider to make one observation each** — both are written
into the plans, both take under a minute, and each can confirm or *demolish* the plan it
belongs to. Ask; do not build blind. Part M's may reveal that its main task fixes nothing on the
actual hardware.

**The rider is Russian-speaking and the app's UI is Russian.** Strings go in **both** `values/`
and `values-ru/`, and Compose Multiplatform does **not** process Android backslash escapes.
