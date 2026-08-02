package ru.sodovaya.volty.presentation.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.picker.addBmsType
import ru.sodovaya.volty.presentation.picker.addControllerType
import ru.sodovaya.volty.presentation.picker.withScanHit

/** The complete transient state of the one shared composer BLE scan. */
internal data class VehicleSourceScanState(
    val scanning: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList()
)

/**
 * BLE source discovery lifted out of [DefaultVehicleEditComponent].
 *
 * The editor and setup wizard both delegate start, stop and scanned-device
 * draft insertion here. The repository still owns the one real BLE scanner;
 * this object owns only one collector and the presentation list folded from it.
 */
internal class VehicleSourceScanner(
    private val scope: CoroutineScope,
    private val scanAll: () -> Flow<DiscoveredDevice>,
    private val publish: (VehicleSourceScanState) -> Unit
) {
    private var job: Job? = null
    private var state = VehicleSourceScanState()

    fun start() {
        if (job?.isActive == true) return
        update(VehicleSourceScanState(scanning = true))
        job = scope.launch {
            scanAll().collect { device ->
                update(state.copy(devices = state.devices.withScanHit(device)))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        update(VehicleSourceScanState())
    }

    /** The sole mapping from one scan row and add choice into draft algebra. */
    fun addTo(draft: VehicleDraft, device: DiscoveredDevice, add: ScannedAdd): VehicleDraft {
        val label = device.name.orEmpty()
        return when (add) {
            ScannedAdd.CONTROLLER ->
                draft.addController(device.addControllerType(), device.address, label)
            ScannedAdd.BATTERY ->
                draft.addPack(device.addBmsType(), device.address, label)
            ScannedAdd.WHEEL ->
                draft.addWheel(device.addControllerType(), device.addBmsType(), device.address, label)
        }
    }

    private fun update(next: VehicleSourceScanState) {
        state = next
        publish(next)
    }
}
