package com.vibe.music.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.rememberArtworkColor
import kotlinx.coroutines.delay

/** Pozice přehrávání aktualizovaná několikrát za sekundu. */
@Composable
fun rememberPlaybackPosition(): State<Long> {
    val connection = LocalContainer.current.player
    val state by connection.state.collectAsStateWithLifecycle()
    return produceState(connection.positionMs, state.isPlaying, state.current?.mediaId) {
        while (true) {
            value = connection.positionMs
            delay(if (state.isPlaying) 200 else 1000)
        }
    }
}

@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val connection = LocalContainer.current.player
    val state by connection.state.collectAsStateWithLifecycle()
    val item = state.current ?: return
    val meta = item.mediaMetadata
    val artwork = meta.artworkUri?.toString()
    val color by rememberArtworkColor(artwork)
    val position by rememberPlaybackPosition()
    val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    var drag by remember { mutableFloatStateOf(0f) }
    // Švihnutí doleva/doprava = zavřít (ukončit přehrávání)
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(1f) }

    Box(
        modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .offset { IntOffset(swipe.value.roundToInt(), 0) }
            .alpha(1f - (abs(swipe.value) / width).coerceIn(0f, 1f))
            .clip(RoundedCornerShape(10.dp))
            .background(lerp(color, Color.Black, 0.15f))
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (drag < -60f) onExpand() },
                ) { _, dy -> drag += dy }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(swipe.value) > width * 0.35f) {
                                swipe.animateTo(if (swipe.value > 0) width else -width, tween(180))
                                connection.stopAndClear()
                                swipe.snapTo(0f)
                            } else {
                                swipe.animateTo(0f, spring())
                            }
                        }
                    },
                    onDragCancel = { scope.launch { swipe.animateTo(0f, spring()) } },
                ) { change, dx ->
                    change.consume()
                    scope.launch { swipe.snapTo(swipe.value + dx) }
                }
            },
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork, Modifier.size(42.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.title?.toString().orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    meta.artist?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = connection::togglePlay) {
                if (state.isBuffering && !state.isPlaying) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Přehrát/Pozastavit", Modifier.size(30.dp))
                }
            }
            IconButton(onClick = connection::next, enabled = state.hasNext) {
                Icon(Icons.Rounded.SkipNext, "Další", Modifier.size(28.dp))
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.2f)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Color.White))
        }
    }
}

/** Tenký seek bar ve stylu Spotify, při tažení se zvětší. */
@Composable
fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = when {
        dragging -> dragFraction
        durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val thickness by animateDpAsState(if (dragging) 6.dp else 4.dp, label = "seek")
    val thumb by animateDpAsState(if (dragging) 8.dp else 6.dp, label = "thumb")

    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) onSeek((offset.x / size.width * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragFraction = (it.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        val h = thickness.toPx()
        val y = size.height / 2
        drawRoundRect(
            Color.White.copy(alpha = 0.25f),
            topLeft = Offset(0f, y - h / 2),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(h / 2),
        )
        drawRoundRect(
            Color.White,
            topLeft = Offset(0f, y - h / 2),
            size = Size(size.width * fraction, h),
            cornerRadius = CornerRadius(h / 2),
        )
        drawCircle(Color.White, radius = thumb.toPx(), center = Offset(size.width * fraction, y))
    }
}

@Composable
fun PanelTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
