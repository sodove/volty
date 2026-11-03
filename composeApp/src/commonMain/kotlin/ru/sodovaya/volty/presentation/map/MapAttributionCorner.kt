package ru.sodovaya.volty.presentation.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Retraction: this was a single 9sp [Text] at alpha 0x88 with no click handling. It now sits on a
 * translucent chip so it stays readable over any tile, and each credit is a tap target of its own.
 * Rendering is judged on a device; there is no Compose UI test in this repo.
 */
@Composable
fun MapAttributionCorner(darkTheme: Boolean, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val text = if (darkTheme) Color(0xFFD8E2E6) else Color(0xFF283238)
    val chip = if (darkTheme) Color(0xCC10171A) else Color(0xCCFFFFFF)
    val sources = listOf(
        stringResource(Res.string.history_preview_source_ofm) to "https://openfreemap.org/",
        stringResource(Res.string.history_preview_source_omt) to "https://openmaptiles.org/",
        stringResource(Res.string.history_preview_source_osm) to "https://www.openstreetmap.org/copyright",
    )
    Row(
        modifier = modifier.background(chip, RoundedCornerShape(8.dp)).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for ((name, url) in sources) {
            Text(
                text = name,
                color = text,
                fontSize = 10.sp,
                modifier = Modifier.clickable(role = Role.Button) { uri.openUri(url) }.padding(horizontal = 5.dp, vertical = 6.dp),
            )
        }
    }
}
