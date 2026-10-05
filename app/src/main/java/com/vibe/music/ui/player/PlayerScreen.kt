package com.vibe.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.vibe.music.data.db.DownloadState
import com.vibe.music.data.db.TrackEntity
import androidx.media3.cast.MediaRouteButton
import com.vibe.music.playback.QueueEntry
import com.vibe.music.playback.SleepState
import com.vibe.music.playback.SleepTimer
import kotlinx.coroutines.delay
import com.vibe.music.remote.Lyrics
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.LocalNavigator
import com.vibe.music.ui.common.TrackMenuSheet
import com.vibe.music.ui.common.formatMs
import com.vibe.music.ui.common.rememberArtworkColor
import com.vibe.music.ui.common.rememberTrackActions
import com.vibe.music.ui.common.rememberTrackMenuState
import com.vibe.music.ui.theme.VibeColors

private enum class Panel { ARTWORK, LYRICS, QUEUE }

@Composable
fun PlayerScreen(onCollapse: () -> Unit) {
    val c = LocalContainer.current
    val connection = c.player
    val state by connection.state.collectAsStateWithLifecycle()
    // Registruje se až s přehrávačem → má přednost před navigací pod ním
    BackHandler(onBack = onCollapse)
    val item = state.current ?: return
    val meta = item.mediaMetadata
    val artwork = meta.artworkUri?.toString()
    val title = meta.title?.toString().orEmpty()
    val artist = meta.artist?.toString().orEmpty()

    val stored by remember(item.mediaId) { c.music.tracks.observe(item.mediaId) }.collectAsState(null)
    val track = stored ?: TrackEntity(item.mediaId, title, artist, meta.albumTitle?.toString(), artworkUrl = artwork)
    val progress by c.downloads.progress.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()
    val navigator = LocalNavigator.current
    val menu = rememberTrackMenuState()

    val color by rememberArtworkColor(artwork, fallback = Color(0xFF2A2A38))
    val position by rememberPlaybackPosition()
    var panel by rememberSaveable { mutableStateOf(Panel.ARTWORK) }
    var drag by remember { mutableFloatStateOf(0f) }
    var showSleep by remember { mutableStateOf(false) }
    val sleep by c.sleepTimer.state.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis(), sleep) {
        while (sleep is SleepState.At) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Přehrávač leží mimo Scaffold → bílou barvu obsahu je nutné nastavit explicitně
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(color, lerp(color, Color.Black, 0.55f), VibeColors.Background)))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = { if (drag > 180f) onCollapse() },
                    ) { _, dy -> drag += dy }
                }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            // Horní lišta
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Zavřít", Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        sleepLabel(sleep, now) ?: "PRÁVĚ HRAJE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sleep != SleepState.Off) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        meta.albumTitle?.toString() ?: artist,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            if (track.album != null) navigator.album(track.albumId, track.album, track.artist)
                            else navigator.artist(track.artistId, track.artist)
                        },
                    )
                }
                if (c.castAvailable) MediaRouteButton()
                IconButton(onClick = { showSleep = true }) {
                    Icon(
                        Icons.Rounded.Bedtime, "Časovač vypnutí",
                        tint = if (sleep != SleepState.Off) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = { menu.open(track) }) { Icon(Icons.Rounded.MoreVert, "Možnosti") }
            }

            // Obal / texty / fronta
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(panel, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "panel") { p ->
                    when (p) {
                        Panel.ARTWORK -> {
                            val scale by animateFloatAsState(
                                if (state.isPlaying) 1f else 0.9f,
                                spring(dampingRatio = 0.6f, stiffness = 300f),
                                label = "artScale",
                            )
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Artwork(
                                    artwork,
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .scale(scale)
                                        .shadow(32.dp, RoundedCornerShape(12.dp)),
                                    RoundedCornerShape(12.dp),
                                )
                            }
                        }
                        Panel.LYRICS -> LyricsPanel(title, artist, (state.durationMs / 1000).toInt(), position, connection::seekTo)
                        Panel.QUEUE -> QueuePanel(
                            upNext = state.upNext,
                            reorderable = !state.shuffle,
                            onPlay = connection::skipTo,
                            onRemove = connection::removeAt,
                            onMove = connection::moveItem,
                            onSave = { name -> actions.savePlaylistFromIds(name, state.upNext.map { it.item.mediaId }) },
                        )
                    }
                }
            }

            // Název + like
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { navigator.artist(track.artistId, track.artist) },
                    )
                }
                IconButton(onClick = { actions.setLiked(track, !track.liked) }) {
                    Icon(
                        if (track.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        "Líbí se",
                        tint = if (track.liked) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SeekBar(position, state.durationMs, connection::seekTo)
            Row(Modifier.fillMaxWidth()) {
                Text(formatMs(position), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text(formatMs(state.durationMs), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }

            // Ovládání
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = connection::toggleShuffle) {
                    Icon(
                        Icons.Rounded.Shuffle, "Náhodně",
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = connection::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Předchozí", Modifier.size(40.dp))
                }
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = connection::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering && !state.isPlaying) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            "Přehrát/Pozastavit",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                IconButton(onClick = connection::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Další", Modifier.size(40.dp))
                }
                IconButton(onClick = connection::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Opakovat",
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }

            state.error?.let {
                Text(
                    "Chyba přehrávání: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }

            // Spodní lišta: stažení, texty, fronta
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dlProgress = progress[track.id]
                IconButton(onClick = {
                    when (track.downloadState) {
                        DownloadState.DONE -> Unit
                        DownloadState.QUEUED, DownloadState.DOWNLOADING -> actions.cancelDownload(track)
                        else -> actions.download(listOf(track))
                    }
                }) {
                    when {
                        dlProgress != null -> CircularProgressIndicator(
                            progress = { (dlProgress / 100f).coerceIn(0.02f, 1f) },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        track.downloadState == DownloadState.DONE ->
                            Icon(Icons.Rounded.DownloadDone, "Staženo", tint = MaterialTheme.colorScheme.primary)
                        else -> Icon(Icons.Rounded.Download, "Stáhnout")
                    }
                }
                Row {
                    PanelToggle(Icons.Rounded.Lyrics, "Texty", panel == Panel.LYRICS) {
                        panel = if (panel == Panel.LYRICS) Panel.ARTWORK else Panel.LYRICS
                    }
                    PanelToggle(Icons.AutoMirrored.Rounded.QueueMusic, "Fronta", panel == Panel.QUEUE) {
                        panel = if (panel == Panel.QUEUE) Panel.ARTWORK else Panel.QUEUE
                    }
                }
            }
        }
    }

    TrackMenuSheet(menu)
    if (showSleep) SleepTimerSheet(c.sleepTimer, sleep, onDismiss = { showSleep = false })
}

private fun sleepLabel(state: SleepState, now: Long): String? = when (state) {
    SleepState.Off -> null
    SleepState.EndOfTrack -> "VYPNUTÍ PO TÉTO SKLADBĚ"
    is SleepState.At -> {
        val min = ((state.endsAt - now) / 60_000).coerceAtLeast(0) + 1
        "VYPNUTÍ ZA $min MIN"
    }
}

@Composable
private fun SleepTimerSheet(timer: SleepTimer, state: SleepState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Časovač vypnutí", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            listOf(5, 15, 30, 45, 60, 90).forEach { min ->
                SleepOption("$min minut") { timer.start(min); onDismiss() }
            }
            SleepOption("Do konce skladby") { timer.startEndOfTrack(); onDismiss() }
            if (state != SleepState.Off) {
                SleepOption("Vypnout časovač", color = MaterialTheme.colorScheme.error) { timer.cancel(); onDismiss() }
            }
        }
    }
}

@Composable
private fun SleepOption(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

@Composable
private fun PanelToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else Color.White)
    }
}

private sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data object Missing : LyricsLoad
    data class Loaded(val lyrics: Lyrics) : LyricsLoad
}

@Composable
private fun LyricsPanel(title: String, artist: String, durationSec: Int, positionMs: Long, onSeek: (Long) -> Unit) {
    val c = LocalContainer.current
    val load by produceState<LyricsLoad>(LyricsLoad.Loading, title, artist) {
        value = LyricsLoad.Loading
        value = c.lyrics.lyrics(title, artist, durationSec)?.let { LyricsLoad.Loaded(it) } ?: LyricsLoad.Missing
    }

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val l = load) {
            LyricsLoad.Loading -> CircularProgressIndicator(color = Color.White)
            LyricsLoad.Missing -> Text("Text písně není k dispozici", color = Color.White.copy(alpha = 0.7f))
            is LyricsLoad.Loaded -> {
                val synced = l.lyrics.synced
                if (synced != null) {
                    val listState = rememberLazyListState()
                    val current = synced.indexOfLast { it.timeMs <= positionMs + 250 }
                    LaunchedEffect(current) {
                        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item { Spacer(Modifier.height(24.dp)) }
                        itemsIndexed(synced) { i, line ->
                            Text(
                                line.text.ifBlank { "♪" },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    i == current -> Color.White
                                    i < current -> Color.White.copy(alpha = 0.55f)
                                    else -> Color.White.copy(alpha = 0.35f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSeek(line.timeMs) }
                                    .padding(vertical = 6.dp),
                            )
                        }
                        item { Spacer(Modifier.height(120.dp)) }
                    }
                } else {
                    Text(
                        l.lyrics.plain.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(
    upNext: List<QueueEntry>,
    reorderable: Boolean,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSave: (String) -> Unit,
) {
    // Pracovní kopie fronty pro tažení; po puštění se změna pošle přehrávači
    var upcoming by remember(upNext) { mutableStateOf(upNext.drop(1)) }
    var saving by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val f = upcoming.indexOfFirst { queueKey(it) == from.key }
        val t = upcoming.indexOfFirst { queueKey(it) == to.key }
        if (f >= 0 && t >= 0) upcoming = upcoming.toMutableList().apply { add(t, removeAt(f)) }
    }
    fun commit(entry: QueueEntry) {
        val first = upNext.getOrNull(1)?.index ?: return
        val target = first + upcoming.indexOf(entry)
        if (target != entry.index) onMove(entry.index, target)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.25f)),
    ) {
        upNext.firstOrNull()?.let { now ->
            item(key = "now-title") {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PanelTitle("Právě hraje") }
                    TextButton(onClick = { saving = true }) { Text("Uložit frontu", color = Color.White) }
                }
            }
            item(key = "now") { QueueRow(now, current = true, onClick = {}, onRemove = null) }
        }
        if (upcoming.isNotEmpty()) {
            item(key = "next-title") {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    PanelTitle(if (reorderable) "Další ve frontě" else "Další ve frontě (náhodně)")
                }
            }
            items(upcoming, key = ::queueKey) { entry ->
                ReorderableItem(reorderState, key = queueKey(entry), enabled = reorderable) { dragging ->
                    QueueRow(
                        entry,
                        current = false,
                        onClick = { onPlay(entry.index) },
                        onRemove = { onRemove(entry.index) },
                        modifier = Modifier.background(if (dragging) Color.White.copy(alpha = 0.08f) else Color.Transparent),
                        handle = if (reorderable) {
                            {
                                Icon(
                                    Icons.Rounded.DragHandle, "Přesunout",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .draggableHandle(onDragStopped = { commit(entry) })
                                        .padding(8.dp),
                                )
                            }
                        } else null,
                    )
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf("Moje fronta") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Uložit frontu jako playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Název") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()); saving = false }) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Zrušit") } },
        )
    }
}

private fun queueKey(e: QueueEntry) = "${e.index}-${e.item.mediaId}"

@Composable
private fun QueueRow(
    entry: QueueEntry,
    current: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    handle: (@Composable () -> Unit)? = null,
) {
    val meta = entry.item.mediaMetadata
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(meta.artworkUri?.toString(), Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                meta.title?.toString().orEmpty(),
                color = if (current) MaterialTheme.colorScheme.primary else Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                meta.artist?.toString().orEmpty(),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Odebrat", tint = Color.White.copy(alpha = 0.6f)) }
        }
        handle?.invoke()
    }
}
