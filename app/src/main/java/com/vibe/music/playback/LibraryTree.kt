package com.vibe.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.vibe.music.AppContainer
import com.vibe.music.data.db.TrackEntity

/**
 * Strom knihovny pro Android Auto (a další prohlížeče médií).
 * ID: „root“, složky („liked“, „recent“, „playlists“, „mixes“, „pl:<id>“, „downloaded“),
 * skladby „<složka>|<videoId>“ (aby se po výběru přehrála celá složka od dané skladby)
 * a mixy „mix:library“ / „mix:pl:<id>“.
 */
class LibraryTree(private val c: AppContainer) {

    @Volatile private var lastSearch: List<TrackEntity> = emptyList()

    fun root(): MediaItem = folder(ROOT, "Vibe")

    /** Kořen pro systémové „pokračovat v poslechu“ (karta médií po restartu telefonu). */
    fun recentRoot(): MediaItem = folder(RECENT_ROOT, "Naposledy")

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(LIKED, "Oblíbené"),
            folder(RECENT, "Naposledy"),
            folder(PLAYLISTS, "Playlisty"),
            folder(MIXES, "Mixy"),
        )
        parentId == RECENT_ROOT -> c.music.tracks.recent(1).map { trackItem(RECENT, it) }
        parentId == PLAYLISTS -> buildList {
            add(folder(DOWNLOADED, "Stažené"))
            c.music.playlists.summaries().forEach {
                add(folder("pl:${it.playlist.id}", it.playlist.name, "${it.trackCount} skladeb", it.playlist.artworkUrl))
            }
        }
        parentId == MIXES -> buildList {
            add(playableFolder("mix:library", "Tvůj mix", "Oblíbené + podobné skladby", null))
            c.music.playlists.summaries().filter { it.trackCount >= 3 }.forEach {
                add(playableFolder("mix:pl:${it.playlist.id}", "${it.playlist.name} rádio", "Playlist + podobné", it.playlist.artworkUrl))
            }
        }
        else -> tracks(parentId).map { trackItem(parentId, it) }
    }

    /** Skladby ve složce (pro výběr skladby i pro přehrání celé složky). */
    suspend fun tracks(parentId: String): List<TrackEntity> = when {
        parentId == LIKED -> c.music.tracks.liked()
        parentId == RECENT -> c.music.tracks.recent(50)
        parentId == DOWNLOADED -> c.music.tracks.downloaded()
        parentId == SEARCH -> lastSearch
        parentId.startsWith("pl:") -> c.music.playlists.tracksOf(parentId.removePrefix("pl:").toLongOrNull() ?: -1)
        else -> emptyList()
    }

    suspend fun mix(mixId: String): List<TrackEntity> = when {
        mixId == "mix:library" -> c.radio.mix(c.music.tracks.libraryPool(), seeds = 6)
        mixId.startsWith("mix:pl:") ->
            c.radio.mix(c.music.playlists.tracksOf(mixId.removePrefix("mix:pl:").toLongOrNull() ?: -1))
        else -> emptyList()
    }

    suspend fun search(query: String): List<TrackEntity> {
        val results = runCatching { c.ytMusic.searchSongs(query) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: c.ytDlp.search(query, 15)
        return results.map { it.toEntity() }.also { lastSearch = it }
    }

    fun searchItems(): List<MediaItem> = lastSearch.map { trackItem(SEARCH, it) }

    private fun folder(id: String, title: String, subtitle: String? = null, artwork: String? = null) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(ArtworkProvider.uriFor(c.appContext, artwork))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun playableFolder(id: String, title: String, subtitle: String, artwork: String?) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(ArtworkProvider.uriFor(c.appContext, artwork))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()

    private fun trackItem(parentId: String, t: TrackEntity): MediaItem {
        val base = MediaResolver.mediaItem(t)
        return base.buildUpon()
            .setMediaId("$parentId|${t.id}")
            .setMediaMetadata(
                base.mediaMetadata.buildUpon()
                    .setArtworkUri(ArtworkProvider.uriFor(c.appContext, t.artworkUrl))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    companion object {
        const val ROOT = "root"
        const val RECENT_ROOT = "recent_root"
        const val LIKED = "liked"
        const val RECENT = "recent"
        const val PLAYLISTS = "playlists"
        const val MIXES = "mixes"
        const val DOWNLOADED = "downloaded"
        const val SEARCH = "search"

        /** „složka|videoId“ → (složka, videoId) */
        fun splitTrackId(mediaId: String): Pair<String, String>? =
            mediaId.indexOf('|').takeIf { it > 0 }?.let { mediaId.substring(0, it) to mediaId.substring(it + 1) }
    }
}
