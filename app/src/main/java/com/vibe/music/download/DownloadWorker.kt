package com.vibe.music.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vibe.music.VibeApp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as VibeApp).container

    override suspend fun doWork(): Result {
        runCatching { setForeground(foregroundInfo(container.db.tracks().pendingDownloadCount())) }
        container.ytDlp.ensureReady()
        while (true) {
            val batch = container.db.tracks().queued(PARALLEL)
            if (batch.isEmpty()) break
            runCatching { setForeground(foregroundInfo(container.db.tracks().pendingDownloadCount())) }
            coroutineScope {
                batch.forEach { track -> launch { container.downloads.downloadOne(track) } }
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    private fun foregroundInfo(remaining: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Stahování", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stahuji hudbu")
            .setContentText(if (remaining > 0) "Ve frontě: $remaining" else "Připravuji…")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 42
        private const val PARALLEL = 2
    }
}
