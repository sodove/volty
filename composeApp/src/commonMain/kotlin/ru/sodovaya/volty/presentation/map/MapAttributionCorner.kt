package ru.sodovaya.volty.presentation.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.*

/**
 * The live map's credit line: OpenFreeMap, OpenMapTiles and OpenStreetMap, each a link to its
 * attribution page (OpenMapTiles requires a visible, linked credit in the corner of the map).
 *
 * Retraction: this was a single 9sp [Text] at alpha 0x88 with no click handling. 6546eaa3 put it on a
 * translucent rounded chip (0xCC10171A dark / 0xCCFFFFFF light) "so it stays readable over any
 * tile"; the owner rejected that plate (feedback 2026-11-04, 0.7.64 screenshots): the credit is
 * plain text over the map again, in the pre-chip colours, and only the links are kept. Each credit
 * is still its own tap target; the padding around it is invisible and only widens the target.
 * Every live map host (ride dashboard, history, group map, route preview) draws this one
 * composable from `AndroidMapLibreView`. Rendering is judged on a device; there is no Compose UI
 * test in this repo.
 */
@Composable
fun MapAttributionCorner(darkTheme: Boolean, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val text = if (darkTheme) Color(0xB8C8D4DA) else Color(0x88364048)
    val sources = listOf(
        stringResource(Res.string.history_preview_source_ofm) to "https://openfreemap.org/",
        stringResource(Res.string.history_preview_source_omt) to "https://openmaptiles.org/",
        stringResource(Res.string.history_preview_source_osm) to "https://www.openstreetmap.org/copyright",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for ((name, url) in sources) {
            Text(
                text = name,
                color = text,
                fontSize = 9.sp,
                modifier = Modifier.clickable(role = Role.Button) { uri.openUri(url) }.padding(horizontal = 3.dp, vertical = 6.dp),
            )
        }
    }
}
