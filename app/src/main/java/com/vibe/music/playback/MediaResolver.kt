package com.vibe.music.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSpec
import com.vibe.music.data.AgeGateFallback
import com.vibe.music.data.SettingsRepository
import com.vibe.music.data.db.TrackDao
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.remote.StreamInfo
import com.vibe.music.remote.YtDlp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Převádí `vibe://track/<videoId>` na skutečný zdroj:
 * stažený soubor, pokud existuje, jinak stream URL z yt-dlp (cachovanou do vypršení).
 */
class MediaResolver(
    private val tracks: TrackDao,
    private val ytDlp: YtDlp,
    private val settings: SettingsRepository,
    private val ageGate: AgeGateFallback,
) {
    private val streams = ConcurrentHashMap<String, StreamInfo>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Krok 1 (před cache): stažená skladba → lokální soubor, jinak ponechat `vibe://` URI
     * se stabilním klíčem do cache (URL streamu se mění, ID skladby ne).
     * Volá ExoPlayer na svém loader vlákně – blokování je zde v pořádku.
     */
    fun resolveLocal(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SCHEME) return spec
        val id = spec.uri.lastPathSegment ?: return spec
        localFile(id)?.let { return spec.withUri(Uri.fromFile(it)) }
        return spec.buildUpon().setKey("track:$id").build()
    }

    /** Krok 2 (jen při minutí cache): `vibe://` → URL streamu z yt-dlp. */
    fun resolveStream(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SCHEME) return spec
        val id = spec.uri.lastPathSegment ?: return spec
        val stream = try {
            runBlocking { stream(id) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Nepodařilo se získat stream: ${e.message}", e)
        }
        return spec.buildUpon()
            .setUri(stream.url.toUri())
            .setHttpRequestHeaders(stream.headers)
            .build()
    }

    suspend fun stream(id: String): StreamInfo {
        streams[id]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it }
        return locks.getOrPut(id) { Mutex() }.withLock {
            streams[id]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withLock it }
            val format = settings.current().audioFormat.ytDlpFormat
            ageGate.run(id) { sourceId -> ytDlp.stream(sourceId, format) }.also { streams[id] = it }
        }
    }

    /** Předem připraví stream další skladby, aby přechod byl okamžitý. */
    suspend fun prefetch(id: String) {
        if (localFile(id) == null) runCatching { stream(id) }
    }

    fun invalidate(id: String) {
        streams.remove(id)
    }

    private fun localFile(id: String): File? {
        val track = runBlocking { tracks.get(id) } ?: return null
        return track.localPath?.let(::File)?.takeIf { it.exists() }
    }

    companion object {
        const val SCHEME = "vibe"

        fun uriFor(id: String): Uri = "$SCHEME://track/$id".toUri()

        fun mediaItem(track: TrackEntity): MediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriFor(track.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.artworkUrl?.toUri())
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
    }
}
