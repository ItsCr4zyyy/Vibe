package com.vibe.music.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.vibe.music.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Pošle playlist jako soubor .vibe.json – kamarád ho otevře ve Vibe a playlist se mu přidá. */
suspend fun sharePlaylist(context: Context, c: AppContainer, playlistId: Long) {
    val (name, json) = withContext(Dispatchers.IO) { c.backup.exportPlaylist(playlistId) }
    val safeName = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "playlist" }
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "share").apply { mkdirs() }.resolve("$safeName.vibe.json").apply { writeText(json) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, "Playlist „$name“ pro Vibe – otevři soubor ve Vibe.")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Sdílet playlist").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Načte sdílený playlist / zálohu z [uri] a přidá ho do knihovny. */
suspend fun importVibeFile(context: Context, c: AppContainer, uri: Uri) {
    val message = runCatching {
        val json = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("Soubor nelze přečíst")
        }
        val result = withContext(Dispatchers.IO) { c.backup.restore(json) }
        when (result.playlists.size) {
            0 -> "Přidáno ${pluralTracks(result.tracks)}"
            1 -> "Playlist „${result.playlists.first()}“ přidán do knihovny"
            else -> "Obnoveno: ${result.playlists.size} playlistů, ${pluralTracks(result.tracks)}"
        }
    }.getOrElse { "Import selhal: ${it.message}" }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
