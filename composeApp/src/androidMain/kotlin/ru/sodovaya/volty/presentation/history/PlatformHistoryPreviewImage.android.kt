package ru.sodovaya.volty.presentation.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun PlatformHistoryPreviewImage(image: HistoryPreviewImage, modifier: Modifier) {
    val decoded by produceState<Bitmap?>(null, image) {
        value = withContext(Dispatchers.Default) {
            val bytes = image.copyBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if (bounds.outWidth in 1..512 && bounds.outHeight in 1..512) BitmapFactory.decodeByteArray(bytes,0,bytes.size) else null
        }
    }
    // Native Bitmap ownership remains with this image; do not recycle a buffer
    // while Compose may still have a render command referencing its pixels.
    decoded?.let { bitmap -> Image(bitmap.asImageBitmap(),null,modifier,contentScale=ContentScale.FillBounds) }
}
