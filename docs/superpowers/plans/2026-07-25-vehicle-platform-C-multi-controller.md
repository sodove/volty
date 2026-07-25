# Part C — Multi-controller, CAN forwarding & the head-unit gateway — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** make one BLE link carry a whole vehicle. Today a link speaks to exactly one controller or one battery. After Part C, a single link to a VESC Express head unit carries **several CAN controllers plus a hosted battery**, which is the product owner's actual scooter: nyxdash + 2×uBox on CAN + an ANT battery the head unit owns.

**Architecture.** The aggregation already exists — `MotionAggregator` and `PackAggregator` (Part A) fold many sources into one vehicle. Part C adds the **transport** (`PING_CAN`, `FORWARD_CAN`) and a **gateway multiplexer** that decodes several sources off one link. Independent controller links (dual-VESC-BLE) need no new transport at all: that is Part A's multi-link orchestration plus Part B's `VescProtocol` twice.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform (Android target), Kable, SQLDelight, kotlin.test + Turbine.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-24-vehicle-platform/C-multi-controller.md`. **Read §10 first** — all four of its open questions were pinned from source on 2026-07-25 and several answers constrain the design hard. Also `01-linking.md` and `A-foundation.md §4`.
- Branch `feat/can-gateway`, off `main` (Part B closed at `46adefb`).
- Package root `ru.sodovaya.volty`; tests `kotlin.test` + Turbine.
- **710 tests are green at branch point — all stay green.**
- **The existing single-link paths must not change behaviourally.** A user with one BMS, or one directly-connected VESC, must see zero difference. Verify cumulatively at the end, not per-task.
- No Compose UI test harness exists in this repo. Part C is nearly all non-UI; do not add one, and claim no UI coverage.
- `./gradlew :composeApp:testDebugUnitTest` and `:composeApp:compileDebugKotlinAndroid` must pass before each commit. Commit after every task.
- **This project has shipped by-construction assertions repeatedly.** After each assertion ask whether it would fail if the code were wrong, and report which carry real discriminating power.

## The three pinned facts that shape everything

1. **Forwarded requests must be serialised — exactly one in flight.** `FORWARD_CAN` replies come back **bare**: no wrapper, no source id. The gateway keeps a *single* `send_func_can_fwd` and a *single* `rx_buffer_last_id`, so a second forward before the first reply lands races state **on the gateway**, whatever the client does. Two replies to the same inner opcode are byte-identical; only arrival order distinguishes them. Spec §10.1.
2. **`PING_CAN` blocks the gateway ~2.55 s** (255 ids × 10 ms, no early exit) and a second `PING_CAN` arriving meanwhile is **silently dropped — no error reply**. Spec §10.2.
3. **An ANT battery behind the gateway leaves fields at zero**: `v_charge`, humidity, pressure, status, the four charge/discharge totals, `data_version`. Absent must not render as a measured zero. Spec §10.5.

---

### Task 1: CAN transport primitives — pure codec

**Files:** create `data/bms/vesc/VescCan.kt`; test `commonTest/.../vesc/VescCanTest.kt`.

**Interfaces:**
- `fun forwardCan(canId: Int, inner: ByteArray): ByteArray` — builds the `COMM_FORWARD_CAN` (34) payload `[34, canId, inner…]`. Reuse Part B's `VescPacket` framing; this is the payload only.
- `fun parsePingCan(payload: ByteArray): List<Int>` — payload is `[62][id][id]…`, **one raw byte per responding id, no count prefix and no terminator** (spec §10.2). Ids are 0..254 ascending; 255 is never probed. Length comes from the frame.

- [ ] **Step 1: Write the failing test** — `forwardCan` byte layout for a couple of inner commands; `parsePingCan` on an empty list, a single id, several ascending ids, and a payload whose first byte is the opcode (confirm it is consumed, not returned as an id). Assert ids stay in wire order.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Non-vacuity** — swap the opcode/canId order in `forwardCan` and show the test fails; restore, record the output.
- [ ] **Step 4: Tests + compile.**
- [ ] **Step 5: Commit** — `feat(can): FORWARD_CAN and PING_CAN codec`

---

### Task 2: VESC-BMS decoder (`COMM_BMS_GET_VALUES`, 96)

**Files:** create `data/bms/vesc/VescBmsValues.kt`; test alongside.

Field order and scales are in spec **§4**, verified field-by-field against the firmware serialiser in §10.4. Decode with Part B's `VescReader`. **Three traps §4 does not mention — all three are in §10.4, read it:**
- `pressure` uses scale `1e-1`, so decoding is `raw × 10` — the inverse of every other d16 in the frame.
- `can_id` is an int truncated to a byte and initialised to `-1`, so "no data yet" arrives as **`0xFF`**, not `0`.
- Trailing fields are gated on **remaining byte count**, and the four charge/discharge totals are **all-or-nothing** (`>= 16` bytes; they are `float32_auto`, a bit-packed custom float, *not* a fixed divisor). Follow VESC Tool's exact order.

**The requirement that matters most:** an ANT battery behind the gateway leaves `v_charge`, humidity, pressure, status, the four totals and `data_version` at zero (§10.5). **The decoder must distinguish "absent" from "measured zero"** — model the optional tail as nullable/absent rather than defaulting to `0.0`, or the Battery screen will present a 0.0 V charge voltage and 0% humidity as real readings. Say in your report how you expressed that.

Map to `BmsData`: `voltage = v_tot`, `soc = soc × 100`, `socKnown = true` (this BMS coulomb-counts), cells, temperatures, balancing flags. **Sign: `i_in > 0` means charging** (§10.4) — which already matches `BmsData`'s convention, so no flip. `BmsType.VESC_BMS`.

- [ ] **Step 1: Write the failing test** — build a byte frame by hand from the field table and decode it: a full frame, a frame truncated before the totals, one truncated before `pressure`, and the ANT-shaped frame (totals present but zero vs genuinely absent). Assert `pressure`'s ×10, `can_id == 0xFF` meaning no-data, and that absent tail fields are absent rather than zero.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Non-vacuity** — invert `pressure`'s scale (divide instead of multiply) and show a test fails; restore, record it.
- [ ] **Step 4: Tests + compile.**
- [ ] **Step 5: Commit** — `feat(vesc): COMM_BMS_GET_VALUES decoder`

---

### Task 3: `LinkSpec` learns CAN sources

**Files:** modify `data/ble/LinkPlan.kt`; extend `LinkPlanTest`.

Part A left `OwnedSource(globalIndex, canId)` and `planLinks` **asserts `canId == null`**. Lift it, per spec §6:
```kotlin
data class OwnedSource(
    val globalIndex: Int,
    val canId: Int? = null,
    val kind: ProtocolKind? = null   // for CAN/hosted sources decoded differently
)                                    // from the gateway link's own kind
```
`planLinks` now accepts `canId != null`, groups by gateway `address`, and tags each owned source with its kind. A gateway `LinkSpec` (kind `VESC`) can own controllers (`kind = VESC`, various `canId`) **and** a battery (`kind = VESC_BMS`, hosted, no canId).

**Do not weaken the existing conflict rule**: one address still resolves to exactly one *link* protocol kind. What changes is that sources *behind* that link may differ from it.

- [ ] **Step 1: Write the failing test** — the product owner's scooter as one link with three sources (2 controllers at distinct canIds + one hosted VESC_BMS), asserting one `LinkSpec` with the right owned sets and kinds. Keep a test proving conflicting *link* kinds at one address is still rejected.
- [ ] **Step 2: Implement.** Search for every reader of `OwnedSource`/`LinkSpec` and confirm none assumed `canId == null`; report what you found.
- [ ] **Step 3: Tests + compile** — the whole existing `LinkPlanTest` must stay green; if any test asserted the old rejection, replace it deliberately and say so.
- [ ] **Step 4: Commit** — `feat(link): CAN-forwarded and hosted sources in LinkSpec`

---

### Task 4: `VescGatewayProtocol` — the serialised multiplexer

**Files:** create `data/bms/VescGatewayProtocol.kt`; tests alongside.

One `BmsProtocol + MotionSource` per gateway link, decoding several sources. It supersedes the single `VescProtocol` when a link owns more than one source or any CAN source. **Wire it into `controllerMotionProtocol`/`createProtocol` (`data/ble/ControllerProtocols.kt`) — that is the single statement of controller coverage; do not add a second decision point.**

**The poll loop is strictly serial** (§10.1) — this is the core of the task:
- one owned source at a time: send → **await its bare reply, matched by expected opcode with a timeout** → advance. Never two forwards in flight.
- controllers: `FORWARD_CAN(canId, GET_VALUES)` for per-unit duty/current/rpm/temps/fault.
- speed: **one** `GET_VALUES_SETUP` to the primary controller per cycle (spec §9.3) — every extra forward is a full serialised round-trip, so do not send SETUP to all.
- hosted battery: `BMS_GET_VALUES` **unwrapped** — the gateway answers it itself, no forwarding.
- a source that times out is skipped this cycle and the loop moves on; it must never wedge the loop.

**Sleeping controllers come for free**: no reply → no sample → the existing staleness sweep marks that controller offline while the hosted battery keeps reporting. A uBox that wakes materialises through the existing latent-slot machinery. Do not build a second mechanism for this — verify the existing one covers it, and say so.

Routing: each decoded reply goes to its **global** index (`MotionSource.latestMotion` / `latestData(packIndex)`), taken from the `LinkSpec`'s owned sources — never a positional guess.

- [ ] **Step 1: Write the failing test** — a fake link that scripts replies. One link, two controllers + one hosted BMS: round-robin visits every source; each reply routes to the right global index; **only one forward is outstanding at any moment** (assert this directly — it is the property the gateway's shared state demands); a silent controller goes offline while the BMS stays online; it recovers when it answers again.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Non-vacuity** — make the loop fire both forwards before awaiting, and show the one-in-flight test fails; restore and record it.
- [ ] **Step 4: Tests + compile.**
- [ ] **Step 5: Commit** — `feat(vesc): gateway multiplexer over one BLE link`

---

### Task 5: Alias-path handoff — release the ANT while riding

**Files:** `data/ble/KableBmsRepository.kt` (or wherever link raising lives), plus the per-vehicle toggle.

The ANT battery has two paths in one vehicle, sharing an `aliasGroup`: **direct ANT** (parked) and **hosted VESC-BMS via the head unit** (riding, because the head unit owns the ANT's single central slot). `PackAggregator`'s alias collapse (A §3.1) already counts it once and keeps it visible on whichever path is online — do not duplicate that.

What Part C adds is contention avoidance:
- when the head-unit link is up **and the hosted BMS is actually reporting**, release the direct ANT link via `disconnectLink(antAddress)` (A §4.6);
- when the head-unit link drops, re-raise the direct ANT link.

**Trigger on the hosted BMS actually reporting, not merely on the link being up** (spec §9.4) — otherwise a momentarily silent hosted BMS leaves a battery gap with both paths down.

Per-vehicle toggle **"yield BMS to head unit while riding"**, defaulting **on** when an alias group spans a direct BMS and a gateway-hosted BMS. Persisting it needs a schema migration — check whether `AlertConfig`/`VehicleRow` can carry it without one, and if a migration is genuinely required, write the `.sqm` **and** update the `.sq` so fresh installs and upgrades agree.

- [ ] **Step 1: Write the failing test** — alias group spanning direct ANT + hosted VESC-BMS: raising the head-unit link and delivering a hosted sample releases the direct link; a head-unit link that is up but silent does **not**; dropping it re-raises; **no gap in the aggregated battery across the swap** (that last one is the user-visible property).
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Tests + compile.**
- [ ] **Step 4: Commit** — `feat(ble): yield the direct BMS to the head unit while riding`

---

### Task 6: Independent controller links — verify, don't build

**Files:** tests only, unless a gap turns up.

Two separate BLE links, each a single-controller `VescProtocol`, is **already** supported: Part A's multi-link orchestration raises both and `MotionAggregator` folds them (speed/duty max, current/power sum, temps max, odo/trip max). Part E's FarDriver AWD reuses this verbatim.

This task is a **characterisation** task: prove it works, and if it does not, that discovery is the deliverable.

- [ ] **Step 1: Write tests** — a two-controller, two-link vehicle: both links raise; the aggregate sums current and power, takes the max of speed and duty; one link dropping leaves the other reporting; the vehicle stays online.
- [ ] **Step 2: Report honestly** — if it already works, say so plainly and change nothing. If a gap exists, fix it and flag it prominently as a Part A/B defect found late.
- [ ] **Step 3: Commit** — `test(motion): independent controller links fold into one vehicle`

---

### Task 7: Verification pass

- [ ] **Step 1:** Full suite + `assembleDebug`.
- [ ] **Step 2: Prove the single-link paths did not move** — a one-BMS vehicle and a one-VESC vehicle must behave exactly as on `main`. Enumerate what you compared.
- [ ] **Step 3: Emulator pass** — seed a gateway-shaped vehicle (one link, two controllers, one hosted battery) directly into the database as Part G1's verification did, and walk every screen: Ride with two controllers folded, Battery on the hosted pack, Settings, Vehicle Edit. The emulator has no BLE, so the transport itself cannot be exercised there — say so rather than implying coverage.
- [ ] **Step 4: List what only the real head unit can settle** — the forwarded-reply timing budget, whether `PING_CAN`'s ~2.55 s blocking disturbs the poll loop in practice, and the ANT-behind-gateway field coverage. These are the product owner's to run.

---

## Self-Review

- [ ] Is exactly one forwarded request ever in flight, and is that asserted by a test rather than assumed?
- [ ] Does a sleeping controller go offline without wedging the loop or disturbing the hosted battery?
- [ ] Does the BMS decoder distinguish absent tail fields from measured zeros?
- [ ] Is `pressure` multiplied by 10, and is `can_id == 0xFF` read as "no data"?
- [ ] Does the handoff trigger on the hosted BMS reporting, not merely on the link being up?
- [ ] Did the single-link BMS and single-VESC paths stay behaviourally identical?
- [ ] If a migration was written, do the `.sq` and `.sqm` agree?
