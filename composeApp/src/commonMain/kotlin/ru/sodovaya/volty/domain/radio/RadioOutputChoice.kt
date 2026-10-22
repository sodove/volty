package ru.sodovaya.volty.domain.radio

/** The kinds of audio output device the radio's routing cares about. */
enum class RadioDeviceKind { BLUETOOTH_SCO, BLE_HEADSET, WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET, BUILTIN_SPEAKER, EARPIECE, OTHER }

enum class RadioOutputChoice { BLUETOOTH_HEADSET, WIRED_HEADSET, BUILT_IN_SPEAKER }

/**
 * Where the radio should play (rulings R17 and R19). A Bluetooth SCO / LE headset wins, then a wired
 * or USB headset; only when none is present does the phone fall back to its loudspeaker, because
 * the in-communication default earpiece is inaudible on a bike. The loudspeaker must never be
 * forced over a headset the rider is wearing.
 */
fun radioOutputChoice(devices: Set<RadioDeviceKind>): RadioOutputChoice = when {
    RadioDeviceKind.BLUETOOTH_SCO in devices || RadioDeviceKind.BLE_HEADSET in devices -> RadioOutputChoice.BLUETOOTH_HEADSET
    RadioDeviceKind.WIRED_HEADSET in devices || RadioDeviceKind.WIRED_HEADPHONES in devices ||
        RadioDeviceKind.USB_HEADSET in devices -> RadioOutputChoice.WIRED_HEADSET
    else -> RadioOutputChoice.BUILT_IN_SPEAKER
}

/** How the radio wires its audio for the rider's voice settings — the same switches as server voice. */
data class RadioAudioPlan(
    /** Record the phone's own microphone and play as media, never opening the headset's SCO link. */
    val phoneMic: Boolean,
    val output: RadioOutputChoice,
)

/**
 * An earlier revision ignored the voice settings and always took the headset's SCO microphone,
 * which a rider with a half-dead headset mic could not override. A Phone microphone cannot share
 * a Bluetooth headset's SCO link, so it plays as media (A2DP): better mic, more headset latency.
 */
fun radioAudioPlan(preferences: ru.sodovaya.volty.domain.voice.VoiceAudioPreferences, devices: Set<RadioDeviceKind>): RadioAudioPlan {
    val automatic = radioOutputChoice(devices)
    val output = when (preferences.output) {
        ru.sodovaya.volty.domain.voice.VoiceOutputPreference.SPEAKER -> RadioOutputChoice.BUILT_IN_SPEAKER
        else -> automatic
    }
    return RadioAudioPlan(phoneMic = preferences.input == ru.sodovaya.volty.domain.voice.VoiceInputPreference.PHONE, output = output)
}
