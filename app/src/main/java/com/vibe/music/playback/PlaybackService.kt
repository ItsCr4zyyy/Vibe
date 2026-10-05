package com.vibe.music.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.DeviceInfo
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vibe.music.MainActivity
import com.vibe.music.VibeApp
import com.vibe.music.data.PlaybackMode
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.remote.YouTubeMusicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaLibrarySession? = null
    /** Lokální přehrávač (ExoPlayer) – hlasitost, ekvalizér, crossfade. */
    private lateinit var player: ExoPlayer
    /** Přehrávač pro session: CastPlayer (lokálně ↔ Chromecast), nebo přímo [player]. */
    private lateinit var sessionPlayer: Player
    private lateinit var tree: LibraryTree
    private lateinit var cachedFactory: CacheDataSource.Factory
    private val normalizer = VolumeNormalizer()
    private val faderNormalizer = VolumeNormalizer()
    private var preloadJob: Job? = null
    @Volatile private var crossfadeMs = 0L
    @Volatile private var preloadNext = true
    /** Druhý přehrávač, který při crossfadu dohrává konec předchozí skladby. */
    private var fader: ExoPlayer? = null
    private var crossfadeStart = 0L
    private var lastRecoveredId: String? = null
    private var autoplayJob: Job? = null
    @Volatile private var fadeTransitions = false

    private val currentId = MutableStateFlow<String?>(null)
    private var currentLiked = false

    // Statistiky: nasbíraný čas poslechu aktuální skladby, který se průběžně ukládá
    private var statsTrackId: String? = null
    private var statsPendingMs = 0L

    private val container get() = (application as VibeApp).container

    override fun onCreate() {
        super.onCreate()
        tree = LibraryTree(container)

        player = ExoPlayer.Builder(this, renderersFactory(normalizer))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setAudioAttributes(AUDIO_ATTRIBUTES, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Chromecast: CastPlayer sám přepíná mezi telefonem a přijímačem
        sessionPlayer = if (container.castAvailable) {
            runCatching {
                CastPlayer.Builder(this)
                    .setLocalPlayer(player)
                    .setRemotePlayer(RemoteCastPlayer.Builder(this).setMediaItemConverter(CastConverter(container)).build())
                    .build()
            }.onFailure { Log.w(TAG, "Cast přehrávač nelze vytvořit", it) }.getOrNull() ?: player
        } else player
        sessionPlayer.addListener(PlayerListener())
        if (sessionPlayer !== player) {
            // Audio session (ekvalizér) patří lokálnímu přehrávači
            player.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    scope.launch { container.equalizer.attach(audioSessionId, container.settings.current()) }
                }
            })
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(mediaButtons())
            .build()

        container.sleepTimer.pauseAction = { scope.launch { sessionPlayer.pause() } }
        scope.launch { container.equalizer.attach(player.audioSessionId, container.settings.current()) }

        // Nastavení → audio efekty
        scope.launch {
            container.settings.settings.collect { s ->
                normalizer.enabled = s.normalizeVolume
                faderNormalizer.enabled = s.normalizeVolume
                fadeTransitions = s.fadeTransitions
                crossfadeMs = s.crossfadeSec * 1000L
                preloadNext = s.preloadNext
                container.equalizer.apply(s)
            }
        }
        // Srdíčko v notifikaci sleduje stav v knihovně (like z aplikace se projeví i tady)
        scope.launch {
            currentId
                .flatMapLatest { id -> id?.let { container.music.tracks.observe(it) } ?: flowOf(null) }
                .collect { track ->
                    currentLiked = track?.liked == true
                    updateMediaButtons()
                }
        }
        scope.launch { volumeAndStatsLoop() }
    }

    /** Přehrávač s vlastním audio procesorem pro vyrovnání hlasitosti. */
    private fun renderersFactory(normalizer: VolumeNormalizer) = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(normalizer))
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
    }

    /**
     * vibe://track/<id> → stažený soubor, nebo cache streamů → (při minutí) stream z yt-dlp.
     * Díky cache se opakovaně přehrávané skladby nestahují znovu a startují okamžitě.
     */
    private fun dataSourceFactory(): ResolvingDataSource.Factory {
        val resolver = container.mediaResolver
        // Bez setUserAgent(): ten by přepsal User-Agent, ke kterému yt-dlp URL vydal (→ 403).
        // Výchozí UA se použije jen tam, kde yt-dlp žádný nedodal.
        val http = OkHttpDataSource.Factory(container.streamHttp)
            .setDefaultRequestProperties(mapOf("User-Agent" to YouTubeMusicApi.USER_AGENT))
        val stream = ResolvingDataSource.Factory(http) { resolver.resolveStream(it) }
        val cached = CacheDataSource.Factory()
            .setCache(container.streamCache)
            .setUpstreamDataSourceFactory(stream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        cachedFactory = cached
        // DefaultDataSource pošle file:// na disk a vše ostatní (vibe://) do cache
        val routed = DefaultDataSource.Factory(this, cached)
        return ResolvingDataSource.Factory(routed) { resolver.resolveLocal(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!sessionPlayer.playWhenReady || sessionPlayer.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        flushStats()
        container.sleepTimer.pauseAction = null
        container.equalizer.release()
        releaseFader()
        session?.run {
            sessionPlayer.release()
            if (sessionPlayer !== player) runCatching { player.release() }
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Hlasitost (přechody, časovač) + statistiky ----------

    private suspend fun volumeAndStatsLoop() {
        var last = SystemClock.elapsedRealtime()
        var sinceFlush = 0L
        while (scope.isActive) {
            delay(100)
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - last
            last = now

            val base = container.sleepTimer.fadeFactor.value
            var volume = base
            val f = fader
            if (f != null) {
                // Probíhá crossfade: stará skladba doznívá, nová nabíhá
                val progress = ((now - crossfadeStart).toFloat() / crossfadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                // Hlavní přehrávač při crossfadu chvíli načítá novou skladbu – končíme jen při skutečné pauze
                if (progress >= 1f || !player.playWhenReady) releaseFader() else f.volume = base * (1f - progress)
                volume *= progress
            } else if (crossfadeMs > 0) {
                maybeStartCrossfade()
                if (fader != null) volume = 0f
            } else if (fadeTransitions && player.isPlaying) {
                val pos = player.currentPosition
                val dur = player.duration
                if (dur != C.TIME_UNSET && dur > 10_000) {
                    volume *= minOf(1f, pos / FADE_IN_MS, (dur - pos) / FADE_OUT_MS).coerceIn(0f, 1f)
                }
            }
            if (player.volume != volume) player.volume = volume

            if (sessionPlayer.isPlaying) {
                val id = sessionPlayer.currentMediaItem?.mediaId
                if (id != statsTrackId) flushStats()
                statsTrackId = id
                statsPendingMs += elapsed
                sinceFlush += elapsed
                if (sinceFlush >= 15_000) {
                    flushStats()
                    sinceFlush = 0
                }
            }
        }
    }

    /**
     * Skutečný crossfade: konec aktuální skladby dohraje druhý přehrávač (z cache, takže hned),
     * hlavní přehrávač mezitím přeskočí na další skladbu a postupně se zesílí.
     */
    private fun maybeStartCrossfade() {
        if (sessionPlayer !== player && sessionPlayer.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        if (!player.isPlaying || !player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE) return
        if (container.sleepTimer.state.value == SleepState.EndOfTrack) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur == C.TIME_UNSET || dur < crossfadeMs * 3 || dur - pos > crossfadeMs) return
        val item = player.currentMediaItem ?: return
        val f = ExoPlayer.Builder(this, renderersFactory(faderNormalizer))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setAudioAttributes(AUDIO_ATTRIBUTES, false)
            .build()
        f.setMediaItem(item, pos)
        f.volume = container.sleepTimer.fadeFactor.value
        f.prepare()
        f.play()
        fader = f
        crossfadeStart = SystemClock.elapsedRealtime()
        crossfadeTransition = true
        player.volume = 0f
        player.seekToNextMediaItem()
    }

    private var crossfadeTransition = false

    private fun releaseFader() {
        fader?.let { runCatching { it.release() } }
        fader = null
    }

    /** Celou další skladbu předem stáhne do cache – přeskočení je pak okamžité i ve slabém signálu. */
    private fun preload(nextId: String) {
        preloadJob?.cancel()
        if (!preloadNext || !::cachedFactory.isInitialized) return
        preloadJob = scope.launch(Dispatchers.IO) {
            delay(8_000) // nejdřív ať se rozehraje aktuální skladba
            if (container.music.tracks.get(nextId)?.isDownloaded == true) return@launch
            val spec = DataSpec.Builder().setUri(MediaResolver.uriFor(nextId)).setKey("track:$nextId").build()
            runCatching {
                runInterruptible { CacheWriter(cachedFactory.createDataSource(), spec, null, null).cache() }
            }.onSuccess { Log.d(TAG, "Přednačteno $nextId") }.onFailure { if (it !is CancellationException) Log.d(TAG, "Přednačtení $nextId selhalo: ${it.message}") }
        }
    }

    private fun flushStats() {
        val id = statsTrackId ?: return
        val ms = statsPendingMs
        statsPendingMs = 0
        if (ms < 1000) return
        container.appScope.launch(NonCancellable) {
            runCatching { container.db.stats().record(id, LocalDate.now().toString(), ms) }
        }
    }

    // ---------- Tlačítka v notifikaci / ovládání na zamykací obrazovce ----------

    /**
     * Systémové ovládání médií (Samsung „Media“ panel, notifikace, zamykací obrazovka, Android Auto)
     * ukáže kromě přehrát/další/předchozí i tato tlačítka – jako Spotify / YT Music.
     */
    private fun mediaButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(if (currentLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (currentLiked) "Odebrat z Oblíbených" else "Líbí se")
            .setSessionCommand(CMD_LIKE)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(
            if (::sessionPlayer.isInitialized && sessionPlayer.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName("Náhodně")
            .setSessionCommand(CMD_SHUFFLE)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(
            when (if (::sessionPlayer.isInitialized) sessionPlayer.repeatMode else Player.REPEAT_MODE_OFF) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            }
        )
            .setDisplayName("Opakovat")
            .setSessionCommand(CMD_REPEAT)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun updateMediaButtons() {
        session?.setMediaButtonPreferences(mediaButtons())
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(CMD_LIKE).add(CMD_SHUFFLE).add(CMD_REPEAT)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(mediaButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_LIKE.customAction -> sessionPlayer.currentMediaItem?.mediaId?.let { id ->
                    val liked = !currentLiked
                    scope.launch(Dispatchers.IO) { container.music.tracks.setLiked(id, liked) }
                }
                CMD_SHUFFLE.customAction -> sessionPlayer.shuffleModeEnabled = !sessionPlayer.shuffleModeEnabled
                CMD_REPEAT.customAction -> sessionPlayer.repeatMode = when (sessionPlayer.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // --- Procházení knihovny (Android Auto) ---

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(if (params?.isRecent == true) tree.recentRoot() else tree.root(), params)
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = withContext(Dispatchers.IO) { tree.children(parentId) }
            val pageItems = all.drop(page * pageSize).take(pageSize)
            LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val id = LibraryTree.splitTrackId(mediaId)?.second ?: mediaId
            val track = withContext(Dispatchers.IO) { container.music.tracks.get(id) }
            if (track != null) LibraryResult.ofItem(MediaResolver.mediaItem(track), null)
            else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = withContext(Dispatchers.IO) { runCatching { tree.search(query).size }.getOrDefault(0) }
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(
            LibraryResult.ofItemList(ImmutableList.copyOf(tree.searchItems().drop(page * pageSize).take(pageSize)), params)
        )

        // --- Spuštění přehrávání ---

        /**
         * - skladba ze složky („složka|id“) → přehrát celou složku od ní,
         * - mix („mix:…“) → sestavit mix,
         * - hlasový příkaz („pusť X na Vibe“) → najít a spustit rádio,
         * - jinak (aplikace) → jen doplnit URI.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val single = mediaItems.singleOrNull()
            val query = single?.requestMetadata?.searchQuery
            val folderTrack = single?.mediaId?.let(LibraryTree::splitTrackId)
            return when {
                query != null -> scope.future {
                    val found = withContext(Dispatchers.IO) { tree.search(query) }
                    val list = found.firstOrNull()?.let { first ->
                        withContext(Dispatchers.IO) { runCatching { container.radio.trackRadio(first) }.getOrDefault(found) }
                    } ?: emptyList()
                    itemsAt(list, 0)
                }
                single != null && single.mediaId.startsWith("mix:") -> scope.future {
                    val list = withContext(Dispatchers.IO) { tree.mix(single.mediaId) }
                    sessionPlayer.repeatMode = Player.REPEAT_MODE_ALL
                    itemsAt(list, 0)
                }
                folderTrack != null -> scope.future {
                    val (folder, id) = folderTrack
                    val list = withContext(Dispatchers.IO) { tree.tracks(folder) }
                    itemsAt(list, list.indexOfFirst { it.id == id }.coerceAtLeast(0))
                }
                else -> Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(withUris(mediaItems), startIndex, startPositionMs)
                )
            }
        }

        // MediaItem z controlleru přijde bez URI → doplníme náš vibe:// odkaz
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(withUris(mediaItems).toMutableList())

        /** Tlačítko „přehrát“ po restartu telefonu / v Android Auto – pokračovat historií. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val recent = withContext(Dispatchers.IO) { container.music.tracks.recent(30) }
            itemsAt(recent, 0)
        }
    }

    private fun itemsAt(tracks: List<TrackEntity>, index: Int): MediaSession.MediaItemsWithStartPosition {
        scope.launch(Dispatchers.IO) { container.music.ensureTracks(tracks) }
        return MediaSession.MediaItemsWithStartPosition(tracks.map(MediaResolver::mediaItem), index, 0)
    }

    private fun withUris(items: List<MediaItem>): List<MediaItem> = items.map {
        val id = LibraryTree.splitTrackId(it.mediaId)?.second ?: it.mediaId
        it.buildUpon().setMediaId(id).setUri(MediaResolver.uriFor(id)).build()
    }

    // ---------- Automatické pokračování (rádio) ----------

    /** Když ve frontě zbývá poslední skladba, doplní podobné skladby k té aktuální. */
    private fun maybeExtendQueue() {
        if (sessionPlayer.repeatMode != Player.REPEAT_MODE_OFF || autoplayJob?.isActive == true) return
        val remaining = generateSequence(sessionPlayer.currentMediaItemIndex) { i ->
            sessionPlayer.currentTimeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, sessionPlayer.shuffleModeEnabled)
                .takeIf { it != C.INDEX_UNSET }
        }.count() - 1
        if (remaining > 1) return
        val seed = sessionPlayer.currentMediaItem?.mediaId ?: return
        val queued = (0 until sessionPlayer.mediaItemCount).mapTo(HashSet()) { sessionPlayer.getMediaItemAt(it).mediaId }

        autoplayJob = scope.launch {
            if (!container.settings.current().autoplay) return@launch
            val tracks = withContext(Dispatchers.IO) {
                runCatching { container.radio.similar(seed) }.getOrDefault(emptyList())
            }.filter { it.id !in queued }.take(25)
            if (tracks.isEmpty()) return@launch
            withContext(Dispatchers.IO) { container.music.ensureTracks(tracks) }
            sessionPlayer.addMediaItems(tracks.map(MediaResolver::mediaItem))
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Časovač „do konce skladby“
            val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || crossfadeTransition
            crossfadeTransition = false
            if (auto && container.sleepTimer.onTrackEnded()) {
                sessionPlayer.pause()
            }
            val id = mediaItem?.mediaId ?: return
            currentId.value = id
            scope.launch {
                container.music.onPlayed(id)
                if (container.settings.current().playbackMode == PlaybackMode.STREAM_AND_SAVE) {
                    container.music.tracks.get(id)?.takeIf { !it.isDownloaded }?.let {
                        container.downloads.enqueue(listOf(it))
                    }
                }
            }
            maybeExtendQueue()
            // Připrav stream další skladby
            val next = sessionPlayer.nextMediaItemIndex
            if (next != C.INDEX_UNSET) {
                val nextId = sessionPlayer.getMediaItemAt(next).mediaId
                scope.launch(Dispatchers.IO) { container.mediaResolver.prefetch(nextId) }
                preload(nextId)
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            scope.launch { container.equalizer.attach(audioSessionId, container.settings.current()) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateMediaButtons()

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateMediaButtons()
            maybeExtendQueue()
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = sessionPlayer.currentMediaItem?.mediaId ?: return
            Log.w(TAG, "Chyba přehrávání $id", error)
            container.lastPlaybackError = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
            container.lastPlaybackErrorId = id
            container.mediaResolver.invalidate(id)
            val httpError = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (lastRecoveredId != id && (httpError == 403 || httpError == 410)) {
                // Vypršelá stream URL → zkusit znovu se stejnou pozicí
                lastRecoveredId = id
                sessionPlayer.prepare()
                sessionPlayer.play()
            } else if (sessionPlayer.hasNextMediaItem()) {
                sessionPlayer.seekToNextMediaItem()
                sessionPlayer.prepare()
                sessionPlayer.play()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                lastRecoveredId = null
                // Chyba se vyřešila (např. nová URL po vypršení) → v diagnostice už nestraší
                if (sessionPlayer.currentMediaItem?.mediaId == container.lastPlaybackErrorId) {
                    container.lastPlaybackError = null
                    container.lastPlaybackErrorId = null
                }
            } else flushStats()
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        private const val FADE_IN_MS = 1500f
        private const val FADE_OUT_MS = 4000f
        private val CMD_LIKE = SessionCommand("vibe.LIKE", Bundle.EMPTY)
        private val CMD_SHUFFLE = SessionCommand("vibe.SHUFFLE", Bundle.EMPTY)
        private val CMD_REPEAT = SessionCommand("vibe.REPEAT", Bundle.EMPTY)
    }
}
