package com.twilightecho.android.mediasession

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.twilightecho.android.coreclient.CoreResult
import com.twilightecho.android.playbackhost.AndroidPlaybackHost

/**
 * AndroidX Media3 `MediaSessionService` hosting `CoreSimpleBasePlayer` (Sections 11.2, 11.3, 11.4).
 *
 * Connects the OS media notification, lock screen controls, Bluetooth AVRCP, and Android Auto
 * to the singleton `AndroidPlaybackHost`.
 */
class TwilightMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val playbackHost = AndroidPlaybackHost.getInstance(applicationContext)
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.playback_notification_channel_name).build().apply {
                setSmallIcon(R.drawable.ic_twilight_notification)
            }
        setMediaNotificationProvider(notificationProvider)
        val player = CoreSimpleBasePlayer(playbackHost = playbackHost,
            artworkLoader = MediaSessionArtwork(applicationContext, playbackHost)::load,
            playRequest = ::playFromSystem)

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivityPendingIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = MediaSession.Builder(this, player)
            .setId("TwilightEchoPrimaryMediaSession")
        if (sessionActivityPendingIntent != null) {
            builder.setSessionActivity(sessionActivityPendingIntent)
        }
        mediaSession = builder.build()
    }

    @OptIn(UnstableApi::class)
    private suspend fun playFromSystem() {
        val session = mediaSession ?: return
        val host = AndroidPlaybackHost.getInstance(applicationContext)
        val phone = !host.systemMediaTransport.isRemote
        // Android 15+ denies background audio-focus requests without a foreground service.
        // Promote before asking for focus, not after Core reports that audio is playing.
        if (phone) startPlaybackForeground(session)
        try {
            val result = host.requestSystemFocusAndPlay()
            if (result is CoreResult.Failure) {
                Log.w("TwilightMediaSession", "System Play failed: ${result.error.message}")
                if (phone) onUpdateNotification(session, false)
            }
        } catch (error: Exception) {
            if (phone) onUpdateNotification(session, false)
            throw error
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlaybackForeground(session: MediaSession) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        manager.createNotificationChannel(NotificationChannel(channelId,
            getString(R.string.playback_notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) })
        val notificationId = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
        // Keep the existing artwork and controls when resuming a paused notification. A newly
        // created session gets a media-style notification until Media3 publishes its full one.
        val notification = manager.activeNotifications.firstOrNull { it.id == notificationId }?.notification
            ?: Notification.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_twilight_notification)
                .setContentTitle(session.player.mediaMetadata.title ?: applicationInfo.loadLabel(packageManager))
                .setContentText(session.player.mediaMetadata.artist)
                .setContentIntent(session.sessionActivity)
                .setStyle(Notification.MediaStyle().setMediaSession(session.platformToken))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
        ContextCompat.startForegroundService(this, Intent(this, TwilightMediaSessionService::class.java))
        val serviceType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, notificationId, notification, serviceType)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val event = if (intent?.action == Intent.ACTION_MEDIA_BUTTON)
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) else null
        if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode in setOf(
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)) {
            val session = mediaSession
            if (session != null && !session.player.playWhenReady &&
                !AndroidPlaybackHost.getInstance(applicationContext).systemMediaTransport.isRemote) {
                // A receiver-started service must enter the foreground even while Core restores.
                startPlaybackForeground(session)
            }
            if (event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                // Media3 1.5 handles HEADSETHOOK through the legacy session, but omits it from
                // the service/notification-controller single-tap mapping. Preserve toggle semantics.
                val normalized = KeyEvent(event.downTime, event.eventTime, event.action,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, event.repeatCount, event.metaState,
                    event.deviceId, event.scanCode, event.flags, event.source)
                return super.onStartCommand(Intent(intent).putExtra(Intent.EXTRA_KEY_EVENT, normalized), flags, startId)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
