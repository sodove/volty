package ru.sodovaya.volty.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.model.ThemeAccent
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.settings_theme_accent
import volty.composeapp.generated.resources.settings_theme_accent_blue
import volty.composeapp.generated.resources.settings_theme_accent_default
import volty.composeapp.generated.resources.settings_theme_accent_green
import volty.composeapp.generated.resources.settings_theme_accent_orange
import volty.composeapp.generated.resources.settings_theme_accent_pink
import volty.composeapp.generated.resources.settings_theme_accent_teal

@Composable
internal fun ThemeAccentControls(
    selectedAccent: ThemeAccent,
    onAccentSelected: (ThemeAccent) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_theme_accent),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ThemeAccent.entries.chunked(2).forEach { rowAccents ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowAccents.forEach { accent ->
                    val label = stringResource(accent.labelResource())
                    FilterChip(
                        selected = accent == selectedAccent,
                        onClick = { onAccentSelected(accent) },
                        modifier = Modifier.weight(1f),
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(accent.seedArgb))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(label)
                            }
                        },
                    )
                }
                if (rowAccents.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun ThemeAccent.labelResource() = when (this) {
    ThemeAccent.DEFAULT -> Res.string.settings_theme_accent_default
    ThemeAccent.ORANGE -> Res.string.settings_theme_accent_orange
    ThemeAccent.GREEN -> Res.string.settings_theme_accent_green
    ThemeAccent.TEAL -> Res.string.settings_theme_accent_teal
    ThemeAccent.BLUE -> Res.string.settings_theme_accent_blue
    ThemeAccent.PINK -> Res.string.settings_theme_accent_pink
}
