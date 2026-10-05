package com.vibe.music.ui.stats

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.music.AppContainer
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.ui.common.Artwork
import com.vibe.music.ui.common.EmptyState
import com.vibe.music.ui.common.LocalNavigator
import com.vibe.music.ui.common.Navigator
import com.vibe.music.ui.common.SectionTitle
import com.vibe.music.ui.common.rememberTrackActions
import com.vibe.music.ui.common.vibeViewModel
import com.vibe.music.ui.theme.VibeColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

enum class StatsPeriod(val label: String) {
    WEEK("Tento týden"),
    MONTH("Tento měsíc"),
    YEAR("Tento rok"),
    ALL("Celkem");

    fun from(): String = when (this) {
        WEEK -> LocalDate.now().with(DayOfWeek.MONDAY).toString()
        MONTH -> LocalDate.now().withDayOfMonth(1).toString()
        YEAR -> LocalDate.now().withDayOfYear(1).toString()
        ALL -> "0000-00-00"
    }
}

data class ArtistStat(val name: String, val ms: Long, val artworkUrl: String?, val artistId: String?)
data class TrackStat(val track: TrackEntity, val ms: Long)

data class StatsState(
    val allTimeMs: Long = 0,
    val periodMs: Long = 0,
    val tracks: List<TrackStat> = emptyList(),
    val artists: List<ArtistStat> = emptyList(),
    val trackCount: Int = 0,
    val artistCount: Int = 0,
    /** Posledních 30 dní (nejstarší první), ms za den. */
    val last30: List<Long> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(c: AppContainer) : ViewModel() {
    val period = MutableStateFlow(StatsPeriod.WEEK)
    private val stats = c.db.stats()

    val state = period.flatMapLatest { p ->
        val since30 = LocalDate.now().minusDays(29)
        combine(
            stats.totalSince(StatsPeriod.ALL.from()),
            stats.tracksSince(p.from()),
            stats.perDay(since30.toString()),
        ) { allTime, tracks, perDay ->
            val artists = tracks
                .groupBy { Navigator.mainArtist(it.track.artist) }
                .map { (name, list) ->
                    ArtistStat(name, list.sumOf { it.listenedMs }, list.maxBy { it.listenedMs }.track.artworkUrl, list.firstNotNullOfOrNull { it.track.artistId })
                }
                .sortedByDescending { it.ms }
            val byDay = perDay.associate { it.day to it.ms }
            StatsState(
                allTimeMs = allTime,
                periodMs = tracks.sumOf { it.listenedMs },
                tracks = tracks.map { TrackStat(it.track, it.listenedMs) },
                artists = artists,
                trackCount = tracks.size,
                artistCount = artists.size,
                last30 = (0 until 30).map { byDay[since30.plusDays(it.toLong()).toString()] ?: 0L },
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsState())
}

private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("cs"))
private fun minutes(ms: Long) = ms / 60_000

@Composable
fun StatsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenWrapped: () -> Unit) {
    val vm = vibeViewModel { StatsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val actions = rememberTrackActions()
    val context = LocalContext.current

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFF3B2A8F), VibeColors.Background)))
                    .statusBarsPadding(),
            ) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zpět") }
                    Text("Tvoje statistiky", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val top = state.artists.firstOrNull()?.name
                        val text = buildString {
                            append("Na Vibe mám naposloucháno ${numberFormat.format(minutes(state.allTimeMs))} minut 🎧")
                            if (top != null) append("\nNejposlouchanější interpret: $top")
                        }
                        context.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Pochlubit se")
                        )
                    }) { Icon(Icons.Rounded.Share, "Sdílet") }
                }
                HeroNumber(state.allTimeMs)
                Button(
                    onClick = onOpenWrapped,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
                ) { Text("Vibe Wrapped ${LocalDate.now().year} ✨") }
            }
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(StatsPeriod.entries) { p ->
                    FilterChip(
                        selected = period == p,
                        onClick = { vm.period.value = p },
                        label = { Text(p.label) },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        if (state.loaded && state.allTimeMs == 0L) {
            item {
                EmptyState(Icons.Rounded.BarChart, "Zatím žádná data", "Statistiky se začnou počítat, jakmile si něco pustíš.")
            }
            return@LazyColumn
        }

        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Minut", numberFormat.format(minutes(state.periodMs)), Modifier.weight(1f))
                StatTile("Skladeb", numberFormat.format(state.trackCount), Modifier.weight(1f))
                StatTile("Interpretů", numberFormat.format(state.artistCount), Modifier.weight(1f))
            }
        }

        item { SectionTitle("Posledních 30 dní") }
        item { DailyChart(state.last30) }

        if (state.artists.isNotEmpty()) {
            item { SectionTitle("Top interpreti") }
            itemsIndexed(state.artists.take(5), key = { _, a -> "a-${a.name}" }) { i, a ->
                RankRow(
                    rank = i + 1,
                    title = a.name,
                    subtitle = "${numberFormat.format(minutes(a.ms))} min",
                    artwork = a.artworkUrl,
                    circle = true,
                    onClick = { navigator.artist(a.artistId, a.name) },
                )
            }
        }
        if (state.tracks.isNotEmpty()) {
            item { SectionTitle("Top skladby") }
            itemsIndexed(state.tracks.take(10), key = { _, t -> "t-${t.track.id}" }) { i, t ->
                RankRow(
                    rank = i + 1,
                    title = t.track.title,
                    subtitle = "${t.track.artist} • ${numberFormat.format(minutes(t.ms))} min",
                    artwork = t.track.artworkUrl,
                    circle = false,
                    onClick = { actions.play(state.tracks.take(10).map { it.track }, i) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Velké číslo minut s animací „načítání“ – na flexení. */
@Composable
private fun HeroNumber(allTimeMs: Long) {
    val target = minutes(allTimeMs).toFloat()
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target) { animated.animateTo(target, tween(1200, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(
            numberFormat.format(animated.value.toLong()),
            fontSize = 64.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-2).sp,
            color = Color.White,
        )
        Text(
            "minut naposloucháno na Vibe",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.8f),
        )
        val hours = allTimeMs / 3_600_000
        if (hours >= 24) {
            Text(
                "To je ${numberFormat.format(hours / 24)} dní nonstop hudby",
                style = MaterialTheme.typography.bodyMedium,
                color = VibeColors.Accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DailyChart(values: List<Long>) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.padding(horizontal = 16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (values.size - 1)) / values.size
            values.forEachIndexed { i, v ->
                val h = if (v == 0L) 3.dp.toPx() else (v.toFloat() / max * size.height).coerceAtLeast(4.dp.toPx())
                drawRoundRect(
                    color = if (v == 0L) empty else accent,
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("před 30 dny", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("dnes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RankRow(rank: Int, title: String, subtitle: String, artwork: String?, circle: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = if (rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Box {
            Artwork(
                artwork,
                Modifier.size(52.dp),
                if (circle) CircleShape else RoundedCornerShape(6.dp),
                if (circle) Icons.Rounded.Person else Icons.Rounded.BarChart,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
