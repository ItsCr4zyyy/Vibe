package com.vibe.music.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.vibe.music.data.db.DownloadState
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.ui.theme.VibeColors

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(modifier.clip(shape).background(VibeColors.SurfaceHighest)) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { ArtworkPlaceholder(placeholderIcon) },
            error = { ArtworkPlaceholder(placeholderIcon) },
        )
    }
}

@Composable
private fun ArtworkPlaceholder(icon: ImageVector) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(VibeColors.SurfaceHighest, VibeColors.SurfaceHigh))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = VibeColors.TextSecondary, modifier = Modifier.size(28.dp))
    }
}

/** Čtvercová dlaždice s gradientem a ikonou (Oblíbené, Stažené…). */
@Composable
fun GradientTile(icon: ImageVector, colors: List<Color>, modifier: Modifier = Modifier, iconSize: Dp = 26.dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

val LikedGradient = listOf(Color(0xFF6D28D9), Color(0xFFEC4899))
val DownloadedGradient = listOf(Color(0xFF3B4FD8), Color(0xFF8FB8FF))

@Composable
fun TrackRow(
    track: TrackEntity,
    isCurrent: Boolean,
    downloadProgress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else Artwork(track.artworkUrl, Modifier.size(50.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(
                        Icons.Rounded.GraphicEq, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                    )
                }
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DownloadBadge(track, downloadProgress)
                if (track.liked) {
                    Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                }
                Text(
                    listOfNotNull(track.artist, formatDuration(track.durationSec).ifEmpty { null }).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onMenu != null) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Rounded.MoreVert, "Možnosti", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun DownloadBadge(track: TrackEntity, progress: Float?) {
    val size = Modifier.size(13.dp)
    when {
        progress != null -> CircularProgressIndicator(
            progress = { (progress / 100f).coerceIn(0.02f, 1f) },
            modifier = size,
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        track.downloadState == DownloadState.DONE ->
            Icon(Icons.Rounded.CheckCircle, "Staženo", tint = MaterialTheme.colorScheme.primary, modifier = size)
        track.downloadState == DownloadState.QUEUED || track.downloadState == DownloadState.DOWNLOADING ->
            Icon(Icons.Rounded.Schedule, "Ve frontě", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = size)
        track.downloadState == DownloadState.FAILED ->
            Icon(Icons.Rounded.ErrorOutline, "Chyba stahování", tint = MaterialTheme.colorScheme.error, modifier = size)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

/** Drží stav otevřeného menu skladby – stačí jedna instance na obrazovku. */
class TrackMenuState {
    var track by mutableStateOf<TrackEntity?>(null)
    var playlistId by mutableStateOf<Long?>(null)

    fun open(t: TrackEntity, inPlaylist: Long? = null) {
        track = t
        playlistId = inPlaylist
    }

    fun close() {
        track = null
    }
}

@Composable
fun rememberTrackMenuState() = remember { TrackMenuState() }
