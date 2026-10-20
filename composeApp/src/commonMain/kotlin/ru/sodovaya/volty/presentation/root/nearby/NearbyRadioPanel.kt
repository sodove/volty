package ru.sodovaya.volty.presentation.root.nearby

import ru.sodovaya.volty.domain.radio.RadioClientPhase
import ru.sodovaya.volty.domain.radio.RadioError
import ru.sodovaya.volty.domain.radio.RadioMember
import ru.sodovaya.volty.domain.radio.RadioMode
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
    /** The mode to show; null when it is not known yet (a client that has not heard its hub). */
    val shownMode: RadioMode? = null,
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
    mode: RadioMode? = null,
    hasDiagnostics: Boolean = false,
): RadioPanelState = radioPanelBase(state, roster, supported, community, mode).let {
    if (hasDiagnostics && supported) it.copy(canExportDiagnostics = true) else it
}

private fun radioPanelBase(
    state: RadioState,
    roster: List<RadioMember>,
    supported: Boolean,
    community: String?,
    mode: RadioMode?,
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
        state == RadioState.Idle || foreign -> idle(mode)
        state is RadioState.Starting ->
            RadioPanelState(RadioTitle.STARTING, RadioAction.LEAVE, null, emptyList(), null)
        // Never show a number that has not been earned: no roster yet means "still connecting", not "0 people".
        state is RadioState.Hub && roster.isEmpty() ->
            RadioPanelState(RadioTitle.STARTING, RadioAction.LEAVE, null, emptyList(), null)
        state is RadioState.Hub ->
            RadioPanelState(RadioTitle.MEMBERS, RadioAction.LEAVE, null, names, null, RadioModePicker.EDITABLE, shownMode = mode)
        state is RadioState.Client -> {
            val heard = state.phase == RadioClientPhase.CONNECTED && roster.isNotEmpty()
            RadioPanelState(
                if (heard) RadioTitle.MEMBERS else RadioTitle.CONNECTING,
                RadioAction.LEAVE, null, if (heard) names else emptyList(), null,
                RadioModePicker.READ_ONLY,
                // The hub's mode arrives with its roster; before that the rider's own saved mode would be a guess.
                shownMode = if (roster.isNotEmpty()) mode else null,
            )
        }
        state is RadioState.Lost ->
            RadioPanelState(RadioTitle.LOST, RadioAction.RERAISE, RadioAction.LEAVE, emptyList(), null)
        state is RadioState.Error -> errorPanel(state.reason, mode)
        else -> idle(mode)
    }
}

private fun idle(mode: RadioMode?) = RadioPanelState(
    RadioTitle.IDLE, RadioAction.RAISE, RadioAction.JOIN, emptyList(), null, RadioModePicker.EDITABLE, shownMode = mode,
)

private fun errorPanel(reason: RadioError, mode: RadioMode?): RadioPanelState {
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
        shownMode = mode,
    )
}

/**
 * The one-line radio status under the conversation panel: shown only while a radio is starting,
 * running or lost; null (no line) when idle, failed or unsupported, where the server screen's card
 * is the place to look.
 */
fun radioStatusTitle(panel: RadioPanelState): RadioTitle? =
    panel.title.takeIf { it == RadioTitle.STARTING || it == RadioTitle.CONNECTING || it == RadioTitle.MEMBERS || it == RadioTitle.LOST }
