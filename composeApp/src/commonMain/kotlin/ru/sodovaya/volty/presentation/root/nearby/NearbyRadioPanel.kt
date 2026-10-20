package ru.sodovaya.volty.presentation.root.nearby

import ru.sodovaya.volty.domain.radio.RadioClientPhase
import ru.sodovaya.volty.domain.radio.RadioError
import ru.sodovaya.volty.domain.radio.RadioMember
import ru.sodovaya.volty.domain.radio.RadioState

enum class RadioTitle { IDLE, UNSUPPORTED, STARTING, CONNECTING, MEMBERS, LOST, ERROR, ELSEWHERE }

enum class RadioAction { RAISE, JOIN, LEAVE, RERAISE }

/** Only the hub (and anyone about to raise) may pick a mode; a client sees the hub's mode. */
enum class RadioModePicker { HIDDEN, EDITABLE, READ_ONLY }

data class RadioPanelState(
    val title: RadioTitle,
    val primary: RadioAction?,
    val secondary: RadioAction?,
    val members: List<String>,
    val error: RadioError?,
    val modePicker: RadioModePicker = RadioModePicker.HIDDEN,
    val canExportDiagnostics: Boolean = false,
)

/**
 * Everything the radio card decides, as data; the composable only renders it. [community] is the
 * server whose screen shows the card: a radio running for another server is shown as such (with
 * a way to leave), and another server's old error is not this server's business.
 */
fun radioPanelState(
    state: RadioState,
    roster: List<RadioMember>,
    supported: Boolean,
    community: String? = null,
): RadioPanelState {
    if (!supported) return RadioPanelState(RadioTitle.UNSUPPORTED, null, null, emptyList(), RadioError.UNSUPPORTED_ANDROID)
    val bound = when (state) {
        is RadioState.Starting -> state.community
        is RadioState.Hub -> state.community
        is RadioState.Client -> state.community
        is RadioState.Lost -> state.community
        is RadioState.Error -> state.community
        RadioState.Idle -> null
    }
    val foreign = community != null && bound != null && bound != community
    if (foreign && state !is RadioState.Error) {
        return RadioPanelState(RadioTitle.ELSEWHERE, RadioAction.LEAVE, null, emptyList(), null)
    }
    val names = roster.map { it.name }
    return when {
        state == RadioState.Idle || foreign -> idle()
        state is RadioState.Starting ->
            RadioPanelState(RadioTitle.STARTING, RadioAction.LEAVE, null, emptyList(), null)
        state is RadioState.Hub ->
            RadioPanelState(RadioTitle.MEMBERS, RadioAction.LEAVE, null, names, null, RadioModePicker.EDITABLE, canExportDiagnostics = true)
        state is RadioState.Client -> RadioPanelState(
            if (state.phase == RadioClientPhase.CONNECTED) RadioTitle.MEMBERS else RadioTitle.CONNECTING,
            RadioAction.LEAVE, null, if (state.phase == RadioClientPhase.CONNECTED) names else emptyList(), null,
            RadioModePicker.READ_ONLY, canExportDiagnostics = true,
        )
        state is RadioState.Lost ->
            RadioPanelState(RadioTitle.LOST, RadioAction.RERAISE, RadioAction.LEAVE, emptyList(), null, canExportDiagnostics = true)
        state is RadioState.Error -> errorPanel(state.reason)
        else -> idle()
    }
}

private fun idle() = RadioPanelState(
    RadioTitle.IDLE, RadioAction.RAISE, RadioAction.JOIN, emptyList(), null, RadioModePicker.EDITABLE,
)

private fun errorPanel(reason: RadioError): RadioPanelState {
    val (primary, secondary) = when (reason) {
        // A radio that is full or was not found may exist; retry joining it, or start one.
        RadioError.FULL -> RadioAction.JOIN to null
        RadioError.NOT_FOUND, RadioError.STALE_KEY -> RadioAction.JOIN to RadioAction.RAISE
        // Raise looks for a radio first and only then hosts, so it is the safe retry for a failed start.
        RadioError.WIFI_FAILED, RadioError.AUDIO_UNAVAILABLE -> RadioAction.RAISE to null
        // Nothing a button can fix: the key arrives with the internet, the Android version never changes.
        RadioError.NO_KEY, RadioError.UNSUPPORTED_ANDROID -> null to null
    }
    return RadioPanelState(
        RadioTitle.ERROR, primary, secondary, emptyList(), reason,
        if (primary != null) RadioModePicker.EDITABLE else RadioModePicker.HIDDEN,
    )
}

/**
 * The one-line radio status under the conversation panel: shown only while a radio is starting,
 * running or lost; null (no line) when idle, failed or unsupported, where the server screen's card
 * is the place to look.
 */
fun radioStatusTitle(panel: RadioPanelState): RadioTitle? =
    panel.title.takeIf { it == RadioTitle.STARTING || it == RadioTitle.CONNECTING || it == RadioTitle.MEMBERS || it == RadioTitle.LOST }
