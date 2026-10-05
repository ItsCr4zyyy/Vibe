package com.vibe.music.remote

import android.content.Context
import android.util.Log
import com.vibe.music.data.YtDlpChannel
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

data class StreamInfo(
    val url: String,
    val headers: Map<String, String>,
    val expiresAt: Long,
)

/**
 * Obal kolem yt-dlp (youtubedl-android). yt-dlp běží jako Python proces v telefonu
 * a umí se aktualizovat sám z GitHubu – bez nové verze aplikace.
 */
class YtDlp(private val context: Context) {
    private val initMutex = Mutex()
    @Volatile private var ready = false

    /** Poslední chyba yt-dlp (diagnostika v Nastavení), null = poslední operace proběhla v pořádku. */
    @Volatile var lastError: String? = null
        private set

    /** Výsledek poslední aktualizace (čas + chyba), pro diagnostiku. */
    @Volatile var lastUpdateError: String? = null
        private set

    private val _version = MutableStateFlow<String?>(null)
    val version: StateFlow<String?> = _version

    suspend fun ensureReady() {
        if (ready) return
        initMutex.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                YoutubeDL.init(context)
                FFmpeg.init(context)
            }
            ready = true
            _version.value = YoutubeDL.version(context)
        }
    }

    /** Vrací true, pokud byla stažena nová verze. */
    suspend fun update(channel: YtDlpChannel): Boolean {
        ensureReady()
        val status = try {
            withContext(Dispatchers.IO) {
                YoutubeDL.updateYoutubeDL(
                context,
                when (channel) {
                    YtDlpChannel.STABLE -> YoutubeDL.UpdateChannel.STABLE
                        YtDlpChannel.NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
                    },
                )
            }
        } catch (e: Exception) {
            lastUpdateError = e.message ?: e.javaClass.simpleName
            throw e
        }
        lastUpdateError = null
        _version.value = YoutubeDL.version(context)
        return status == YoutubeDL.UpdateStatus.DONE
    }

    /** Přímá URL audio streamu (platí několik hodin). [id] = YouTube ID nebo „sc:…“. */
    suspend fun stream(id: String, format: String): StreamInfo {
        val info = dumpJson(Sources.url(id), "-f", Sources.format(id, format), "--no-playlist")
        val url = info["url"].str
            ?: info["requested_formats"].arr?.firstOrNull()?.path("url").str
            ?: throw IOException("yt-dlp nevrátil URL streamu")
        val headers = info["http_headers"].obj?.mapValues { it.value.str.orEmpty() }.orEmpty()
        val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val expiresAt = expire?.let { it * 1000 - 10 * 60_000 } ?: (System.currentTimeMillis() + 3 * 3600_000)
        return StreamInfo(url, headers, expiresAt)
    }

    /** Playlist (nebo jedno video) z YouTube / YouTube Music / SoundCloudu. */
    suspend fun playlist(url: String): RemotePlaylist {
        // SoundCloud v „flat“ režimu nevrací interpreta, délku ani obal → plné načtení (je rychlé)
        val info = if ("soundcloud.com" in url) dumpJson(url, "--ignore-errors")
        else dumpJson(normalizeYouTubeUrl(url), "--flat-playlist")
        val entries = info["entries"].arr
        if (entries == null) {
            val single = entryToTrack(info) ?: throw IOException("Odkaz neobsahuje žádné skladby")
            return RemotePlaylist(single.title, single.artworkUrl, listOf(single))
        }
        val tracks = entries.mapNotNull { it.obj?.let(::entryToTrack) }
        return RemotePlaylist(
            title = info["title"].str?.removePrefix("Album - ") ?: "Playlist",
            artworkUrl = Sources.soundCloudArtwork(info["thumbnails"].arr?.lastOrNull()?.path("url").str)
                ?: tracks.firstOrNull()?.artworkUrl,
            tracks = tracks,
        )
    }

    /** Záložní rádio přes yt-dlp – YouTube „Mix“ k dané skladbě. */
    suspend fun radio(videoId: String, limit: Int = 50): List<RemoteTrack> {
        val info = dumpJson(
            "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId",
            "--flat-playlist", "--playlist-end", limit.toString(),
        )
        return info["entries"].arr.orEmpty().mapNotNull { it.obj?.let(::entryToTrack) }
    }

    /** Hledání na SoundCloudu (remixy, edity, věci, které na YouTube nejsou nebo jsou 18+). */
    suspend fun searchSoundCloud(query: String, limit: Int = 10): List<RemoteTrack> {
        val info = dumpJson("scsearch$limit:$query", "--flat-playlist")
        return info["entries"].arr.orEmpty().mapNotNull { it.obj?.let(::entryToTrack) }
    }

    /** Záložní hledání přes yt-dlp (když InnerTube API selže). */
    suspend fun search(query: String, limit: Int = 10): List<RemoteTrack> {
        val info = dumpJson("ytsearch$limit:$query", "--flat-playlist")
        return info["entries"].arr.orEmpty().mapNotNull { it.obj?.let(::entryToTrack) }
    }

    /**
     * Stáhne audio skladby [sourceId] do [dir] jako `<fileBase>.<ext>` a vrátí výsledný soubor.
     * [fileBase] se může lišit od zdroje (náhradní nahrávka 18+ skladby se uloží pod původním ID).
     * Pro zrušení zavolej [cancel] se stejným [processId].
     */
    suspend fun download(
        sourceId: String,
        fileBase: String,
        dir: File,
        format: String,
        processId: String,
        onProgress: (Float) -> Unit,
    ): File {
        ensureReady()
        dir.mkdirs()
        val request = YoutubeDLRequest(Sources.url(sourceId))
            .addOption("-f", Sources.format(sourceId, format))
            .addOption("-o", "${dir.absolutePath}/$fileBase.%(ext)s")
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--newline")
            .addOption("--force-ipv4")
        try {
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.execute(request, processId, false) { progress, _, _ -> onProgress(progress) }
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300) ?: e.javaClass.simpleName
            throw e
        }
        return dir.listFiles()
            ?.filter { it.name.startsWith("$fileBase.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IOException("Stažený soubor nebyl nalezen")
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.destroyProcessById(processId) }
    }

    private suspend fun dumpJson(url: String, vararg options: String): JsonObject {
        ensureReady()
        val request = YoutubeDLRequest(url).addOption("-J").addOption("--force-ipv4")
        var i = 0
        while (i < options.size) {
            val opt = options[i]
            val arg = options.getOrNull(i + 1)
            if (arg != null && !arg.startsWith("-")) {
                request.addOption(opt, arg); i += 2
            } else {
                request.addOption(opt); i += 1
            }
        }
        val response = try {
            runInterruptible(Dispatchers.IO) { YoutubeDL.execute(request, null, false, null) }
        } catch (e: Exception) {
            lastError = e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300) ?: e.javaClass.simpleName
            throw e
        }
        lastError = null
        return try {
            json.parseToJsonElement(response.out).obj ?: throw IOException("Neplatná odpověď yt-dlp")
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp output: ${response.out.take(500)} / ${response.err.take(500)}")
            throw IOException("Neplatná odpověď yt-dlp", e)
        }
    }

    private fun entryToTrack(e: JsonObject): RemoteTrack? {
        if (e["ie_key"].str == "Soundcloud" || e["extractor_key"].str == "Soundcloud" ||
            e["url"].str?.contains("soundcloud.com") == true || e["webpage_url"].str?.contains("soundcloud.com") == true
        ) return soundCloudEntry(e)
        val id = e["id"].str ?: return null
        if (id.length != 11) return null // přeskoč kanály / playlisty ve výsledcích
        val rawTitle = e["title"].str ?: return null
        if (rawTitle == "[Deleted video]" || rawTitle == "[Private video]") return null
        val explicitArtist = e["artists"].arr?.mapNotNull { it.str }?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: e["artist"].str
        val channel = e["channel"].str ?: e["uploader"].str
        val (artist, title) = if (explicitArtist != null) explicitArtist to rawTitle else cleanVideoTitle(rawTitle, channel)
        val thumbs = e["thumbnails"].arr?.mapNotNull { it.path("url").str }.orEmpty()
        val artwork = thumbs.firstOrNull { "googleusercontent" in it }?.let { upscaleArtwork(it) }
            ?: youtubeThumbnail(id)
        return RemoteTrack(
            id = id,
            title = title,
            artist = artist,
            album = e["album"].str,
            durationSec = e["duration"].num?.toInt() ?: 0,
            artworkUrl = artwork,
        )
    }

    private fun soundCloudEntry(e: JsonObject): RemoteTrack? {
        val id = e["id"].str ?: return null
        val rawTitle = e["title"].str ?: return null
        val uploader = e["uploader"].str
        // Na SoundCloudu bývá „Interpret - Název“ v titulku, uploader je často jen profil
        val dash = rawTitle.indexOf(" - ")
        val (artist, title) = if (dash > 0) rawTitle.substring(0, dash).trim() to rawTitle.substring(dash + 3).trim()
        else (uploader ?: "SoundCloud") to rawTitle
        val artwork = e["thumbnail"].str ?: e["thumbnails"].arr?.lastOrNull()?.path("url").str
        return RemoteTrack(
            id = Sources.SC_PREFIX + id,
            title = title,
            artist = artist,
            durationSec = e["duration"].num?.toInt() ?: 0,
            artworkUrl = Sources.soundCloudArtwork(artwork),
        )
    }

    companion object {
        private const val TAG = "YtDlp"

        fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

        /** music.youtube.com playlisty mají na www.youtube.com bohatší metadata (délka, kanál). */
        fun normalizeYouTubeUrl(url: String): String =
            if ("music.youtube.com/playlist" in url) url.replace("music.youtube.com", "www.youtube.com") else url

        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
