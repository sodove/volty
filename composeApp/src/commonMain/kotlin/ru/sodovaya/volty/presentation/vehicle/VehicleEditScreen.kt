package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import ru.sodovaya.volty.presentation.common.chemistryLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.action_delete
import volty.composeapp.generated.resources.vehicle_delete
import volty.composeapp.generated.resources.vehicle_delete_confirm_text
import volty.composeapp.generated.resources.vehicle_delete_confirm_title
import volty.composeapp.generated.resources.vehicle_edit_edit
import volty.composeapp.generated.resources.vehicle_edit_new
import volty.composeapp.generated.resources.vehicle_field_averaging_window
import volty.composeapp.generated.resources.vehicle_field_bms_address
import volty.composeapp.generated.resources.vehicle_field_bms_type
import volty.composeapp.generated.resources.vehicle_field_cell_count
import volty.composeapp.generated.resources.vehicle_field_cell_high
import volty.composeapp.generated.resources.vehicle_field_cell_low
import volty.composeapp.generated.resources.vehicle_field_chemistry
import volty.composeapp.generated.resources.vehicle_field_icon
import volty.composeapp.generated.resources.vehicle_field_name
import volty.composeapp.generated.resources.vehicle_field_name_required
import volty.composeapp.generated.resources.vehicle_field_soc_low
import volty.composeapp.generated.resources.vehicle_field_temp_high
import volty.composeapp.generated.resources.vehicle_save
import volty.composeapp.generated.resources.vehicle_section_alerts

private val ICON_KEYS = listOf("generic", "skateboard", "ebike", "scooter", "moto", "solar", "ev", "boat", "rv")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(component: VehicleEditComponent) {
    val state by component.state.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) stringResource(Res.string.vehicle_edit_edit) else stringResource(Res.string.vehicle_edit_new)) },
                navigationIcon = {
                    TextButton(onClick = component::onCancel) { Text(stringResource(Res.string.action_cancel)) }
                },
                actions = {
                    TextButton(onClick = component::onSave, enabled = !state.saving) {
                        if (state.saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(Res.string.vehicle_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = component::onNameChanged,
                label = { Text(stringResource(Res.string.vehicle_field_name)) },
                isError = state.nameError,
                supportingText = { if (state.nameError) Text(stringResource(Res.string.vehicle_field_name_required)) },
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel(stringResource(Res.string.vehicle_field_icon))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ICON_KEYS.forEach { key ->
                    val selected = state.iconKey == key
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { component.onIconChanged(key) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = iconKeyToEmoji(key),
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            ReadOnlyRow(stringResource(Res.string.vehicle_field_bms_type), bmsTypeLabel(state.bmsType))
            ReadOnlyRow(stringResource(Res.string.vehicle_field_bms_address), state.bmsAddress.ifEmpty { "—" })

            SectionLabel(stringResource(Res.string.vehicle_field_chemistry))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val chemistries = Chemistry.entries
                chemistries.forEachIndexed { idx, c ->
                    SegmentedButton(
                        selected = state.chemistry == c,
                        onClick = { component.onChemistryChanged(c) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = chemistries.size)
                    ) { Text(chemistryLabel(c), fontSize = 12.sp) }
                }
            }

            OutlinedTextField(
                value = state.cellCount?.toString() ?: "",
                onValueChange = { component.onCellCountChanged(it.toIntOrNull()) },
                label = { Text(stringResource(Res.string.vehicle_field_cell_count)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel(stringResource(Res.string.vehicle_field_averaging_window))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 30).forEach { min ->
                    FilterChip(
                        selected = state.averagingWindowMin == min,
                        onClick = { component.onAveragingWindowChanged(min) },
                        label = { Text("${min}m") }
                    )
                }
            }

            HorizontalDivider()
            SectionLabel(stringResource(Res.string.vehicle_section_alerts))
            FloatField(stringResource(Res.string.vehicle_field_cell_high), state.cellHighV, component::onCellHighVChanged)
            FloatField(stringResource(Res.string.vehicle_field_cell_low), state.cellLowV, component::onCellLowVChanged)
            FloatField(stringResource(Res.string.vehicle_field_temp_high), state.temperatureHighC, component::onTemperatureHighChanged)
            IntField(stringResource(Res.string.vehicle_field_soc_low), state.socLowPercent, component::onSocLowChanged)

            if (state.isEditing) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showDeleteConfirm = true }
                ) {
                    Text(stringResource(Res.string.vehicle_delete), color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                confirmButton = {
                    TextButton(onClick = { showDeleteConfirm = false; component.onDelete() }) {
                        Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(Res.string.action_cancel)) } },
                title = { Text(stringResource(Res.string.vehicle_delete_confirm_title)) },
                text = { Text(stringResource(Res.string.vehicle_delete_confirm_text)) }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
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
private fun FloatField(label: String, value: Float?, onChange: (Float?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { onChange(it.toFloatOrNull()) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun IntField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { onChange(it.toIntOrNull()) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

