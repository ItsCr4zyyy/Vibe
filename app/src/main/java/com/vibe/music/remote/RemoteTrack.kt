package com.vibe.music.remote

import com.vibe.music.data.db.TrackEntity

/** Skladba z online zdroje (hledání, playlist), zatím neuložená v knihovně. */
data class RemoteTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int = 0,
    val artworkUrl: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
) {
    fun toEntity() = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec,
        artworkUrl = artworkUrl ?: if (Sources.isSoundCloud(id)) null else youtubeThumbnail(id),
        artistId = artistId,
        albumId = albumId,
    )
}

data class RemotePlaylist(
    val title: String,
    val artworkUrl: String?,
    val tracks: List<RemoteTrack>,
)

fun youtubeThumbnail(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/** Obaly z YouTube Music (googleusercontent) lze zvětšit změnou parametru velikosti. */
fun upscaleArtwork(url: String?, size: Int = 544): String? =
    url?.replace(Regex("=w\\d+-h\\d+.*$"), "=w$size-h$size-l90-rj")

private val noiseRegex = Regex(
    """\s*[(\[](official|lyrics?|audio|video|music video|hd|hq|4k|visuali[sz]er|clip officiel|videoclip)[^)\]]*[)\]]""",
    RegexOption.IGNORE_CASE,
)

/** „Interpret - Název (Official Video)“ → („Interpret“, „Název“) */
fun cleanVideoTitle(rawTitle: String, channel: String?): Pair<String, String> {
    val title = rawTitle.replace(noiseRegex, "").trim()
    val artistFromChannel = channel?.removeSuffix(" - Topic")?.removeSuffix("VEVO")?.trim()
    val dash = title.indexOf(" - ")
    if (dash > 0 && (channel == null || !channel.endsWith(" - Topic"))) {
        return title.substring(0, dash).trim() to title.substring(dash + 3).trim()
    }
    return (artistFromChannel?.takeIf { it.isNotBlank() } ?: "Neznámý interpret") to title
}

fun parseDuration(text: String?): Int? {
    if (text == null || !text.matches(Regex("""\d+(:\d+)+"""))) return null
    return text.split(':').fold(0) { acc, part -> acc * 60 + part.toInt() }
}
