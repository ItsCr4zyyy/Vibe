package com.vibe.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    fun observeMany(ids: List<String>): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE liked = 1 ORDER BY addedAt DESC")
    fun observeLiked(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE downloadState = ${DownloadState.DONE} ORDER BY title COLLATE NOCASE")
    fun observeDownloaded(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeMostPlayed(limit: Int): Flow<List<TrackEntity>>

    /** Vše, co uživatel „zná“ – oblíbené, poslouchané a skladby z playlistů (zdroj pro mixy). */
    @Query(
        """
        SELECT * FROM tracks
        WHERE liked = 1 OR playCount > 0 OR id IN (SELECT trackId FROM playlist_tracks)
        """
    )
    suspend fun libraryPool(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE liked = 1 ORDER BY addedAt DESC")
    suspend fun liked(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<TrackEntity>

    /** Sloučení statistik ze zálohy – ponechá vyšší počet přehrání a novější datum. */
    @Query(
        """
        UPDATE tracks SET
            playCount = MAX(playCount, :count),
            lastPlayedAt = CASE
                WHEN :at IS NULL THEN lastPlayedAt
                WHEN lastPlayedAt IS NULL OR :at > lastPlayedAt THEN :at
                ELSE lastPlayedAt END
        WHERE id = :id
        """
    )
    suspend fun mergePlayStats(id: String, count: Int, at: Long?)

    @Query("SELECT * FROM tracks WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE downloadState = ${DownloadState.QUEUED} ORDER BY addedAt LIMIT :limit")
    suspend fun queued(limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE downloadState IN (${DownloadState.QUEUED}, ${DownloadState.DOWNLOADING})")
    suspend fun pendingDownloadCount(): Int

    @Query("SELECT * FROM tracks WHERE downloadState = ${DownloadState.DONE}")
    suspend fun downloaded(): List<TrackEntity>

    @Query("UPDATE tracks SET liked = :liked WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    suspend fun markPlayed(id: String, at: Long)

    @Query("UPDATE tracks SET downloadState = :state WHERE id = :id")
    suspend fun setDownloadState(id: String, state: Int)

    @Query("UPDATE tracks SET downloadState = ${DownloadState.QUEUED} WHERE id IN (:ids) AND downloadState != ${DownloadState.DONE}")
    suspend fun queue(ids: List<String>)

    @Query("UPDATE tracks SET downloadState = ${DownloadState.QUEUED} WHERE downloadState = ${DownloadState.DOWNLOADING}")
    suspend fun requeueInterrupted()

    @Query("UPDATE tracks SET localPath = :path, downloadState = ${DownloadState.DONE} WHERE id = :id")
    suspend fun setDownloaded(id: String, path: String)

    @Query("UPDATE tracks SET localPath = NULL, downloadState = ${DownloadState.NONE} WHERE id = :id")
    suspend fun clearDownload(id: String)
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE sourceUrl = :url LIMIT 1")
    fun observeBySourceUrl(url: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE autoSync = 1")
    suspend fun autoSyncPlaylists(): List<PlaylistEntity>

    @Query(
        """
        SELECT p.*,
               COUNT(pt.trackId) AS trackCount,
               COALESCE(SUM(CASE WHEN t.downloadState = ${DownloadState.DONE} THEN 1 ELSE 0 END), 0) AS downloadedCount
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        LEFT JOIN tracks t ON t.id = pt.trackId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query(
        """
        SELECT p.*,
               COUNT(pt.trackId) AS trackCount,
               COALESCE(SUM(CASE WHEN t.downloadState = ${DownloadState.DONE} THEN 1 ELSE 0 END), 0) AS downloadedCount
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        LEFT JOIN tracks t ON t.id = pt.trackId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
        """
    )
    suspend fun summaries(): List<PlaylistSummary>

    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """
    )
    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """
    )
    suspend fun tracksOf(playlistId: Long): List<TrackEntity>

    /** Obaly prvních skladeb – pro koláž obalu playlistu. */
    @Query(
        """
        SELECT t.artworkUrl FROM tracks t
        JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId AND t.artworkUrl IS NOT NULL
        ORDER BY pt.position LIMIT 12
        """
    )
    fun observeArtworks(playlistId: Long): Flow<List<String>>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun trackIds(playlistId: Long): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRefs(refs: List<PlaylistTrackRef>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: String)

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun setPosition(playlistId: Long, trackId: String, position: Int)

    /** Uloží nové pořadí skladeb (po přetažení v režimu úprav). */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedIds: List<String>) {
        orderedIds.forEachIndexed { i, id -> setPosition(playlistId, id, i) }
    }

    @Query("UPDATE playlists SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long = System.currentTimeMillis())

    /** Přidá skladby na konec playlistu; duplicity přeskočí. Vrací počet nově přidaných. */
    @Transaction
    suspend fun appendTracks(playlistId: Long, trackIds: List<String>): Int {
        val existing = trackIds(playlistId).toHashSet()
        var position = maxPosition(playlistId)
        val refs = trackIds.distinct()
            .filter { it !in existing }
            .map { PlaylistTrackRef(playlistId, it, ++position) }
        insertRefs(refs)
        touch(playlistId)
        return refs.size
    }
}

@Dao
interface MatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(match: MatchEntity)

    @Query("SELECT trackId FROM match_cache WHERE sourceKey = :key")
    suspend fun get(key: String): String?
}

@Dao
interface StatsDao {
    @Query("UPDATE listening SET ms = ms + :ms WHERE trackId = :trackId AND day = :day")
    suspend fun add(trackId: String, day: String, ms: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ListeningEntity)

    /** Přičte poslech (starší SQLite na Androidu 8–10 neumí UPSERT, proto UPDATE + INSERT). */
    @Transaction
    suspend fun record(trackId: String, day: String, ms: Long) {
        if (add(trackId, day, ms) == 0) insert(ListeningEntity(trackId, day, ms))
    }

    @Query("SELECT * FROM listening")
    suspend fun all(): List<ListeningEntity>

    @Query("SELECT COALESCE(SUM(ms), 0) FROM listening WHERE day >= :from")
    fun totalSince(from: String): Flow<Long>

    @Query(
        """
        SELECT t.*, SUM(l.ms) AS listenedMs FROM listening l
        JOIN tracks t ON t.id = l.trackId
        WHERE l.day >= :from
        GROUP BY l.trackId
        ORDER BY listenedMs DESC
        """
    )
    fun tracksSince(from: String): Flow<List<TrackListening>>

    @Query("SELECT day, SUM(ms) AS ms FROM listening WHERE day >= :from GROUP BY day ORDER BY day")
    fun perDay(from: String): Flow<List<DayListening>>
}

@Dao
interface ArtistDao {
    @Query("SELECT * FROM followed_artists ORDER BY name COLLATE NOCASE")
    fun observeFollowed(): Flow<List<FollowedArtist>>

    @Query("SELECT * FROM followed_artists")
    suspend fun followed(): List<FollowedArtist>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE id = :id)")
    fun observeIsFollowed(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun follow(artist: FollowedArtist)

    @Query("DELETE FROM followed_artists WHERE id = :id")
    suspend fun unfollow(id: String)

    @Query("DELETE FROM artist_releases WHERE artistId = :artistId")
    suspend fun deleteReleases(artistId: String)

    /** Vrací rowId vložených záznamů (-1 = už existoval). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReleases(releases: List<ArtistRelease>): List<Long>

    @Query("SELECT * FROM artist_releases ORDER BY isNew DESC, year DESC, discoveredAt DESC LIMIT :limit")
    fun observeReleases(limit: Int): Flow<List<ArtistRelease>>

    @Query("UPDATE artist_releases SET isNew = 0 WHERE albumId = :albumId")
    suspend fun markSeen(albumId: String)
}
