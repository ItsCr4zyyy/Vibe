package com.vibe.music.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.vibe.music.AppContainer
import com.vibe.music.ui.theme.VibeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer není k dispozici") }

@Composable
inline fun <reified VM : ViewModel> vibeViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatMs(ms: Long) = formatDuration((ms / 1000).toInt()).ifEmpty { "0:00" }

fun formatTotal(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}

fun pluralTracks(n: Int) = when {
    n == 1 -> "1 skladba"
    n in 2..4 -> "$n skladby"
    else -> "$n skladeb"
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} kB"
}

// ---------- Dynamické barvy z obalu alba ----------

private val colorCache = LruCache<String, Int>(200)

private suspend fun extractColor(context: Context, url: String): Color? {
    colorCache.get(url)?.let { return Color(it) }
    val bitmap: Bitmap = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    } ?: return null
    val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
    val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch ?: return null
    // Ztmavit, aby byl bílý text vždy čitelný
    val color = lerp(Color(swatch.rgb), Color.Black, 0.35f)
    colorCache.put(url, color.toArgb())
    return color
}

/** Plynule animovaná barva vytažená z obalu (jako pozadí přehrávače ve Spotify). */
@Composable
fun rememberArtworkColor(url: String?, fallback: Color = VibeColors.SurfaceHigh): State<Color> {
    val context = LocalContext.current
    val target by produceState(fallback, url) {
        value = url?.let { runCatching { extractColor(context, it) }.getOrNull() } ?: fallback
    }
    return animateColorAsState(target, tween(600), label = "artworkColor")
}
