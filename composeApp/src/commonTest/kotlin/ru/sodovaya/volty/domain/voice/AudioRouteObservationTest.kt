package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioRouteObservationTest {
    private val phone = AudioDeviceRef("phone-mic", "builtin")
    private val headset = AudioDeviceRef("headset", "wired")
    private val speaker = AudioDeviceRef("speaker", "builtin")

    @Test
    fun selectedOutputCannotClaimActualStreamRoute() {
        val route = AudioRouteState(desiredInput = phone, desiredOutput = headset).withSelectedOutput(headset)
        assertEquals(headset, route.selectedOutput)
        assertEquals(phone, route.desiredInput)
        assertNull(route.observedInput.device)
        assertNull(route.observedOutput.device)
        assertNull(route.observedOutput.provenance)
        assertNull(route.observedOutput.observedAtMillis)
    }

    @Test
    fun selectionDoesNotOverwriteContradictoryActualEvidence() {
        val observation = RouteObservation(speaker, AudioRouteProvenance.STREAM_ROUTING, 100)
        val initial = AudioRouteState(desiredOutput = headset, observedOutput = observation)
        val selected = initial.withSelectedOutput(headset)
        assertEquals(headset, selected.selectedOutput)
        assertEquals(observation, selected.observedOutput)
    }

    @Test
    fun communicationDeviceIsNeverPromotedToStreamObservation() {
        val observation = RouteObservation(headset, AudioRouteProvenance.COMMUNICATION_DEVICE, 100)
        val route = AudioRouteState(observedOutput = observation).withSelectedOutput(speaker)
        assertEquals(speaker, route.selectedOutput)
        assertEquals(AudioRouteProvenance.COMMUNICATION_DEVICE, route.observedOutput.provenance)
        assertEquals(headset, route.observedOutput.device)
    }

    @Test
    fun clearingSelectionKeepsObservedAndDesiredDevices() {
        val observed = RouteObservation(phone, AudioRouteProvenance.RECORDING_CONFIGURATION, 100)
        val initial = AudioRouteState(desiredInput = phone, desiredOutput = headset,
            selectedOutput = headset, observedInput = observed)
        val result = initial.withSelectedOutput(null)
        assertNull(result.selectedOutput)
        assertEquals(observed, result.observedInput)
        assertEquals(headset, result.desiredOutput)
    }
}
