package com.vibe.music.data

import com.vibe.music.data.db.TrackEntity
import com.vibe.music.data.db.VibeDatabase
import com.vibe.music.remote.Sources
import com.vibe.music.remote.YouTubeMusicApi
import com.vibe.music.remote.YtDlp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * „Rádio“ – nekonečné přehrávání podobné hudby.
 * - z jedné skladby: rádio YouTube Music k dané skladbě,
 * - z playlistu / celé knihovny: náhodně promíchané vlastní skladby proložené
 *   podobnými skladbami k několika náhodně vybraným „semínkům“.
 */
class RadioRepository(
    private val ytMusic: YouTubeMusicApi,
    private val ytDlp: YtDlp,
    private val db: VibeDatabase,
) {

    /** Podobné skladby – primárně YouTube Music API, při výpadku YouTube „Mix“ přes yt-dlp. */
    suspend fun similar(seedId: String): List<TrackEntity> {
        val ytSeed = youtubeSeed(seedId) ?: return emptyList()
        val tracks = runCatching { ytMusic.radio(ytSeed) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: ytDlp.radio(ytSeed)
        return tracks.map { it.toEntity() }.filter { it.id != seedId }
    }

    /** SoundCloud skladba nemá rádio – vezme se nejbližší skladba z YouTube Music. */
    private suspend fun youtubeSeed(seedId: String): String? {
        if (!Sources.isSoundCloud(seedId)) return seedId
        val t = db.tracks().get(seedId) ?: return null
        return runCatching { ytMusic.searchSongs("${t.artist} ${t.title}").firstOrNull()?.id }.getOrNull()
    }

    /** Skladba + podobné skladby. */
    suspend fun trackRadio(seed: TrackEntity): List<TrackEntity> =
        listOf(seed) + similar(seed.id).distinctBy { it.id }

    /**
     * Mix z vlastních skladeb: zamíchá [pool] a mezi každé dvě vlastní skladby vloží jednu podobnou.
     * Když rádio selže (offline), vrátí aspoň zamíchaný [pool].
     */
    suspend fun mix(pool: List<TrackEntity>, seeds: Int = 5, perSeed: Int = 10): List<TrackEntity> {
        val own = pool.distinctBy { it.id }.shuffled()
        if (own.isEmpty()) return emptyList()
        val ownIds = own.mapTo(HashSet()) { it.id }
        val related = coroutineScope {
            own.take(seeds).map { seed ->
                async { runCatching { similar(seed.id).take(perSeed) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }.flatten().distinctBy { it.id }.filter { it.id !in ownIds }.shuffled()

        val result = ArrayList<TrackEntity>(own.size + related.size)
        val rel = related.iterator()
        own.forEachIndexed { i, t ->
            result += t
            if (i % 2 == 1 && rel.hasNext()) result += rel.next()
        }
        rel.forEachRemaining { result += it }
        return result
    }
}
