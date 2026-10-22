package ru.sodovaya.volty.domain.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences
import ru.sodovaya.volty.domain.voice.VoiceInputPreference
import ru.sodovaya.volty.domain.voice.VoiceOutputPreference

class RadioOutputChoiceTest {
    private fun choice(vararg kinds: RadioDeviceKind) = radioOutputChoice(kinds.toSet())

    @Test
    fun noHeadsetFallsBackToBuiltInSpeaker() {
        assertEquals(RadioOutputChoice.BUILT_IN_SPEAKER, choice())
        assertEquals(RadioOutputChoice.BUILT_IN_SPEAKER, choice(RadioDeviceKind.BUILTIN_SPEAKER, RadioDeviceKind.EARPIECE))
    }

    @Test
    fun bluetoothScoOrLeHeadsetWins() {
        assertEquals(RadioOutputChoice.BLUETOOTH_HEADSET, choice(RadioDeviceKind.BLUETOOTH_SCO))
        assertEquals(RadioOutputChoice.BLUETOOTH_HEADSET, choice(RadioDeviceKind.BLE_HEADSET, RadioDeviceKind.BUILTIN_SPEAKER))
        // Bluetooth stays ahead of a wired headset, as before ruling R19.
        assertEquals(RadioOutputChoice.BLUETOOTH_HEADSET, choice(RadioDeviceKind.BLUETOOTH_SCO, RadioDeviceKind.WIRED_HEADSET))
    }

    @Test
    fun wiredAndUsbHeadsetsAreNeverOverriddenBySpeaker() {
        for (kind in listOf(RadioDeviceKind.WIRED_HEADSET, RadioDeviceKind.WIRED_HEADPHONES, RadioDeviceKind.USB_HEADSET)) {
            assertEquals(RadioOutputChoice.WIRED_HEADSET, choice(kind, RadioDeviceKind.BUILTIN_SPEAKER, RadioDeviceKind.EARPIECE), kind.name)
        }
    }

    @Test
    fun unrelatedDevicesDoNotCountAsHeadset() {
        assertEquals(RadioOutputChoice.BUILT_IN_SPEAKER, choice(RadioDeviceKind.OTHER))
    }

    @Test
    fun voiceMicrophoneAndOutputSettingsAlsoDriveTheRadio() {
        val bt = setOf(RadioDeviceKind.BLUETOOTH_SCO, RadioDeviceKind.BUILTIN_SPEAKER)
        assertEquals(RadioAudioPlan(phoneMic = false, output = RadioOutputChoice.BLUETOOTH_HEADSET),
            radioAudioPlan(VoiceAudioPreferences(), bt))
        assertEquals(RadioAudioPlan(phoneMic = true, output = RadioOutputChoice.BLUETOOTH_HEADSET),
            radioAudioPlan(VoiceAudioPreferences(input = VoiceInputPreference.PHONE), bt))
        assertEquals(RadioAudioPlan(phoneMic = false, output = RadioOutputChoice.BUILT_IN_SPEAKER),
            radioAudioPlan(VoiceAudioPreferences(output = VoiceOutputPreference.SPEAKER), bt))
        assertEquals(false, radioAudioPlan(VoiceAudioPreferences(input = VoiceInputPreference.HEADSET), bt).phoneMic)
    }
}
