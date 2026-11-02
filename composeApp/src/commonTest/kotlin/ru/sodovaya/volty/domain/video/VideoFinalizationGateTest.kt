package ru.sodovaya.volty.domain.video

import kotlin.test.*

class VideoFinalizationGateTest {
    @Test fun destroyingAnActiveRecorderRequestsStopButRetainsResourcesUntilFinalize() {
        val issued = mutableListOf<String>()
        val gate = VideoFinalizationGate({ issued += "stop" }, { issued += "release" })
        gate.recordingStarted()
        gate.close()
        gate.close()
        assertEquals(listOf("stop"), issued)
        gate.finalized()
        gate.finalized()
        assertEquals(listOf("stop", "release"), issued)
    }

    @Test fun closingIdleOrAlreadyFinalizedRecorderReleasesOnceWithoutRequestingStop() {
        val issued = mutableListOf<String>()
        val gate = VideoFinalizationGate({ issued += "stop" }, { issued += "release" })
        gate.recordingStarted()
        gate.finalized()
        gate.close()
        gate.close()
        assertEquals(listOf("release"), issued)
        assertFailsWith<IllegalStateException> { gate.recordingStarted() }
    }

    @Test fun ordinaryFinalizeDoesNotReleaseReusableCamera() {
        val issued = mutableListOf<String>()
        val gate = VideoFinalizationGate({ issued += "stop" }, { issued += "release" })
        repeat(2) { gate.recordingStarted(); gate.finalized() }
        assertTrue(issued.isEmpty())
        gate.close()
        assertEquals(listOf("release"), issued)
    }
}
