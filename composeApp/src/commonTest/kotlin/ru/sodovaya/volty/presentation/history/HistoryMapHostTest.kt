package ru.sodovaya.volty.presentation.history

import kotlin.test.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

class HistoryMapHostTest {
    private val source = HistoryFavoriteSource("ride", "bike", HistoryStorageKind.V2, HistoryTick(1000, 10, 1))
    private val range = HistoryRange(1000, 2000)
    private val scene = historyMapNavigationScene(HistoryMapScene(HistoryMapIdentity("ride", 0), emptyList(), null, emptyList()), null)
    @Test fun oldViewportCannotPublishAfterReopeningTheSameRide() {
        val owner = Any(); val host = HistoryMapHost(owner, viewportGeneration = 42)
        val frame = HistoryHostedMap(source, range, scene)
        host.publish(owner, frame); assertNull(host.state.value)
        val current = frame.copy(scene = historyMapNavigationScene(
            HistoryMapScene(HistoryMapIdentity("ride", 42), emptyList(), null, emptyList()), null))
        host.publish(owner, current); assertEquals(current, host.state.value)
    }
    @Test fun onlyCurrentOwnerCanPublishAndRetireTheSharedSlot() {
        val owner = Any(); val stale = Any(); val host = HistoryMapHost(owner)
        val frame = HistoryHostedMap(source, range, scene)
        host.publish(owner, frame); assertEquals(frame, host.state.value)
        host.publish(stale, frame.copy(range = HistoryRange(1000, 1500))); host.clear(stale)
        assertEquals(frame, host.state.value)
        host.clear(owner); assertNull(host.state.value)
    }
    @Test fun frameCannotEscapeFullStorageIdentityOrTheSelectedInterval() {
        val frame = HistoryHostedMap(source, range, scene)
        assertEquals(scene, qualifiedHistoryMap(frame, source, range))
        assertNull(qualifiedHistoryMap(frame, source.copy(vehicleId = "other"), range))
        assertNull(qualifiedHistoryMap(frame, source.copy(kind = HistoryStorageKind.LEGACY), range))
        assertNull(qualifiedHistoryMap(frame, source.copy(started = source.started.copy(clockGeneration = 2)), range))
        assertNull(qualifiedHistoryMap(frame, source, HistoryRange(1000, 1500)))
        assertNull(qualifiedHistoryMap(frame, null, range)); assertNull(qualifiedHistoryMap(null, source, range))
    }
    @Test fun mismatchedGeometryAndLiveNavigationDataCannotEnterHistorySlot() {
        val owner = Any(); val host = HistoryMapHost(owner)
        val frame = HistoryHostedMap(source, range, scene)
        host.publish(owner, frame)
        host.publish(owner, frame.copy(source = source.copy(rideId = "other")))
        assertNull(host.state.value)
        host.publish(owner, frame.copy(scene = scene.copy(destination = GeoCoordinate(56.0, 60.0))))
        assertNull(host.state.value)
    }
}
