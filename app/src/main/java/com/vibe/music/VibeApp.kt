package com.vibe.music

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.vibe.music.data.AgeGateFallback
import com.vibe.music.data.BackupRepository
import com.vibe.music.data.FollowRepository
import com.vibe.music.data.ImportRepository
import com.vibe.music.data.MusicRepository
import com.vibe.music.data.RadioRepository
import com.vibe.music.data.SettingsRepository
import com.vibe.music.data.db.DownloadState
import com.vibe.music.data.db.VibeDatabase
import com.vibe.music.download.DownloadManager
import androidx.media3.cast.Cast
import com.vibe.music.playback.CastServer
import com.vibe.music.playback.EqualizerController
import com.vibe.music.playback.MediaResolver
import com.vibe.music.playback.PlayerConnection
import com.vibe.music.playback.SleepTimer
import com.vibe.music.remote.LyricsApi
import com.vibe.music.remote.SpotifyClient
import com.vibe.music.remote.YouTubeMusicApi
import com.vibe.music.remote.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.io.File
import java.util.concurrent.TimeUnit

/** Jednoduchý ruční DI kontejner – pro jednu aplikaci bohatě stačí. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = VibeDatabase.create(context)
    val settings = SettingsRepository(context)
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Klient pro stahování streamů. YouTube váže URL streamu na IP adresu, ze které ji yt-dlp získal
     * (yt-dlp běží s --force-ipv4). Na mobilních datech s IPv6 by přehrávač jinak šel přes IPv6
     * a dostal 403 – proto i tady přednostně IPv4.
     */
    val streamHttp: OkHttpClient = http.newBuilder()
        .dns { host -> Dns.SYSTEM.lookup(host).sortedBy { if (it is Inet4Address) 0 else 1 } }
        .build()

    val ytDlp = YtDlp(context)
    val ytMusic = YouTubeMusicApi(http, context.getSharedPreferences("ytmusic", Context.MODE_PRIVATE))
    val spotify = SpotifyClient(http, settings)
    val lyrics = LyricsApi(http)
    val music = MusicRepository(db)
    val ageGate = AgeGateFallback(db, ytDlp, ytMusic, settings)
    val radio = RadioRepository(ytMusic, ytDlp, db)
    val backup = BackupRepository(db)
    val follows = FollowRepository(context, db, ytMusic)
    val downloads = DownloadManager(context, db, ytDlp, settings, ageGate)
    val imports = ImportRepository(db, http, music, ytDlp, ytMusic, spotify, downloads, settings)
    val mediaResolver = MediaResolver(db.tracks(), ytDlp, settings, ageGate)

    /** Cache streamů – opakovaně přehrávané skladby se nestahují znovu (LRU, 512 MB). */
    val streamCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "stream-cache"),
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }
    val equalizer = EqualizerController()

    /** Chromecast (vyžaduje Google Play služby – bez nich se jen skryje tlačítko). */
    @Volatile var castAvailable = false
    val castServer by lazy { CastServer(context, this) }

    /** Poslední chyba přehrávání (diagnostika v Nastavení); zmizí, když se skladba nakonec rozehraje. */
    @Volatile var lastPlaybackError: String? = null
    @Volatile var lastPlaybackErrorId: String? = null
    val sleepTimer = SleepTimer(appScope)

    /** Vytváří se líně na hlavním vlákně (z UI). */
    val player by lazy { PlayerConnection(context) }
}

const val STREAM_CACHE_BYTES = 512L * 1024 * 1024

class VibeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.castAvailable = runCatching { Cast.getSingletonInstance(this).initialize() }
            .onFailure { Log.w("VibeApp", "Chromecast není k dispozici", it) }
            .isSuccess
        MaintenanceWorker.schedule(this)

        // Oblíbené → offline (je-li zapnuto); zachytí like z aplikace, notifikace i Android Auto
        container.appScope.launch {
            combine(container.settings.settings, container.music.tracks.observeLiked()) { s, liked ->
                if (s.autoDownloadLiked) liked.filter { it.downloadState == DownloadState.NONE } else emptyList()
            }.collect { missing -> if (missing.isNotEmpty()) container.downloads.enqueue(missing) }
        }

        container.appScope.launch {
            // Aktuální verze klienta YouTube Music (jednou denně)
            launch { runCatching { container.ytMusic.refreshClientVersion() } }

            runCatching { container.ytDlp.ensureReady() }
                .onFailure { Log.e("VibeApp", "Inicializace yt-dlp selhala", it) }

            // Stahování přerušená zabitím aplikace vrátit do fronty
            container.db.tracks().requeueInterrupted()
            if (container.db.tracks().pendingDownloadCount() > 0) container.downloads.kick()

            // Aktualizace yt-dlp při startu, pokud poslední kontrola je starší než 12 h
            val s = container.settings.current()
            if (s.ytDlpAutoUpdate && System.currentTimeMillis() - s.ytDlpLastCheck > TimeUnit.HOURS.toMillis(12)) {
                runCatching {
                    container.ytDlp.update(s.ytDlpChannel)
                    container.settings.setYtDlpLastCheck(System.currentTimeMillis())
                }.onFailure { Log.w("VibeApp", "Aktualizace yt-dlp selhala", it) }
            }
        }
    }
}
