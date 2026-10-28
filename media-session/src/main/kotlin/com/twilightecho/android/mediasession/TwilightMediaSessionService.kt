package com.twilightecho.android.mediasession

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.twilightecho.android.playbackhost.AndroidPlaybackHost

/**
 * AndroidX Media3 `MediaSessionService` hosting `CoreSimpleBasePlayer` (Sections 11.2, 11.3, 11.4).
 *
 * Connects the OS media notification, lock screen controls, Bluetooth AVRCP, and Android Auto
 * to the singleton `AndroidPlaybackHost`.
 */
class TwilightMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var playerAdapter: CoreSimpleBasePlayer? = null

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
            artworkLoader = MediaSessionArtwork(applicationContext, playbackHost)::load)
        playerAdapter = player

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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
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
        playerAdapter = null
        super.onDestroy()
    }
}
