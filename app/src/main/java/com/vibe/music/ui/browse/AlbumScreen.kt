package com.vibe.music.ui.browse

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.data.db.PlaylistSource
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.remote.AlbumPage
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.LocalNavigator
import com.vibe.music.ui.common.TrackMenuSheet
import com.vibe.music.ui.common.TrackRow
import com.vibe.music.ui.common.formatTotal
import com.vibe.music.ui.common.pluralTracks
import com.vibe.music.ui.common.rememberArtworkColor
import com.vibe.music.ui.common.rememberTrackActions
import com.vibe.music.ui.common.rememberTrackMenuState
import com.vibe.music.ui.common.vibeViewModel
import com.vibe.music.ui.theme.VibeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** [key] je „browseId|název interpret“; bez ID se album dohledá hledáním. */
class AlbumViewModel(private val c: AppContainer, key: String) : ViewModel() {
    private val idPart = key.substringBefore('|')
    private val query = key.substringAfter('|', "")

    val state = MutableStateFlow<LoadState<AlbumPage>>(LoadState.Loading)

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        state.value = LoadState.Loading
        state.value = runCatching {
            val id = idPart.ifEmpty { null }
                ?: c.ytMusic.searchAlbums(query).firstOrNull()?.id
                ?: error("Album nenalezeno")
            runCatching { c.ytMusic.album(id) }.getOrNull() ?: fallback(id)
        }.fold({ LoadState.Ready(it) }, { LoadState.Error(it.message ?: "Neznámá chyba") })
    }

    /** Záloha přes yt-dlp (YouTube Music album → jeho playlist na YouTube). */
    private suspend fun fallback(id: String): AlbumPage {
        val pl = c.ytDlp.playlist("https://music.youtube.com/browse/$id")
        return AlbumPage(
            id = id,
            title = pl.title.removePrefix("Album - "),
            artist = pl.tracks.firstOrNull()?.artist ?: "Neznámý interpret",
            artistId = null,
            year = null,
            artworkUrl = pl.artworkUrl,
            playlistId = null,
            tracks = pl.tracks,
        )
    }

    /** Uloží album do knihovny jako playlist (s odkazem pro synchronizaci). */
    fun save(page: AlbumPage, tracks: List<TrackEntity>) = viewModelScope.launch {
        val id = c.music.createPlaylist(page.title, PlaylistSource.YOUTUBE, page.sourceUrl, page.artworkUrl)
        c.music.addToPlaylist(id, tracks)
    }
}

val AlbumPage.sourceUrl: String
    get() = playlistId?.let { "https://www.youtube.com/playlist?list=$it" } ?: "https://music.youtube.com/browse/$id"

@Composable
fun AlbumScreen(key: String, contentPadding: PaddingValues, onBack: () -> Unit) {
    val vm = vibeViewModel(key = "album-$key") { AlbumViewModel(it, key) }
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is LoadState.Ready -> AlbumContent(s.data, vm, contentPadding, onBack)
        else -> LoadingOrError(s, onRetry = { vm.load() }, onBack = onBack)
    }
}

@Composable
private fun AlbumContent(page: AlbumPage, vm: AlbumViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    val c = LocalContainer.current
    val navigator = LocalNavigator.current
    val actions = rememberTrackActions()
    val menu = rememberTrackMenuState()
    val player by c.player.state.collectAsStateWithLifecycle()
    val progress by c.downloads.progress.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val color by rememberArtworkColor(page.artworkUrl)
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")

    // Spojit data z YouTube Music se stavem v knihovně (like, stažení)
    val remote = remember(page) { page.tracks.map { it.toEntity() } }
    val stored by remember(page) {
        if (remote.isEmpty()) flowOf(emptyList()) else c.music.tracks.observeMany(remote.map { it.id })
    }.collectAsState(emptyList())
    val tracks = remember(remote, stored) {
        val byId = stored.associateBy { it.id }
        remote.map { byId[it.id] ?: it }
    }
    val saved by remember(page) { c.music.playlists.observeBySourceUrl(page.sourceUrl) }.collectAsState(null)
    val allDownloaded = tracks.isNotEmpty() && tracks.all { it.isDownloaded }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(color, VibeColors.Background)))
                        .statusBarsPadding()
                        .padding(top = 56.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(
                        page.artworkUrl,
                        Modifier.size(230.dp).shadow(24.dp, RoundedCornerShape(8.dp)),
                        RoundedCornerShape(8.dp),
                        Icons.Rounded.Album,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(page.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        page.artist,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { navigator.artist(page.artistId, page.artist) },
                    )
                    Text(
                        listOfNotNull("Album", page.year, pluralTracks(tracks.size), formatTotal(tracks.sumOf { it.durationSec }))
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { saved?.let { navigator.playlist(it.id) } ?: vm.save(page, tracks) }) {
                            Icon(
                                if (saved != null) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                if (saved != null) "V knihovně" else "Uložit do knihovny",
                                tint = if (saved != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        IconButton(onClick = { if (!allDownloaded) actions.download(tracks) }, enabled = tracks.isNotEmpty()) {
                            Icon(
                                if (allDownloaded) Icons.Rounded.DownloadForOffline else Icons.Rounded.Download,
                                "Stáhnout album",
                                tint = if (allDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        IconButton(onClick = { actions.startMix(tracks) }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Rounded.Radio, "Rádio z alba", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { actions.shuffleLoop(tracks) }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Rounded.Shuffle, "Náhodně", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { actions.play(tracks) },
                            enabled = tracks.isNotEmpty(),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, "Přehrát", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(
                    track = t,
                    isCurrent = player.current?.mediaId == t.id,
                    downloadProgress = progress[t.id],
                    onClick = { actions.play(tracks, i) },
                    onMenu = { menu.open(t) },
                    leading = {
                        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                            Text("${i + 1}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        CollapsingTopBar(page.title, color, barAlpha, onBack)
    }
    TrackMenuSheet(menu)
}
