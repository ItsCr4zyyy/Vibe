package com.vibe.music.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileDownloadDone
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.data.db.PlaylistSummary
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.DownloadedGradient
import com.vibe.music.ui.common.GradientTile
import com.vibe.music.ui.common.LikedGradient
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.LocalNavigator
import com.vibe.music.ui.common.PlaylistArtwork
import com.vibe.music.ui.common.pluralTracks
import com.vibe.music.ui.common.SectionTitle
import com.vibe.music.ui.common.rememberTrackActions
import com.vibe.music.ui.common.vibeViewModel
import com.vibe.music.ui.theme.VibeColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

data class HomeState(
    val recent: List<TrackEntity> = emptyList(),
    val mostPlayed: List<TrackEntity> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val likedCount: Int = 0,
    val downloadedCount: Int = 0,
    val loaded: Boolean = false,
)

class HomeViewModel(c: AppContainer) : ViewModel() {
    val state = combine(
        c.music.tracks.observeRecent(20),
        c.music.tracks.observeMostPlayed(20),
        c.music.playlists.observeSummaries(),
        c.music.tracks.observeLiked(),
        c.music.tracks.observeDownloaded(),
    ) { recent, most, playlists, liked, downloaded ->
        HomeState(recent, most, playlists, liked.size, downloaded.size, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())
}

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenPlaylist: (Long) -> Unit,
    onOpenCollection: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
) {
    val vm = vibeViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()
    val c = LocalContainer.current
    val navigator = LocalNavigator.current
    val releases by remember { c.db.artists().observeReleases(15) }.collectAsState(emptyList())

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF241A3D), VibeColors.Background), endY = 900f))
    ) {
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(greeting(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenStats) { Icon(Icons.Rounded.BarChart, "Statistiky") }
                    IconButton(onClick = onImport) { Icon(Icons.Rounded.CloudDownload, "Importovat") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Nastavení") }
                }
            }

            // Rychlý přístup – mřížka 2 sloupce jako ve Spotify
            item {
                val tiles = buildList<QuickTile> {
                    add(QuickTile.Liked(state.likedCount))
                    if (state.downloadedCount > 0) add(QuickTile.Downloaded(state.downloadedCount))
                    state.playlists.take(6 - size).forEach { add(QuickTile.Playlist(it)) }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { tile ->
                                QuickAccessTile(tile, Modifier.weight(1f)) {
                                    when (tile) {
                                        is QuickTile.Liked -> onOpenCollection("liked")
                                        is QuickTile.Downloaded -> onOpenCollection("downloaded")
                                        is QuickTile.Playlist -> onOpenPlaylist(tile.summary.playlist.id)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (state.loaded && state.recent.isEmpty() && state.playlists.isEmpty()) {
                item { WelcomeCard(onImport, onSearch) }
            }

            // Rádia a mixy – nekonečné přehrávání z vlastní hudby + podobných skladeb
            val radioPlaylists = state.playlists.filter { it.trackCount >= 3 }.take(6)
            val hasLibrary = state.recent.isNotEmpty() || state.likedCount > 0 || radioPlaylists.isNotEmpty()
            if (hasLibrary) {
                item { SectionTitle("Rádia a mixy") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        item(key = "my-mix") {
                            MixCard(
                                title = "Tvůj mix",
                                subtitle = "Tvoje oblíbené + podobné",
                                artwork = null,
                                onClick = actions::startLibraryMix,
                            )
                        }
                        items(radioPlaylists, key = { "radio-${it.playlist.id}" }) { p ->
                            MixCard(
                                title = "${p.playlist.name} rádio",
                                subtitle = "Playlist + podobné skladby",
                                artwork = p.playlist.artworkUrl,
                                onClick = { actions.startPlaylistMix(p.playlist.id) },
                            )
                        }
                    }
                }
            }

            if (releases.isNotEmpty()) {
                item { SectionTitle("Novinky od sledovaných") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(releases, key = { "rel-${it.albumId}" }) { rel ->
                            Column(
                                Modifier.width(148.dp).clickable {
                                    c.appScope.launch { c.db.artists().markSeen(rel.albumId) }
                                    navigator.album(rel.albumId, rel.title, rel.artistName)
                                }
                            ) {
                                Box {
                                    Artwork(rel.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(8.dp))
                                    if (rel.isNew) {
                                        Text(
                                            "NOVÉ",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(rel.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(rel.artistName, rel.year.takeIf { it > 0 }?.toString()).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            if (state.recent.isNotEmpty()) {
                item { SectionTitle("Naposledy přehrané") }
                item { TrackCarousel(state.recent) { i -> actions.play(state.recent, i) } }
            }
            if (state.playlists.isNotEmpty()) {
                item { SectionTitle("Tvoje playlisty") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.playlists, key = { it.playlist.id }) { p ->
                            Column(Modifier.width(148.dp).clickable { onOpenPlaylist(p.playlist.id) }) {
                                PlaylistArtwork(p.playlist, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(8.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(p.playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pluralTracks(p.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (state.mostPlayed.size >= 3) {
                item { SectionTitle("Nejposlouchanější") }
                item { TrackCarousel(state.mostPlayed) { i -> actions.play(state.mostPlayed, i) } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private sealed interface QuickTile {
    data class Liked(val count: Int) : QuickTile
    data class Downloaded(val count: Int) : QuickTile
    data class Playlist(val summary: PlaylistSummary) : QuickTile
}

@Composable
private fun QuickAccessTile(tile: QuickTile, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val art = Modifier.size(56.dp)
        when (tile) {
            is QuickTile.Liked -> GradientTile(Icons.Rounded.Favorite, LikedGradient, art, 22.dp)
            is QuickTile.Downloaded -> GradientTile(Icons.Rounded.FileDownloadDone, DownloadedGradient, art, 22.dp)
            is QuickTile.Playlist -> PlaylistArtwork(tile.summary.playlist, art, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
        }
        Text(
            when (tile) {
                is QuickTile.Liked -> "Oblíbené skladby"
                is QuickTile.Downloaded -> "Stažené"
                is QuickTile.Playlist -> tile.summary.playlist.name
            },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

@Composable
private fun TrackCarousel(tracks: List<TrackEntity>, onPlay: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            CoverCard(t.artworkUrl, t.title, t.artist) { onPlay(i) }
        }
    }
}

@Composable
private fun CoverCard(artwork: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.width(148.dp).clickable(onClick = onClick)) {
        Artwork(artwork, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(8.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WelcomeCard(onImport: () -> Unit, onSearch: () -> Unit) {
    Column(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A2470), Color(0xFF6B2A6E))))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Vítej ve Vibe 👋", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Importuj playlist z YouTube nebo Spotify, nebo si najdi cokoli v hledání. Hudbu můžeš streamovat i stahovat offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = onImport) {
                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Importovat")
            }
            OutlinedButton(
                onClick = onSearch,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Hledat")
            }
        }
    }
}

@Composable
private fun MixCard(title: String, subtitle: String, artwork: String?, onClick: () -> Unit) {
    Column(Modifier.width(148.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(148.dp).clip(RoundedCornerShape(8.dp))) {
            if (artwork != null) {
                Artwork(artwork, Modifier.fillMaxSize(), RoundedCornerShape(8.dp))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(VibeColors.AccentDeep, VibeColors.Violet))))
            }
            // Spodní pruh se štítkem „RÁDIO“ jako na Spotify
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Radio, null, tint = VibeColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("RÁDIO", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Dobré ráno"
    in 11..17 -> "Dobré odpoledne"
    else -> "Dobrý večer"
}
