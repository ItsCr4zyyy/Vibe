package com.vibe.music.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vibe.music.MainActivity
import com.vibe.music.data.db.ArtistRelease
import com.vibe.music.data.db.FollowedArtist
import com.vibe.music.data.db.VibeDatabase
import com.vibe.music.remote.AlbumRef
import com.vibe.music.remote.ArtistPage
import com.vibe.music.remote.YouTubeMusicApi

/**
 * Sledování interpretů: při sledování se uloží jejich současná alba/singly (jako „známá“),
 * pravidelná kontrola pak najde nové vydání, označí ho jako novinku a pošle notifikaci.
 */
class FollowRepository(
    private val context: Context,
    private val db: VibeDatabase,
    private val ytMusic: YouTubeMusicApi,
) {
    private val dao get() = db.artists()

    suspend fun follow(page: ArtistPage) {
        dao.follow(FollowedArtist(page.id, page.name, page.artworkUrl))
        dao.insertReleases(releasesOf(page, isNew = false, discoveredAt = 0))
    }

    suspend fun unfollow(artistId: String) {
        dao.unfollow(artistId)
        dao.deleteReleases(artistId)
    }

    /** Zkontroluje nová vydání všech sledovaných interpretů. Vrací počet novinek. */
    suspend fun checkNewReleases(): Int {
        val found = mutableListOf<ArtistRelease>()
        for (artist in dao.followed()) {
            val page = runCatching { ytMusic.artist(artist.id) }.getOrNull() ?: continue
            val candidates = releasesOf(page, isNew = true, discoveredAt = System.currentTimeMillis())
            val inserted = dao.insertReleases(candidates)
            found += candidates.filterIndexed { i, _ -> inserted.getOrNull(i)?.let { it != -1L } == true }
        }
        if (found.isNotEmpty()) notify(found)
        return found.size
    }

    private fun releasesOf(page: ArtistPage, isNew: Boolean, discoveredAt: Long): List<ArtistRelease> =
        (page.singles.take(6) + page.albums.take(6)).map { it.toRelease(page, isNew, discoveredAt) }

    private fun AlbumRef.toRelease(page: ArtistPage, isNew: Boolean, discoveredAt: Long) = ArtistRelease(
        albumId = id,
        artistId = page.id,
        artistName = page.name,
        title = title,
        subtitle = subtitle,
        artworkUrl = artworkUrl,
        year = subtitle?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() } ?: 0,
        discoveredAt = discoveredAt,
        isNew = isNew,
    )

    private fun notify(releases: List<ArtistRelease>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Novinky od interpretů", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val first = releases.first()
        val text = if (releases.size == 1) "${first.artistName} – ${first.title}"
        else releases.take(3).joinToString(", ") { "${it.artistName} – ${it.title}" } + if (releases.size > 3) " a další" else ""
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.vibe.music.R.drawable.media3_notification_small_icon)
            .setContentTitle(if (releases.size == 1) "Nová hudba" else "Nová hudba (${releases.size})")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "releases"
        private const val NOTIFICATION_ID = 77
    }
}
