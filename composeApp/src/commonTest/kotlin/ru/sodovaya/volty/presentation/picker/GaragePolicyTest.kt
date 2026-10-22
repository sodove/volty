package ru.sodovaya.volty.presentation.picker

import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class GaragePolicyTest {
    private fun v(id: String, connectedAt: Long? = null, pinned: Boolean = false) = Vehicle(id, id, "bicycle",
        listOf(Pack(0, "Pack", BmsType.ANT_BMS, "AA:$id")), emptyList(),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0),
        lastConnectedAt = connectedAt?.let { Instant.fromEpochSeconds(it) }, isPinned = pinned)

    @Test fun heroIsPinnedThenLastConnectedAndNearbyOnlyBreaksTies() {
        val old = v("old", 100); val recent = v("recent", 900); val near = v("near", 50); val pinned = v("pinned", 10, pinned = true)
        assertEquals("recent", garageLayout(listOf(old, recent), emptySet()).hero?.id)
        // The scooter parked nearby must not take the top from the wheel just ridden.
        assertEquals("recent", garageLayout(listOf(old, recent, near), setOf("near")).hero?.id)
        val layout = garageLayout(listOf(old, recent, near, pinned), setOf("near"))
        assertEquals("pinned", layout.hero?.id)
        assertEquals(listOf("recent", "old", "near"), layout.others.map { it.id })
        assertEquals("b", garageLayout(listOf(v("a"), v("b")), setOf("b")).hero?.id, "never connected: nearby first")
    }

    @Test fun neverConnectedVehiclesGoLastAndAnEmptyGarageHasNoHero() {
        assertEquals(listOf("a", "b"), garageLayout(listOf(v("b"), v("a", 5)), emptySet()).let { listOf(it.hero!!.id) + it.others.map { o -> o.id } })
        assertNull(garageLayout(emptyList(), emptySet()).hero)
    }

    @Test fun whenCountsCalendarDaysNotHours() {
        val utc = TimeZone.UTC
        val day = 86_400_000L
        val now = 10 * day + 10 * 60_000 // 00:10 on day 10
        assertEquals(GarageWhen.Today(0, 5), garageWhen(10 * day + 5 * 60_000, now, utc))
        assertEquals(GarageWhen.Yesterday(23, 50), garageWhen(10 * day - 10 * 60_000, now, utc), "twenty minutes ago is still yesterday")
        assertEquals(GarageWhen.DaysAgo(6), garageWhen(4 * day, now, utc))
        assertEquals(GarageWhen.Date(3, 1), garageWhen(2 * day, now, utc))
    }
}
