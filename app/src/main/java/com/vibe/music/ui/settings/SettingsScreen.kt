package com.vibe.music.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.net.Uri
import com.vibe.music.STREAM_CACHE_BYTES
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.BuildConfig
import com.vibe.music.data.AudioFormat
import com.vibe.music.data.PlaybackMode
import com.vibe.music.data.Settings
import com.vibe.music.data.YtDlpChannel
import com.vibe.music.ui.common.formatBytes
import com.vibe.music.ui.common.vibeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val ytDlpVersion = c.ytDlp.version
    val updateStatus = MutableStateFlow<String?>(null)
    val updating = MutableStateFlow(false)
    val storage = MutableStateFlow(0L)
    val cacheBytes = MutableStateFlow(0L)
    val message = MutableStateFlow<String?>(null)
    val diagnostics = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val testing = MutableStateFlow(false)

    init {
        refreshStorage()
        viewModelScope.launch { runCatching { c.ytDlp.ensureReady() } }
    }

    fun refreshStorage() = viewModelScope.launch {
        storage.value = withContext(Dispatchers.IO) { c.downloads.storageUsedBytes() }
        cacheBytes.value = withContext(Dispatchers.IO) { runCatching { c.streamCache.cacheSpace }.getOrDefault(0L) }
        refreshDiagnostics()
    }

    fun clearCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching { c.streamCache.keys.toList().forEach { c.streamCache.removeResource(it) } }
        }
        refreshStorage()
    }

    fun setNormalize(v: Boolean) = viewModelScope.launch { c.settings.setNormalizeVolume(v) }
    fun setFade(v: Boolean) = viewModelScope.launch { c.settings.setFadeTransitions(v) }
    fun setCrossfade(v: Int) = viewModelScope.launch { c.settings.setCrossfadeSec(v) }
    fun setPreload(v: Boolean) = viewModelScope.launch { c.settings.setPreloadNext(v) }
    fun setAutoDownloadLiked(v: Boolean) = viewModelScope.launch { c.settings.setAutoDownloadLiked(v) }
    fun setSoundCloud(v: Boolean) = viewModelScope.launch { c.settings.setSoundCloudEnabled(v) }

    fun exportBackup(uri: Uri, context: Context) = viewModelScope.launch {
        message.value = runCatching {
            val json = withContext(Dispatchers.IO) { c.backup.exportLibrary() }
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                    ?: error("Soubor nelze zapsat")
            }
            "Záloha uložena ✓"
        }.getOrElse { "Záloha selhala: ${it.message}" }
    }

    fun restoreBackup(uri: Uri, context: Context) = viewModelScope.launch {
        message.value = runCatching {
            val json = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Soubor nelze přečíst")
            }
            val r = withContext(Dispatchers.IO) { c.backup.restore(json) }
            "Obnoveno: ${r.playlists.size} playlistů, ${r.tracks} skladeb ✓"
        }.getOrElse { "Obnova selhala: ${it.message}" }
    }

    private fun refreshDiagnostics() {
        diagnostics.value = listOf(
            "YouTube Music klient" to c.ytMusic.clientVersion,
            "YouTube Music API" to (c.ytMusic.lastError ?: "v pořádku"),
            "yt-dlp" to (c.ytDlp.lastError ?: "v pořádku"),
            "Aktualizace yt-dlp" to (c.ytDlp.lastUpdateError ?: "v pořádku"),
            "Přehrávání" to (c.lastPlaybackError ?: "v pořádku"),
        )
    }

    /** Vyzkouší celý řetězec: hledání → stream přes yt-dlp → rádio. */
    fun runTest() = viewModelScope.launch {
        testing.value = true
        val results = mutableListOf<Pair<String, String>>()
        val start = System.currentTimeMillis()
        val track = runCatching {
            c.ytMusic.refreshClientVersion(force = true)
            c.ytMusic.searchSongs("Daft Punk Get Lucky").first()
        }
            .onSuccess { results += "Hledání (YouTube Music)" to "✓ ${System.currentTimeMillis() - start} ms" }
            .onFailure { results += "Hledání (YouTube Music)" to "✗ ${it.message}" }
            .getOrNull()
            ?: runCatching { c.ytDlp.search("Daft Punk Get Lucky", 1).first() }
                .onSuccess { results += "Hledání (záloha yt-dlp)" to "✓" }
                .onFailure { results += "Hledání (záloha yt-dlp)" to "✗ ${it.message}" }
                .getOrNull()
        if (track != null) {
            val t = System.currentTimeMillis()
            val stream = runCatching { c.ytDlp.stream(track.id, settings.value.audioFormat.ytDlpFormat) }
                .onSuccess { results += "Stream (yt-dlp)" to "✓ ${(System.currentTimeMillis() - t) / 100 / 10.0} s" }
                .onFailure { results += "Stream (yt-dlp)" to "✗ ${it.message?.take(200)}" }
                .getOrNull()
            // Stejnou cestou jako přehrávač stáhnout prvních 64 kB – odhalí 403 / IP / hlavičky
            if (stream != null) {
                results += "Přehrání (data)" to withContext(Dispatchers.IO) {
                    runCatching {
                        val request = okhttp3.Request.Builder()
                            .url(stream.url)
                            .apply { stream.headers.forEach { (k, v) -> header(k, v) } }
                            .header("Range", "bytes=0-65535")
                            .build()
                        c.streamHttp.newCall(request).execute().use { r ->
                            if (r.code == 200 || r.code == 206) "✓ HTTP ${r.code}, ${r.body.bytes().size / 1024} kB"
                            else "✗ HTTP ${r.code}"
                        }
                    }.getOrElse { "✗ ${it.message}" }
                }
            }
            runCatching { c.radio.similar(track.id).size }
                .onSuccess { results += "Rádio" to "✓ $it skladeb" }
                .onFailure { results += "Rádio" to "✗ ${it.message}" }
        }
        refreshDiagnostics()
        diagnostics.value = results + diagnostics.value
        testing.value = false
    }

    fun setPlaybackMode(m: PlaybackMode) = viewModelScope.launch { c.settings.setPlaybackMode(m) }
    fun setAudioFormat(f: AudioFormat) = viewModelScope.launch { c.settings.setAudioFormat(f) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch {
        c.settings.setWifiOnly(v)
        c.downloads.kick(restart = true)
    }
    fun setAutoDownloadImports(v: Boolean) = viewModelScope.launch { c.settings.setAutoDownloadImports(v) }
    fun setAutoUpdate(v: Boolean) = viewModelScope.launch { c.settings.setYtDlpAutoUpdate(v) }
    fun setChannel(ch: YtDlpChannel) = viewModelScope.launch { c.settings.setYtDlpChannel(ch) }
    fun setAutoplay(v: Boolean) = viewModelScope.launch { c.settings.setAutoplay(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(v) }
    fun saveSpotify(id: String, secret: String) = viewModelScope.launch { c.settings.setSpotifyCredentials(id, secret) }

    fun updateYtDlp() = viewModelScope.launch {
        updating.value = true
        updateStatus.value = runCatching {
            val updated = c.ytDlp.update(settings.value.ytDlpChannel)
            c.settings.setYtDlpLastCheck(System.currentTimeMillis())
            if (updated) "Aktualizováno ✓" else "Máš nejnovější verzi"
        }.getOrElse { "Aktualizace selhala: ${it.message}" }
        updating.value = false
    }

    fun deleteAllDownloads() = viewModelScope.launch {
        c.downloads.deleteAll()
        refreshStorage()
    }
}

@Composable
fun SettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenEqualizer: () -> Unit) {
    val vm = vibeViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val version by vm.ytDlpVersion.collectAsStateWithLifecycle()
    val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportBackup(it, context) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.restoreBackup(it, context) }
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zpět") }
                Text("Nastavení", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item { Header("Přehrávání") }
        PlaybackMode.entries.forEach { mode ->
            item { RadioRow(mode.label, mode.description, s.playbackMode == mode) { vm.setPlaybackMode(mode) } }
        }
        item {
            SwitchRow(
                "Automatické pokračování (rádio)",
                "Po dohrání fronty hrát dál podobné skladby",
                s.autoplay,
                vm::setAutoplay,
            )
        }
        item {
            SwitchRow(
                "Vyrovnat hlasitost",
                "Hlasité skladby ztiší, tiché mírně zesílí – žádné skoky v hlasitosti",
                s.normalizeVolume,
                vm::setNormalize,
            )
        }
        item {
            SwitchRow(
                "Plynulé přechody",
                "Jemné zesílení na začátku a zeslabení na konci skladby",
                s.fadeTransitions,
                vm::setFade,
            )
        }
        item { CrossfadeRow(s.crossfadeSec, vm::setCrossfade) }
        item {
            SwitchRow(
                "Předem načíst další skladbu",
                "Přeskočení je okamžité i ve slabém signálu (stáhne o skladbu dopředu)",
                s.preloadNext,
                vm::setPreload,
            )
        }
        item { NavRow("Ekvalizér", if (s.eqEnabled) "Zapnuto" else "Vypnuto", onOpenEqualizer) }
        item { Header("Formát zvuku") }
        AudioFormat.entries.forEach { f ->
            item { RadioRow(f.label, null, s.audioFormat == f) { vm.setAudioFormat(f) } }
        }

        item { Header("Stahování") }
        item { SwitchRow("Stahovat jen přes Wi-Fi", "Šetří mobilní data", s.wifiOnlyDownloads, vm::setWifiOnly) }
        item {
            SwitchRow(
                "Automaticky stahovat importy",
                "Nové importované playlisty se rovnou stáhnou offline",
                s.autoDownloadImports,
                vm::setAutoDownloadImports,
            )
        }
        item {
            SwitchRow(
                "Stahovat oblíbené",
                "Co označíš srdíčkem, to se samo uloží offline",
                s.autoDownloadLiked,
                vm::setAutoDownloadLiked,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mezipaměť streamů", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${formatBytes(cacheBytes)} z ${formatBytes(STREAM_CACHE_BYTES)} – opakované přehrání bez internetu",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = vm::clearCache, enabled = cacheBytes > 0) { Text("Vymazat") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Využité úložiště", style = MaterialTheme.typography.bodyLarge)
                    Text(formatBytes(storage), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { confirmDelete = true }, enabled = storage > 0) { Text("Smazat vše") }
            }
        }

        item { Header("Stahovací engine (yt-dlp)") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Verze: ${version ?: "načítám…"}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Poslední kontrola: " + if (s.ytDlpLastCheck > 0)
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.ytDlpLastCheck))
                    else "zatím nikdy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = vm::updateYtDlp, enabled = !updating) {
                        if (updating) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Zkontrolovat aktualizace")
                    }
                    updateStatus?.let {
                        Spacer(Modifier.width(12.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            SwitchRow(
                "Automatické aktualizace",
                "Kontrola při spuštění a každých 12 h na Wi-Fi. YouTube často mění web – čerstvé yt-dlp je klíčové.",
                s.ytDlpAutoUpdate,
                vm::setAutoUpdate,
            )
        }
        YtDlpChannel.entries.forEach { ch ->
            item { RadioRow(ch.label, null, s.ytDlpChannel == ch) { vm.setChannel(ch) } }
        }

        item { Header("Zdroje") }
        item {
            SwitchRow(
                "SoundCloud",
                "Výsledky ze SoundCloudu v hledání a jako náhrada 18+ videí. Import SoundCloud odkazů funguje vždy.",
                s.soundCloudEnabled,
                vm::setSoundCloud,
            )
        }

        item { Header("Knihovna") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Záloha obsahuje skladby, oblíbené, playlisty i statistiky. Hodí se při přechodu na nový telefon.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { exportLauncher.launch("vibe-zaloha-${java.time.LocalDate.now()}.json") }) { Text("Zálohovat") }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
                        Text("Obnovit ze zálohy")
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }

        item { Header("Spotify (volitelné)") }
        item { SpotifyCredentials(s, vm::saveSpotify) }

        item { Header("Diagnostika") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                diagnostics.forEach { (k, v) ->
                    Row {
                        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.45f))
                        Text(
                            v,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                v.startsWith("✗") -> MaterialTheme.colorScheme.error
                                v.startsWith("✓") || v == "v pořádku" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(0.55f),
                        )
                    }
                }
                OutlinedButton(onClick = vm::runTest, enabled = !testing) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (testing) "Testuji…" else "Otestovat vše")
                }
            }
        }

        item { Header("Vzhled") }
        item {
            SwitchRow("Barvy podle systému", "Material You (Android 12+)", s.dynamicColor, vm::setDynamicColor)
        }

        item {
            Text(
                "Vibe ${BuildConfig.VERSION_NAME} • soukromý projekt (nedokončený)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Smazat všechny stažené skladby?") },
            text = { Text("Skladby zůstanou v knihovně a půjdou dál streamovat.") },
            confirmButton = { TextButton(onClick = { vm.deleteAllDownloads(); confirmDelete = false }) { Text("Smazat") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun SpotifyCredentials(s: Settings, onSave: (String, String) -> Unit) {
    var id by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(s.spotifyClientId, s.spotifyClientSecret) {
        id = s.spotifyClientId
        secret = s.spotifyClientSecret
    }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Bez klíče se z playlistu načte max. 100 skladeb. Pro neomezený import si zdarma vytvoř aplikaci na developer.spotify.com (Dashboard → Create app) a vlož Client ID a Secret.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(id, { id = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        OutlinedTextField(
            secret, { secret = it },
            label = { Text("Client Secret") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        val changed = id != s.spotifyClientId || secret != s.spotifyClientSecret
        Button(onClick = { onSave(id, secret) }, enabled = changed) { Text(if (changed) "Uložit" else "Uloženo") }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Spacer(Modifier.height(0.dp))
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CrossfadeRow(seconds: Int, onChange: (Int) -> Unit) {
    var value by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prolínání skladeb (crossfade)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Konec skladby se prolne se začátkem další, jako na Spotify",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (value.toInt() == 0) "Vyp." else "${value.toInt()} s",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(value.toInt()) },
            valueRange = 0f..12f,
            steps = 11,
        )
    }
}
