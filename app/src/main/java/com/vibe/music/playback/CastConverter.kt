package com.vibe.music.playback

import androidx.core.net.toUri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import com.vibe.music.AppContainer
import org.json.JSONObject

/** Převod skladeb Vibe na položky Chromecastu (URL z lokálního [CastServer]u) a zpět. */
class CastConverter(private val c: AppContainer) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val id = mediaItem.mediaId
        val meta = mediaItem.mediaMetadata
        val castMeta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, meta.title?.toString().orEmpty())
            putString(MediaMetadata.KEY_ARTIST, meta.artist?.toString().orEmpty())
            meta.albumTitle?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
            meta.artworkUri?.takeIf { it.scheme == "https" }?.let { addImage(WebImage(it)) }
        }
        val info = MediaInfo.Builder(c.castServer.urlFor(id))
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(c.castServer.contentType(id))
            .setMetadata(castMeta)
            .setCustomData(
                JSONObject()
                    .put(KEY_ID, id)
                    .put(KEY_ARTWORK, meta.artworkUri?.toString().orEmpty())
            )
            .build()
        return MediaQueueItem.Builder(info).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val custom = info?.customData
        val id = custom?.optString(KEY_ID).orEmpty()
        val meta = info?.metadata
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(MediaResolver.uriFor(id))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(meta?.getString(MediaMetadata.KEY_TITLE))
                    .setArtist(meta?.getString(MediaMetadata.KEY_ARTIST))
                    .setAlbumTitle(meta?.getString(MediaMetadata.KEY_ALBUM_TITLE))
                    .setArtworkUri(custom?.optString(KEY_ARTWORK)?.takeIf { it.isNotEmpty() }?.toUri())
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
    }

    companion object {
        private const val KEY_ID = "vibeId"
        private const val KEY_ARTWORK = "artwork"
    }
}
