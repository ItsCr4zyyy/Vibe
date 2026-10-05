package com.vibe.music.ui.browse

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.vibe.music.AppContainer
import com.vibe.music.remote.ArtistPage
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.LocalNavigator
import com.vibe.music.ui.common.SectionTitle
import com.vibe.music.ui.common.TrackMenuSheet
import com.vibe.music.ui.common.TrackRow
import com.vibe.music.ui.common.rememberArtworkColor
import com.vibe.music.ui.common.rememberTrackActions
import com.vibe.music.ui.common.rememberTrackMenuState
import com.vibe.music.ui.common.vibeViewModel
import com.vibe.music.ui.theme.VibeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** [key] je „browseId|jméno“; bez ID se interpret dohledá podle jména. */
class ArtistViewModel(private val c: AppContainer, key: String) : ViewModel() {
    private val idPart = key.substringBefore('|')
    private val name = key.substringAfter('|', "")

    val state = MutableStateFlow<LoadState<ArtistPage>>(LoadState.Loading)

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        state.value = LoadState.Loading
        state.value = runCatching {
            val id = idPart.ifEmpty { null }
                ?: runCatching { c.ytMusic.searchArtists(name).firstOrNull()?.id }.getOrNull()
            id?.let { runCatching { c.ytMusic.artist(it) }.getOrNull() } ?: fallback()
        }.fold({ LoadState.Ready(it) }, { LoadState.Error(it.message ?: "Neznámá chyba") })
    }

    /** Omezená stránka přes yt-dlp, když YouTube Music API neodpovídá. */
    private suspend fun fallback(): ArtistPage {
        if (name.isBlank()) error("Interpret se nepodařilo načíst")
        val songs = c.ytDlp.search(name, 15)
        if (songs.isEmpty()) error("Interpret „$name“ nenalezen")
        return ArtistPage(
            id = idPart,
            name = name,
            artworkUrl = songs.first().artworkUrl,
            listeners = null,
            description = "Omezený režim – YouTube Music teď neodpovídá, skladby jsou z YouTube.",
            topSongs = songs,
            albums = emptyList(),
            singles = emptyList(),
            similar = emptyList(),
        )
    }
}

@Composable
fun ArtistScreen(key: String, contentPadding: PaddingValues, onBack: () -> Unit) {
    val vm = vibeViewModel(key = "artist-$key") { ArtistViewModel(it, key) }
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is LoadState.Ready -> ArtistContent(s.data, contentPadding, onBack)
        else -> LoadingOrError(s, onRetry = { vm.load() }, onBack = onBack)
    }
}

@Composable
private fun ArtistContent(page: ArtistPage, contentPadding: PaddingValues, onBack: () -> Unit) {
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
    val songs = remember(page) { page.topSongs.map { it.toEntity() } }
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }
    val followed by remember(page.id) { c.db.artists().observeIsFollowed(page.id) }.collectAsState(false)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            // Velká fotka interpreta se jménem
            item {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    AsyncImage(
                        model = page.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(color),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0.35f to Color.Transparent, 1f to VibeColors.Background)
                        )
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(page.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        page.listeners?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { songs.firstOrNull()?.let(actions::startRadio) }, enabled = songs.isNotEmpty()) {
                        Icon(Icons.Rounded.Radio, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Rádio")
                    }
                    if (page.id.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            c.appScope.launch {
                                if (followed) c.follows.unfollow(page.id) else c.follows.follow(page)
                            }
                        }) {
                            Text(if (followed) "Sleduješ" else "Sledovat")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { actions.shuffleLoop(songs) }, enabled = songs.isNotEmpty()) {
                        Icon(Icons.Rounded.Shuffle, "Náhodně", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { actions.play(songs) },
                        enabled = songs.isNotEmpty(),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, "Přehrát", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                    }
                }
            }

            if (songs.isNotEmpty()) {
                item { SectionTitle("Populární") }
                itemsIndexed(songs, key = { _, t -> "song-${t.id}" }) { i, t ->
                    TrackRow(
                        track = t,
                        isCurrent = player.current?.mediaId == t.id,
                        downloadProgress = progress[t.id],
                        onClick = { actions.play(songs, i) },
                        onMenu = { menu.open(t) },
                        leading = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${i + 1}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(24.dp),
                                )
                                Artwork(t.artworkUrl, Modifier.size(50.dp))
                            }
                        },
                    )
                }
            }
            if (page.albums.isNotEmpty()) {
                item { SectionTitle("Alba") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(page.albums, key = { it.id }) { a ->
                            AlbumCard(a, onClick = { navigator.album(a.id, a.title, page.name) })
                        }
                    }
                }
            }
            if (page.singles.isNotEmpty()) {
                item { SectionTitle("Singly a EP") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(page.singles, key = { it.id }) { a ->
                            AlbumCard(a, onClick = { navigator.album(a.id, a.title, page.name) })
                        }
                    }
                }
            }
            if (page.similar.isNotEmpty()) {
                item { SectionTitle("Podobní interpreti") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(page.similar, key = { it.id }) { a ->
                            ArtistAvatar(a, onClick = { navigator.artist(a.id, a.name) })
                        }
                    }
                }
            }
            page.description?.let { about ->
                item { SectionTitle("O interpretovi") }
                item {
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                            .clickable { aboutExpanded = !aboutExpanded }
                            .padding(16.dp)
                            .animateContentSize(),
                    ) {
                        Text(
                            about,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (aboutExpanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        CollapsingTopBar(page.name, color, barAlpha, onBack)
    }
    TrackMenuSheet(menu)
}
