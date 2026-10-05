package com.vibe.music

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Pravidelná údržba: aktualizace yt-dlp a synchronizace importovaných playlistů. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as VibeApp).container
        runCatching { c.ytMusic.refreshClientVersion(force = true) }
        val settings = c.settings.current()
        if (settings.ytDlpAutoUpdate) {
            runCatching {
                c.ytDlp.update(settings.ytDlpChannel)
                c.settings.setYtDlpLastCheck(System.currentTimeMillis())
            }.onFailure { Log.w(TAG, "Aktualizace yt-dlp selhala", it) }
        }
        runCatching { c.follows.checkNewReleases() }.onFailure { Log.w(TAG, "Kontrola novinek selhala", it) }
        c.music.playlists.autoSyncPlaylists().forEach { playlist ->
            runCatching { c.imports.sync(playlist.id) }
                .onFailure { Log.w(TAG, "Sync ${playlist.name} selhal", it) }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "Maintenance"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
