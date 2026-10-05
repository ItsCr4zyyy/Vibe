package com.vibe.music.remote

import com.vibe.music.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class SpotifyTrack(
    val key: String,
    val title: String,
    val artists: String,
    val album: String?,
    val durationSec: Int,
    val artworkUrl: String?,
)

data class SpotifyCollection(
    val name: String,
    val artworkUrl: String?,
    val tracks: List<SpotifyTrack>,
    /** Embed stránka vrací max. ~100 skladeb; s API klíčem limit neplatí. */
    val possiblyTruncated: Boolean,
)

/**
 * Načtení playlistu / alba ze Spotify.
 * - Bez klíče: veřejná embed stránka (open.spotify.com/embed/…), max. ~100 skladeb.
 * - S Client ID + Secret (zdarma na developer.spotify.com): oficiální Web API bez limitu.
 * Audio ze Spotify stáhnout nejde (DRM) – skladby se dohledají na YouTube Music.
 */
class SpotifyClient(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private var token: String? = null
    private var tokenExpiresAt = 0L

    suspend fun fetch(type: String, id: String): SpotifyCollection = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (s.hasSpotifyCredentials) {
            runCatching { fetchViaApi(type, id, s.spotifyClientId, s.spotifyClientSecret) }
                .getOrElse { fetchViaEmbed(type, id) }
        } else {
            fetchViaEmbed(type, id)
        }
    }

    // ---------- Embed (bez přihlášení) ----------

    private fun fetchViaEmbed(type: String, id: String): SpotifyCollection {
        val html = get("https://open.spotify.com/embed/$type/$id")
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw IOException("Spotify: nepodařilo se načíst stránku playlistu")
        val entity = json.parseToJsonElement(data).path("props", "pageProps", "state", "data", "entity")
            ?: throw IOException("Spotify: playlist nenalezen (je veřejný?)")
        val cover = entity.path("coverArt", "sources").arr?.maxByOrNull { it.path("width").num ?: 0.0 }?.path("url").str
        val tracks = entity.path("trackList").arr.orEmpty().mapNotNull { t ->
            val title = t.path("title").str ?: return@mapNotNull null
            SpotifyTrack(
                key = t.path("uri").str ?: "spotify:${t.path("subtitle").str}:$title",
                title = title,
                artists = t.path("subtitle").str.orEmpty().replace(" ", " "),
                album = if (type == "album") entity.path("name").str else null,
                durationSec = ((t.path("duration").num ?: 0.0) / 1000).toInt(),
                artworkUrl = if (type == "album") cover else null,
            )
        }
        if (tracks.isEmpty()) throw IOException("Spotify: playlist je prázdný nebo soukromý")
        return SpotifyCollection(
            name = entity.path("name").str ?: entity.path("title").str ?: "Spotify playlist",
            artworkUrl = cover,
            tracks = tracks,
            possiblyTruncated = tracks.size >= 100,
        )
    }

    // ---------- Oficiální Web API ----------

    private fun fetchViaApi(type: String, id: String, clientId: String, secret: String): SpotifyCollection {
        val auth = accessToken(clientId, secret)
        val meta = api("https://api.spotify.com/v1/${type}s/$id", auth)
        val name = meta.path("name").str ?: "Spotify"
        val cover = meta.path("images", 0, "url").str
        val tracks = mutableListOf<SpotifyTrack>()

        if (type == "track") {
            parseApiTrack(meta, null)?.let(tracks::add)
        } else {
            // Novější API používá /items + "item", starší /tracks + "track" – zkusíme obojí
            var next: String? = if (type == "playlist") "https://api.spotify.com/v1/playlists/$id/items?limit=100"
            else "https://api.spotify.com/v1/albums/$id/tracks?limit=50"
            var triedLegacy = false
            while (next != null) {
                val page = try {
                    api(next, auth)
                } catch (e: IOException) {
                    if (type == "playlist" && !triedLegacy) {
                        triedLegacy = true
                        next = "https://api.spotify.com/v1/playlists/$id/tracks?limit=100"
                        continue
                    } else throw e
                }
                page.path("items").arr.orEmpty().forEach { item ->
                    val track = if (type == "album") item else item.path("track").obj ?: item.path("item").obj
                    parseApiTrack(track, if (type == "album") meta else null)?.let(tracks::add)
                }
                next = page.path("next").str
            }
        }
        return SpotifyCollection(name, cover, tracks, possiblyTruncated = false)
    }

    private fun parseApiTrack(t: JsonElement?, album: JsonElement?): SpotifyTrack? {
        val title = t.path("name").str ?: return null
        val albumObj = t.path("album") ?: album
        return SpotifyTrack(
            key = t.path("uri").str ?: "spotify:$title",
            title = title,
            artists = t.path("artists").arr.orEmpty().mapNotNull { it.path("name").str }.joinToString(", "),
            album = albumObj.path("name").str,
            durationSec = ((t.path("duration_ms").num ?: 0.0) / 1000).toInt(),
            artworkUrl = albumObj.path("images", 0, "url").str,
        )
    }

    @Synchronized
    private fun accessToken(clientId: String, secret: String): String {
        token?.let { if (System.currentTimeMillis() < tokenExpiresAt) return it }
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", Credentials.basic(clientId, secret))
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Spotify přihlášení selhalo (${r.code}) – zkontroluj Client ID/Secret")
            val body = json.parseToJsonElement(r.body.string())
            val t = body.path("access_token").str ?: throw IOException("Spotify: chybí token")
            token = t
            tokenExpiresAt = System.currentTimeMillis() + ((body.path("expires_in").num ?: 3600.0).toLong() - 60) * 1000
            return t
        }
    }

    private fun api(url: String, token: String): JsonElement {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Spotify API: HTTP ${r.code}")
            return json.parseToJsonElement(r.body.string())
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", YouTubeMusicApi.USER_AGENT).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Spotify: HTTP ${r.code}")
            return r.body.string()
        }
    }
}
