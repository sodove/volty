package ru.sodovaya.volty.presentation.video

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.video.RideVideoActivity
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.video_title

@Composable
actual fun RideVideoButton(modifier: Modifier) {
    val context = LocalContext.current
    FilledTonalIconButton(onClick = { context.startActivity(Intent(context, RideVideoActivity::class.java)) }, modifier = modifier) {
        Icon(Icons.Default.Videocam, contentDescription = stringResource(Res.string.video_title))
    }
}
