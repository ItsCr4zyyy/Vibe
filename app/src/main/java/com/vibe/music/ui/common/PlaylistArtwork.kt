package com.vibe.music.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.vibe.music.data.db.PlaylistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Obal playlistu: vlastní/importovaný obrázek, jinak koláž ze 4 obalů skladeb (jako Spotify),
 * případně obal první skladby.
 */
@Composable
fun PlaylistArtwork(
    playlist: PlaylistEntity,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    if (playlist.artworkUrl != null) {
        Artwork(playlist.artworkUrl, modifier, shape, Icons.AutoMirrored.Rounded.QueueMusic)
        return
    }
    val c = LocalContainer.current
    val artworks by remember(playlist.id) { c.music.playlists.observeArtworks(playlist.id) }.collectAsState(emptyList())
    val distinct = artworks.distinct()
    when {
        distinct.size >= 4 -> Column(modifier.clip(shape)) {
            listOf(distinct.take(2), distinct.drop(2).take(2)).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { Artwork(it, Modifier.weight(1f).fillMaxSize(), RectangleShape) }
                }
            }
        }
        distinct.isNotEmpty() -> Artwork(distinct.first(), modifier, shape, Icons.AutoMirrored.Rounded.QueueMusic)
        else -> Artwork(null, modifier, shape, Icons.AutoMirrored.Rounded.QueueMusic)
    }
}

/**
 * Uloží obrázek z galerie jako obal playlistu (zmenšený na max. 800 px) a vrátí jeho file:// URI.
 */
suspend fun saveCoverImage(context: Context, playlistId: Long, source: Uri): String = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: error("Obrázek nelze načíst")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 800) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Nepodporovaný formát obrázku")
    val dir = File(context.filesDir, "covers").apply { mkdirs() }
    // Staré obaly tohoto playlistu smazat; nový název kvůli cache obrázků
    dir.listFiles { f -> f.name.startsWith("$playlistId-") }?.forEach { it.delete() }
    val file = File(dir, "$playlistId-${System.currentTimeMillis()}.jpg")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    Uri.fromFile(file).toString()
}
