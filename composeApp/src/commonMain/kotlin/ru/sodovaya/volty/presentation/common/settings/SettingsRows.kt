package ru.sodovaya.volty.presentation.common.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which colour family an icon tile in a settings row takes; always from the theme, never hard-coded. */
enum class SettingsTint { DRIVE, SOUND, MAPS, VEHICLE, NEUTRAL }

@Composable
private fun SettingsTint.tileColors(): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (this) {
        SettingsTint.DRIVE -> c.primaryContainer to c.onPrimaryContainer
        SettingsTint.SOUND -> c.tertiaryContainer to c.onTertiaryContainer
        SettingsTint.MAPS -> c.secondaryContainer to c.onSecondaryContainer
        SettingsTint.VEHICLE -> c.primary to c.onPrimary
        SettingsTint.NEUTRAL -> c.surfaceVariant to c.onSurfaceVariant
    }
}

/** A small label above a rounded card whose rows are separated by hairline dividers. */
@Composable
fun SettingsGroup(label: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            content = content,
        )
    }
}

/** A hairline between two rows of a [SettingsGroup]. */
@Composable
fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** An icon tile, a title, a one-line summary of the current value and a chevron. */
@Composable
fun SettingsNavRow(
    icon: ImageVector,
    tint: SettingsTint,
    title: String,
    summary: String?,
    onClick: () -> Unit,
) {
    val (container, content) = tint.tileColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (summary != null) {
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SettingsSwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A title with the current value on the right and a slider under it. */
@Composable
fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    hint: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Up to four options as a segmented control; more than that becomes a list of selectable rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (options.size <= MAX_SEGMENTS) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = selected == option.first,
                        enabled = enabled,
                        onClick = { onSelect(option.first) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) { Text(option.second, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        } else {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { onSelect(option.first) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option.first, onClick = null, enabled = enabled)
                    Text(option.second, modifier = Modifier.padding(start = 12.dp), fontSize = 14.sp)
                }
            }
        }
        if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A destructive action as a full-width row, in the error colour. */
@Composable
fun SettingsDangerRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.error,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

private const val MAX_SEGMENTS = 4
