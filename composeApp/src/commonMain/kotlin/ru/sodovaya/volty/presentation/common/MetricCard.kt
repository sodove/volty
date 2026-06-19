package ru.sodovaya.volty.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class MetricCardVariant { Default, Tertiary, Primary }

@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    variant: MetricCardVariant = MetricCardVariant.Default,
    sub: String? = null,
    extra: @Composable (() -> Unit)? = null,
    containerColor: Color? = null,
    onColor: Color? = null
) {
    val (defaultBg, defaultFg) = when (variant) {
        MetricCardVariant.Default -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurface
        MetricCardVariant.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        MetricCardVariant.Primary -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    val bg = containerColor ?: defaultBg
    val fg = onColor ?: defaultFg
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .heightIn(min = 110.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = label.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(2.dp))
            // Auto-shrink to one line so wide values (e.g. "-3876 W") never wrap
            // onto a second line. autoSize fits within the card width, clamped so
            // it stays readable; fontSize in the style is ignored when autoSize set.
            BasicText(
                text = value,
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(color = fg, fontWeight = FontWeight.Medium),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 13.sp,
                    maxFontSize = 22.sp,
                    stepSize = 1.sp
                )
            )
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = sub, fontSize = 11.sp, color = fg.copy(alpha = 0.7f))
            }
        }
        if (extra != null) {
            Spacer(Modifier.height(6.dp))
            extra()
        }
    }
}
