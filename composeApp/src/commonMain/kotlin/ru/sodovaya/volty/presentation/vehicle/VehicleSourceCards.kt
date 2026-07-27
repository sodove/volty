package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.composer_issue_ambiguous_gateway
import volty.composeapp.generated.resources.composer_issue_blank_address
import volty.composeapp.generated.resources.composer_issue_conflicting_kinds
import volty.composeapp.generated.resources.composer_issue_duplicate_can_id
import volty.composeapp.generated.resources.composer_issue_hostless_vesc_bms
import volty.composeapp.generated.resources.composer_issue_no_decoder
import volty.composeapp.generated.resources.composer_issue_unroutable_gateway
import volty.composeapp.generated.resources.vehicle_field_alias_group
import volty.composeapp.generated.resources.vehicle_field_bms_type
import volty.composeapp.generated.resources.vehicle_field_can_id
import volty.composeapp.generated.resources.vehicle_field_cell_count_auto
import volty.composeapp.generated.resources.vehicle_field_controller_type
import volty.composeapp.generated.resources.vehicle_field_derived_battery
import volty.composeapp.generated.resources.vehicle_field_derived_battery_caption
import volty.composeapp.generated.resources.vehicle_field_gear_ratio
import volty.composeapp.generated.resources.vehicle_field_link
import volty.composeapp.generated.resources.vehicle_field_link_same_as
import volty.composeapp.generated.resources.vehicle_field_pole_pairs
import volty.composeapp.generated.resources.vehicle_field_source_label
import volty.composeapp.generated.resources.vehicle_field_wheel_diameter
import volty.composeapp.generated.resources.vehicle_source_advanced
import volty.composeapp.generated.resources.vehicle_source_controller
import volty.composeapp.generated.resources.vehicle_source_move_down
import volty.composeapp.generated.resources.vehicle_source_move_up
import volty.composeapp.generated.resources.vehicle_source_pack
import volty.composeapp.generated.resources.vehicle_source_remove

/**
 * The composer's per-source cards (G2 Task 3): one card per controller and one
 * per battery pack.
 *
 * **A renderer, and deliberately nothing more** — the same rule
 * `VehicleAlertsScreen` states, and the reason it matters here is that Compose
 * is not unit-testable in this repo. Every judgement a card appears to make has
 * already been made where a test can reach it: what may be removed
 * ([VehicleEditComponent.State.canRemoveSource]), what is wrong with a source
 * and which card must say so ([issuesBySource]), what a cleared motor box means
 * ([MotorDraft]), which links exist ([VehicleDraft.linkAddresses]), and what a
 * controller's derived-battery switch is currently resolved to
 * ([VehicleDraft.resolvedDerivedBattery]). What is left here is layout, wording
 * and colour.
 *
 * ### Why `canId`, the link and the alias sit behind a disclosure
 *
 * `G §3`'s first flow is *scan, pick, "Controller", done* — one or two taps. The
 * fields that make the product owner's two-uBox-plus-two-ANT scooter describable
 * are meaningless on that vehicle: a lone controller has no bus to hold a CAN
 * id, no second source to share a link with, and no pack to be aliased to. They
 * are therefore collapsed by default, and the card opens itself (never closes
 * itself) as soon as the source has something wrong with it — because every
 * issue but one is fixed by editing a field behind that disclosure.
 */

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
internal fun ControllerSourceCard(
    draft: ControllerDraft,
    index: Int,
    count: Int,
    issues: List<ComposerIssue>,
    derivedBattery: Boolean,
    canRemove: Boolean,
    linkAddresses: List<String>,
    component: VehicleEditComponent
) {
    SourceCard(
        title = "${stringResource(Res.string.vehicle_source_controller)} ${index + 1}",
        index = index,
        count = count,
        canRemove = canRemove,
        issues = issues,
        onMove = component::onMoveController,
        onRemove = { component.onRemoveController(draft.key) }
    ) { advanced ->
        // Type: auto-detected by the picker (`G §4`, `G §7`), editable here
        // because detection is a hint and a misdetected device must still be
        // describable.
        TypeChips(
            label = stringResource(Res.string.vehicle_field_controller_type),
            options = ControllerType.entries,
            selected = draft.controllerType,
            text = { it.label },
            onSelect = { component.onControllerTypeChanged(draft.key, it) }
        )
        LabelField(draft.label) { component.onControllerLabelChanged(draft.key, it) }

        // MotorConfig, per controller. Two motors on one vehicle are two
        // different wheels, so these can never be one shared set of numbers —
        // which is exactly what the flat Motor card this replaced was.
        IntField(
            stringResource(Res.string.vehicle_field_pole_pairs),
            draft.motor.polePairs
        ) { component.onControllerPolePairsChanged(draft.key, it) }
        IntField(
            stringResource(Res.string.vehicle_field_wheel_diameter),
            draft.motor.wheelDiameterMm
        ) { component.onControllerWheelDiameterChanged(draft.key, it) }
        FloatField(
            stringResource(Res.string.vehicle_field_gear_ratio),
            draft.motor.gearRatio
        ) { component.onControllerGearRatioChanged(draft.key, it) }

        // `G §6`. Shown resolved — the rule's answer until the rider overrides
        // it, theirs afterwards — and not hidden behind Advanced, because on a
        // controller-only vehicle it is the switch that decides whether the
        // rider sees a battery at all.
        SwitchRow(
            label = stringResource(Res.string.vehicle_field_derived_battery),
            subtitle = stringResource(Res.string.vehicle_field_derived_battery_caption),
            checked = derivedBattery,
            onChange = { component.onControllerDerivedBatteryChanged(draft.key, it) }
        )

        if (advanced) {
            LinkField(
                address = draft.address,
                linkAddresses = linkAddresses,
                onAddressChanged = { component.onControllerAddressChanged(draft.key, it) }
            )
            IntField(
                stringResource(Res.string.vehicle_field_can_id),
                draft.canId
            ) { component.onControllerCanIdChanged(draft.key, it) }
        }
    }
}

@Composable
internal fun PackSourceCard(
    draft: PackDraft,
    index: Int,
    count: Int,
    issues: List<ComposerIssue>,
    canRemove: Boolean,
    linkAddresses: List<String>,
    component: VehicleEditComponent
) {
    SourceCard(
        title = "${stringResource(Res.string.vehicle_source_pack)} ${index + 1}",
        index = index,
        count = count,
        canRemove = canRemove,
        issues = issues,
        onMove = component::onMovePack,
        onRemove = { component.onRemovePack(draft.key) }
    ) { advanced ->
        TypeChips(
            label = stringResource(Res.string.vehicle_field_bms_type),
            options = BmsType.entries,
            selected = draft.bmsType,
            text = { bmsTypeLabel(it) },
            onSelect = { component.onPackTypeChanged(draft.key, it) }
        )
        LabelField(draft.label) { component.onPackLabelChanged(draft.key, it) }

        // Read-only on purpose (`G §4`): the cell count is auto-filled from
        // telemetry (`KableBmsRepository.maybePersistCellCount`), so a text box
        // here would ask the rider to type what the app already learned — and
        // would hand a stale load-time value back to the row the connection is
        // writing to underneath this form (see `VehicleDraft.reanchoredTo`).
        ReadOnlyRow(
            stringResource(Res.string.vehicle_field_cell_count_auto),
            draft.cellCount?.toString() ?: EM_DASH
        )

        if (advanced) {
            LinkField(
                address = draft.address,
                linkAddresses = linkAddresses,
                onAddressChanged = { component.onPackAddressChanged(draft.key, it) }
            )
            IntField(
                stringResource(Res.string.vehicle_field_can_id),
                draft.canId
            ) { component.onPackCanIdChanged(draft.key, it) }
            // The slot Task 4 fills in (`G §5`): two battery sources marked as
            // one physical pack. It already displays whatever is stored, so a
            // vehicle seeded elsewhere shows its grouping today; nothing on
            // this screen writes it yet.
            ReadOnlyRow(
                stringResource(Res.string.vehicle_field_alias_group),
                draft.aliasGroup ?: EM_DASH
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The card scaffold
// ---------------------------------------------------------------------------

private const val EM_DASH = "—"

/**
 * Header (name, reorder, remove) + the issues this source has + the body, with
 * the Advanced disclosure the body is handed as a flag.
 *
 * Reorder is two buttons rather than a drag handle: reordering renumbers the
 * saved indices (`VehicleDraft.toControllers`), so it is a rare, deliberate act
 * — and a drag gesture on a form that already scrolls vertically is the kind of
 * thing that needs a device to get right, which this repo cannot test.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
    title: String,
    index: Int,
    count: Int,
    canRemove: Boolean,
    issues: List<ComposerIssue>,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit,
    content: @Composable (advanced: Boolean) -> Unit
) {
    var advanced by remember { mutableStateOf(false) }
    val hasIssue = issues.isNotEmpty()
    // Opens itself when something is wrong and never closes itself again: all
    // but one issue kind is fixed by a field down there, and a rider who has
    // opened it deliberately must not have it shut under them by an unrelated
    // edit elsewhere on the form.
    LaunchedEffect(hasIssue) { if (hasIssue) advanced = true }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                Text(stringResource(Res.string.vehicle_source_move_up), fontSize = 11.sp)
            }
            TextButton(onClick = { onMove(index, index + 1) }, enabled = index < count - 1) {
                Text(stringResource(Res.string.vehicle_source_move_down), fontSize = 11.sp)
            }
            // Disabled at the last source: `Vehicle`'s own init requires one,
            // and the component refuses the call as well. Disabled AND
            // prevented — see VehicleDraft.canRemoveSource.
            TextButton(onClick = onRemove, enabled = canRemove) {
                Text(
                    stringResource(Res.string.vehicle_source_remove),
                    fontSize = 11.sp,
                    color = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }
        }

        issues.forEach { issue ->
            Text(
                text = composerIssueText(issue),
                fontSize = 11.sp,
                color = if (issue.blocking) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        content(advanced)

        TextButton(onClick = { advanced = !advanced }) {
            Text(
                text = stringResource(Res.string.vehicle_source_advanced) + if (advanced) " ▴" else " ▾",
                fontSize = 11.sp
            )
        }
    }
}

/**
 * The rider-facing sentence for a [ComposerIssue]. Wording only — whether it
 * blocks the save, and which card it lands on, are both decided in
 * `VehicleComposer.kt`.
 */
@Composable
internal fun composerIssueText(issue: ComposerIssue): String = when (issue) {
    is ComposerIssue.ConflictingKinds -> stringResource(Res.string.composer_issue_conflicting_kinds)
    is ComposerIssue.DuplicateCanId -> stringResource(Res.string.composer_issue_duplicate_can_id, issue.canId)
    is ComposerIssue.AmbiguousGatewaySource -> stringResource(Res.string.composer_issue_ambiguous_gateway)
    is ComposerIssue.BlankAddress -> stringResource(Res.string.composer_issue_blank_address)
    is ComposerIssue.NoControllerDecoder ->
        stringResource(Res.string.composer_issue_no_decoder, issue.controllerType.label)
    is ComposerIssue.HostlessVescBms -> stringResource(Res.string.composer_issue_hostless_vesc_bms)
    is ComposerIssue.UnroutableGateway -> stringResource(Res.string.composer_issue_unroutable_gateway)
}

// ---------------------------------------------------------------------------
// Small shared fields
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> TypeChips(
    label: String,
    options: List<T>,
    selected: T,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    Caption(label)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(text(option), fontSize = 12.sp) }
            )
        }
    }
}

@Composable
private fun LabelField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(Res.string.vehicle_field_source_label)) },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * A source's BLE link — typed, or picked in one tap from the links this vehicle
 * already has.
 *
 * The chip row is what "hosted" (`G §4`) looks like once you admit a vehicle can
 * have more than one link: a battery behind the head unit is one that shares the
 * head unit's address, and so is a slave uBox. A boolean could not have said
 * *which* source hosts it, and would not have applied to the controller at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkField(address: String, linkAddresses: List<String>, onAddressChanged: (String) -> Unit) {
    OutlinedTextField(
        value = address,
        onValueChange = onAddressChanged,
        singleLine = true,
        label = { Text(stringResource(Res.string.vehicle_field_link)) },
        modifier = Modifier.fillMaxWidth()
    )
    if (linkAddresses.isNotEmpty()) {
        Caption(stringResource(Res.string.vehicle_field_link_same_as))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            linkAddresses.forEach { candidate ->
                FilterChip(
                    selected = candidate == address,
                    onClick = { onAddressChanged(candidate) },
                    label = { Text(candidate, fontSize = 11.sp) }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            Caption(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ReadOnlyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Caption(text: String) {
    Text(text = text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// The numeric fields keep their own text state: rendering straight from the
// parsed component value made intermediate input impossible ("3." reparsed to
// "3.0" mid-typing; any invalid char wiped the field). External value changes
// (the async initial load) still sync in, but only when they disagree with
// what the current text parses to — so in-progress typing is never clobbered.
//
// Which is also why `MotorDraft` keeps a cleared field null instead of
// resolving it to `MotorConfig()`'s default on the spot: the resolved default
// would disagree with the empty text and this sync would type it back in.

@Composable
internal fun FloatField(label: String, value: Float?, onChange: (Float?) -> Unit) {
    var text by remember { mutableStateOf(value?.toString() ?: "") }
    if (value != text.toFloatOrNull()) text = value?.toString() ?: ""
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it.toFloatOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun IntField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    var text by remember { mutableStateOf(value?.toString() ?: "") }
    if (value != text.toIntOrNull()) text = value?.toString() ?: ""
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it.toIntOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
