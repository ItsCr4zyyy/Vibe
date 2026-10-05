package com.vibe.music.remote

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class ArtistRef(val id: String, val name: String, val artworkUrl: String?, val subtitle: String? = null)

data class AlbumRef(val id: String, val title: String, val subtitle: String?, val artworkUrl: String?)

data class ArtistPage(
    val id: String,
    val name: String,
    val artworkUrl: String?,
    val listeners: String?,
    val description: String?,
    val topSongs: List<RemoteTrack>,
    val albums: List<AlbumRef>,
    val singles: List<AlbumRef>,
    val similar: List<ArtistRef>,
)

data class AlbumPage(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val year: String?,
    val artworkUrl: String?,
    /** „OLAK5uy_…“ – playlist alba, použitelný pro import/synchronizaci přes yt-dlp. */
    val playlistId: String?,
    val tracks: List<RemoteTrack>,
)

/**
 * Interní API YouTube Music (InnerTube), stejně jako InnerTune/ViMusic.
 * Hledání, stránky interpretů/alb a rádio vrací za pár set ms kompletní metadata,
 * což yt-dlp v telefonu nezvládne. Stream a stažení dál řeší yt-dlp.
 */
class YouTubeMusicApi(private val http: OkHttpClient, private val prefs: SharedPreferences) {

    /** Aktuálně používaná verze webového klienta (sama se obnovuje z music.youtube.com). */
    val clientVersion: String get() = prefs.getString(KEY_VERSION, null) ?: FALLBACK_CLIENT_VERSION

    /** Poslední chyba API (pro diagnostiku v Nastavení), null = vše v pořádku. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Zjistí aktuální verzi klienta z webu YouTube Music. Díky tomu API funguje dál,
     * i když YouTube přestane přijímat starší verze – bez aktualizace aplikace.
     */
    suspend fun refreshClientVersion(force: Boolean = false): String = withContext(Dispatchers.IO) {
        val checked = prefs.getLong(KEY_CHECKED, 0)
        if (!force && System.currentTimeMillis() - checked < 24 * 3600_000L) return@withContext clientVersion
        val request = Request.Builder()
            .url("https://music.youtube.com/")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "cs,en;q=0.8")
            // Souhlas s cookies – jinak EU verze přesměruje na consent.youtube.com
            .header("Cookie", "SOCS=CAI; CONSENT=YES+")
            .build()
        val version = runCatching {
            http.newCall(request).execute().use { r ->
                Regex("\"INNERTUBE_CLIENT_VERSION\":\"([0-9.]+)\"").find(r.body.string())?.groupValues?.get(1)
            }
        }.getOrNull()
        prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        if (version != null) prefs.edit().putString(KEY_VERSION, version).apply()
        clientVersion
    }

    /** Návrhy při psaní do hledání. */
    suspend fun suggestions(input: String): List<String> {
        val root = post("music/get_search_suggestions") { put("input", input) }
        return root.findAll("searchSuggestionRenderer").mapNotNull { s ->
            text(s.path("suggestion")).takeIf { it.isNotBlank() }
        }.distinct()
    }

    suspend fun searchSongs(query: String): List<RemoteTrack> =
        search(query, SONGS_FILTER).mapNotNull { parseSong(it) }

    /** Videa (nahrávky uživatelů – remixy, edity, věci, které nejsou jako oficiální skladba). */
    suspend fun searchVideos(query: String): List<RemoteTrack> =
        search(query, VIDEOS_FILTER).mapNotNull { parseSong(it) }

    /** Metadata jedné skladby/videa – funguje i u videí omezených na 18+. */
    suspend fun trackInfo(videoId: String): RemoteTrack? = nextTracks(videoId, radio = false).firstOrNull { it.id == videoId }

    suspend fun searchArtists(query: String): List<ArtistRef> =
        search(query, ARTISTS_FILTER).mapNotNull(::parseArtistItem)

    suspend fun searchAlbums(query: String): List<AlbumRef> =
        search(query, ALBUMS_FILTER).mapNotNull(::parseAlbumItem)

    suspend fun artist(browseId: String): ArtistPage {
        val root = post("browse") { put("browseId", browseId) }
        val header = root.path("header", "musicImmersiveHeaderRenderer")
            ?: root.path("header", "musicVisualHeaderRenderer")
        val name = text(header.path("title")).ifBlank { "Interpret" }

        val topSongs = mutableListOf<RemoteTrack>()
        val albums = mutableListOf<AlbumRef>()
        val singles = mutableListOf<AlbumRef>()
        val similar = mutableListOf<ArtistRef>()

        val sections = root.path("contents", "singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents").arr.orEmpty()
        for (section in sections) {
            section.path("musicShelfRenderer")?.let { shelf ->
                if (topSongs.isEmpty()) {
                    shelf.findAll("musicResponsiveListItemRenderer").mapNotNullTo(topSongs) {
                        parseSong(it, fallbackArtist = name, fallbackArtistId = browseId)
                    }
                }
            }
            section.path("musicCarouselShelfRenderer")?.let { carousel ->
                val title = text(carousel.path("header", "musicCarouselShelfBasicHeaderRenderer", "title"))
                val items = carousel.path("contents").arr.orEmpty().mapNotNull { it.path("musicTwoRowItemRenderer").obj }
                for (item in items) {
                    val nav = item.path("navigationEndpoint", "browseEndpoint")
                    val id = nav.path("browseId").str ?: continue
                    val art = upscaleArtwork(thumbnail(item.path("thumbnailRenderer")))
                    val itemTitle = text(item.path("title"))
                    when (pageTypeOf(nav)) {
                        "MUSIC_PAGE_TYPE_ALBUM" -> {
                            val ref = AlbumRef(id, itemTitle, text(item.path("subtitle")), art)
                            if (title.contains("Singl", true) || title.contains("EP")) singles += ref else albums += ref
                        }
                        "MUSIC_PAGE_TYPE_ARTIST" -> similar += ArtistRef(id, itemTitle, art, text(item.path("subtitle")))
                    }
                }
            }
        }
        return ArtistPage(
            id = browseId,
            name = name,
            artworkUrl = thumbnail(header.path("thumbnail")),
            listeners = text(header.path("monthlyListenerCount")).ifBlank { null },
            description = text(header.path("description")).ifBlank { null },
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            similar = similar,
        )
    }

    suspend fun album(browseId: String): AlbumPage {
        val root = post("browse") { put("browseId", browseId) }
        val header = root.findAll("musicResponsiveHeaderRenderer").firstOrNull()
            ?: throw IOException("Album se nepodařilo načíst")
        val title = text(header.path("title"))
        val strapline = header.path("straplineTextOne", "runs").arr.orEmpty()
        val artist = text(header.path("straplineTextOne")).ifBlank { "Neznámý interpret" }
        val artistId = strapline.firstNotNullOfOrNull { it.path("navigationEndpoint", "browseEndpoint", "browseId").str }
        val year = header.path("subtitle", "runs").arr?.mapNotNull { it.path("text").str }?.lastOrNull { it.matches(Regex("\\d{4}")) }
        val artwork = upscaleArtwork(thumbnail(header))

        val list = root.path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents") ?: root
        val tracks = list.findAll("musicResponsiveListItemRenderer").mapNotNull {
            parseSong(
                it,
                fallbackArtist = artist,
                fallbackArtistId = artistId,
                fallbackAlbum = title,
                fallbackAlbumId = browseId,
                fallbackArtwork = artwork,
            )
        }
        val playlistId = list.findAll("watchEndpoint").firstNotNullOfOrNull { e ->
            e["playlistId"].str?.takeIf { it.startsWith("OLAK") }
        }
        return AlbumPage(browseId, title, artist, artistId, year, artwork, playlistId, tracks)
    }

    /** „Rádio“ YouTube Music – podobné skladby k [videoId] (první položka je samotná skladba). */
    suspend fun radio(videoId: String): List<RemoteTrack> = nextTracks(videoId, radio = true)

    private suspend fun nextTracks(videoId: String, radio: Boolean): List<RemoteTrack> {
        val root = post("next") {
            put("videoId", videoId)
            if (radio) put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
        }
        return root.findAll("playlistPanelVideoRenderer").mapNotNull { p ->
            val id = p["videoId"].str ?: return@mapNotNull null
            val segments = segments(p.path("longBylineText", "runs").arr.orEmpty())
            val artistRuns = segments.firstOrNull().orEmpty()
            val albumRun = p.path("longBylineText", "runs").arr.orEmpty().firstOrNull { pageTypeOf(it.path("navigationEndpoint", "browseEndpoint")) == "MUSIC_PAGE_TYPE_ALBUM" }
            RemoteTrack(
                id = id,
                title = text(p.path("title")).ifBlank { return@mapNotNull null },
                artist = joinRuns(artistRuns).ifBlank { "Neznámý interpret" },
                album = albumRun?.path("text").str,
                durationSec = parseDuration(text(p.path("lengthText"))) ?: 0,
                artworkUrl = upscaleArtwork(thumbnail(p)) ?: youtubeThumbnail(id),
                artistId = artistRuns.firstNotNullOfOrNull { it.path("navigationEndpoint", "browseEndpoint", "browseId").str },
                albumId = albumRun?.path("navigationEndpoint", "browseEndpoint", "browseId").str,
            )
        }
    }

    // ---------- Parsování ----------

    private fun parseSong(
        item: JsonObject,
        fallbackArtist: String? = null,
        fallbackArtistId: String? = null,
        fallbackAlbum: String? = null,
        fallbackAlbumId: String? = null,
        fallbackArtwork: String? = null,
    ): RemoteTrack? {
        val columns = item["flexColumns"].arr ?: return null
        fun runs(col: Int) = columns.path(col, "musicResponsiveListItemFlexColumnRenderer", "text", "runs").arr.orEmpty()

        val titleRuns = runs(0)
        val videoId = item.path("playlistItemData", "videoId").str
            ?: titleRuns.firstNotNullOfOrNull { it.path("navigationEndpoint", "watchEndpoint", "videoId").str }
            ?: return null
        val title = joinRuns(titleRuns).ifBlank { return null }

        val subtitleRuns = runs(1)
        val segments = segments(subtitleRuns).filter { joinRuns(it).isNotEmpty() }.toMutableList()
        // Bez filtru začíná řádek typem („Skladba“, „Video“) – ten zahodíme
        if (segments.size > 1 && segments.first().none { linkOf(it) != null } &&
            segments[1].any { pageTypeOf(linkOf(it)) == "MUSIC_PAGE_TYPE_ARTIST" }
        ) segments.removeAt(0)

        val artistSegment = segments.firstOrNull()?.takeIf { seg ->
            // Alba mají ve sloupci počet přehrání – interpret je jen segment s odkazem nebo první text
            seg.any { pageTypeOf(linkOf(it)) == "MUSIC_PAGE_TYPE_ARTIST" } || fallbackArtist == null
        }
        val albumRun = subtitleRuns.firstOrNull { pageTypeOf(linkOf(it)) == "MUSIC_PAGE_TYPE_ALBUM" }
            ?: runs(3).firstOrNull { pageTypeOf(linkOf(it)) == "MUSIC_PAGE_TYPE_ALBUM" }
        val fixedDuration = item["fixedColumns"].arr?.firstNotNullOfOrNull {
            parseDuration(text(it.path("musicResponsiveListItemFixedColumnRenderer", "text")))
        }
        val duration = fixedDuration ?: segments.lastOrNull()?.let { parseDuration(joinRuns(it)) } ?: 0
        val thumb = upscaleArtwork(thumbnail(item.path("thumbnail")))

        return RemoteTrack(
            id = videoId,
            title = title,
            artist = artistSegment?.let(::joinRuns) ?: fallbackArtist ?: "Neznámý interpret",
            album = albumRun?.path("text").str ?: fallbackAlbum,
            durationSec = duration,
            artworkUrl = thumb ?: fallbackArtwork ?: youtubeThumbnail(videoId),
            artistId = artistSegment?.firstNotNullOfOrNull { r ->
                linkOf(r)?.takeIf { pageTypeOf(it) == "MUSIC_PAGE_TYPE_ARTIST" }?.path("browseId").str
            } ?: fallbackArtistId,
            albumId = albumRun?.let { linkOf(it)?.path("browseId").str } ?: fallbackAlbumId,
        )
    }

    private fun parseArtistItem(item: JsonObject): ArtistRef? {
        val nav = item.path("navigationEndpoint", "browseEndpoint") ?: return null
        if (pageTypeOf(nav) != "MUSIC_PAGE_TYPE_ARTIST") return null
        val columns = item["flexColumns"].arr ?: return null
        val name = text(columns.path(0, "musicResponsiveListItemFlexColumnRenderer", "text"))
        val subtitle = segments(columns.path(1, "musicResponsiveListItemFlexColumnRenderer", "text", "runs").arr.orEmpty())
            .drop(1).joinToString(" • ") { joinRuns(it) }
        return ArtistRef(
            id = nav.path("browseId").str ?: return null,
            name = name,
            artworkUrl = upscaleArtwork(thumbnail(item.path("thumbnail"))),
            subtitle = subtitle.ifBlank { null },
        )
    }

    private fun parseAlbumItem(item: JsonObject): AlbumRef? {
        val nav = item.path("navigationEndpoint", "browseEndpoint") ?: return null
        if (pageTypeOf(nav) != "MUSIC_PAGE_TYPE_ALBUM") return null
        val columns = item["flexColumns"].arr ?: return null
        val subtitle = segments(columns.path(1, "musicResponsiveListItemFlexColumnRenderer", "text", "runs").arr.orEmpty())
            .map { joinRuns(it) }.filter { it.isNotBlank() }
        return AlbumRef(
            id = nav.path("browseId").str ?: return null,
            title = text(columns.path(0, "musicResponsiveListItemFlexColumnRenderer", "text")),
            subtitle = subtitle.joinToString(" • ").ifBlank { null },
            artworkUrl = upscaleArtwork(thumbnail(item.path("thumbnail"))),
        )
    }

    private fun text(e: JsonElement?): String = joinRuns(e.path("runs").arr.orEmpty()).trim()

    private fun joinRuns(runs: List<JsonElement>) = runs.joinToString("") { it.path("text").str.orEmpty() }.trim()

    /** Rozdělí „A, B • Album • 3:45“ na segmenty podle oddělovače „•“. */
    private fun segments(runs: List<JsonElement>): List<List<JsonElement>> {
        val out = mutableListOf(mutableListOf<JsonElement>())
        for (run in runs) {
            if (run.path("text").str?.trim() == "•") out += mutableListOf<JsonElement>() else out.last() += run
        }
        return out
    }

    private fun linkOf(run: JsonElement): JsonElement? = run.path("navigationEndpoint", "browseEndpoint")

    private fun pageTypeOf(browseEndpoint: JsonElement?): String? =
        browseEndpoint.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str

    /** URL největšího obrázku z prvního pole „thumbnails“ uvnitř [e]. */
    private fun thumbnail(e: JsonElement?): String? =
        e?.let(::firstThumbnails)?.lastOrNull()?.path("url").str

    private fun firstThumbnails(e: JsonElement): JsonArray? = when (e) {
        is JsonObject -> (e["thumbnails"] as? JsonArray) ?: e.values.firstNotNullOfOrNull { firstThumbnails(it) }
        is JsonArray -> e.firstNotNullOfOrNull { firstThumbnails(it) }
        else -> null
    }

    // ---------- HTTP ----------

    private suspend fun search(query: String, params: String): List<JsonObject> {
        val root = post("search") {
            put("query", query)
            put("params", params)
        }
        return root.findAll("musicResponsiveListItemRenderer")
    }

    private suspend fun post(endpoint: String, extra: JsonObjectBuilder.() -> Unit): JsonElement {
        val result = try {
            postOnce(endpoint, clientVersion, extra)
        } catch (e: ClientRejected) {
            // YouTube odmítl verzi klienta → zjistit aktuální a zkusit znovu
            val fresh = refreshClientVersion(force = true)
            postOnce(endpoint, fresh, extra)
        }
        lastError = null
        return result
    }

    private class ClientRejected(code: Int) : IOException("YouTube Music: HTTP $code")

    private suspend fun postOnce(endpoint: String, version: String, extra: JsonObjectBuilder.() -> Unit): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", version)
                    put("hl", "cs")
                    put("gl", "CZ")
                }
            }
            extra()
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 400 || response.code == 404 -> throw ClientRejected(response.code)
                    !response.isSuccessful -> throw IOException("YouTube Music: HTTP ${response.code}")
                }
                json.parseToJsonElement(response.body.string())
            }
        } catch (e: IOException) {
            lastError = e.message
            throw e
        }
    }

    companion object {
        /** Záložní verze, než se poprvé načte aktuální z webu. */
        private const val FALLBACK_CLIENT_VERSION = "1.20260928.13.00"
        private const val KEY_VERSION = "ytm_client_version"
        private const val KEY_CHECKED = "ytm_client_checked"
        private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val ARTISTS_FILTER = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        private const val VIDEOS_FILTER = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        private const val ALBUMS_FILTER = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
    }
}
