package com.vibe.music.ui.search

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.data.ImportLink
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.ui.common.EmptyState
import com.vibe.music.remote.AlbumRef
import com.vibe.music.remote.ArtistRef
import com.vibe.music.ui.browse.AlbumCard
import com.vibe.music.ui.browse.ArtistAvatar
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.LocalNavigator
import com.vibe.music.ui.common.SectionTitle
import com.vibe.music.ui.common.TrackMenuSheet
import com.vibe.music.ui.common.TrackRow
import com.vibe.music.ui.common.rememberTrackActions
import com.vibe.music.ui.common.rememberTrackMenuState
import com.vibe.music.ui.common.vibeViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface SearchResult {
    data object Idle : SearchResult
    data object Loading : SearchResult
    data class Done(
        val tracks: List<TrackEntity>,
        val artists: List<ArtistRef> = emptyList(),
        val albums: List<AlbumRef> = emptyList(),
    ) : SearchResult
    data class Error(val message: String) : SearchResult
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")

    val result = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(350)
        .flatMapLatest { q ->
            flow {
                if (q.length < 2 || ImportLink.parse(q) != null) {
                    emit(SearchResult.Idle); return@flow
                }
                emit(SearchResult.Loading)
                val (songs, artists, albums) = coroutineScope {
                    val s = async { runCatching { c.ytMusic.searchSongs(q) }.getOrNull() }
                    val ar = async { runCatching { c.ytMusic.searchArtists(q) }.getOrDefault(emptyList()) }
                    val al = async { runCatching { c.ytMusic.searchAlbums(q) }.getOrDefault(emptyList()) }
                    Triple(s.await(), ar.await(), al.await())
                }
                val tracks = songs?.takeIf { it.isNotEmpty() }
                    ?: runCatching { c.ytDlp.search(q, 15) }.getOrElse {
                        emit(SearchResult.Error("Hledání selhalo: ${it.message}")); return@flow
                    }
                emit(
                    SearchResult.Done(
                        tracks = tracks.map { it.toEntity() },
                        artists = artists.take(8),
                        albums = albums.take(10),
                    )
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchResult.Idle)

    /**
     * SoundCloud (volitelný, v Nastavení) běží přes yt-dlp – načítá se zvlášť, aby nebrzdil ostatní výsledky.
     * null = načítá se, prázdný seznam = nic / vypnuto.
     */
    val soundCloud = combine(
        query.map { it.trim() }.distinctUntilChanged().debounce(600),
        c.settings.settings.map { it.soundCloudEnabled }.distinctUntilChanged(),
    ) { q, enabled -> q to enabled }
        .flatMapLatest { (q, enabled) ->
            flow {
                if (!enabled) {
                    emit(emptyList()); return@flow
                }
                emit(null)
                if (q.length >= 2 && ImportLink.parse(q) == null) {
                    emit(runCatching { c.ytDlp.searchSoundCloud(q, 8).map { it.toEntity() } }.getOrDefault(emptyList()))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val history = c.settings.settings.map { it.searchHistory }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Našeptávač – rychlé návrhy z YouTube Music při psaní. */
    val suggestions = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(150)
        .flatMapLatest { q ->
            flow {
                emit(emptyList())
                if (q.length >= 2 && ImportLink.parse(q) == null) {
                    emit(runCatching { c.ytMusic.suggestions(q) }.getOrDefault(emptyList()).filter { !it.equals(q, true) }.take(5))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun remember(q: String) = viewModelScope.launch { c.settings.addSearch(q) }
    fun forget(q: String) = viewModelScope.launch { c.settings.removeSearch(q) }
}

private val genres = listOf(
    "Pop hity" to listOf(Color(0xFFE13300), Color(0xFFFF7A45)),
    "Česká hudba" to listOf(Color(0xFF1E3264), Color(0xFF4A6FD4)),
    "Lo-fi beats" to listOf(Color(0xFF7358FF), Color(0xFFB39DFF)),
    "Rock klasika" to listOf(Color(0xFF8D1932), Color(0xFFE13A5C)),
    "Hip hop" to listOf(Color(0xFFBA5D07), Color(0xFFF59B23)),
    "Elektronika" to listOf(Color(0xFF0D73EC), Color(0xFF5AB0FF)),
    "Chill" to listOf(Color(0xFF148A08), Color(0xFF52C744)),
    "Workout" to listOf(Color(0xFF503750), Color(0xFFAF2896)),
)

@Composable
fun SearchScreen(contentPadding: PaddingValues, onImportLink: (String) -> Unit) {
    val vm = vibeViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val soundCloud by vm.soundCloud.collectAsStateWithLifecycle()
    val expanded = remember(query) { mutableStateMapOf<String, Boolean>() }
    var fieldFocused by remember { mutableStateOf(false) }
    // Našeptávač jen při psaní – po potvrzení hledání (schované klávesnici) zmizí
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val player by LocalContainer.current.player.state.collectAsStateWithLifecycle()
    val progress by LocalContainer.current.downloads.progress.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()
    val menu = rememberTrackMenuState()
    val focus = LocalFocusManager.current
    val navigator = LocalNavigator.current

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Hledat",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
            )
        }
        item {
            TextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text("Co chceš poslouchat?") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Clear, "Smazat") }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedLeadingIconColor = Color.Black,
                    unfocusedLeadingIconColor = Color.Black,
                    focusedTrailingIconColor = Color.Black,
                    unfocusedTrailingIconColor = Color.Black,
                    focusedPlaceholderColor = Color(0xFF555555),
                    unfocusedPlaceholderColor = Color(0xFF555555),
                    cursorColor = Color.Black,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    vm.remember(query)
                    focus.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .onFocusChanged { fieldFocused = it.isFocused },
            )
        }

        // Našeptávač při psaní
        if (fieldFocused && imeVisible && suggestions.isNotEmpty()) {
            items(suggestions, key = { "sug-$it" }) { s ->
                SearchRow(Icons.Rounded.Search, s, onClick = {
                    vm.query.value = s
                    vm.remember(s)
                    focus.clearFocus()
                })
            }
        }

        // Vložený odkaz → nabídnout import
        if (ImportLink.parse(query) != null) {
            item {
                Row(
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .clickable { onImportLink(query) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Importovat tento odkaz", style = MaterialTheme.typography.titleSmall)
                        Text("Playlist / album / skladba", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        when (val r = result) {
            SearchResult.Idle -> if (query.isBlank()) {
                if (history.isNotEmpty()) {
                    item {
                        Text("Nedávná hledání", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
                    }
                    items(history.take(6), key = { "hist-$it" }) { h ->
                        SearchRow(Icons.Rounded.History, h, onClick = { vm.query.value = h }, onRemove = { vm.forget(h) })
                    }
                }
                item {
                    Text("Procházet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp))
                }
                items(genres.chunked(2).size) { rowIndex ->
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        genres.chunked(2)[rowIndex].forEach { (name, colors) ->
                            GenreTile(name, colors, Modifier.weight(1f)) { vm.query.value = name }
                        }
                    }
                }
            }
            SearchResult.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            is SearchResult.Error -> item {
                EmptyState(Icons.Rounded.SearchOff, "Něco se pokazilo", r.message)
            }
            is SearchResult.Done -> {
                if (r.tracks.isEmpty()) {
                    item { EmptyState(Icons.Rounded.SearchOff, "Nic nenalezeno", "Zkus jiný dotaz.") }
                }
                if (r.artists.isNotEmpty()) {
                    item { SectionTitle("Interpreti") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(r.artists, key = { it.id }) { a ->
                                ArtistAvatar(a, size = 104, onClick = { navigator.artist(a.id, a.name) })
                            }
                        }
                    }
                }
                fun section(key: String, title: String, list: List<TrackEntity>) {
                    if (list.isEmpty()) return
                    item(key = "title-$key") { SectionTitle(title) }
                    val shown = if (expanded[key] == true) list else list.take(5)
                    itemsIndexed(shown, key = { _, t -> "$key-${t.id}" }) { i, t ->
                        TrackRow(
                            track = t,
                            isCurrent = player.current?.mediaId == t.id,
                            downloadProgress = progress[t.id],
                            onClick = {
                                focus.clearFocus()
                                vm.remember(query)
                                actions.play(list, i)
                            },
                            onMenu = { menu.open(t) },
                        )
                    }
                    if (list.size > 5 && expanded[key] != true) {
                        item(key = "more-$key") {
                            TextButton(onClick = { expanded[key] = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Zobrazit další (${list.size - 5})")
                            }
                        }
                    }
                }
                section("songs", "Skladby", r.tracks)
                val sc = soundCloud
                if (sc == null) {
                    item(key = "sc-loading") {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Hledám i na SoundCloudu…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    section("soundcloud", "SoundCloud", sc)
                }
                if (r.albums.isNotEmpty()) {
                    item(key = "albums-title") { SectionTitle("Alba") }
                    item(key = "albums") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(r.albums, key = { it.id }) { a ->
                                AlbumCard(a, onClick = { navigator.album(a.id, a.title, "") })
                            }
                        }
                    }
                }
            }
        }
    }
    TrackMenuSheet(menu)
}

@Composable
private fun GenreTile(name: String, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(92.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 0.dp)
                .width(46.dp)
                .height(46.dp)
                .rotate(22f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.18f)),
        )
    }
}

@Composable
private fun SearchRow(icon: ImageVector, text: String, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Odebrat", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
