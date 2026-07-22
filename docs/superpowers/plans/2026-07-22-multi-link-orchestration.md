# Multi-Link Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The repository can hold N BLE links for one vehicle — the behaviour-neutral foundation that makes a group of independent BMS possible.

**Architecture:** Links are keyed by distinct BLE address; each owns a set of global pack indices and translates its session's local index to them. Sample enrichment (voltage scaling, SoC estimate) stays on each session's own coroutine because it depends on that link's protocol; only the shared-state mutations — `VehicleConnection.submit`, ring buffer, `_activeData` — are serialised, through a single `Channel` drained by one consumer coroutine. Connection state folds over the links. No stored vehicle has packs at more than one address until sub-project B, so nothing a user sees changes.

**Tech Stack:** Kotlin Multiplatform (androidTarget), kotlinx.coroutines (Channel), kotlin.test + Turbine.

**Spec:** `docs/superpowers/specs/2026-07-22-multi-link-orchestration-design.md`

## Global Constraints

- Package `ru.sodovaya.volty`, module `composeApp`. Common code in `commonMain`, tests in `commonTest`.
- Compile: `./gradlew :composeApp:compileDebugKotlinAndroid`; full suite: `./gradlew :composeApp:testDebugUnitTest`.
- Comments in English.
- **Behaviour-neutral.** A single-link vehicle (every existing user, and Begode over its one address) must behave byte-identically. `KableBmsRepositoryDisconnectRaceTest` and `KableBmsRepositoryOnAppResumedTest` must pass **unedited** — that is the signal the delicate disconnect-vs-reconnect lifecycle was not disturbed.
- `sessionLock` is a non-reentrant `Mutex`. Every `vehicleConnection` and link write goes through it; never take it from a path that already holds it.
- `VehicleConnection` stays free of coroutines, locks, atomics and `suspend`.
- **Never write to a BLE characteristic** beyond what a protocol's (empty, for Begode) command lists dictate.
- After every task the whole suite is green.

---

### Task 1: Pack-to-link mapping

Pure grouping of a vehicle's packs into links, each owning global indices with a local→global translation. No BLE, no coroutines — fully unit-tested.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/LinkPlan.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/LinkPlanTest.kt`

**Interfaces:**
- Consumes: `Pack` (has `index`, `bmsType`, `bmsAddress`).
- Produces: `data class LinkSpec(val address: String, val bmsType: BmsType, val ownedIndices: List<Int>)` with `fun globalIndex(local: Int): Int = ownedIndices[local]`; `fun planLinks(packs: List<Pack>): List<LinkSpec>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkPlanTest {

    private fun pack(index: Int, addr: String, type: BmsType = BmsType.ANT_BMS) =
        Pack(index = index, label = "P$index", bmsType = type, bmsAddress = addr)

    @Test
    fun beGodeTwoPacksOneAddressIsOneLink() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.BEGODE), pack(1, "AA", BmsType.BEGODE)))
        assertEquals(1, links.size)
        assertEquals("AA", links[0].address)
        assertEquals(listOf(0, 1), links[0].ownedIndices)
        assertEquals(BmsType.BEGODE, links[0].bmsType)
    }

    @Test
    fun twoAntPacksTwoAddressesAreTwoLinks() {
        val links = planLinks(listOf(pack(0, "AA"), pack(1, "BB")))
        assertEquals(2, links.size)
        assertEquals(listOf(0), links[0].ownedIndices)
        assertEquals(listOf(1), links[1].ownedIndices)
    }

    @Test
    fun localToGlobalTranslatesWithinALink() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.BEGODE), pack(1, "AA", BmsType.BEGODE)))
        assertEquals(0, links[0].globalIndex(0))
        assertEquals(1, links[0].globalIndex(1))
    }

    @Test
    fun mixedBegodePlusAuxSplitsCorrectly() {
        val links = planLinks(
            listOf(
                pack(0, "AA", BmsType.BEGODE),
                pack(1, "AA", BmsType.BEGODE),
                pack(2, "BB", BmsType.JBD_BMS)
            )
        )
        assertEquals(2, links.size)
        assertEquals(listOf(0, 1), links.first { it.address == "AA" }.ownedIndices)
        assertEquals(listOf(2), links.first { it.address == "BB" }.ownedIndices)
    }

    @Test
    fun ownedIndicesAreSortedByPackIndex() {
        // Packs may arrive unsorted; each link's owned indices must be ascending.
        val links = planLinks(listOf(pack(2, "AA"), pack(0, "AA"), pack(1, "AA")))
        assertEquals(listOf(0, 1, 2), links[0].ownedIndices)
    }

    @Test
    fun linkOrderFollowsFirstAppearanceOfEachAddress() {
        val links = planLinks(listOf(pack(0, "BB"), pack(1, "AA")))
        assertEquals(listOf("BB", "AA"), links.map { it.address })
    }

    @Test
    fun aSingleLinkVehicleYieldsExactlyOneLink() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.JK_BMS)))
        assertEquals(1, links.size)
        assertEquals(listOf(0), links[0].ownedIndices)
    }
}
```

- [ ] **Step 2: Run, observe fail** — `Unresolved reference: planLinks`.

- [ ] **Step 3: Implement `LinkPlan.kt`**

```kotlin
package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack

/**
 * One BLE link: a distinct address, the BMS type behind it, and the vehicle's
 * global pack indices it is responsible for, in local order. A session speaks
 * local indices (0-based within its own protocol); [globalIndex] maps them to
 * the vehicle's pack indices the orchestrator is keyed by.
 */
data class LinkSpec(
    val address: String,
    val bmsType: BmsType,
    val ownedIndices: List<Int>
) {
    fun globalIndex(local: Int): Int = ownedIndices[local]
}

/**
 * Group a vehicle's packs into links by distinct address. A Begode's two
 * packs share one address and form one link owning [0, 1]; a group of two
 * independent BMS at two addresses forms two links owning [0] and [1]. Link
 * order follows each address's first appearance; each link's owned indices
 * are ascending. Pure — no BLE, no ordering assumption on the input.
 */
fun planLinks(packs: List<Pack>): List<LinkSpec> {
    val byAddress = LinkedHashMap<String, MutableList<Pack>>()
    for (p in packs) byAddress.getOrPut(p.bmsAddress) { mutableListOf() }.add(p)
    return byAddress.map { (address, group) ->
        val sorted = group.sortedBy { it.index }
        LinkSpec(
            address = address,
            bmsType = sorted.first().bmsType,
            ownedIndices = sorted.map { it.index }
        )
    }
}
```

- [ ] **Step 4: Run, observe pass (7 tests).**
- [ ] **Step 5: Full suite green.** `./gradlew :composeApp:testDebugUnitTest`
- [ ] **Step 6: Commit.** `git add … && git commit -m "feat(ble): plan links from a vehicle's packs by address"`

---

### Task 2: Serialise the funnel through a channel (single link, neutral)

Move the shared-state mutations (submit, ring buffer, `_activeData`) off the session coroutine and behind a single `Channel` drained by one consumer. Enrichment stays on the session coroutine, ahead of the channel. Still one link, so this is behaviour-neutral — its whole point is that the race tests and single-link behaviour are untouched while the serialisation point is introduced.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt` (the `SamplePipeline` / `buildSamplePipeline` / `onSample` region, and where the consumer is launched and torn down)
- Test: extend `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/SampleRoutingTest.kt` (or the existing routing test file)

**Interfaces:**
- Consumes: `LinkSpec` from Task 1 (not yet used for fan-out — Task 3 — but the enrichment now takes a global index).
- Produces: a `Channel<PackSample>` funnel where `data class PackSample(val globalPackIndex: Int, val data: BmsData, val sections: List<SectionState>)`; the per-session `onSample` becomes enrich-then-`channel.trySend`; a single consumer coroutine performs `submit` + ring buffer + `_activeData`.

**Contract for the implementer (write the body; this is the delicate core, so it is specified by behaviour, not verbatim):**

- Keep the enrichment chain exactly as it is today — `withScaledBegodeLiveVoltage` then `VoltageSocEstimator.withEstimatedSoc` — but it now runs in `onSample` on the session coroutine and produces an enriched `BmsData`, which is sent into the channel together with the global pack index and sections. For a single link the global index equals the local index the session already passes; introduce the translation seam now (identity for one link) so Task 3 only has to populate it.
- The consumer coroutine, one per connection, drains the channel and performs, in this order and with the existing comment preserved: `vehicleConnection?.submit(globalPackIndex, data, sections)?.aggregate ?: data`, then `ringBuffer.push(forActive)`, then `_activeData.value = forActive`. The ring-buffer-before-`_activeData` ordering is load-bearing (the graph collector maps over `_activeData` and reads the buffer) — preserve it and its comment.
- The channel and its consumer are created in `doConnect` alongside the orchestrator and torn down with the session, under the existing `sessionLock` discipline. Closing the channel must not deadlock teardown; the consumer must end cleanly when the channel closes. Do not change any other lifecycle step.
- The channel writer is the session's `onSample`; use `trySend` (the buffer must be large enough that a burst is never dropped at 1–6 Hz) or document why a suspending send on the session coroutine is safe. If you choose `trySend`, a full-buffer drop must be logged, never silent.

**Steps:**

- [ ] **Step 1: Write a failing test** that drives samples through the real funnel (via the existing `installSampleFunnelForTest` seam or its successor) and asserts that a sample submitted for a pack reaches `_activeVehicleData`/`_activeData` with its enrichment intact and in order, now that a channel sits in between. Include a test that ordering (buffer before activeData) still holds — e.g. the graph window contains the sample when `_activeData` announces it.
- [ ] **Step 2: Run, observe fail.**
- [ ] **Step 3: Implement** per the contract above.
- [ ] **Step 4: Run the new test — pass.**
- [ ] **Step 5: The neutrality gate.** `KableBmsRepositoryDisconnectRaceTest`, `KableBmsRepositoryOnAppResumedTest`, `SampleRoutingTest`, `KableBmsRepositoryPackSizingTest` and the Begode tests pass **unedited**. Full suite green.
- [ ] **Step 6: Commit.** `git commit -m "refactor(ble): serialise the sample funnel through a channel"`

---

### Task 3: Session fan-out, per-link reconnect, state fold

`doConnect` raises one link per `LinkSpec` instead of one session. Each link has its own reconnect loop and target; the vehicle's `ConnectionState` folds over the links; a dropped link reconnects independently while the vehicle stays connected on the others. This is the coupled heart — it lands on the already-serialised funnel from Task 2.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt` (the single-session fields, `doConnect`, `startReconnectLoop`, `onSessionDrop`, `disconnect`, `onAppResumed`)
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/PackLink.kt` (internal — one link's session + reconnect target + liveness)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryMultiLinkTest.kt`

**Interfaces:**
- Consumes: `planLinks` (Task 1), the channel funnel (Task 2), `LinkSpec`.
- Produces: N-link connect; `ConnectionState` folded from link states; per-link independent reconnect.

**Contract for the implementer (behaviour-specified — this touches the project's most delicate file):**

- Replace the single `currentSession`/`reconnectJob`/`lastConnectionTarget` with a per-link structure. Each link owns a `ConnectionSession`, a reconnect job, and a target carrying its `LinkSpec` (so its session's local sample index is translated to global before entering the channel).
- `doConnect(vehicle)` computes `planLinks(vehicle.packs)`, builds ONE `VehicleConnection` for all packs and ONE channel+consumer (Task 2), then raises each link with a start stagger of `BleConfig.linkStaggerMs` (add the constant; ~300 ms). Each link's `onSample` translates local→global via its `LinkSpec` and sends into the shared channel.
- The vehicle is `Connecting` until the first link yields a sample, then `Connected`; it stays `Connected` while any link is online; `Reconnecting` when all are down but at least one is retrying; `Failed` when all have given up. Implement this as a fold over the links' states — do not scatter state transitions.
- A link that drops runs its own reconnect loop (today's `startReconnectLoop` behaviour, per link) without disturbing the others. The existing disconnect-vs-reconnect race handling must be preserved per link — this is why the two race tests must keep passing unedited.
- `disconnect` tears down all links, cancels all reconnect loops, closes the channel, clears the orchestrator — under `sessionLock`, never taking it twice.
- `onAppResumed` re-checks each link's freshness and reconnects the stale ones.
- **The single-link path must remain byte-identical in behaviour.** A vehicle whose packs share one address raises exactly one link; the fold degenerates to today's state machine.

**Tests (`KableBmsRepositoryMultiLinkTest`, on fakes — no real BLE):**

- [ ] Two links, samples interleaved from separate coroutines into the channel: `VehicleConnection` ends with both packs online, aggregate consistent (current sums both).
- [ ] First link online → `Connected`; second still connecting → `isPartial` until it lands.
- [ ] One of two links drops → vehicle stays `Connected`, `isPartial=true`, only the dropped link's reconnect loop runs.
- [ ] Both links drop → `Reconnecting`; all give up → `Failed`.
- [ ] Initial partial: two links declared, one answers → `Connected` with the other offline and retrying.
- [ ] Single-link vehicle: one link raised, behaviour identical; the two race tests and `OnAppResumed` pass unedited.

Assert concrete values, tight tolerances.

- [ ] **Steps:** failing tests first, observe red, implement per contract, green, then the full neutrality gate (race + resume tests unedited), commit as `feat(ble): raise one BLE link per address, fold their states`.

---

## Self-review checklist (run before dispatching Task 1)

- Spec coverage: pack-to-link mapping → T1; channel barrier → T2; fan-out + fold + per-link reconnect → T3; neutrality → the gate in T2 and T3.
- No placeholder: T1 is fully verbatim; T2 and T3 bodies are contract-specified deliberately because they rewrite the race-sensitive core, where a strong implementer working in-file under adversarial review has beaten verbatim dictation all session — the contracts pin behaviour, ordering, locking and the neutrality gate precisely.
- Type consistency: `LinkSpec`, `planLinks`, `PackSample`, `PackLink` names are used consistently across tasks.
