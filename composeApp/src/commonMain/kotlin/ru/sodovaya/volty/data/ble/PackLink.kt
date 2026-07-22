package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.Vehicle
import kotlinx.coroutines.Job
import kotlin.concurrent.Volatile

/**
 * Where one link currently stands. The vehicle's [ru.sodovaya.volty.domain.model.ConnectionState]
 * is a FOLD over these — see the fold in [KableBmsRepository]:
 *
 *  - any link [ONLINE]                          → Connected
 *  - none online, any still [CONNECTING]        → Connecting
 *  - all down, at least one [RECONNECTING]      → Reconnecting
 *  - all [FAILED]                               → Failed
 *
 * A single-link vehicle degenerates to exactly the pre-multi-link state
 * machine: its one link's status maps 1:1 onto the vehicle state.
 */
internal enum class LinkStatus { CONNECTING, ONLINE, RECONNECTING, FAILED }

/**
 * One BLE link of a connected vehicle: the successor of the repository's
 * single `currentSession` / `reconnectJob` / target triple, one instance per
 * distinct pack address.
 *
 * A link owns:
 *  - its [spec] — the address, the BMS type behind it, and the vehicle-global
 *    pack indices it is responsible for ([LinkSpec.globalIndex] translates the
 *    session's local sample index before the sample enters the shared funnel);
 *  - its [session] — the current [ConnectionSession], swapped per attempt;
 *  - its [reconnectJob] — the link's own reconnect loop, independent of every
 *    other link's.
 *
 * Locking: [session] and [reconnectJob] follow the repository's `sessionLock`
 * discipline (every production write inside a `sessionLock.withLock` block or
 * the loop that owns the job, exactly as the old single-session fields did).
 * The status triple ([status] / [reconnectAttempt] / [lastReason]) is written
 * only under the repository's fold lock, so a fold never reads a half-updated
 * link. Fields are volatile because links are touched from several coroutines
 * on a multithreaded dispatcher.
 */
internal class PackLink(
    val spec: LinkSpec,
    /**
     * The vehicle this link belongs to — the SAME instance for every link of
     * one connection, captured at connect time (matching how the pre-multi-link
     * state machine stamped the connect-time vehicle into its states).
     */
    val vehicle: Vehicle?
) {
    @Volatile
    var session: ConnectionSession? = null

    @Volatile
    var reconnectJob: Job? = null

    @Volatile
    var status: LinkStatus = LinkStatus.CONNECTING

    /** Current reconnect attempt number, 0 right after a drop. */
    @Volatile
    var reconnectAttempt: Int = 0

    /** Last failure / drop reason — what the fold reports for this link. */
    @Volatile
    var lastReason: String = ""
}
