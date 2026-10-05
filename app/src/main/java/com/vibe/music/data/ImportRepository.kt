package com.vibe.music.data

import com.vibe.music.data.db.MatchEntity
import com.vibe.music.data.db.PlaylistSource
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.data.db.VibeDatabase
import com.vibe.music.download.DownloadManager
import com.vibe.music.remote.RemotePlaylist
import com.vibe.music.remote.RemoteTrack
import com.vibe.music.remote.SpotifyClient
import com.vibe.music.remote.SpotifyTrack
import com.vibe.music.remote.YouTubeMusicApi
import com.vibe.music.remote.YtDlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

sealed interface ImportLink {
    val url: String

    data class YouTube(override val url: String) : ImportLink
    data class SoundCloud(override val url: String) : ImportLink
    data class Spotify(val type: String, val id: String, override val url: String) : ImportLink

    companion object {
        private val urlRegex = Regex("""https?://\S+""")
        private val spotifyRegex = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)""")

        /** Vytáhne podporovaný odkaz z libovolného textu (např. ze „Sdílet“). */
        fun parse(text: String): ImportLink? {
            val url = urlRegex.find(text.trim())?.value?.trimEnd('.', ',', ')') ?: return null
            spotifyRegex.find(url)?.let { m ->
                return Spotify(m.groupValues[1], m.groupValues[2], url)
            }
            if ("spotify.link" in url || "spotify.app.link" in url) return Spotify("short", "", url)
            if ("youtube.com" in url || "youtu.be" in url) return YouTube(url)
            if ("soundcloud.com" in url) return SoundCloud(url)
            return null
        }
    }
}

data class ImportProgress(val message: String, val done: Int = 0, val total: Int = 0)

data class ImportResult(
    /** null = importována jedna skladba (přidána do Oblíbených) */
    val playlistId: Long?,
    val title: String,
    val added: Int,
    val notFound: List<String>,
    val warning: String? = null,
)

class ImportRepository(
    private val db: VibeDatabase,
    private val http: OkHttpClient,
    private val music: MusicRepository,
    private val ytDlp: YtDlp,
    private val ytMusic: YouTubeMusicApi,
    private val spotify: SpotifyClient,
    private val downloads: DownloadManager,
    private val settings: SettingsRepository,
) {
    suspend fun import(link: ImportLink, onProgress: (ImportProgress) -> Unit): ImportResult {
        val resolved = resolveShortLink(link)
        val fetched = fetch(resolved, onProgress)

        if (fetched.tracks.size == 1 && fetched.singleTrack) {
            val track = fetched.tracks.first()
            music.setLiked(track, true)
            return ImportResult(null, track.title, 1, fetched.notFound)
        }

        onProgress(ImportProgress("Ukládám playlist…"))
        val source = when (resolved) {
            is ImportLink.Spotify -> PlaylistSource.SPOTIFY
            is ImportLink.SoundCloud -> PlaylistSource.SOUNDCLOUD
            else -> PlaylistSource.YOUTUBE
        }
        val playlistId = music.createPlaylist(fetched.title, source, resolved.url, fetched.artworkUrl)
        val added = music.addToPlaylist(playlistId, fetched.tracks)

        if (settings.current().autoDownloadImports) {
            music.playlists.get(playlistId)?.let { music.updatePlaylist(it.copy(autoDownload = true)) }
            downloads.enqueue(fetched.tracks)
        }
        return ImportResult(playlistId, fetched.title, added, fetched.notFound, fetched.warning)
    }

    /** Dotáhne nové skladby ze zdroje playlistu. Vrací počet přidaných. */
    suspend fun sync(playlistId: Long, onProgress: (ImportProgress) -> Unit = {}): Int {
        val playlist = music.playlists.get(playlistId) ?: return 0
        val link = playlist.sourceUrl?.let(ImportLink::parse) ?: return 0
        val fetched = fetch(resolveShortLink(link), onProgress)
        val added = music.addToPlaylist(playlistId, fetched.tracks)
        if (playlist.autoDownload) downloads.enqueue(fetched.tracks)
        return added
    }

    private class Fetched(
        val title: String,
        val artworkUrl: String?,
        val tracks: List<TrackEntity>,
        val notFound: List<String>,
        val singleTrack: Boolean,
        val warning: String? = null,
    )

    private suspend fun fetch(link: ImportLink, onProgress: (ImportProgress) -> Unit): Fetched = when (link) {
        is ImportLink.YouTube -> {
            onProgress(ImportProgress("Načítám z YouTube…"))
            val videoId = singleVideoId(link.url)
            val pl: RemotePlaylist = try {
                ytDlp.playlist(link.url)
            } catch (e: Exception) {
                // Jedno 18+ video yt-dlp bez přihlášení nenačte – metadata vezmeme z YouTube Music;
                // přehraje se pak automaticky náhradní nahrávka (viz AgeGateFallback)
                val info = videoId?.let { runCatching { ytMusic.trackInfo(it) }.getOrNull() } ?: throw e
                RemotePlaylist(info.title, info.artworkUrl, listOf(info))
            }
            val single = videoId != null && pl.tracks.size == 1
            Fetched(pl.title, pl.artworkUrl, pl.tracks.map { it.toEntity() }, emptyList(), single)
        }
        is ImportLink.SoundCloud -> {
            onProgress(ImportProgress("Načítám ze SoundCloudu…"))
            val pl = ytDlp.playlist(link.url)
            if (pl.tracks.isEmpty()) throw IOException("Odkaz neobsahuje žádné skladby (je veřejný?)")
            // Odkaz na jednu skladbu = soundcloud.com/<interpret>/<skladba> (bez /sets/, /tracks, /likes…)
            val path = link.url.substringAfter("soundcloud.com/").substringBefore('?').trim('/')
            val single = pl.tracks.size == 1 && path.count { it == '/' } == 1 && "/sets/" !in link.url
            Fetched(pl.title, pl.artworkUrl, pl.tracks.map { it.toEntity() }, emptyList(), single)
        }
        is ImportLink.Spotify -> {
            onProgress(ImportProgress("Načítám ze Spotify…"))
            val collection = spotify.fetch(link.type, link.id)
            val total = collection.tracks.size
            val done = AtomicInteger(0)
            onProgress(ImportProgress("Hledám skladby na YouTube Music…", 0, total))
            val semaphore = Semaphore(4)
            val matched = coroutineScope {
                collection.tracks.map { t ->
                    async {
                        semaphore.withPermit {
                            val m = runCatching { match(t) }.getOrNull()
                            onProgress(ImportProgress("Hledám skladby na YouTube Music…", done.incrementAndGet(), total))
                            t to m
                        }
                    }
                }.awaitAll()
            }
            Fetched(
                title = collection.name,
                artworkUrl = collection.artworkUrl,
                tracks = matched.mapNotNull { it.second?.toEntity() },
                notFound = matched.filter { it.second == null }.map { "${it.first.artists} – ${it.first.title}" },
                singleTrack = link.type == "track",
                warning = if (collection.possiblyTruncated)
                    "Bez Spotify API klíče lze načíst max. 100 skladeb. Klíč doplníš v Nastavení." else null,
            )
        }
    }

    /** Najde nejlepší shodu skladby ze Spotify na YouTube Music (výsledky se cachují). */
    private suspend fun match(t: SpotifyTrack): RemoteTrack? {
        val cachedId = db.matches().get(t.key)
        val best: RemoteTrack = if (cachedId != null) {
            RemoteTrack(cachedId, t.title, t.artists)
        } else {
            val query = "${t.title} ${t.artists.split(",").take(2).joinToString(" ")}"
            val results = runCatching { ytMusic.searchSongs(query) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
                ?: ytDlp.search(query, 5)
            val candidate = results.take(6).withIndex().minByOrNull { (i, r) -> score(t, r) + i * 2 }?.value
                ?: return null
            if (score(t, candidate) >= 60) return null
            db.matches().put(MatchEntity(t.key, candidate.id))
            candidate
        }
        // Metadata ze Spotify jsou čistší – YouTube dává jen ID a případně obal
        return RemoteTrack(
            id = best.id,
            title = t.title,
            artist = t.artists,
            album = t.album ?: best.album,
            durationSec = if (t.durationSec > 0) t.durationSec else best.durationSec,
            artworkUrl = t.artworkUrl ?: best.artworkUrl,
        )
    }

    private fun score(t: SpotifyTrack, r: RemoteTrack): Int {
        var s = 0
        if (t.durationSec > 0 && r.durationSec > 0) {
            val diff = abs(t.durationSec - r.durationSec)
            s += diff.coerceAtMost(30)
            if (diff > 30) s += 40
        } else s += 8
        val title = normalize(t.title)
        val rTitle = normalize(r.title)
        if (!(rTitle.contains(title) || title.contains(rTitle))) s += 20
        val mainArtist = normalize(t.artists.split(",").first())
        if (!normalize(r.artist).contains(mainArtist) && !rTitle.contains(mainArtist)) s += 15
        return s
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]| - .*"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** spotify.link/xyz → open.spotify.com/playlist/… */
    /** ID videa z odkazu na jedno video (watch?v=…, youtu.be/…, shorts/…), jinak null. */
    private fun singleVideoId(url: String): String? {
        if ("list=" in url && "watch" !in url) return null
        return Regex("""(?:v=|youtu\.be/|shorts/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
    }

    private suspend fun resolveShortLink(link: ImportLink): ImportLink {
        if (link !is ImportLink.Spotify || link.type != "short") return link
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(link.url).header("User-Agent", YouTubeMusicApi.USER_AGENT).build()
            val finalUrl = http.newCall(request).execute().use { it.request.url.toString() }
            (ImportLink.parse(finalUrl) as? ImportLink.Spotify)?.takeIf { it.type != "short" }
                ?: throw IOException("Nepodařilo se rozbalit zkrácený Spotify odkaz")
        }
    }
}
