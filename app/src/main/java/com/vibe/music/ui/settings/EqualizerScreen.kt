package com.vibe.music.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.music.data.Settings
import com.vibe.music.ui.common.LocalContainer
import kotlinx.coroutines.launch

@Composable
fun EqualizerScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val c = LocalContainer.current
    val info by c.equalizer.info.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(Settings())
    // Lokální kopie úrovní, aby posuvníky reagovaly plynule; uloží se po puštění
    val levels = remember { mutableStateListOf<Int>() }
    LaunchedEffect(info?.levels) {
        info?.levels?.let {
            levels.clear()
            levels.addAll(it)
        }
    }
    fun save() = c.appScope.launch { c.settings.setEqLevels(levels.toList()) }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zpět") }
                Text("Ekvalizér", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.eqEnabled,
                    onCheckedChange = { v -> c.appScope.launch { c.settings.setEqEnabled(v) } },
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }
        val eq = info
        if (eq == null) {
            item {
                Text(
                    "Ekvalizér bude dostupný, jakmile se spustí přehrávání. Pusť libovolnou skladbu a vrať se sem.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            return@LazyColumn
        }
        item {
            Text("Předvolby", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    EqChip("Vlastní", settings.eqPreset < 0) { save() }
                }
                items(eq.presets.size) { i ->
                    EqChip(eq.presets[i], settings.eqPreset == i) {
                        c.appScope.launch { c.settings.setEqPreset(i) }
                    }
                }
            }
        }
        item {
            Text("Pásma", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp))
        }
        items(eq.bands.size) { i ->
            val band = eq.bands[i]
            val value = levels.getOrNull(i) ?: 0
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (band.centerHz >= 1000) "${band.centerHz / 1000} kHz" else "${band.centerHz} Hz",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(72.dp),
                    )
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { v -> if (i < levels.size) levels[i] = v.toInt() },
                        onValueChangeFinished = { save() },
                        valueRange = eq.minLevel.toFloat()..eq.maxLevel.toFloat(),
                        enabled = settings.eqEnabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%+.0f dB".format(value / 100f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp).padding(start = 8.dp),
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    for (i in levels.indices) levels[i] = 0
                    save()
                },
                enabled = settings.eqEnabled,
                modifier = Modifier.padding(20.dp),
            ) { Text("Vynulovat") }
        }
    }
}

@Composable
private fun EqChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = MaterialTheme.shapes.extraLarge,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
