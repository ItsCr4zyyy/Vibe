package com.vibe.music.data

import com.vibe.music.data.db.PlaylistEntity
import com.vibe.music.data.db.PlaylistSource
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.data.db.VibeDatabase

/** Operace nad knihovnou (skladby, oblíbené, playlisty, historie). */
class MusicRepository(db: VibeDatabase) {
    val tracks = db.tracks()
    val playlists = db.playlists()

    /** Uloží skladby, které v knihovně ještě nejsou (existující nepřepisuje – zachová like/stažení). */
    suspend fun ensureTracks(list: List<TrackEntity>) {
        if (list.isNotEmpty()) tracks.insertIgnore(list)
    }

    suspend fun setLiked(track: TrackEntity, liked: Boolean) {
        ensureTracks(listOf(track))
        tracks.setLiked(track.id, liked)
    }

    suspend fun onPlayed(id: String) = tracks.markPlayed(id, System.currentTimeMillis())

    suspend fun createPlaylist(
        name: String,
        source: String = PlaylistSource.LOCAL,
        sourceUrl: String? = null,
        artworkUrl: String? = null,
    ): Long = playlists.insert(
        PlaylistEntity(
            name = name,
            source = source,
            sourceUrl = sourceUrl,
            artworkUrl = artworkUrl,
            autoSync = sourceUrl != null,
        )
    )

    suspend fun addToPlaylist(playlistId: Long, list: List<TrackEntity>): Int {
        ensureTracks(list)
        return playlists.appendTracks(playlistId, list.map { it.id })
    }

    suspend fun removeFromPlaylist(playlistId: Long, trackId: String) = playlists.removeTrack(playlistId, trackId)

    suspend fun renamePlaylist(id: Long, name: String) {
        playlists.get(id)?.let { playlists.update(it.copy(name = name)) }
    }

    suspend fun updatePlaylist(playlist: PlaylistEntity) = playlists.update(playlist)

    suspend fun deletePlaylist(id: Long) = playlists.delete(id)
}
