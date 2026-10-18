package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.map.*
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.location.LocationSource
import kotlin.test.*

class HistoryMapCameraPolicyTest {
    private val identity = HistoryMapIdentity("ride-a", 2)
    private val fix = HistoryGpsFix(HistoryTick(1000, 0, 1), 56.0, 60.0, 2.0, null)
    private val history = HistoryMapScene(identity, listOf(HistoryMapSegment(listOf(fix))), fix, emptyList())
    private val padding = HistoryMapInsets(40, 100, 40, 300)
    private val fit = MapCameraRequest.FitHistory(7, identity, padding, true)

    @Test fun historicalSceneNeverCarriesLivePositionTrailParticipantsOrNavigation() {
        val scene = historyMapNavigationScene(history, fit)
        assertSame(history, scene.history); assertSame(fit, scene.cameraRequest)
        assertNull(scene.ownFix); assertTrue(scene.trail.isEmpty()); assertTrue(scene.participantMarkers.isEmpty())
        assertTrue(scene.routes.isEmpty()); assertNull(scene.destination)
        assertEquals(RideMapFollowMode.FREE, scene.followState.mode)
    }

    @Test fun fitMustBelongToCurrentRideAndGenerationRegardlessOfSequenceCollision() {
        val scene = historyMapNavigationScene(history, fit)
        assertTrue(mapCameraRequestApplies(scene, fit))
        for (wrong in listOf(fit.copy(identity = identity.copy(rideId = "ride-b")),
            fit.copy(identity = identity.copy(generation = 1)))) {
            assertFalse(mapCameraRequestApplies(scene, wrong))
            assertNull(historyMapNavigationScene(history, wrong).cameraRequest)
        }
        assertFalse(mapCameraRequestApplies(scene, MapCameraRequest.FitParticipants(7, emptyList())))
        assertFalse(mapCameraRequestApplies(scene.copy(history = null), fit))
        assertTrue(mapCameraRequestApplies(scene.copy(history = null), MapCameraRequest.FitParticipants(7, emptyList())))
        assertEquals(fit, historyMapNavigationScene(history.copy(selectedFix = null), fit).cameraRequest)
    }

    @Test fun fittingRespectsActualPanelInsetsAndRejectsEmptyOrOverflowedViewport() {
        assertEquals(padding, historyFitPadding(1080, 2220, padding))
        assertNull(historyFitPadding(0, 0, padding))
        assertNull(historyFitPadding(80, 400, padding))
        assertNull(historyFitPadding(1080, 2220, HistoryMapInsets(Int.MAX_VALUE, 0, Int.MAX_VALUE, 0)))
        assertFailsWith<IllegalArgumentException> { HistoryMapInsets(0, -1, 0, 0) }
    }

    @Test fun noGpsHasNoCameraFitEvenIfCallerRetainsPreviousRequest() {
        val empty = history.copy(segments = emptyList(), selectedFix = null)
        assertNull(historyMapNavigationScene(empty, fit).cameraRequest)
    }

    @Test fun newerLiveFixCannotRepeatOldCommandButDifferentDestinationWithSameSequenceCanRun() {
        val live = RideLocationFix(GeoCoordinate(56.0, 60.0), 3.0, null, null, 1000, 1000, LocationSource.GPS)
        val recenter = MapCameraRequest.Recenter(7, live)
        assertTrue(sameMapCameraCommand(recenter, recenter.copy(fix = live.copy(capturedAtEpochMillis = 2000,
            coordinate = GeoCoordinate(56.1, 60.1)))), "New live fix must not repeat a consumed recenter command")
        val first = MapCameraRequest.FitParticipants(7, listOf(GeoCoordinate(56.0, 60.0)))
        val moved = first.copy(points = listOf(GeoCoordinate(56.1, 60.1)))
        assertTrue(sameMapCameraCommand(first, moved), "New marker data is not a new camera command")
        assertFalse(sameMapCameraCommand(first, moved.copy(sequence = 8)))
        assertFalse(sameMapCameraCommand(first, MapCameraRequest.FitAlternatives(7, first.points)))
        assertFalse(sameMapCameraCommand(first, fit))
        assertTrue(sameMapCameraCommand(fit, fit.copy()))
        assertFalse(sameMapCameraCommand(fit, fit.copy(identity = identity.copy(generation = 3))))
        assertFalse(sameMapCameraCommand(fit, fit.copy(identity = identity.copy(rideId = "other"))))
        assertFalse(sameMapCameraCommand(fit, fit.copy(insets = padding.copy(bottom = 400))))
        assertFalse(sameMapCameraCommand(null, fit))
    }
}
