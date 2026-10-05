package com.vibe.music.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: List<LyricLine>?, val plain: String?)

/** Texty písní z lrclib.net (zdarma, bez klíče, často synchronizované po řádcích). */
class LyricsApi(private val http: OkHttpClient) {
    private val cache = HashMap<String, Lyrics?>()

    suspend fun lyrics(title: String, artist: String, durationSec: Int): Lyrics? = withContext(Dispatchers.IO) {
        val key = "$artist|$title"
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }
        val mainArtist = artist.split(",", "&", " feat", " ft.").first().trim()
        val result = runCatching {
            exact(title, mainArtist, durationSec) ?: search("$mainArtist $title")
        }.getOrNull()?.toLyrics()
        synchronized(cache) { cache[key] = result }
        result
    }

    private fun exact(title: String, artist: String, durationSec: Int): JsonElement? {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        return request(url.toString())
    }

    private fun search(query: String): JsonElement? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val results = request(url.toString()).arr ?: return null
        return results.firstOrNull { it.path("syncedLyrics").str != null } ?: results.firstOrNull()
    }

    private fun request(url: String): JsonElement? {
        val req = Request.Builder().url(url).header("User-Agent", "Vibe Music Player (personal)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return json.parseToJsonElement(r.body.string())
        }
    }

    private fun JsonElement.toLyrics(): Lyrics? {
        val synced = path("syncedLyrics").str?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        val plain = path("plainLyrics").str?.takeIf { it.isNotBlank() }
        return if (synced == null && plain == null) null else Lyrics(synced, plain)
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val regex = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?](.*)""")
        return lrc.lines().mapNotNull { line ->
            val m = regex.find(line) ?: return@mapNotNull null
            val (min, sec, frac, text) = m.destructured
            val ms = when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.take(3).toLong()
            }
            LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + ms, text.trim())
        }.sortedBy { it.timeMs }
    }
}
