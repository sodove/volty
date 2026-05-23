package com.volty.app.presentation.vehicle

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
import com.volty.app.domain.model.Chemistry

private val ICON_KEYS = listOf("generic", "skateboard", "ebike", "scooter", "moto", "solar", "ev", "boat", "rv")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(component: VehicleEditComponent) {
    val state by component.state.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit battery" else "New battery") },
                navigationIcon = {
                    TextButton(onClick = component::onCancel) { Text("Cancel") }
                },
                actions = {
                    TextButton(onClick = component::onSave, enabled = !state.saving) {
                        if (state.saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Save")
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
                label = { Text("Name") },
                isError = state.nameError != null,
                supportingText = { state.nameError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Icon")
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
                            text = iconEmoji(key),
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            ReadOnlyRow("BMS type", state.bmsType.label)
            ReadOnlyRow("BMS address", state.bmsAddress.ifEmpty { "—" })

            SectionLabel("Chemistry")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val chemistries = Chemistry.entries
                chemistries.forEachIndexed { idx, c ->
                    SegmentedButton(
                        selected = state.chemistry == c,
                        onClick = { component.onChemistryChanged(c) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = chemistries.size)
                    ) { Text(c.label, fontSize = 12.sp) }
                }
            }

            OutlinedTextField(
                value = state.cellCount?.toString() ?: "",
                onValueChange = { component.onCellCountChanged(it.toIntOrNull()) },
                label = { Text("Cell count (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Averaging window")
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
            SectionLabel("Alert thresholds")
            FloatField("Cell V high", state.cellHighV, component::onCellHighVChanged)
            FloatField("Cell V low", state.cellLowV, component::onCellLowVChanged)
            FloatField("Temperature high (°C)", state.temperatureHighC, component::onTemperatureHighChanged)
            IntField("SOC low (%)", state.socLowPercent, component::onSocLowChanged)

            if (state.isEditing) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showDeleteConfirm = true }
                ) {
                    Text("Delete battery", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                confirmButton = {
                    TextButton(onClick = { showDeleteConfirm = false; component.onDelete() }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
                title = { Text("Delete this battery?") },
                text = { Text("This cannot be undone.") }
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

private fun iconEmoji(key: String): String = when (key) {
    "skateboard" -> "🛹"
    "ebike" -> "🚲"
    "scooter" -> "🛵"
    "moto" -> "🏍"
    "solar" -> "☀"
    "ev" -> "🚗"
    "boat" -> "⛵"
    "rv" -> "🚐"
    else -> "⚡"
}
