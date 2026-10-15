package ru.sodovaya.volty.data.history

import kotlin.test.*
import ru.sodovaya.volty.domain.model.ConnectionState

class HistoryServiceOwnershipTest {
    @Test fun recording_stop_cannot_release_service_while_native_location_cleanup_remains_held() {
        val calls = mutableListOf<String>()
        var held = false
        val owner = HistoryServiceOwnership({ calls += "start" }, { calls += "stop" }, { held })
        owner.setRecordingEnabled(true)
        held = true
        owner.setRecordingEnabled(false)
        owner.setBleEnabled(false)
        assertEquals(listOf("start"), calls, "Desired stop must not outrun original location cleanup ACK")
        assertTrue(owner.required)
        held = false
        owner.reconcileLocationRetention()
        assertEquals(listOf("start", "stop"), calls)
        assertFalse(owner.required)
    }

    @Test fun live_location_retention_keeps_existing_service_without_inventing_a_native_start() {
        val calls = mutableListOf<String>()
        var held = true
        val owner = HistoryServiceOwnership({ calls += "start" }, { calls += "stop" }, { held })
        assertTrue(owner.required)
        assertTrue(owner.shouldKeepService(ConnectionState.Idle))
        owner.reconcileLocationRetention()
        assertTrue(calls.isEmpty(), "Retained cleanup must never itself launch a background FGS")
        held = false
        assertFalse(owner.required)
        assertFalse(owner.shouldKeepService(ConnectionState.Idle))
    }

    @Test fun location_release_rechecks_live_ack_and_preserves_any_remaining_telemetry_owner() {
        val calls = mutableListOf<String>()
        var held = true
        val owner = HistoryServiceOwnership({ calls += "start" }, { calls += "stop" }, { held })
        owner.reconcileLocationRetention()
        assertTrue(calls.isEmpty())
        held = false
        owner.reconcileLocationRetention()
        assertEquals(listOf("stop"), calls)
        owner.setBleEnabled(true)
        held = true
        owner.reconcileLocationRetention()
        held = false
        owner.reconcileLocationRetention()
        assertEquals(listOf("stop", "start"), calls)
        owner.setBleEnabled(false)
        assertEquals(listOf("stop", "start", "stop"), calls)
    }

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
