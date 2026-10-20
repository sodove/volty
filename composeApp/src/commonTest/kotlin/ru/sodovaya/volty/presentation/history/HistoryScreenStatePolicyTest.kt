package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryScreenStatePolicyTest {
    private val origin = HistoryTick(1000, 10, 1)
    private val range = HistoryRange(1000, 5000)
    private val ride = HistoryRide("a", "bike", origin, HistoryTick(5000, 4010, 1), HistoryRideStatus.COMPLETED)
    private val detail = HistoryDetailState(rideId = "a", ride = ride, range = range, availableRange = range)
    private val route = HistoryRouteState(rideId = "a", range = range, loaded = true,
        fixes = listOf(HistoryGpsFix(origin, 56.0, 60.0, 3.0, null)))
    private val ready = HistoryUiState(HistoryDestination.RIDE, "a", detail = detail, route = route)

    @Test fun readyAndFullscreenUseTheSameQualifiedFacts() {
        for (destination in listOf(HistoryDestination.RIDE, HistoryDestination.CHARTS)) {
            val result = historyScreenContent(ready.copy(destination = destination))
            assertEquals(HistoryScreenPhase.READY, result.phase)
            assertSame(detail, result.detail); assertSame(route, result.route)
        }
    }
    @Test fun catalogAndLoadingNeverExposeRetainedOldFacts() {
        for (state in listOf(ready.copy(destination = HistoryDestination.CATALOG),
            ready.copy(detail = detail.copy(loading = true)), ready.copy(selectedRideId = "b"),
            ready.copy(detail = detail.copy(rideId = "b")), ready.copy(detail = detail.copy(ride = ride.copy(id = "b"))),
            ready.copy(detail = detail.copy(range = null)))) {
            val result = historyScreenContent(state)
            assertNotEquals(HistoryScreenPhase.READY, result.phase)
            assertNull(result.detail); assertNull(result.route)
        }
    }
    @Test fun missingAndStorageErrorHaveDifferentVisiblePhases() {
        assertEquals(HistoryScreenPhase.NOT_FOUND, historyScreenContent(ready.copy(detail = detail.copy(error = HistoryReadError.NOT_FOUND))).phase)
        val result = historyScreenContent(ready.copy(detail = detail.copy(error = HistoryReadError.STORAGE)))
        assertEquals(HistoryScreenPhase.ERROR, result.phase); assertNull(result.detail); assertNull(result.route)
    }
    @Test fun oldRideOrRangeRouteCannotBorrowTheSelectedDetail() {
        for (foreign in listOf(route.copy(rideId = "b"), route.copy(range = HistoryRange(2000, 4000)))) {
            val result = historyScreenContent(ready.copy(route = foreign))
            assertEquals(HistoryScreenPhase.READY, result.phase); assertSame(detail, result.detail)
            assertNull(result.route)
        }
    }
    @Test fun partialErroredAndGpslessFactsKeepTheirOwnQuality() {
        for (snapshot in listOf(route.copy(error = HistoryReadError.STORAGE, truncated = true),
            route.copy(fixes = emptyList()), route.copy(loading = true, loaded = false))) {
            val result = historyScreenContent(ready.copy(route = snapshot))
            assertSame(snapshot, result.route)
        }
    }
    @Test fun neighborsUseLoadedOrderWithoutWrapOrTimestampGuessing() {
        assertEquals(HistoryRideNeighbors("z", "b"), historyRideNeighbors(listOf("z", "a", "b"), "a"))
        assertEquals(HistoryRideNeighbors(null, "a"), historyRideNeighbors(listOf("z", "a", "b"), "z"))
        assertEquals(HistoryRideNeighbors("a", null), historyRideNeighbors(listOf("z", "a", "b"), "b"))
        for (ids in listOf(emptyList(), listOf("a"), listOf("a", "b", "a"))) {
            assertEquals(HistoryRideNeighbors(), historyRideNeighbors(ids, "a"))
        }
        assertEquals(HistoryRideNeighbors(), historyRideNeighbors(listOf("a", "b"), "missing"))
        assertEquals(HistoryRideNeighbors(), historyRideNeighbors(listOf("a", "b"), null))
    }
}
