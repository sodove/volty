package ru.sodovaya.volty.presentation.video

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ru.sodovaya.volty.video.RideVideoActivity

@Composable
actual fun rememberRideVideoOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { { context.startActivity(Intent(context, RideVideoActivity::class.java)) } }
}
