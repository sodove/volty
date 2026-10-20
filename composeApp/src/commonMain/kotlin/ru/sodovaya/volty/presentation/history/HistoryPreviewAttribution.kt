package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.*

/** Snapshot attribution is retained too; these links remain readable at row size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryPreviewAttribution() {
    val uri=LocalUriHandler.current
    val sources=listOf(stringResource(Res.string.history_preview_source_ofm) to "https://openfreemap.org/",
        stringResource(Res.string.history_preview_source_omt) to "https://openmaptiles.org/",
        stringResource(Res.string.history_preview_source_osm) to "https://www.openstreetmap.org/copyright")
    FlowRow(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.Center) {
        for((name,url) in sources) Text(name,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
            modifier=Modifier.clickable(role=Role.Button){uri.openUri(url)}.padding(horizontal=4.dp,vertical=4.dp))
    }
}
