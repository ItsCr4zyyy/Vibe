package com.vibe.music.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object DownloadState {
    const val NONE = 0
    const val QUEUED = 1
    const val DOWNLOADING = 2
    const val DONE = 3
    const val FAILED = 4
}

object PlaylistSource {
    const val LOCAL = "local"
    const val YOUTUBE = "youtube"
    const val SPOTIFY = "spotify"
    const val SOUNDCLOUD = "soundcloud"
}

/** Skladba; [id] je YouTube video ID, které slouží pro stream i stažení. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int = 0,
    val artworkUrl: String? = null,
    val localPath: String? = null,
    val downloadState: Int = DownloadState.NONE,
    val liked: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
    /** YouTube Music browseId interpreta / alba (null u skladeb importovaných ze Spotify). */
    val artistId: String? = null,
    val albumId: String? = null,
) {
    val isDownloaded: Boolean get() = downloadState == DownloadState.DONE && localPath != null
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String = PlaylistSource.LOCAL,
    val sourceUrl: String? = null,
    val artworkUrl: String? = null,
    val autoSync: Boolean = false,
    val autoDownload: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("trackId")],
)
data class PlaylistTrackRef(
    val playlistId: Long,
    val trackId: String,
    val position: Int,
)

/** Cache párování skladeb z jiných služeb (např. „spotify:track:…“) na YouTube video ID. */
@Entity(tableName = "match_cache")
data class MatchEntity(
    @PrimaryKey val sourceKey: String,
    val trackId: String,
)

/** Odposlouchaný čas skladby za den (pro statistiky). [day] = „yyyy-MM-dd“. */
@Entity(tableName = "listening", primaryKeys = ["trackId", "day"])
data class ListeningEntity(
    val trackId: String,
    val day: String,
    val ms: Long,
)

@Entity(tableName = "followed_artists")
data class FollowedArtist(
    @PrimaryKey val id: String,
    val name: String,
    val artworkUrl: String?,
    val followedAt: Long = System.currentTimeMillis(),
)

/** Album/singl sledovaného interpreta. [isNew] = objevilo se po začátku sledování a ještě není otevřené. */
@Entity(tableName = "artist_releases")
data class ArtistRelease(
    @PrimaryKey val albumId: String,
    val artistId: String,
    val artistName: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val year: Int,
    val discoveredAt: Long,
    val isNew: Boolean,
)

data class TrackListening(
    @Embedded val track: TrackEntity,
    val listenedMs: Long,
)

data class DayListening(val day: String, val ms: Long)

data class PlaylistSummary(
    @Embedded val playlist: PlaylistEntity,
    val trackCount: Int,
    val downloadedCount: Int,
)
