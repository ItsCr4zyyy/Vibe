package com.vibe.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileDownloadDone
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.data.db.PlaylistSource
import com.vibe.music.data.db.PlaylistSummary
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.DownloadedGradient
import com.vibe.music.ui.common.EmptyState
import com.vibe.music.ui.common.GradientTile
import com.vibe.music.ui.common.LikedGradient
import com.vibe.music.remote.ArtistRef
import com.vibe.music.ui.browse.ArtistAvatar
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.LocalNavigator
import com.vibe.music.ui.common.PlaylistArtwork
import com.vibe.music.ui.common.pluralTracks
import com.vibe.music.ui.common.vibeViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val likedCount: Int = 0,
    val downloadedCount: Int = 0,
)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val state = combine(
        c.music.playlists.observeSummaries(),
        c.music.tracks.observeLiked(),
        c.music.tracks.observeDownloaded(),
    ) { p, liked, downloaded -> LibraryState(p, liked.size, downloaded.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())

    fun create(name: String) = viewModelScope.launch { c.music.createPlaylist(name) }
}

private enum class Filter(val label: String) { ALL("Vše"), MINE("Moje"), YOUTUBE("YouTube"), SPOTIFY("Spotify"), SOUNDCLOUD("SoundCloud") }

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    onOpenPlaylist: (Long) -> Unit,
    onOpenCollection: (String) -> Unit,
    onImport: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val vm = vibeViewModel { LibraryViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val c = LocalContainer.current
    val navigator = LocalNavigator.current
    val followed by remember { c.db.artists().observeFollowed() }.collectAsState(emptyList())

    val playlists = state.playlists.filter {
        when (filter) {
            Filter.ALL -> true
            Filter.MINE -> it.playlist.source == PlaylistSource.LOCAL
            Filter.YOUTUBE -> it.playlist.source == PlaylistSource.YOUTUBE
            Filter.SPOTIFY -> it.playlist.source == PlaylistSource.SPOTIFY
            Filter.SOUNDCLOUD -> it.playlist.source == PlaylistSource.SOUNDCLOUD
        }
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Knihovna", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onImport) { Icon(Icons.Rounded.CloudDownload, "Importovat") }
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "Nový playlist") }
            }
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Filter.entries) { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
        if (filter == Filter.ALL && followed.isNotEmpty()) {
            item {
                Text(
                    "Sledovaní interpreti",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(followed, key = { it.id }) { a ->
                        ArtistAvatar(ArtistRef(a.id, a.name, a.artworkUrl), size = 84, onClick = { navigator.artist(a.id, a.name) })
                    }
                }
            }
        }
        if (filter == Filter.ALL) {
            item {
                LibraryRow(
                    title = "Oblíbené skladby",
                    subtitle = "Playlist • ${pluralTracks(state.likedCount)}",
                    art = { GradientTile(Icons.Rounded.Favorite, LikedGradient, it) },
                    onClick = { onOpenCollection("liked") },
                )
            }
            item {
                LibraryRow(
                    title = "Stažené",
                    subtitle = "Offline • ${pluralTracks(state.downloadedCount)}",
                    art = { GradientTile(Icons.Rounded.FileDownloadDone, DownloadedGradient, it) },
                    onClick = { onOpenCollection("downloaded") },
                )
            }
            item {
                LibraryRow(
                    title = "Historie",
                    subtitle = "Naposledy přehrané",
                    art = { GradientTile(Icons.Rounded.History, listOf(Color(0xFF3A3A48), Color(0xFF5A5A6E)), it) },
                    onClick = { onOpenCollection("recent") },
                )
            }
            item {
                LibraryRow(
                    title = "Statistiky",
                    subtitle = "Kolik toho posloucháš",
                    art = { GradientTile(Icons.Rounded.BarChart, listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)), it) },
                    onClick = onOpenStats,
                )
            }
        }
        items(playlists, key = { it.playlist.id }) { p ->
            val source = when (p.playlist.source) {
                PlaylistSource.SPOTIFY -> "Spotify"
                PlaylistSource.YOUTUBE -> "YouTube"
                PlaylistSource.SOUNDCLOUD -> "SoundCloud"
                else -> "Playlist"
            }
            LibraryRow(
                title = p.playlist.name,
                subtitle = "$source • ${pluralTracks(p.trackCount)}",
                art = { PlaylistArtwork(p.playlist, it) },
                onClick = { onOpenPlaylist(p.playlist.id) },
                badges = {
                    if (p.playlist.autoSync) Icon(Icons.Rounded.Sync, "Synchronizuje se", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.trackCount > 0 && p.downloadedCount == p.trackCount) {
                        Icon(Icons.Rounded.CheckCircle, "Staženo", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        }
        if (playlists.isEmpty() && filter != Filter.ALL) {
            item { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Žádné playlisty", "V této kategorii zatím nic nemáš.") }
        }
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Nový playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Název") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.create(name.trim()); creating = false }) { Text("Vytvořit") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    art: @Composable (Modifier) -> Unit,
    onClick: () -> Unit,
    badges: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art(Modifier.size(64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                badges()
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
