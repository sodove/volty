package ru.sodovaya.volty.data.history

import kotlin.test.*
import ru.sodovaya.volty.domain.model.ConnectionState

class HistoryServiceOwnershipTest {
    @Test fun staleBleDemandCannotKeepOrphanServiceAfterFailedOrIdleConnection() {
        val owner = HistoryServiceOwnership({}, {})
        owner.setBleEnabled(true)
        assertTrue(owner.required)
        for (connection in listOf(ConnectionState.Idle, ConnectionState.Disconnected, ConnectionState.Failed("lost"))) {
            assertFalse(owner.shouldKeepService(connection))
        }
        for (connection in listOf(ConnectionState.Scanning, ConnectionState.Connecting(null),
            ConnectionState.Connected(null), ConnectionState.Reconnecting(1, "lost"))) {
            assertTrue(owner.shouldKeepService(connection))
        }
    }

    @Test fun recordingKeepsServiceAcrossBleLossAndRestoresOrphanGuardAfterStop() {
        val owner = HistoryServiceOwnership({}, {})
        val absent = listOf(ConnectionState.Idle, ConnectionState.Disconnected, ConnectionState.Failed("lost"))
        owner.setRecordingEnabled(true)
        for (connection in absent) assertTrue(owner.shouldKeepService(connection))
        owner.setRecordingEnabled(false)
        for (connection in absent) assertFalse(owner.shouldKeepService(connection))
    }

    @Test fun bleAloneStartsAndReleasesNativeService() {
        val calls = mutableListOf<String>()
        val owner = HistoryServiceOwnership({ calls += "start" }, { calls += "stop" })
        assertFalse(owner.required)
        owner.setBleEnabled(true)
        assertTrue(owner.required)
        assertEquals(listOf("start"), calls)
        owner.setBleEnabled(false)
        assertFalse(owner.required)
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test fun eitherOwnerCanReleaseFirstWithoutStoppingTheOther() {
        for (bleFirst in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val owner = HistoryServiceOwnership({ calls += "start" }, { calls += "stop" })
            owner.setBleEnabled(true); owner.setRecordingEnabled(true)
            if (bleFirst) owner.setBleEnabled(false) else owner.setRecordingEnabled(false)
            assertTrue(owner.required)
            assertEquals(listOf("start", "start"), calls)
            if (bleFirst) owner.setRecordingEnabled(false) else owner.setBleEnabled(false)
            assertFalse(owner.required)
            assertEquals(listOf("start", "start", "stop"), calls)
        }
    }

    @Test fun recordingRetainsServiceWithoutAnyBleOrNearbyOwner() {
        val calls = mutableListOf<String>()
        val owner = HistoryServiceOwnership({ calls += "start" }, { calls += "stop" })
        owner.setRecordingEnabled(true)
        owner.setBleEnabled(false)
        assertTrue(owner.required)
        assertEquals(listOf("start"), calls)
        owner.setRecordingEnabled(false)
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test fun repeatedEnableRequestsNativeStartAgainInsteadOfAssumingServiceStillLives() {
        var starts = 0
        val owner = HistoryServiceOwnership({ starts++ }, {})
        owner.setBleEnabled(true); owner.setBleEnabled(true)
        assertEquals(2, starts)
        owner.setRecordingEnabled(true); owner.setRecordingEnabled(true)
        assertEquals(4, starts)
        assertTrue(owner.required)
    }

    @Test fun failedStartRetainsDesiredOwnershipAndAllowsRetryOrCleanup() {
        var starts = 0; var stops = 0
        val owner = HistoryServiceOwnership({ if (++starts == 1) error("native start rejected") }, { stops++ })
        assertFailsWith<IllegalStateException> { owner.setRecordingEnabled(true) }
        assertTrue(owner.required)
        owner.setBleEnabled(false)
        assertEquals(0, stops)
        owner.setRecordingEnabled(true)
        assertEquals(2, starts)
        owner.setRecordingEnabled(false)
        assertFalse(owner.required)
        assertEquals(1, stops)
    }

    @Test fun failedStopDoesNotInventAnOwnerAndCanBeRetried() {
        var stops = 0
        val owner = HistoryServiceOwnership({}, { if (++stops == 1) error("native stop rejected") })
        owner.setBleEnabled(true)
        assertFailsWith<IllegalStateException> { owner.setBleEnabled(false) }
        assertFalse(owner.required)
        owner.setBleEnabled(false)
        assertEquals(2, stops)
    }

    @Test fun nativeCallbackSeesPublishedDemandAndCannotLoseReentrantRecordingOwner() {
        val calls = mutableListOf<String>()
        var enter = true
        lateinit var owner: HistoryServiceOwnership
        owner = HistoryServiceOwnership({
            assertTrue(owner.required)
            calls += "start"
            if (enter) { enter = false; owner.setRecordingEnabled(true) }
        }, { calls += "stop" })
        owner.setBleEnabled(true)
        owner.setBleEnabled(false)
        assertTrue(owner.required)
        assertEquals(listOf("start", "start"), calls)
        owner.setRecordingEnabled(false)
        assertFalse(owner.required)
        assertEquals(listOf("start", "start", "stop"), calls)
    }
}
