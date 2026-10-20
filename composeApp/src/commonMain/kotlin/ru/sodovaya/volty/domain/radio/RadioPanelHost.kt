package ru.sodovaya.volty.domain.radio

import kotlinx.coroutines.flow.StateFlow

/**
 * What the radio card on a server's screen talks to. The platform implementation owns the
 * controller, the runtime, the settings and the account; the card only renders and forwards taps.
 */
interface RadioPanelHost {
    /** False below Android 10: the card then shows why and offers nothing. */
    val supported: Boolean
    val state: StateFlow<RadioState>

    /** Everyone on the radio, this phone included; empty with no radio. */
    val roster: StateFlow<List<RadioMember>>

    /** The mode in use: the hub's while on a radio, otherwise the rider's own choice. */
    val mode: StateFlow<RadioMode>
    val autoJoin: StateFlow<Boolean>

    /** True once a radio has run, so there is something to export (also after it ended or failed). */
    val hasDiagnostics: StateFlow<Boolean>

    /** Runtime permissions not granted yet for raising/joining, or with [scanning] also for auto-join. */
    fun missingPermissions(scanning: Boolean): List<String>

    fun raise(community: String)
    fun join(community: String)
    fun reRaise()
    fun leave()
    fun setMode(mode: RadioMode)
    fun setAutoJoin(enabled: Boolean)

    /** Shares the last radio's diagnostics as a text file; does nothing when none ran. */
    fun exportDiagnostics()
}
