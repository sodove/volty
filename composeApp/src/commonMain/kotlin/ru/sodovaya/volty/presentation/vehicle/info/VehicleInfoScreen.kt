package ru.sodovaya.volty.presentation.vehicle.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.model.DeviceFactKey
import ru.sodovaya.volty.presentation.common.settings.SettingsGroup
import ru.sodovaya.volty.util.UnitSystem
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.info_instance_title
import volty.composeapp.generated.resources.info_persisted
import volty.composeapp.generated.resources.info_title
import volty.composeapp.generated.resources.vehicle_hub_back

/**
 * «Всё о ТС»: a renderer for [VehicleInfoComponent.info], and nothing more. Every decision (which
 * rows exist, what is absent and why, what is converted to km or mi) was made by `vehicleInfoOf`,
 * which has tests; this only turns enums into words and lays them out in the same grouped cards as
 * the settings and the vehicle editor.
 *
 * **Unverified on a device**: Compose is not unit-testable in this repo, so the layout, the wrapping
 * of long values (fault lists, wheel alerts) and the dark theme have been compiled, not looked at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleInfoScreen(component: VehicleInfoComponent) {
    val info by component.info.collectAsState()
    val units by component.units.collectAsState()
    val name by component.vehicleName.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.info_title))
                        name?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.vehicle_hub_back))
                    }
                },
            )
        }
    ) { padding ->
        val current = info
        if (current == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (section in current.sections) {
                val heading = stringResource(section.kind.stringRes())
                SettingsGroup(section.title?.let { stringResource(Res.string.info_instance_title, heading, it) } ?: heading) {
                    section.rows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        InfoRowView(row, units)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRowView(row: InfoRow, units: UnitSystem) {
    val label = rowLabel(row)
    val reason = row.reason?.let { stringResource(it.stringRes()) }
    val shown = rowValue(row, units)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(0.5f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (row.value == null && reason != null) {
                Text(reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.persisted) {
                Text(stringResource(Res.string.info_persisted), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            shown ?: ABSENT,
            fontSize = 15.sp,
            color = if (shown == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.5f),
        )
    }
}

/** The name of a row; a device fact is named by its key, and a repeated branch carries its number. */
@Composable
private fun rowLabel(row: InfoRow): String {
    val fact = row.fact
    if (row.label != InfoLabel.DEVICE_FACT || fact == null) return stringResource(row.label.stringRes())
    val base = stringResource(fact.stringRes())
    val numbered = fact == DeviceFactKey.BRANCH_VOLTAGE_V || fact == DeviceFactKey.BRANCH_CURRENT_A
    return if (numbered && row.index != null) "$base ${row.index}" else base
}

/** The right-hand side: the number with its unit, then the choice; null (drawn as a dash) when it has neither. */
@Composable
private fun rowValue(row: InfoRow, units: UnitSystem): String? {
    val parts = buildList {
        row.value?.let { v -> add(row.unit?.let { "$v ${stringResource(it.stringRes(units))}" } ?: v) }
        row.choice?.let { add(stringResource(it.stringRes())) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private const val ABSENT = "—"
