# Part O — Telemetry Cadence: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The dashboard keeps up with the vehicle. Today it does not: the rider could not catch
a 1 km/h push, and reports VESC Tool as *"кратно отзывчивее"* — several times more responsive.

**Architecture:** Two untouched transport levers and one visibility gap. Nothing in this app
has ever asked the radio for a faster connection or a larger packet, so every reply is
fragmented into 20-byte pieces and every round trip waits on a slow connection interval. The
poll loop is then blamed for a limit it did not set. Separately, the cadence is bimodal and
invisible — the rider had to guess at a number the app knows exactly.

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform, Decompose, Koin,
Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

**Evidence base:** the rider, 2026-08-01 — *"скорость есть, но скорость опроса довольно низкая
(в vesctool кратно отзывчивее), я 1кмч не успевал замечать"*, and on being told the rate is
bimodal: *"может конечно у нас скорость опроса низкая"* — which they raised themselves,
unaided, about a number the app could have told them.

---

## What the numbers actually are

Measured from the code, not estimated:

| | rate | note |
|---|---|---|
| Gateway link, first ~6.6 s of every connection | **~0.9 Hz** | the head unit's two dead opcodes still being proven |
| Gateway link, steady state | **~6–9 Hz** | after Part I Task 11's suppression engages |
| Plain link | **~5 Hz** | `pollIntervalMs` 150 + `writeSpacingMs` 50, fire-and-forget |
| VESC Tool, realtime page | **~20 Hz** | 50 ms timer |

So the rider's "several times" is right, and it is worst exactly where a rider looks first — the
opening seconds of a connection.

**But the poll interval is not the binding constraint, and that is the finding of this part.**
A serial request/reply loop cannot go faster than its round trips, and a round trip is bounded
by two radio parameters this app has **never once set**:

- **Nothing requests a connection priority.** Android's default balanced interval is roughly
  30–50 ms; the high-priority interval is 11.25–15 ms. Every request *and* every reply waits on
  a connection event, so on a serial loop this multiplies through the whole cycle.
- **Nothing requests an MTU.** Every link in this app — gateway and plain alike — runs at the
  default ATT MTU of 23, i.e. a **20-byte payload**. A `COMM_GET_VALUES` reply is ~70 bytes, so
  it arrives as **four notifications** that the accumulator must reassemble. At a raised MTU it
  is one.

Neither appears anywhere in `composeApp/src`. Tuning `pollIntervalMs` without them is tuning
the one number that is not the limit.

---

## Global Constraints

1. **Non-vacuity proven per assertion, in both directions.**
2. **A zero-failure mutation run is not evidence unless the build compiled *and* the test count
   is non-zero and exactly right.** Reuse the audited harness.
3. **Sweep your own additions.**
4. **Where a contract concerns absent data, the fixture must be deliberately incoherent.**
5. **Never write to Begode's FFE1 characteristic.** A cadence change must not turn into more
   writes on a link that must receive none — Begode is notify-only and its rate is the wheel's
   choice, not ours.
6. **The battery path must not change behaviour** except where a task names it. Tasks 1 and 2
   change transport parameters for **every** link including battery links, so this constraint
   binds them hardest: the requirement is that nothing changes except how fast bytes arrive.
7. **`runTest` hazard:** an unbounded delayed loop wedges the build instead of failing.
8. **Compose UI is not unit-testable here.** Task 4's readout goes in the component.
9. Russian UI strings in **both** `values/` and `values-ru/`.
10. Run `.\gradlew.bat :composeApp:testDebugUnitTest` green before every commit.

**A constraint specific to this part: none of Tasks 1–3 can be proven by a unit test.** They
change how a radio behaves. What tests *can* hold is that the request is made, that a failure to
grant it is survived, and that nothing else moved. **Do not write a test that dresses a
throughput claim as verified** — measure on the device and say so.

---

### Task 1 — ask the radio for a faster connection

**Files:**
- Modify: `data/ble/ConnectionSession.kt` or the connect path in `KableBmsRepository.kt`;
  an `expect`/`actual` if Kable does not expose it directly
- Test: the session tests

**Why first.** It is the single biggest lever on a serial loop, it applies to every link, and it
is one call.

**Ruling — request it, survive its refusal, and drop it when nobody is looking.** A peripheral
may decline, and Android may ignore the request; neither is an error and neither may break a
link. And high priority costs battery on both ends, so it belongs to the *foreground, screen-on,
actively-watched* state — the app already tracks app resume/pause and has somewhere to hang
that. A rider whose phone is in a pocket on a two-hour ride must not pay 15 ms intervals for a
dashboard nobody is reading.

- [x] **Step 1: Write the failing tests.** The request is issued on connect; a refusal or an
      exception leaves the link fully working; the priority is lowered when the app backgrounds
      and restored on resume; **no protocol behaviour changes** — the same requests in the same
      order (constraint 6).
- [x] **Step 2: Run them and watch them fail.**
- [x] **Step 3: Implement.**
- [x] **Step 4: Sweep, full suite, commit.** Report that the throughput effect itself is
      unmeasured until a device says otherwise.

```bash
git commit -m "feat(ble): every round trip was waiting on a connection interval nobody asked to shorten"
```

---

### Task 2 — ask for a packet big enough for one reply

**Files:**
- Modify: the same connect path
- Test: the session tests, and the accumulator's reassembly tests

**Why.** A ~70-byte VESC reply currently arrives as four notifications at a 20-byte payload. The
accumulator handles that correctly — and it should keep handling it, because **the MTU request
may be refused and a Begode's frames straddle notifications regardless.**

**Ruling — raise the ceiling, change nothing else.** Reassembly stays exactly as it is. This
task must not become "now that frames are whole, simplify the accumulator": that would trade a
tested, field-proven path for an assumption about every peripheral we will ever meet.

- [x] **Step 1: Write the failing tests.** The request is issued; a refusal leaves the link
      working at the old size; **the accumulator still reassembles a fragmented reply** after
      the change, because a granted MTU is not a guarantee about any particular device; a
      single-notification reply also decodes.
- [x] **Step 2: Run them and watch them fail.**
- [x] **Step 3: Implement.**
- [x] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "feat(ble): one reply, one packet — when the peripheral agrees"
```

---

### Task 3 — the opening seconds are the slowest, and that is backwards

**Files:**
- Modify: `data/bms/VescGatewayProtocol.kt` (the suppression warm-up)
- Test: `VescGatewayProtocolTest`

**Why.** On the rider's scooter the first ~6.6 s of every connection run at ~0.9 Hz, because
suppression must prove the head unit's two dead opcodes before it stops asking them. That is
the correct rule — Part I Task 11's warm-up exists because a booting node has not refused
anything — but it lands the worst cadence of the whole session on the moment a rider is most
likely to be looking at the screen.

**Ruling — do not shorten the warm-up.** It is calibrated against a node that may still be
booting, and shortening it re-opens the defect that whole task closed. What can change is
**what the loop does while it waits**: a request known to be outstanding-and-slow should not
block the requests that answer instantly. Whether this is reachable without breaking the
strictly-serial guarantee is the task's real question — and **that guarantee is not negotiable**,
because two replies to one opcode are byte-identical and only arrival order tells them apart.

**If it turns out not to be reachable, say so and stop.** A correct 6.6 s is better than a fast
wrong answer, and this part has two other tasks that help every link unconditionally.

- [x] **Step 1: Establish reachability before writing code.** Report whether the serial
      guarantee permits any reordering at all, with the late-reply-pair analysis that Part I
      Tasks 4 and 5 established. This step's deliverable is an answer, not a change.
- [x] **Step 2: Not applicable — the serial guarantee makes the proposed reordering unreachable.**
- [x] **Step 3: Record the refusal with its reasoning.**
- [x] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "perf(vesc): the slowest cadence of the session was its first six seconds"
```

### Task 3 decision (2026-08-09)

The answering requests cannot keep their cadence while a slow request is in
flight. `runPollLoop` is deliberately one exchange at a time, and `exchange`
holds the sole `pending` expectation until the reply timeout and the late-reply
guard have both elapsed. Advancing the loop early would either put a second
`COMM_FORWARD_CAN` request in the gateway's single forwarding slot or let a late
byte-identical reply be consumed by the next controller/battery request. Both
would violate the serial guarantee that identifies bare gateway replies by
arrival order. The warm-up therefore remains unchanged; Tasks 1 and 2 are the
safe cadence levers for every link.

---

### Task 4 — tell the rider the rate

**Files:**
- Modify: `presentation/ride/RideDashboardComponent.kt` and the diagnostics surface
- Test: the ride component tests

**Why.** The rider diagnosed a bimodal poll rate from feel, unaided, and was right. The app
knows the number exactly and never says it. That is a question the app can answer about itself
and does not.

**Ruling — a rate, not a graph, and where a rider already looks for truth.** This is not a
dashboard gauge; it is diagnostics. It should say the sample rate, and — because the rate is
bimodal by design — **whether suppression has engaged**, since that is the difference between
"still proving your head unit" and "this is as fast as it gets".

- [x] **Step 1: Write the failing tests.** The component exposes a sample rate derived from
      actual arrivals, not from the configured interval — a configured rate the link is not
      achieving is exactly the thing worth showing; it reports the warm-up state distinctly
      from steady state; a link with no samples reports **no rate rather than zero** (the same
      contract Part I spent itself on).
- [x] **Step 2: Run them and watch them fail.**
- [x] **Step 3: Implement**, decision in the component per constraint 8, strings in both locales.
- [x] **Step 4: Sweep, full suite, commit.**

```bash
git commit -m "feat(diag): the app knew its own sample rate and never said it"
```

---

## What needs the device

Tasks 1–3 are unprovable in this repo. The measurement that settles them:

**Connect to the scooter, and to the bicycle, and record the interval between motion samples** —
before and after each task. Task 4 exists partly so this measurement can be read off the screen
instead of a logcat. The target to beat is VESC Tool on the same phone and the same vehicle,
which is the only fair comparison available.

## Out of scope

- **Raising `pollIntervalMs`.** It is not the binding constraint and changing it first would
  mask whether Tasks 1 and 2 did anything.
- **Anything about Begode's rate.** A wheel streams unprompted, at its own cadence, and the app
  must never write to it. Tasks 1 and 2 still help it — fewer, larger notifications — but there
  is no request to tune.
