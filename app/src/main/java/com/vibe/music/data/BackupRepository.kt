package com.vibe.music.data

import androidx.room.withTransaction
import com.vibe.music.data.db.ListeningEntity
import com.vibe.music.data.db.PlaylistEntity
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.data.db.VibeDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int = 0,
    val artworkUrl: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val liked: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val addedAt: Long = 0,
)

@Serializable
data class BackupPlaylist(
    val name: String,
    val source: String,
    val sourceUrl: String? = null,
    val artworkUrl: String? = null,
    val autoSync: Boolean = false,
    val trackIds: List<String>,
)

@Serializable
data class BackupListening(val trackId: String, val day: String, val ms: Long)

@Serializable
data class Backup(
    val format: String = FORMAT,
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val tracks: List<BackupTrack>,
    val playlists: List<BackupPlaylist>,
    val listening: List<BackupListening> = emptyList(),
) {
    companion object {
        const val FORMAT = "vibe-backup"
    }
}

data class RestoreResult(val tracks: Int, val playlists: List<String>, val likedAdded: Int)

/**
 * Záloha celé knihovny (přechod na nový telefon) a sdílení playlistů s kamarády.
 * Obnova knihovnu jen doplňuje – nic nemaže ani nepřepisuje.
 */
class BackupRepository(private val db: VibeDatabase) {
    private val format = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    suspend fun exportLibrary(): String {
        val tracks = db.tracks().libraryPool()
        val playlists = db.playlists().summaries().map { s ->
            val p = s.playlist
            BackupPlaylist(p.name, p.source, p.sourceUrl, p.artworkUrl, p.autoSync, db.playlists().trackIds(p.id))
        }
        val listening = db.stats().all().map { BackupListening(it.trackId, it.day, it.ms) }
        // Přibalit i skladby, které jsou jen ve statistikách
        val known = tracks.mapTo(HashSet()) { it.id }
        val extra = db.tracks().getMany(listening.map { it.trackId }.distinct().filter { it !in known })
        return format.encodeToString(Backup.serializer(), Backup(tracks = (tracks + extra).map { it.toBackup() }, playlists = playlists, listening = listening))
    }

    /** Jeden playlist pro sdílení (bez statistik a lajků). */
    suspend fun exportPlaylist(playlistId: Long): Pair<String, String> {
        val p = db.playlists().get(playlistId) ?: error("Playlist neexistuje")
        val tracks = db.playlists().tracksOf(playlistId)
        val backup = Backup(
            tracks = tracks.map { it.toBackup().copy(liked = false, playCount = 0, lastPlayedAt = null) },
            playlists = listOf(BackupPlaylist(p.name, p.source, p.sourceUrl, p.artworkUrl, false, tracks.map { it.id })),
        )
        return p.name to format.encodeToString(Backup.serializer(), backup)
    }

    suspend fun restore(json: String): RestoreResult {
        val backup = runCatching { format.decodeFromString(Backup.serializer(), json) }
            .getOrElse { throw IllegalArgumentException("Soubor není záloha Vibe", it) }
        require(backup.format == Backup.FORMAT) { "Soubor není záloha Vibe" }

        return db.withTransaction {
            val trackDao = db.tracks()
            trackDao.insertIgnore(backup.tracks.map { it.toEntity() })
            var likedAdded = 0
            backup.tracks.filter { it.liked }.forEach { t ->
                if (trackDao.get(t.id)?.liked == false) {
                    trackDao.setLiked(t.id, true)
                    likedAdded++
                }
            }
            backup.tracks.forEach { t ->
                if (t.playCount > 0) trackDao.mergePlayStats(t.id, t.playCount, t.lastPlayedAt)
            }

            val existing = db.playlists().summaries().map { it.playlist }
            val names = backup.playlists.map { bp ->
                val match = existing.firstOrNull { it.name == bp.name && it.sourceUrl == bp.sourceUrl }
                val id = match?.id ?: db.playlists().insert(
                    PlaylistEntity(
                        name = bp.name,
                        source = bp.source,
                        sourceUrl = bp.sourceUrl,
                        artworkUrl = bp.artworkUrl,
                        autoSync = bp.autoSync,
                    )
                )
                db.playlists().appendTracks(id, bp.trackIds)
                bp.name
            }

            backup.listening.forEach { db.stats().insert(ListeningEntity(it.trackId, it.day, it.ms)) }
            RestoreResult(backup.tracks.size, names, likedAdded)
        }
    }

    private fun TrackEntity.toBackup() = BackupTrack(
        id, title, artist, album, durationSec, artworkUrl, artistId, albumId, liked, playCount, lastPlayedAt, addedAt,
    )

    private fun BackupTrack.toEntity() = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec,
        artworkUrl = artworkUrl,
        artistId = artistId,
        albumId = albumId,
        addedAt = if (addedAt > 0) addedAt else System.currentTimeMillis(),
    )
}
