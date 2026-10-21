package ru.sodovaya.volty.presentation.history

import kotlin.test.*
import ru.sodovaya.volty.domain.history.*

class HistoryMediaPositionsTest {
    private val origin = HistoryTick(100_000, 1000, 3)
    private val ride = HistoryRide("ride", "bike", origin, origin.copy(epochMillis = 200_000, elapsedMillis = 101_000), HistoryRideStatus.COMPLETED)
    private val favorite = HistoryFavorite("fav", ride.favoriteSource(), HistoryRange(100_000, 200_000), "Ride", 1, 10)
    private fun tick(delta: Long) = origin.copy(epochMillis = origin.epochMillis + delta, elapsedMillis = origin.elapsedMillis + delta)
    private fun fix(delta: Long) = HistoryGpsFix(tick(delta), 56.0 + delta / 1_000_000.0, 60.0, 3.0, null)
    private fun item(captured: Long?) = HistoryMediaRecord("photo", "fav", "image/jpeg", captured, 3, "a".repeat(64), HistoryMediaStatus.READY)
    private fun route(fixes: List<HistoryGpsFix> = listOf(fix(0), fix(10_000), fix(20_000)), markers: List<HistoryMarker> = emptyList()) =
        HistoryRouteState(rideId = "ride", range = favorite.range, fixes = fixes, markers = markers, loaded = true)
    private fun media(item: HistoryMediaRecord = item(112_000)) = HistoryMediaState(favoriteId = "fav", items = listOf(item), loaded = true)

    @Test fun selectsNearestActualFixWithoutInterpolating() {
        assertEquals(mapOf("photo" to fix(10_000)), historyMediaPositions(favorite, ride, route(), media(), 15_000))
    }
    @Test fun unknownCaptureAndAbsentGpsStayUnpositioned() {
        assertTrue(historyMediaPositions(favorite, ride, route(), media(item(null)), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route(emptyList()), media(), 15_000).isEmpty())
    }
    @Test fun doesNotSnapAcrossGapOrExtrapolateBeforeOrAfterSegment() {
        val gap = route(markers = listOf(HistoryMarker(tick(11_000), HistoryMarkerKind.GPS_GAP)))
        assertTrue(historyMediaPositions(favorite, ride, gap, media(), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route(), media(item(125_000)), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route(listOf(fix(10_000), fix(20_000))), media(item(105_000)), 15_000).isEmpty())
    }
    @Test fun nearestFixMustBeWithinThirtySeconds() {
        val wide = route(listOf(fix(0), fix(100_000)))
        assertTrue(historyMediaPositions(favorite, ride, wide, media(item(150_000)), 100_000).isEmpty())
        assertEquals(mapOf("photo" to fix(0)), historyMediaPositions(favorite, ride, wide, media(item(130_000)), 100_000))
    }
    @Test fun sourceAndFavoriteMustMatchEvenWhenNamesAndRideIdMatch() {
        assertTrue(historyMediaPositions(favorite, ride.copy(vehicleId = "other"), route(), media(), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route(), media().copy(favoriteId = "other"), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route(), media(item(112_000).copy(favoriteId = "other")), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route().copy(rideId = "other"), media(), 15_000).isEmpty())
    }
    @Test fun doesNotBorrowOutsideFavoriteOrVisibleRangeOrUnreadyFile() {
        assertTrue(historyMediaPositions(favorite.copy(range = HistoryRange(120_000, 200_000)), ride, route(), media(), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route().copy(range = HistoryRange(120_000, 200_000)), media(), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route(), media(item(112_000).copy(status = HistoryMediaStatus.IMPORTING)), 15_000).isEmpty())
    }
    @Test fun clockChangeOrWallClockMismatchCannotPlacePhoto() {
        assertTrue(historyMediaPositions(favorite, ride, route(markers = listOf(HistoryMarker(tick(1000), HistoryMarkerKind.CLOCK_CHANGE))), media(), 15_000).isEmpty())
        val incoherent = fix(10_000).let { it.copy(tick = it.tick.copy(epochMillis = 112_000)) }
        assertTrue(historyMediaPositions(favorite, ride, route(listOf(fix(0), incoherent, fix(20_000))), media(), 15_000).isEmpty())
    }
    @Test fun interruptedOrForeignClockSegmentsDoNotBridgeAndSingleFixNeedsExactTime() {
        val stop = route(markers = listOf(HistoryMarker(tick(11_000), HistoryMarkerKind.INTERRUPTED)))
        assertTrue(historyMediaPositions(favorite, ride, stop, media(), 15_000).isEmpty())
        assertTrue(historyMediaPositions(favorite, ride, route(listOf(fix(10_000))), media(), 15_000).isEmpty())
        assertEquals(mapOf("photo" to fix(10_000)), historyMediaPositions(favorite, ride, route(listOf(fix(10_000))), media(item(110_000)), 15_000))
        assertTrue(historyMediaPositions(favorite, ride, route(listOf(fix(0), fix(20_000).let { it.copy(tick = it.tick.copy(clockGeneration = 4)) })), media(), 30_000).isEmpty())
    }
}
