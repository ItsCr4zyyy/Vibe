package com.vibe.music.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.vibe.music.data.SettingsRepository
import com.vibe.music.data.db.DownloadState
import com.vibe.music.data.db.TrackEntity
import com.vibe.music.data.db.VibeDatabase
import com.vibe.music.data.AgeGateFallback
import com.vibe.music.remote.Sources
import com.vibe.music.remote.YtDlp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fronta stahování. Stav je v DB (QUEUED → DOWNLOADING → DONE/FAILED),
 * frontu zpracovává jeden [DownloadWorker] (max. 2 souběžná stahování).
 */
class DownloadManager(
    private val context: Context,
    private val db: VibeDatabase,
    private val ytDlp: YtDlp,
    private val settings: SettingsRepository,
    private val ageGate: AgeGateFallback,
) {
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Průběh aktuálně stahovaných skladeb (0–100). */
    val progress: StateFlow<Map<String, Float>> = _progress

    val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "music").apply { mkdirs() }

    suspend fun enqueue(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        db.tracks().insertIgnore(tracks)
        db.tracks().queue(tracks.map { it.id })
        kick()
    }

    /** Spustí (nebo naváže) worker. [restart] použij po změně omezení (Wi-Fi). */
    suspend fun kick(restart: Boolean = false) {
        val wifiOnly = settings.current().wifiOnlyDownloads
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    suspend fun cancel(id: String) {
        db.tracks().setDownloadState(id, DownloadState.NONE)
        ytDlp.cancel(processId(id))
    }

    suspend fun delete(track: TrackEntity) {
        track.localPath?.let { File(it).delete() }
        db.tracks().clearDownload(track.id)
    }

    suspend fun deleteAll() {
        db.tracks().downloaded().forEach { delete(it) }
        directory.listFiles()?.forEach { it.delete() }
    }

    fun storageUsedBytes(): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal suspend fun downloadOne(track: TrackEntity) {
        val dao = db.tracks()
        dao.setDownloadState(track.id, DownloadState.DOWNLOADING)
        _progress.update { it + (track.id to 0f) }
        try {
            val format = settings.current().audioFormat.ytDlpFormat
            // 18+ video → stáhne se náhradní nahrávka, uloží se ale pod původním ID
            val file = ageGate.run(track.id) { sourceId ->
                ytDlp.download(
                    sourceId = sourceId,
                    fileBase = Sources.fileBase(track.id),
                    dir = directory,
                    format = format,
                    processId = processId(track.id),
                ) { p -> _progress.update { it + (track.id to p) } }
            }
            dao.setDownloaded(track.id, file.absolutePath)
        } catch (e: CancellationException) {
            // Worker zastaven systémem → vrátit do fronty
            withContext(NonCancellable) { dao.setDownloadState(track.id, DownloadState.QUEUED) }
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            dao.setDownloadState(track.id, DownloadState.NONE)
        } catch (e: Exception) {
            Log.w(TAG, "Stažení ${track.id} selhalo", e)
            dao.setDownloadState(track.id, DownloadState.FAILED)
        } finally {
            _progress.update { it - track.id }
        }
    }

    private fun processId(id: String) = "dl_$id"

    companion object {
        private const val TAG = "Downloads"
        const val WORK_NAME = "downloads"
    }
}
