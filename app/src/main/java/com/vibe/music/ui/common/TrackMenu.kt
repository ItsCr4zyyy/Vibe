package com.vibe.music.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibe.music.data.db.DownloadState
import com.vibe.music.data.db.TrackEntity

/** Spodní menu skladby. Vlož jednou do obrazovky a otevírej přes [TrackMenuState.open]. */
@Composable
fun TrackMenuSheet(state: TrackMenuState) {
    val initial = state.track ?: return
    val c = LocalContainer.current
    val actions = rememberTrackActions()
    val navigator = LocalNavigator.current
    // Aktuální stav z DB (like / stažení); skladba z hledání v DB být nemusí
    val stored by remember(initial.id) { c.music.tracks.observe(initial.id) }.collectAsState(null)
    val track = stored ?: initial
    var pickPlaylist by remember { mutableStateOf(false) }

    if (pickPlaylist) {
        AddToPlaylistDialog(
            tracks = listOf(track),
            onDismiss = { pickPlaylist = false; state.close() },
        )
        return
    }

    ModalBottomSheet(onDismissRequest = state::close) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.artworkUrl, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            fun act(block: () -> Unit) {
                block()
                state.close()
            }

            MenuItem(Icons.Rounded.Radio, "Spustit rádio") { act { actions.startRadio(track) } }
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Přehrát jako další") { act { actions.playNext(track) } }
            MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "Přidat do fronty") { act { actions.addToQueue(track) } }
            if (track.liked) {
                MenuItem(Icons.Rounded.Favorite, "Odebrat z Oblíbených") { act { actions.setLiked(track, false) } }
            } else {
                MenuItem(Icons.Rounded.FavoriteBorder, "Přidat do Oblíbených") { act { actions.setLiked(track, true) } }
            }
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Přidat do playlistu") { pickPlaylist = true }
            when (track.downloadState) {
                DownloadState.DONE ->
                    MenuItem(Icons.Rounded.Delete, "Smazat ze zařízení") { act { actions.deleteDownload(track) } }
                DownloadState.QUEUED, DownloadState.DOWNLOADING ->
                    MenuItem(Icons.Rounded.Close, "Zrušit stahování") { act { actions.cancelDownload(track) } }
                else ->
                    MenuItem(Icons.Rounded.Download, "Stáhnout") { act { actions.download(listOf(track)) } }
            }
            MenuItem(Icons.Rounded.Person, "Přejít na interpreta") { act { navigator.artist(track.artistId, track.artist) } }
            if (track.albumId != null || track.album != null) {
                MenuItem(Icons.Rounded.Album, "Přejít na album") { act { navigator.album(track.albumId, track.album, track.artist) } }
            }
            state.playlistId?.let { pid ->
                MenuItem(Icons.Rounded.RemoveCircleOutline, "Odebrat z tohoto playlistu") {
                    act { actions.removeFromPlaylist(pid, track) }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AddToPlaylistDialog(tracks: List<TrackEntity>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val actions = rememberTrackActions()
    val playlists by remember { c.music.playlists.observeSummaries() }.collectAsState(emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "Nový playlist" else "Přidat do playlistu") },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("Název") }, singleLine = true)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text("Nový playlist", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    items(playlists, key = { it.playlist.id }) { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    actions.addToPlaylist(p.playlist.id, tracks)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(p.playlist.artworkUrl, Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(p.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pluralTracks(p.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    actions.createPlaylistWith(name.trim(), tracks)
                    onDismiss()
                }) { Text("Vytvořit") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
