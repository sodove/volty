package ru.sodovaya.volty.presentation.common

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.hct.Hct

@Composable
actual fun dynamicVoltyTonalColors(): VoltyTonalColors {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        if (Build.VERSION.SDK_INT < 31) seededTonalColors(0xFF5B53D4.toInt())
        else {
            val tones = listOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0)
            fun family(ids: List<Int>): (Int) -> Int {
                val colors = tones.zip(ids.map { context.getColor(it) }).toMap()
                return { tone ->
                    colors[tone] ?: colors.getValue(tones.minBy { kotlin.math.abs(it - tone) }).let { nearest ->
                        // Android publishes 13 tones. Extend those families to the map's T5/T98,
                        // rather than treating a Material surface role as the underlying palette.
                        val hct = Hct.fromInt(nearest)
                        Hct.from(hct.hue, hct.chroma, tone.toDouble()).toInt()
                    }
                }
            }
            VoltyTonalColors(family(accent1), family(accent2), family(accent3), family(neutral1), family(neutral2))
        }
    }
}

@get:RequiresApi(31)
private val accent1 get() = listOf(android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50, android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300, android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600, android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900, android.R.color.system_accent1_1000)
@get:RequiresApi(31)
private val accent2 get() = listOf(android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50, android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300, android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600, android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900, android.R.color.system_accent2_1000)
@get:RequiresApi(31)
private val accent3 get() = listOf(android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50, android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300, android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600, android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900, android.R.color.system_accent3_1000)
@get:RequiresApi(31)
private val neutral1 get() = listOf(android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50, android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300, android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600, android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900, android.R.color.system_neutral1_1000)
@get:RequiresApi(31)
private val neutral2 get() = listOf(android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50, android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300, android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600, android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900, android.R.color.system_neutral2_1000)
