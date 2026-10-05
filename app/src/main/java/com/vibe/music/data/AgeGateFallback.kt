package com.vibe.music.data

import android.util.Log
import com.vibe.music.data.db.MatchEntity
import com.vibe.music.data.db.VibeDatabase
import com.vibe.music.remote.RemoteTrack
import com.vibe.music.remote.Sources
import com.vibe.music.remote.YouTubeMusicApi
import com.vibe.music.remote.YtDlp
import java.io.IOException
import kotlin.math.abs

/**
 * Videa omezená na 18+ YouTube bez přihlášení nevydá. Místo nich se automaticky najde
 * jiná, neomezená nahrávka téže písničky (YouTube, případně SoundCloud) se stejnou délkou.
 * Nalezená náhrada se uloží a příště se použije rovnou.
 */
class AgeGateFallback(
    private val db: VibeDatabase,
    private val ytDlp: YtDlp,
    private val ytMusic: YouTubeMusicApi,
    private val settings: SettingsRepository,
) {
    /** Spustí [block] se zdrojem skladby [id]; je-li 18+, zkusí náhradní zdroje. */
    suspend fun <T> run(id: String, block: suspend (sourceId: String) -> T): T {
        db.matches().get(key(id))?.let { alt ->
            runCatching { return block(alt) }
        }
        try {
            return block(id)
        } catch (e: Exception) {
            if (!Sources.isAgeRestricted(e)) throw e
            Log.i(TAG, "$id je omezené na 18+, hledám náhradu")
            for (candidate in candidates(id)) {
                val result = runCatching { block(candidate) }.getOrNull() ?: continue
                db.matches().put(MatchEntity(key(id), candidate))
                return result
            }
            throw IOException("Skladba je na YouTube omezená na 18+ a jinou verzi se nepodařilo najít", e)
        }
    }

    private suspend fun candidates(id: String): List<String> {
        val track = db.tracks().get(id) ?: ytMusic.runCatching { trackInfo(id)?.toEntity() }.getOrNull() ?: return emptyList()
        val query = "${track.artist} ${track.title}"
        val youtube = buildList<RemoteTrack> {
            runCatching { ytMusic.searchSongs(query) }.getOrNull()?.let(::addAll)
            runCatching { ytMusic.searchVideos(query) }.getOrNull()?.let(::addAll)
            if (isEmpty()) runCatching { ytDlp.search(query, 8) }.getOrNull()?.let(::addAll)
        }
        val soundCloud = if (settings.current().soundCloudEnabled) {
            runCatching { ytDlp.searchSoundCloud(query, 5) }.getOrDefault(emptyList())
        } else emptyList()

        fun fits(r: RemoteTrack) = track.durationSec <= 0 || r.durationSec <= 0 || abs(r.durationSec - track.durationSec) <= 6

        // Nejdřív shodná délka (stejná nahrávka), YouTube před SoundCloudem
        return (youtube + soundCloud)
            .filter { it.id != id && fits(it) }
            .sortedBy { if (it.durationSec > 0 && track.durationSec > 0) 0 else 1 }
            .map { it.id }
            .distinct()
            .take(6)
    }

    private fun key(id: String) = "alt:$id"

    companion object {
        private const val TAG = "AgeGate"
    }
}
