package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.repository.CanScanRefusal
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.picker.SourceRole
import ru.sodovaya.volty.presentation.picker.addsFor
import ru.sodovaya.volty.presentation.picker.sourceRole
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.can_add_as_battery
import volty.composeapp.generated.resources.can_add_as_controller
import volty.composeapp.generated.resources.can_node_added
import volty.composeapp.generated.resources.can_node_controller
import volty.composeapp.generated.resources.can_node_hosted_battery
import volty.composeapp.generated.resources.can_node_raw
import volty.composeapp.generated.resources.can_node_raw_hosted
import volty.composeapp.generated.resources.can_scan_dismiss
import volty.composeapp.generated.resources.can_scan_empty
import volty.composeapp.generated.resources.can_scan_failed_no_bus
import volty.composeapp.generated.resources.can_scan_failed_no_reply
import volty.composeapp.generated.resources.can_scan_failed_not_connected
import volty.composeapp.generated.resources.can_scan_failed_not_ready
import volty.composeapp.generated.resources.can_scan_running
import volty.composeapp.generated.resources.can_scan_start
import volty.composeapp.generated.resources.can_scan_unavailable
import volty.composeapp.generated.resources.can_section
import volty.composeapp.generated.resources.scan_add_battery
import volty.composeapp.generated.resources.scan_add_controller
import volty.composeapp.generated.resources.scan_add_wheel
import volty.composeapp.generated.resources.scan_role_battery
import volty.composeapp.generated.resources.scan_role_both
import volty.composeapp.generated.resources.scan_role_controller
import volty.composeapp.generated.resources.scan_role_unknown
import volty.composeapp.generated.resources.scan_sheet_close
import volty.composeapp.generated.resources.scan_sheet_empty
import volty.composeapp.generated.resources.scan_sheet_title

/**
 * The composer's two ways of learning an address (G2 Task 5) — a BLE scan sheet
 * and the CAN-bus section — and, like every other file in this screen, **a
 * renderer and nothing more**.
 *
 * Compose is not unit-testable in this repo, so no judgement is made here. What
 * a scan found and what may be added of it is `presentation/picker/SourceScan.kt`
 * ([sourceRole], [addsFor]); what a CAN scan offers, whether a scan is possible
 * at all, and whether a candidate is already taken is `ComposerCanDiscovery.kt`
 * ([CanCandidate], [canScanTarget]); whether a second scan is allowed to start
 * is `DefaultVehicleEditComponent`. What is left below is wording, layout and
 * which button is disabled.
 */

// ---------------------------------------------------------------------------
// The BLE scan sheet
// ---------------------------------------------------------------------------

/**
 * The devices in range, each offering the adds its detection makes most likely
 * first.
 *
 * Every device offers **every** add, never a filtered set — detection is a hint
 * and not a lock, the same rule the picker's type sheet follows. So a rider
 * whose wheel is not recognised can still tap "+ Wheel", and a rider whose
 * controller was misdetected as a battery is not stuck.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceScanSheet(
    devices: List<DiscoveredDevice>,
    component: VehicleEditComponent
) {
    ModalBottomSheet(onDismissRequest = component::onStopDeviceScan) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.scan_sheet_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // A spinner beside the title rather than in place of the list:
                // a BLE scan never "finishes", so a list that is still filling
                // must stay usable while it does.
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            if (devices.isEmpty()) {
                Text(
                    text = stringResource(Res.string.scan_sheet_empty),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            devices.forEach { device -> ScannedDeviceRow(device, component) }
            TextButton(onClick = component::onStopDeviceScan) {
                Text(stringResource(Res.string.scan_sheet_close))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScannedDeviceRow(device: DiscoveredDevice, component: VehicleEditComponent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = device.name ?: device.address,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        // `G §7`: a discovered device is labelled controller / battery / both.
        // The address rides along because two identical unnamed devices are
        // otherwise indistinguishable, and it is what the rider would have had
        // to type.
        Text(
            text = "${sourceRoleText(device.sourceRole())}  ·  ${device.address}  ·  ${device.rssi} dBm",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            addsFor(device).forEach { add ->
                OutlinedButton(onClick = { component.onAddScannedDevice(device, add) }) {
                    Text(scannedAddText(add), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun sourceRoleText(role: SourceRole): String = stringResource(
    when (role) {
        SourceRole.CONTROLLER -> Res.string.scan_role_controller
        SourceRole.BATTERY -> Res.string.scan_role_battery
        SourceRole.BOTH -> Res.string.scan_role_both
        SourceRole.UNKNOWN -> Res.string.scan_role_unknown
    }
)

@Composable
private fun scannedAddText(add: ScannedAdd): String = stringResource(
    when (add) {
        ScannedAdd.CONTROLLER -> Res.string.scan_add_controller
        ScannedAdd.BATTERY -> Res.string.scan_add_battery
        ScannedAdd.WHEEL -> Res.string.scan_add_wheel
    }
)

// ---------------------------------------------------------------------------
// CAN discovery
// ---------------------------------------------------------------------------

/**
 * `G §3` flow 4 — the head unit's own bus.
 *
 * Three things this section must get visibly right, all decided elsewhere and
 * only rendered here:
 *
 *  - **no live connection ⇒ no button.** A sentence explaining what to do
 *    instead, because a disabled control on a screen a rider reached while
 *    connected is indistinguishable from a broken one;
 *  - **something happens for the whole 2.5 s.** The firmware blocks and reports
 *    nothing while it does, so the spinner and its caption ARE the feedback;
 *  - **the button is gone, not just disabled, while a scan runs** — the firmware
 *    silently discards a second `PING_CAN`, so a rider must not be able to aim
 *    at it. (`DefaultVehicleEditComponent` refuses the call as well.)
 *
 * Raw CAN ids sit under each friendly name (`G §9.1`): visible enough to check a
 * bus against, quiet enough that "Контроллер 2" is what the rider reads.
 */
@Composable
internal fun CanDiscoverySection(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    CanDiscoveryContent(
        canScanTarget = state.canScanTarget,
        scan = state.canScan,
        candidates = state.canCandidates,
        onDiscover = component::onDiscoverCanDevices,
        onAddCandidate = component::onAddCanCandidate,
        onDismiss = component::onDismissCanScan
    )
}

/** Shared renderer for editor and setup; every decision arrives as state/callbacks. */
@Composable
internal fun CanDiscoveryContent(
    canScanTarget: String?,
    scan: CanScanState,
    candidates: List<CanCandidate>,
    onDiscover: () -> Unit,
    onAddCandidate: (CanCandidate, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Text(
        text = stringResource(Res.string.can_section),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (canScanTarget == null) {
        // Says plainly what the screen does with no live connection: nothing,
        // and here is why. It does NOT render a dead button.
        Text(
            text = stringResource(Res.string.can_scan_unavailable),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    when (scan) {
        is CanScanState.Running -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(Res.string.can_scan_running),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        else -> {
            OutlinedButton(onClick = onDiscover) {
                Text(stringResource(Res.string.can_scan_start), fontSize = 12.sp)
            }
            if (scan is CanScanState.Failed) {
                Text(
                    text = canScanRefusalText(scan.refusal),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (scan is CanScanState.Found) {
                // The empty bus is a real answer and gets its own sentence: an
                // absent list would read as "the scan never ran".
                if (scan.ids.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.can_scan_empty),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                candidates.forEach { candidate ->
                    CanCandidateRow(candidate, onAddCandidate)
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.can_scan_dismiss), fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * The rider-facing sentence for a refusal, one per reason.
 *
 * The refusals used to arrive as `Throwable.message` and were printed verbatim
 * — an English exception string inside a Russian sentence, e.g. *"Не удалось
 * опросить: Link HU:01 speaks VESC_BMS, which has no CAN bus"*. The reason is
 * typed now ([CanScanRefusal]); the message stays where it belongs, in the log.
 *
 * Exhaustive with no `else`, so a fifth reason has to be given a sentence.
 */
@Composable
private fun canScanRefusalText(refusal: CanScanRefusal): String = stringResource(
    when (refusal) {
        CanScanRefusal.NOT_CONNECTED -> Res.string.can_scan_failed_not_connected
        CanScanRefusal.LINK_NOT_READY -> Res.string.can_scan_failed_not_ready
        CanScanRefusal.NO_CAN_BUS -> Res.string.can_scan_failed_no_bus
        CanScanRefusal.NO_REPLY -> Res.string.can_scan_failed_no_reply
    }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanCandidateRow(
    candidate: CanCandidate,
    onAddCandidate: (CanCandidate, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = canCandidateTitle(candidate), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        // The advanced view of `G §9.1`, inline rather than behind a disclosure:
        // it is one short line, and a rider checking a bus against VESC Tool
        // needs it beside the friendly name rather than two taps away.
        Text(
            text = candidate.canId
                ?.let { stringResource(Res.string.can_node_raw, it) }
                ?: stringResource(Res.string.can_node_raw_hosted),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (candidate.alreadyAdded) {
            // Shown, not hidden: a rider who scans twice must see that the bus
            // still has what they expect, not a list that lost devices.
            Text(
                text = stringResource(Res.string.can_node_added),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // A raw CAN id says nothing about what the node IS — `PING_CAN`
            // answers with ids and nothing else — so the rider says. The hosted
            // battery is the one candidate that can only be a battery.
            if (candidate.kind == CanCandidateKind.NODE) {
                OutlinedButton(onClick = { onAddCandidate(candidate, false) }) {
                    Text(stringResource(Res.string.can_add_as_controller), fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = { onAddCandidate(candidate, true) }) {
                Text(stringResource(Res.string.can_add_as_battery), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun canCandidateTitle(candidate: CanCandidate): String = when (candidate.kind) {
    CanCandidateKind.HOSTED_BATTERY -> stringResource(Res.string.can_node_hosted_battery)
    // The ordinal is always present for a NODE (see [canCandidates]); the
    // fallback exists so this renderer cannot crash on a shape it did not build.
    CanCandidateKind.NODE ->
        stringResource(Res.string.can_node_controller, candidate.ordinal ?: 0)
}
