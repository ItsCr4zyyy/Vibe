package com.vibe.music.ui.stats

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.Navigator
import com.vibe.music.ui.common.vibeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class WrappedData(
    val year: Int,
    val minutes: Long,
    val tracks: List<TrackStat>,
    val artists: List<ArtistStat>,
    val daysListened: Int,
    val topMonth: String?,
    val loaded: Boolean,
)

class WrappedViewModel(c: AppContainer) : ViewModel() {
    private val year = LocalDate.now().year
    private val from = LocalDate.of(year, 1, 1).toString()

    val data = combine(c.db.stats().tracksSince(from), c.db.stats().perDay(from)) { tracks, days ->
        val artists = tracks.groupBy { Navigator.mainArtist(it.track.artist) }
            .map { (name, list) -> ArtistStat(name, list.sumOf { it.listenedMs }, list.maxBy { it.listenedMs }.track.artworkUrl, null) }
            .sortedByDescending { it.ms }
        val topMonth = days.groupBy { it.day.substring(0, 7) }
            .maxByOrNull { (_, d) -> d.sumOf { it.ms } }?.key
            ?.let { LocalDate.parse("$it-01").month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("cs")) }
        WrappedData(
            year = year,
            minutes = tracks.sumOf { it.listenedMs } / 60_000,
            tracks = tracks.map { TrackStat(it.track, it.listenedMs) },
            artists = artists,
            daysListened = days.count { it.ms > 0 },
            topMonth = topMonth,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WrappedData(LocalDate.now().year, 0, emptyList(), emptyList(), 0, null, false))
}

private val fmt: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("cs"))

private val slideGradients = listOf(
    listOf(Color(0xFF3A2470), Color(0xFF6B2A6E)),
    listOf(Color(0xFF7A1F4D), Color(0xFFE85D2A)),
    listOf(Color(0xFF1E2A78), Color(0xFFB79CFF)),
    listOf(Color(0xFF3B2A7A), Color(0xFFEC4899)),
    listOf(Color(0xFF14213D), Color(0xFFFCA311)),
    listOf(Color(0xFF2D0B59), Color(0xFF8B5CF6)),
    listOf(Color(0xFF0C0B10), Color(0xFF3A2470)),
)

/** „Vibe Wrapped“ – roční shrnutí jako příběh; ťuknutím vpravo/vlevo se listuje. */
@Composable
fun WrappedScreen(onClose: () -> Unit) {
    val vm = vibeViewModel { WrappedViewModel(it) }
    val d by vm.data.collectAsStateWithLifecycle()
    var page by remember { mutableIntStateOf(0) }
    val pages = 6
    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(slideGradients[page % slideGradients.size]))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    page = if (offset.x < size.width / 3) (page - 1).coerceAtLeast(0) else (page + 1).coerceAtMost(pages - 1)
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            // Ukazatel postupu jako u příběhů
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(pages) { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (i <= page) 0.95f else 0.3f))
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VIBE WRAPPED ${d.year}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Zavřít", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (d.loaded && d.minutes == 0L) {
                    BigText("Letos zatím nic", "Pusť si něco a tvůj Wrapped se začne plnit.")
                } else {
                    AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "wrapped") { p ->
                        when (p) {
                            0 -> BigText("Tvůj rok ${d.year}\nna Vibe", "Ťukni a projdi si ho →")
                            1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Letos jsi poslouchal", color = Color.White, style = MaterialTheme.typography.titleLarge)
                                Text(fmt.format(d.minutes), color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.Black)
                                Text("minut", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Hudbu sis pustil " + when (d.daysListened) { 1 -> "1 den"; in 2..4 -> "${d.daysListened} dny"; else -> "${d.daysListened} dní" } + (d.topMonth?.let { "\nnejvíc v měsíci $it" } ?: ""),
                                    color = Color.White.copy(alpha = 0.85f),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            2 -> d.artists.firstOrNull()?.let { top ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Tvůj interpret roku", color = Color.White, style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.height(20.dp))
                                    Artwork(top.artworkUrl, Modifier.size(220.dp), CircleShape, Icons.Rounded.Person)
                                    Spacer(Modifier.height(20.dp))
                                    Text(top.name, color = Color.White, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                                    Text("${fmt.format(top.ms / 60_000)} minut", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
                                }
                            } ?: BigText("Žádný interpret", "")
                            3 -> RankList("Top interpreti", d.artists.take(5).map { it.name to "${fmt.format(it.ms / 60_000)} min" })
                            4 -> RankList("Top skladby", d.tracks.take(5).map { "${it.track.title} – ${it.track.artist}" to "${fmt.format(it.ms / 60_000)} min" })
                            else -> ShareCard(d)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigText(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RankList(title: String, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        rows.forEachIndexed { i, (name, value) ->
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(44.dp))
                Column {
                    Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(value, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Shrnutí, které se dá sdílet jako obrázek (Instagram story, WhatsApp…). */
@Composable
private fun ShareCard(d: WrappedData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 14f)
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3A2470), Color(0xFF5B2A8C), Color(0xFF8C2A6E))))
                .padding(24.dp),
        ) {
            Text("VIBE WRAPPED ${d.year}", color = Color(0xFFB79CFF), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))
            Text(fmt.format(d.minutes), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black)
            Text("minut hudby", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            Text("TOP INTERPRETI", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            d.artists.take(3).forEachIndexed { i, a ->
                Text("${i + 1}. ${a.name}", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            Text("TOP SKLADBY", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            d.tracks.take(3).forEachIndexed { i, t ->
                Text("${i + 1}. ${t.track.title}", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Text("🎧 Vibe", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { scope.launch { shareBitmap(context, layer.toImageBitmap().asAndroidBitmap(), d.year) } },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Pochlubit se")
        }
    }
}

private suspend fun shareBitmap(context: Context, bitmap: Bitmap, year: Int) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "share").apply { mkdirs() }.resolve("vibe-wrapped-$year.png").apply {
            val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            outputStream().use { software.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Sdílet Wrapped").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
