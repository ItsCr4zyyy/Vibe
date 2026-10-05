package com.vibe.music.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class PlaybackMode(val label: String, val description: String) {
    STREAM("Jen streamovat", "Skladby se přehrávají online, stahuješ ručně."),
    STREAM_AND_SAVE("Streamovat a ukládat", "Každá přehraná skladba se automaticky uloží offline."),
}

enum class AudioFormat(val label: String, val ytDlpFormat: String) {
    BEST("Nejlepší kvalita (Opus)", "bestaudio/best"),
    COMPATIBLE("Kompatibilní (AAC/M4A)", "bestaudio[ext=m4a]/bestaudio/best"),
}

enum class YtDlpChannel(val label: String) {
    STABLE("Stabilní"),
    NIGHTLY("Nightly (nejrychlejší opravy)"),
}

data class Settings(
    val playbackMode: PlaybackMode = PlaybackMode.STREAM,
    val audioFormat: AudioFormat = AudioFormat.BEST,
    val wifiOnlyDownloads: Boolean = true,
    val autoDownloadImports: Boolean = false,
    val ytDlpAutoUpdate: Boolean = true,
    val ytDlpChannel: YtDlpChannel = YtDlpChannel.NIGHTLY,
    val ytDlpLastCheck: Long = 0,
    val dynamicColor: Boolean = false,
    /** Po dohrání fronty pokračovat podobnými skladbami (rádio YouTube Music). */
    val autoplay: Boolean = true,
    /** Vyrovnání hlasitosti mezi skladbami (vlastní audio procesor). */
    val normalizeVolume: Boolean = false,
    /** Plynulé zesílení/zeslabení na začátku a konci skladby. */
    val fadeTransitions: Boolean = false,
    /** Prolínání skladeb v sekundách (0 = vypnuto). */
    val crossfadeSec: Int = 0,
    /** Další skladba ve frontě se celá předem načte do cache. */
    val preloadNext: Boolean = true,
    /** Co dostane srdíčko, to se samo stáhne offline. */
    val autoDownloadLiked: Boolean = false,
    /** SoundCloud ve výsledcích hledání a jako náhrada 18+ videí (volitelné). */
    val soundCloudEnabled: Boolean = false,
    val eqEnabled: Boolean = false,
    /** Index předvolby ekvalizéru, -1 = vlastní nastavení pásem. */
    val eqPreset: Int = -1,
    /** Úrovně pásem v milibelech („-300,0,200,…“). */
    val eqLevels: List<Int> = emptyList(),
    /** Historie hledání (nejnovější první). */
    val searchHistory: List<String> = emptyList(),
    val spotifyClientId: String = "",
    val spotifyClientSecret: String = "",
) {
    val hasSpotifyCredentials get() = spotifyClientId.isNotBlank() && spotifyClientSecret.isNotBlank()
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val playbackMode = stringPreferencesKey("playback_mode")
        val audioFormat = stringPreferencesKey("audio_format")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val autoDownloadImports = booleanPreferencesKey("auto_download_imports")
        val ytDlpAutoUpdate = booleanPreferencesKey("ytdlp_auto_update")
        val ytDlpChannel = stringPreferencesKey("ytdlp_channel")
        val ytDlpLastCheck = longPreferencesKey("ytdlp_last_check")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val autoplay = booleanPreferencesKey("autoplay")
        val normalize = booleanPreferencesKey("normalize_volume")
        val fade = booleanPreferencesKey("fade_transitions")
        val crossfade = intPreferencesKey("crossfade_sec")
        val preloadNext = booleanPreferencesKey("preload_next")
        val autoDownloadLiked = booleanPreferencesKey("auto_download_liked")
        val soundCloud = booleanPreferencesKey("soundcloud_enabled")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = intPreferencesKey("eq_preset")
        val eqLevels = stringPreferencesKey("eq_levels")
        val searchHistory = stringPreferencesKey("search_history")
        val spotifyId = stringPreferencesKey("spotify_client_id")
        val spotifySecret = stringPreferencesKey("spotify_client_secret")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            playbackMode = p[Keys.playbackMode].toEnum(d.playbackMode),
            audioFormat = p[Keys.audioFormat].toEnum(d.audioFormat),
            wifiOnlyDownloads = p[Keys.wifiOnly] ?: d.wifiOnlyDownloads,
            autoDownloadImports = p[Keys.autoDownloadImports] ?: d.autoDownloadImports,
            ytDlpAutoUpdate = p[Keys.ytDlpAutoUpdate] ?: d.ytDlpAutoUpdate,
            ytDlpChannel = p[Keys.ytDlpChannel].toEnum(d.ytDlpChannel),
            ytDlpLastCheck = p[Keys.ytDlpLastCheck] ?: d.ytDlpLastCheck,
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
            autoplay = p[Keys.autoplay] ?: d.autoplay,
            normalizeVolume = p[Keys.normalize] ?: d.normalizeVolume,
            fadeTransitions = p[Keys.fade] ?: d.fadeTransitions,
            crossfadeSec = p[Keys.crossfade] ?: d.crossfadeSec,
            preloadNext = p[Keys.preloadNext] ?: d.preloadNext,
            autoDownloadLiked = p[Keys.autoDownloadLiked] ?: d.autoDownloadLiked,
            soundCloudEnabled = p[Keys.soundCloud] ?: d.soundCloudEnabled,
            eqEnabled = p[Keys.eqEnabled] ?: d.eqEnabled,
            eqPreset = p[Keys.eqPreset] ?: d.eqPreset,
            eqLevels = p[Keys.eqLevels]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: d.eqLevels,
            searchHistory = p[Keys.searchHistory]?.split('\n')?.filter { it.isNotBlank() } ?: d.searchHistory,
            spotifyClientId = p[Keys.spotifyId] ?: d.spotifyClientId,
            spotifyClientSecret = p[Keys.spotifySecret] ?: d.spotifyClientSecret,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setPlaybackMode(v: PlaybackMode) = put(Keys.playbackMode, v.name)
    suspend fun setAudioFormat(v: AudioFormat) = put(Keys.audioFormat, v.name)
    suspend fun setWifiOnly(v: Boolean) = put(Keys.wifiOnly, v)
    suspend fun setAutoDownloadImports(v: Boolean) = put(Keys.autoDownloadImports, v)
    suspend fun setYtDlpAutoUpdate(v: Boolean) = put(Keys.ytDlpAutoUpdate, v)
    suspend fun setYtDlpChannel(v: YtDlpChannel) = put(Keys.ytDlpChannel, v.name)
    suspend fun setYtDlpLastCheck(v: Long) = put(Keys.ytDlpLastCheck, v)
    suspend fun setDynamicColor(v: Boolean) = put(Keys.dynamicColor, v)
    suspend fun setAutoplay(v: Boolean) = put(Keys.autoplay, v)
    suspend fun setNormalizeVolume(v: Boolean) = put(Keys.normalize, v)
    suspend fun setFadeTransitions(v: Boolean) = put(Keys.fade, v)
    suspend fun setCrossfadeSec(v: Int) = put(Keys.crossfade, v)
    suspend fun setPreloadNext(v: Boolean) = put(Keys.preloadNext, v)
    suspend fun setAutoDownloadLiked(v: Boolean) = put(Keys.autoDownloadLiked, v)
    suspend fun setSoundCloudEnabled(v: Boolean) = put(Keys.soundCloud, v)
    suspend fun setEqEnabled(v: Boolean) = put(Keys.eqEnabled, v)
    suspend fun setEqPreset(preset: Int) = put(Keys.eqPreset, preset)
    suspend fun setEqLevels(levels: List<Int>) {
        context.dataStore.edit {
            it[Keys.eqLevels] = levels.joinToString(",")
            it[Keys.eqPreset] = -1
        }
    }

    suspend fun addSearch(query: String) {
        val q = query.trim().takeIf { it.length >= 2 } ?: return
        context.dataStore.edit { p ->
            val old = p[Keys.searchHistory]?.split('\n').orEmpty()
            p[Keys.searchHistory] = (listOf(q) + old.filter { !it.equals(q, ignoreCase = true) }).take(15).joinToString("\n")
        }
    }

    suspend fun removeSearch(query: String) {
        context.dataStore.edit { p ->
            p[Keys.searchHistory] = p[Keys.searchHistory]?.split('\n').orEmpty().filter { it != query }.joinToString("\n")
        }
    }
    suspend fun setSpotifyCredentials(id: String, secret: String) {
        context.dataStore.edit {
            it[Keys.spotifyId] = id.trim()
            it[Keys.spotifySecret] = secret.trim()
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
