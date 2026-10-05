package com.vibe.music.ui.common

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.Player
import com.vibe.music.AppContainer
import com.vibe.music.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Akce nad skladbami sdílené napříč obrazovkami. Zápisy běží v app scope (přežijí navigaci). */
class TrackActions(private val c: AppContainer) {
    private val scope get() = c.appScope

    fun play(tracks: List<TrackEntity>, index: Int = 0, shuffle: Boolean = false) {
        scope.launch { c.music.ensureTracks(tracks) }
        c.player.play(tracks, index, shuffle)
    }

    /** Náhodné přehrávání ve smyčce – playlist hraje pořád dokola. */
    fun shuffleLoop(tracks: List<TrackEntity>) {
        scope.launch { c.music.ensureTracks(tracks) }
        c.player.play(tracks, shuffle = true, repeat = Player.REPEAT_MODE_ALL)
    }

    /** Rádio ze skladby – podobné skladby, po dohrání pokračuje automaticky. */
    fun startRadio(seed: TrackEntity) = launchRadio("Spouštím rádio…") {
        c.radio.trackRadio(seed) to Player.REPEAT_MODE_OFF
    }

    /** Mix z playlistu / knihovny: vlastní skladby proložené podobnými, ve smyčce. */
    fun startMix(pool: List<TrackEntity>) = launchRadio("Připravuji mix…") {
        c.radio.mix(pool) to Player.REPEAT_MODE_ALL
    }

    /** Rádio z uloženého playlistu. */
    fun startPlaylistMix(playlistId: Long) = launchRadio("Připravuji rádio z playlistu…") {
        c.radio.mix(c.music.playlists.tracksOf(playlistId)) to Player.REPEAT_MODE_ALL
    }

    /** Mix z celé knihovny (oblíbené + poslouchané + playlisty). */
    fun startLibraryMix() = launchRadio("Připravuji tvůj mix…") {
        c.radio.mix(c.music.tracks.libraryPool(), seeds = 6) to Player.REPEAT_MODE_ALL
    }

    private fun launchRadio(message: String, build: suspend () -> Pair<List<TrackEntity>, Int>) = scope.launch {
        toast(message)
        runCatching { build() }
            .onSuccess { (tracks, repeat) ->
                if (tracks.isEmpty()) return@onSuccess toast("Není z čeho vytvořit rádio")
                c.music.ensureTracks(tracks)
                withContext(Dispatchers.Main) { c.player.play(tracks, 0, shuffle = false, repeat = repeat) }
            }
            .onFailure { toast("Rádio se nepodařilo spustit: ${it.message}") }
    }

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(c.appContext, text, Toast.LENGTH_SHORT).show()
    }

    fun playNext(t: TrackEntity) {
        scope.launch { c.music.ensureTracks(listOf(t)) }
        c.player.playNext(t)
    }

    fun addToQueue(t: TrackEntity) {
        scope.launch { c.music.ensureTracks(listOf(t)) }
        c.player.addToQueue(t)
    }

    fun setLiked(t: TrackEntity, liked: Boolean) = scope.launch { c.music.setLiked(t, liked) }

    fun download(tracks: List<TrackEntity>) = scope.launch { c.downloads.enqueue(tracks) }

    fun cancelDownload(t: TrackEntity) = scope.launch { c.downloads.cancel(t.id) }

    fun deleteDownload(t: TrackEntity) = scope.launch { c.downloads.delete(t) }

    fun addToPlaylist(playlistId: Long, tracks: List<TrackEntity>) =
        scope.launch { c.music.addToPlaylist(playlistId, tracks) }

    fun createPlaylistWith(name: String, tracks: List<TrackEntity>) = scope.launch {
        val id = c.music.createPlaylist(name)
        c.music.addToPlaylist(id, tracks)
    }

    /** Uloží skladby (podle ID, v daném pořadí) jako nový playlist. */
    fun savePlaylistFromIds(name: String, ids: List<String>) = scope.launch {
        val byId = c.music.tracks.getMany(ids.distinct()).associateBy { it.id }
        val tracks = ids.mapNotNull { byId[it] }.distinctBy { it.id }
        val id = c.music.createPlaylist(name)
        c.music.addToPlaylist(id, tracks)
        toast("Playlist „$name“ uložen (${tracks.size})")
    }

    fun removeFromPlaylist(playlistId: Long, t: TrackEntity) =
        scope.launch { c.music.removeFromPlaylist(playlistId, t.id) }
}

@Composable
fun rememberTrackActions(): TrackActions {
    val c = LocalContainer.current
    return remember(c) { TrackActions(c) }
}

/**
 * Navigace na interpreta / album odkudkoli (i z přehrávače – ten se při tom sbalí).
 * Bez browseId (např. skladby ze Spotify) se interpret/album dohledá podle jména.
 */
class Navigator(
    private val navigate: (String) -> Unit,
    private val beforeNavigate: () -> Unit,
) {
    /** Klíč trasy: „browseId|jméno“ – jméno slouží k dohledání, když ID chybí nebo API selže. */
    fun artist(id: String?, name: String) {
        val key = "${id.orEmpty()}|${mainArtist(name)}"
        beforeNavigate()
        navigate("artist/" + Uri.encode(key))
    }

    fun album(id: String?, title: String?, artist: String) {
        if (id == null && title == null) return
        val key = "${id.orEmpty()}|${title.orEmpty()} ${mainArtist(artist)}".trim()
        beforeNavigate()
        navigate("album/" + Uri.encode(key))
    }

    fun playlist(id: Long) {
        beforeNavigate()
        navigate("playlist/$id")
    }

    companion object {
        fun mainArtist(artist: String) = artist.split(", ", " & ", " a ", " feat", " ft.", " x ").first().trim()
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator není k dispozici") }
