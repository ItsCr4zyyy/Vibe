package com.vibe.music.ui.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.data.ImportLink
import com.vibe.music.data.ImportProgress
import com.vibe.music.data.ImportResult
import com.vibe.music.ui.common.pluralTracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportUi {
    data object Idle : ImportUi
    data class Running(val progress: ImportProgress) : ImportUi
    data class Done(val result: ImportResult) : ImportUi
    data class Failed(val message: String) : ImportUi
}

/** Drží se na úrovni aktivity, takže import běží dál i po zavření panelu. */
class ImportViewModel(private val c: AppContainer) : ViewModel() {
    val ui = MutableStateFlow<ImportUi>(ImportUi.Idle)

    fun start(text: String) {
        if (ui.value is ImportUi.Running) return
        val link = ImportLink.parse(text)
        if (link == null) {
            ui.value = ImportUi.Failed("Tohle nevypadá jako odkaz na YouTube ani Spotify.")
            return
        }
        ui.value = ImportUi.Running(ImportProgress("Začínám…"))
        viewModelScope.launch {
            ui.value = runCatching {
                c.imports.import(link) { p ->
                    ui.update { cur ->
                        // Paralelní párování může hlásit pokrok mimo pořadí – nevracet se zpět
                        if (cur is ImportUi.Running && cur.progress.total == p.total && cur.progress.done > p.done) cur
                        else ImportUi.Running(p)
                    }
                }
            }.fold(
                { ImportUi.Done(it) },
                { ImportUi.Failed(it.message ?: "Import selhal") },
            )
        }
    }

    fun reset() {
        if (ui.value !is ImportUi.Running) ui.value = ImportUi.Idle
    }
}

@Composable
fun ImportSheet(
    vm: ImportViewModel,
    initialText: String,
    onOpenPlaylist: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(initialText) }
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Importovat", style = MaterialTheme.typography.headlineSmall)

            when (val s = ui) {
                ImportUi.Idle, is ImportUi.Failed -> {
                    Text(
                        "Vlož odkaz na playlist, album, set nebo skladbu. Odkaz můžeš do Vibe poslat i přes „Sdílet“ v aplikaci YouTube nebo Spotify.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceChip("YouTube", Color(0xFFFF0033))
                        SourceChip("Spotify", Color(0xFF1ED760))
                        SourceChip("SoundCloud", Color(0xFFFF7700))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Vlož odkaz…", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.Link, null) },
                        trailingIcon = {
                            IconButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                                Icon(Icons.Rounded.ContentPaste, "Vložit")
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (s is ImportUi.Failed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(
                        onClick = { vm.start(text) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(26.dp),
                    ) { Text("Importovat", fontWeight = FontWeight.Bold) }
                }

                is ImportUi.Running -> {
                    val p = s.progress
                    Text(p.message, style = MaterialTheme.typography.titleMedium)
                    if (p.total > 0) {
                        LinearProgressIndicator(
                            progress = { p.done.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth().clip(CircleShape),
                        )
                        Text("${p.done} / ${p.total}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    }
                    Text(
                        "Panel můžeš zavřít – import poběží na pozadí.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ImportUi.Done -> {
                    val r = s.result
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(r.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (r.playlistId == null) "Skladba přidána do Oblíbených" else "Přidáno ${pluralTracks(r.added)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    r.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    if (r.notFound.isNotEmpty()) {
                        Text("Nenalezeno (${r.notFound.size}):", style = MaterialTheme.typography.titleSmall)
                        LazyColumn(
                            Modifier
                                .heightIn(max = 160.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(10.dp),
                        ) {
                            items(r.notFound) {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { text = ""; vm.reset() }, modifier = Modifier.weight(1f)) { Text("Další import") }
                        r.playlistId?.let { id ->
                            Button(onClick = { vm.reset(); onOpenPlaylist(id) }, modifier = Modifier.weight(1f)) { Text("Otevřít") }
                        } ?: TextButton(onClick = { vm.reset(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Zavřít") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, color: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}
