package com.vibe.music.ui.playlist

import androidx.compose.animation.core.animateFloatAsState
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import com.vibe.music.ui.common.PlaylistArtwork
import com.vibe.music.ui.common.saveCoverImage
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import com.vibe.music.ui.common.sharePlaylist
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileDownloadDone
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.data.db.PlaylistEntity
import com.vibe.music.data.db.PlaylistSource
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.DownloadedGradient
import com.vibe.music.ui.common.EmptyState
import com.vibe.music.ui.common.GradientTile
import com.vibe.music.ui.common.LikedGradient
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.TrackMenuSheet
import com.vibe.music.ui.common.TrackRow
import com.vibe.music.ui.common.formatTotal
import com.vibe.music.ui.common.pluralTracks
import com.vibe.music.ui.common.rememberArtworkColor
import com.vibe.music.ui.common.rememberTrackActions
import com.vibe.music.ui.common.rememberTrackMenuState
import com.vibe.music.ui.common.vibeViewModel
import com.vibe.music.ui.theme.VibeColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [key]: „liked“, „downloaded“, „recent“ nebo ID playlistu. */
class PlaylistViewModel(private val c: AppContainer, private val key: String) : ViewModel() {
    private val playlistId = key.toLongOrNull()

    val playlist: Flow<PlaylistEntity?> = playlistId?.let { c.music.playlists.observe(it) } ?: flowOf(null)

    val tracks = when (key) {
        "liked" -> c.music.tracks.observeLiked()
        "downloaded" -> c.music.tracks.observeDownloaded()
        "recent" -> c.music.tracks.observeRecent(100)
        else -> c.music.playlists.observeTracks(playlistId ?: -1)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val playlistState = playlist.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val message = MutableStateFlow<String?>(null)
    val syncing = MutableStateFlow(false)

    val title = playlist.map { p ->
        p?.name ?: when (key) {
            "liked" -> "Oblíbené skladby"
            "downloaded" -> "Stažené"
            else -> "Historie"
        }
    }

    fun sync() {
        val id = playlistId ?: return
        viewModelScope.launch {
            syncing.value = true
            message.value = runCatching { c.imports.sync(id) }
                .fold({ if (it > 0) "Přidáno ${pluralTracks(it)}" else "Playlist je aktuální" }, { "Synchronizace selhala: ${it.message}" })
            syncing.value = false
        }
    }

    fun downloadAll(list: List<TrackEntity>) = viewModelScope.launch {
        playlistState.value?.let { c.music.updatePlaylist(it.copy(autoDownload = true)) }
        c.downloads.enqueue(list)
    }

    fun removeDownloads(list: List<TrackEntity>) = viewModelScope.launch {
        playlistState.value?.let { c.music.updatePlaylist(it.copy(autoDownload = false)) }
        list.forEach { c.downloads.delete(it) }
    }

    fun setAutoSync(enabled: Boolean) = viewModelScope.launch {
        playlistState.value?.let { c.music.updatePlaylist(it.copy(autoSync = enabled)) }
    }

    fun rename(name: String) = viewModelScope.launch { playlistId?.let { c.music.renamePlaylist(it, name) } }

    fun delete() = viewModelScope.launch { playlistId?.let { c.music.deletePlaylist(it) } }

    fun setCover(context: Context, image: Uri?) = viewModelScope.launch {
        val p = playlistState.value ?: return@launch
        val url = image?.let { runCatching { saveCoverImage(context, p.id, it) }.getOrNull() ?: return@launch }
        c.music.updatePlaylist(p.copy(artworkUrl = url))
    }

    fun reorder(ids: List<String>) = viewModelScope.launch { playlistId?.let { c.music.playlists.reorder(it, ids) } }

    fun remove(trackId: String) = viewModelScope.launch { playlistId?.let { c.music.removeFromPlaylist(it, trackId) } }

    fun share(context: Context) = viewModelScope.launch {
        playlistId?.let { runCatching { sharePlaylist(context, c, it) } }
    }
}

private enum class SortMode(val label: String) {
    CUSTOM("Vlastní pořadí"),
    RECENT("Naposledy přidané"),
    TITLE("Název"),
    ARTIST("Interpret"),
    PLAYS("Nejposlouchanější"),
}

@Composable
fun PlaylistScreen(key: String, contentPadding: PaddingValues, onBack: () -> Unit) {
    val vm = vibeViewModel(key = "playlist-$key") { PlaylistViewModel(it, key) }
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val playlist by vm.playlistState.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle("")
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val player by LocalContainer.current.player.state.collectAsStateWithLifecycle()
    val progress by LocalContainer.current.downloads.progress.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()
    val menu = rememberTrackMenuState()
    val listState = rememberLazyListState()

    val list = tracks.orEmpty()
    val artworkUrl = playlist?.artworkUrl ?: list.firstOrNull()?.artworkUrl.takeIf { key.toLongOrNull() != null }
    val headerColor by rememberArtworkColor(
        artworkUrl,
        fallback = when (key) {
            "liked" -> Color(0xFF4C1D95)
            "downloaded" -> Color(0xFF243A8C)
            else -> Color(0xFF2A2A38)
        },
    )
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 700 } }
    val topBarAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "topbar")

    var showMore by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveDownloads by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SortMode.CUSTOM) }
    var showSort by remember { mutableStateOf(false) }
    var editList by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setCover(context, uri)
    }
    val isPlaylist = key.toLongOrNull() != null

    // Co se zobrazí a přehraje: v režimu úprav pracovní kopie, jinak filtr + řazení
    val display = if (editing) editList else remember(list, filter, sort) {
        val q = filter.trim()
        val filtered = if (q.isEmpty()) list else list.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }
        when (sort) {
            SortMode.CUSTOM -> filtered
            SortMode.RECENT -> filtered.reversed()
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            SortMode.PLAYS -> filtered.sortedByDescending { it.playCount }
        }
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = editList.indexOfFirst { it.id == from.key }
        val toIndex = editList.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            editList = editList.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(headerColor, VibeColors.Background)))
                        .statusBarsPadding()
                        .padding(top = 56.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val artModifier = Modifier.size(220.dp).shadow(24.dp, RoundedCornerShape(8.dp))
                    when (key) {
                        "liked" -> GradientTile(Icons.Rounded.Favorite, LikedGradient, artModifier, 72.dp)
                        "downloaded" -> GradientTile(Icons.Rounded.FileDownloadDone, DownloadedGradient, artModifier, 72.dp)
                        "recent" -> GradientTile(Icons.Rounded.History, listOf(Color(0xFF3A3A48), Color(0xFF6A6A80)), artModifier, 72.dp)
                        else -> playlist?.let { PlaylistArtwork(it, artModifier, RoundedCornerShape(8.dp)) }
                            ?: Artwork(artworkUrl, artModifier, RoundedCornerShape(8.dp), Icons.AutoMirrored.Rounded.QueueMusic)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    val source = when (playlist?.source) {
                        PlaylistSource.SPOTIFY -> "Ze Spotify • "
                        PlaylistSource.YOUTUBE -> "Z YouTube • "
                        PlaylistSource.SOUNDCLOUD -> "Ze SoundCloudu • "
                        else -> ""
                    }
                    Text(
                        source + pluralTracks(list.size) + if (list.isNotEmpty()) " • " + formatTotal(list.sumOf { it.durationSec }) else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    }

                    // Akce
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val allDownloaded = list.isNotEmpty() && list.all { it.isDownloaded }
                        if (key != "downloaded") {
                            IconButton(onClick = { if (allDownloaded) confirmRemoveDownloads = true else vm.downloadAll(list) }, enabled = list.isNotEmpty()) {
                                Icon(
                                    if (allDownloaded) Icons.Rounded.DownloadForOffline else Icons.Rounded.Download,
                                    if (allDownloaded) "Staženo" else "Stáhnout vše",
                                    tint = if (allDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        if (playlist?.sourceUrl != null) {
                            IconButton(onClick = vm::sync, enabled = !syncing) {
                                if (syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.Sync, "Synchronizovat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (playlist != null) {
                            Box {
                                IconButton(onClick = { showMore = true }) {
                                    Icon(Icons.Rounded.MoreVert, "Více", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Přejmenovat") },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                        onClick = { showMore = false; renaming = true },
                                    )
                                    if (playlist?.sourceUrl != null) {
                                        DropdownMenuItem(
                                            text = { Text("Automatická synchronizace") },
                                            leadingIcon = { Icon(Icons.Rounded.Sync, null) },
                                            trailingIcon = { if (playlist?.autoSync == true) Icon(Icons.Rounded.Check, null) },
                                            onClick = { showMore = false; vm.setAutoSync(playlist?.autoSync != true) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Změnit obal") },
                                        leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                        onClick = {
                                            showMore = false
                                            coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        },
                                    )
                                    if (playlist?.artworkUrl != null) {
                                        DropdownMenuItem(
                                            text = { Text("Automatický obal (koláž)") },
                                            leadingIcon = { Icon(Icons.Rounded.GridView, null) },
                                            onClick = { showMore = false; vm.setCover(context, null) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Upravit pořadí") },
                                        leadingIcon = { Icon(Icons.Rounded.SwapVert, null) },
                                        onClick = {
                                            showMore = false
                                            filter = ""
                                            searching = false
                                            sort = SortMode.CUSTOM
                                            editList = list
                                            editing = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sdílet s kamarády") },
                                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                        onClick = { showMore = false; vm.share(context) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Smazat playlist") },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                        onClick = { showMore = false; confirmDelete = true },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { actions.startMix(display) }, enabled = list.isNotEmpty()) {
                            Icon(Icons.Rounded.Radio, "Rádio z playlistu", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { actions.shuffleLoop(display) }, enabled = list.isNotEmpty()) {
                            Icon(Icons.Rounded.Shuffle, "Náhodně", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { actions.play(display) },
                            enabled = list.isNotEmpty(),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, "Přehrát", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }

            if (editing) {
                item(key = "edit-bar") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Přetáhni skladby za úchyt ≡",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = {
                            vm.reorder(editList.map { it.id })
                            editing = false
                        }) { Text("Hotovo") }
                    }
                }
            } else if (list.size >= 2) {
                item(key = "tools") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (searching) {
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                placeholder = { Text("Hledat v playlistu") },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                trailingIcon = {
                                    IconButton(onClick = { filter = ""; searching = false }) { Icon(Icons.Rounded.Close, "Zavřít") }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                        } else {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Rounded.Search, "Hledat v playlistu", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        Box {
                            TextButton(onClick = { showSort = true }) {
                                Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(sort.label)
                            }
                            DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        trailingIcon = { if (sort == mode) Icon(Icons.Rounded.Check, null) },
                                        onClick = { sort = mode; showSort = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (tracks != null && list.isEmpty()) {
                item {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        "Zatím prázdné",
                        when (key) {
                            "liked" -> "Skladby označené srdíčkem se objeví tady."
                            "downloaded" -> "Stažené skladby můžeš poslouchat i bez internetu."
                            "recent" -> "Tady uvidíš, co jsi poslouchal."
                            else -> "Přidej skladby přes menu ⋮ u libovolné skladby."
                        },
                    )
                }
            }
            if (editing) {
                items(editList, key = { it.id }) { t ->
                    ReorderableItem(reorderState, key = t.id) { dragging ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                editList = editList.filter { it.id != t.id }
                                vm.remove(t.id)
                            }) { Icon(Icons.Rounded.RemoveCircleOutline, "Odebrat", tint = MaterialTheme.colorScheme.error) }
                            Artwork(t.artworkUrl, Modifier.size(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(
                                Icons.Rounded.DragHandle, "Přesunout",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.draggableHandle().padding(12.dp),
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(display, key = { _, t -> t.id }) { i, t ->
                    TrackRow(
                        track = t,
                        isCurrent = player.current?.mediaId == t.id,
                        downloadProgress = progress[t.id],
                        onClick = { actions.play(display, i) },
                        onMenu = { menu.open(t, key.toLongOrNull()) },
                    )
                }
            }
        }

        // Horní lišta – při scrollu se zabarví a ukáže název
        Row(
            Modifier
                .fillMaxWidth()
                .background(headerColor.copy(alpha = topBarAlpha))
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zpět") }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(topBarAlpha).weight(1f),
            )
        }
    }

    TrackMenuSheet(menu)

    if (renaming) {
        var name by rememberSaveable { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Přejmenovat playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(name.trim()); renaming = false }) { Text("Uložit") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Zrušit") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Smazat playlist?") },
            text = { Text("Skladby zůstanou v knihovně (Oblíbené, Stažené).") },
            confirmButton = { TextButton(onClick = { vm.delete(); confirmDelete = false; onBack() }) { Text("Smazat") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušit") } },
        )
    }
    if (confirmRemoveDownloads) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownloads = false },
            icon = { Icon(Icons.Rounded.CheckCircle, null) },
            title = { Text("Odebrat stažené?") },
            text = { Text("Skladby z tohoto playlistu se smažou ze zařízení. Dál je půjde streamovat.") },
            confirmButton = { TextButton(onClick = { vm.removeDownloads(list); confirmRemoveDownloads = false }) { Text("Odebrat") } },
            dismissButton = { TextButton(onClick = { confirmRemoveDownloads = false }) { Text("Zrušit") } },
        )
    }
}
