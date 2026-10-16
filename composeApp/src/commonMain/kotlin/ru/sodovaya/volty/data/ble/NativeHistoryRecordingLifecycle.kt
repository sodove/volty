package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.data.history.HistoryRecordingConnections
import ru.sodovaya.volty.data.history.HistoryTelemetrySource
import ru.sodovaya.volty.data.history.RideRecordingController
import ru.sodovaya.volty.domain.history.HistorySource
import ru.sodovaya.volty.domain.history.HistoryTick
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.isGuest

/** All suspending calls belong outside the repository sessionLock. The caller qualifies
 * installed physical intent; protocol object identity qualifies each native attempt.
 */
internal class NativeHistoryRecordingLifecycle(
    controller: RideRecordingController,
    readTick: () -> HistoryTick,
    private val sources: (LinkSpec, Vehicle?) -> Map<HistoryTelemetrySource, HistorySource>,
    private val onFailure: () -> Unit,
) {
    internal class ManualControls internal constructor(
        private val begin: suspend () -> Boolean,
        private val end: suspend (String) -> Boolean,
    ) {
        suspend fun start(): Boolean = begin()
        suspend fun stop(rideId: String): Boolean = end(rideId)
    }
    /** A screen keeps this exact selection capability, never a lookup of whichever
     * vehicle happens to be current when its delayed click finally executes.
     */
    fun manualControls(): ManualControls? {
        val captured = plan ?: return null
        if (captured.links.isEmpty() || !captured.owns()) return null
        return ManualControls(
            begin = { manualControl(captured) { connections.start(captured.selection) } },
            end = { rideId -> manualControl(captured) { connections.stop(captured.selection, rideId) } },
        )
    }

    private suspend fun manualControl(captured: Plan, action: suspend () -> Boolean): Boolean = try {
        control {
            if (plan !== captured || !captured.owns()) false else action()
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { onFailure(); false }

    internal class Binding(val sources: Map<HistoryTelemetrySource, HistorySource>, val prepare: suspend () -> Unit)
    private class Link(val spec: LinkSpec, val binding: Map<HistoryTelemetrySource, HistorySource>)
    private class Attempt(val protocol: Any, val capability: HistoryRecordingConnections.Attempt)
    private class Plan(val selection: HistoryRecordingConnections.Selection,
        val links: List<Link>, val owns: () -> Boolean,
        val attempts: MutableMap<Int, Attempt> = mutableMapOf())
    private val connections = HistoryRecordingConnections(controller, readTick)
    private val controls = Mutex()
    @Volatile private var plan: Plan? = null

    suspend fun install(vehicleId: String, specs: List<LinkSpec>, vehicle: Vehicle?, ownsPlan: () -> Boolean) {
        // No IO: copy the installed decoder plan before any capture can observe it.
        // Unsupported/guest/demo selections must retire old GPS but cannot auto-start a fake ride.
        var failed = false
        val links = if (vehicle == null || vehicle.isGuest || vehicle.isDemo) emptyList() else try {
            specs.toList().map { Link(it, sources(it, vehicle).toMap()) }.also { planned ->
                val ids = planned.flatMap { it.binding.values }.map { it.id }
                require(ids.distinct().size == ids.size) { "Duplicate physical source binding" }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true; emptyList() }
        safeControl {
            if (!ownsPlan()) return@safeControl
            val selection = connections.install(vehicleId, links.mapIndexed { index, link ->
                index.toString() to link.binding.values.toList()
            }.toMap())
            plan = Plan(selection, links, ownsPlan)
            if (failed) onFailure()
        }
    }

    /** Read-only immutable snapshot; native ownership still fences every notification. */
    fun binding(spec: LinkSpec, protocol: Any, ownsAttempt: () -> Boolean): Binding? {
        val captured = plan ?: return null
        if (!captured.owns()) return null
        val index = captured.links.indexOfFirst { it.spec === spec }
        if (index < 0) return null
        val link = captured.links[index]
        return Binding(link.binding) {
            val ready = try {
                control {
                    if (plan !== captured || !captured.owns() || !ownsAttempt()) return@control false
                    val capability = connections.newAttempt(captured.selection, index.toString()) ?: return@control false
                    // Retain before durable begin so cancellation/failed setup can retire exactly it.
                    captured.attempts[index] = Attempt(protocol, capability)
                    connections.ready(capability)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { onFailure(); throw error }
            check(ready) { "Recording attempt retired" }
        }
    }

    suspend fun retire(protocol: Any) = safeControl {
        val current = plan ?: return@safeControl
        val entry = current.attempts.entries.firstOrNull { it.value.protocol === protocol } ?: return@safeControl
        connections.lost(entry.value.capability)
        current.attempts.remove(entry.key)
    }

    /** Deliberate native disconnect; guard is rechecked after lifecycle mutex admission.
     * A racing newer native install makes an old global disconnect inapplicable.
     */
    suspend fun disconnect(ownsDisconnect: () -> Boolean) = safeControl {
        if (!ownsDisconnect()) return@safeControl
        val current = plan ?: return@safeControl
        connections.disconnect(current.selection)
        plan = null
    }

    private suspend fun safeControl(action: suspend () -> Unit) {
        try { control(action) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { onFailure() } // Optional history cannot break live BLE.
    }

    private suspend fun <T> control(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val result = controls.withLock {
            currentCoroutineContext().ensureActive()
            // Keep local ledger and accepted durable controller receipt together.
            withContext(NonCancellable) { action() }
        }
        currentCoroutineContext().ensureActive()
        return result
    }
}
