package com.vibe.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.vibe.music.data.db.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class QueueEntry(val index: Int, val item: MediaItem)

data class PlayerState(
    val current: MediaItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    /** Fronta v pořadí přehrávání (respektuje shuffle), začíná aktuální skladbou. */
    val upNext: List<QueueEntry> = emptyList(),
    val error: String? = null,
)

/** Most mezi UI a [PlaybackService] přes Media3 MediaController. */
class PlayerConnection(context: Context) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    val positionMs: Long get() = controller?.currentPosition ?: 0
    val bufferedMs: Long get() = controller?.bufferedPosition ?: 0

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
            })
            pending.forEach { it(c) }
            pending.clear()
            sync()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    /** [repeat] = Player.REPEAT_MODE_*; null ponechá aktuální nastavení. */
    fun play(tracks: List<TrackEntity>, startIndex: Int = 0, shuffle: Boolean = false, repeat: Int? = null) {
        if (tracks.isEmpty()) return
        withController { c ->
            c.shuffleModeEnabled = shuffle
            repeat?.let { c.repeatMode = it }
            val start = if (shuffle && startIndex == 0) tracks.indices.random() else startIndex
            c.setMediaItems(tracks.map(MediaResolver::mediaItem), start, 0)
            c.prepare()
            c.play()
        }
    }

    fun playNext(track: TrackEntity) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(track))
        else c.addMediaItem(c.currentMediaItemIndex + 1, MediaResolver.mediaItem(track))
    }

    fun addToQueue(track: TrackEntity) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(track)) else c.addMediaItem(MediaResolver.mediaItem(track))
    }

    /** Ukončí přehrávání a vyprázdní frontu (zavření mini přehrávače švihnutím). */
    fun stopAndClear() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    fun togglePlay() = withController { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { c ->
        // Jako Spotify: po 3 s vrátí na začátek, jinak předchozí skladba
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = withController { it.seekTo(ms) }

    fun skipTo(index: Int) = withController { c ->
        c.seekTo(index, 0)
        c.play()
    }

    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }

    fun moveItem(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun sync() {
        val c = controller ?: return
        val timeline = c.currentTimeline
        val upNext = buildList {
            var i = c.currentMediaItemIndex
            var guard = 0
            while (i != C.INDEX_UNSET && i < c.mediaItemCount && guard++ < 500) {
                add(QueueEntry(i, c.getMediaItemAt(i)))
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        _state.value = PlayerState(
            current = c.currentMediaItem,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            hasNext = c.hasNextMediaItem(),
            upNext = upNext,
            error = c.playerError?.let { it.cause?.message ?: it.errorCodeName },
        )
    }
}
