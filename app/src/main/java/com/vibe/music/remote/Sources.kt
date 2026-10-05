package com.vibe.music.remote

/**
 * ID skladby určuje i její zdroj:
 * - YouTube: video ID (11 znaků), např. „dQw4w9WgXcQ“,
 * - SoundCloud: „sc:<číslo skladby>“.
 */
object Sources {
    const val SC_PREFIX = "sc:"

    fun isSoundCloud(id: String) = id.startsWith(SC_PREFIX)

    /** URL, kterou zpracuje yt-dlp. */
    fun url(id: String): String =
        if (isSoundCloud(id)) "https://api.soundcloud.com/tracks/soundcloud%3Atracks%3A${id.removePrefix(SC_PREFIX)}"
        else "https://www.youtube.com/watch?v=$id"

    /** Název souboru bez přípony (dvojtečka v názvu souboru nevadí, ale je nepraktická). */
    fun fileBase(id: String) = id.replace(':', '_')

    /**
     * SoundCloud nabízí progresivní MP3 i HLS – bereme progresivní (funguje s cache a stahováním).
     * U YouTube platí formát zvolený v nastavení.
     */
    fun format(id: String, youtubeFormat: String): String =
        if (isSoundCloud(id)) "bestaudio[protocol^=http]/bestaudio" else youtubeFormat

    /** Obaly ze SoundCloudu ve velikosti 500×500 místo obřího originálu. */
    fun soundCloudArtwork(url: String?): String? =
        url?.replace("-original.", "-t500x500.")?.replace("-large.", "-t500x500.")

    /** Chyba yt-dlp kvůli věkovému omezení (ne kontrola „nejsi bot“). */
    fun isAgeRestricted(error: Throwable): Boolean {
        val msg = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return "confirm your age" in msg || "age-restricted" in msg || "age restricted" in msg ||
            "inappropriate for some users" in msg
    }
}
