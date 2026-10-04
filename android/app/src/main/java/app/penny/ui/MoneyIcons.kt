package app.penny.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.data.IconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded icons, shared by all screens. Thumbnails are at most 96 px, so a few hundred take little memory. */
private val decoded = LruCache<String, ImageBitmap>(300)

/** The icon with this ID once it's on the phone, or null (no icon, not downloaded yet, or unreadable). */
@Composable
fun rememberMoneyIcon(icons: IconStore, id: String?): ImageBitmap? {
    val available by icons.available.collectAsStateWithLifecycle()
    val ready = id != null && id in available
    val bitmap by produceState(id?.let { decoded.get(it) }, id, ready) {
        value = if (id == null || !ready) null else decoded.get(id) ?: withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(icons.file(id).path)?.asImageBitmap()
        }?.also { decoded.put(id, it) }
    }
    return bitmap
}

/** A payee's logo from Money. Logos are made for light backgrounds, so they sit on white in the dark theme too. */
@Composable
fun PayeeLogo(bitmap: ImageBitmap, size: Dp = 32.dp) {
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 5))
            .background(Color.White),
    )
}

/** A category glyph from Money.app. They're template images (only the shape counts), so they take the theme's color. */
@Composable
fun CategoryGlyph(bitmap: ImageBitmap, size: Dp = 24.dp) {
    Icon(bitmap, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size))
}
