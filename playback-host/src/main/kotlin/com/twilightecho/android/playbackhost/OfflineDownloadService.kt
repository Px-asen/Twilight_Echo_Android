package com.twilightecho.android.playbackhost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/** Started only by an explicit download/resume action. Interrupted transfers remain resumable. */
class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var startId = 0
    private val host get() = AndroidPlaybackHost.getInstance(applicationContext)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "离线音乐下载", NotificationManager.IMPORTANCE_LOW))
        scope.launch { host.offlineDownloads.state.collect { updateNotification() } }
    }
    private fun notification(): android.app.Notification {
        val row = host.offlineDownloads.state.value.items.firstOrNull { it.status == OfflineDownloadStatus.DOWNLOADING }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(row?.track?.title ?: "离线音乐下载")
            .setContentText(row?.let { if (it.size > 0) "${it.bytes * 100 / it.size}% · ${it.host}" else "准备下载" } ?: "正在准备下载")
            .setContentIntent(intent?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) })
            .setProgress(100, row?.takeIf { it.size > 0 }?.let { (it.bytes * 100 / it.size).toInt() } ?: 0, row?.size == 0L)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }
    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        ServiceCompat.startForeground(this, NOTIFICATION, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (worker?.isActive != true) worker = scope.launch {
            try {
                host.awaitStarted()
                do { host.offlineDownloads.runPending() }
                while (host.offlineDownloads.state.value.items.any { it.status == OfflineDownloadStatus.QUEUED })
            }
            finally { stopSelfResult(this@OfflineDownloadService.startId) }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { host.offlineDownloads.pauseAll(); stopSelf() }
    override fun onDestroy() {
        host.offlineDownloads.pauseAll()
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "offline-downloads"; private const val NOTIFICATION = 2202 }
}
