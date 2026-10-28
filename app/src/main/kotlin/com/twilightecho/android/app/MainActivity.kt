package com.twilightecho.android.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.twilightecho.android.feature.player.NowPlayingScreen
import com.twilightecho.android.feature.player.PlayerViewModel
import com.twilightecho.android.mediasession.TwilightMediaSessionService
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.ui.theme.TwilightEchoTheme

/**
 * Main Activity hosting Jetpack Compose UI (`NowPlayingScreen`), handling runtime audio permission
 * requests (`READ_MEDIA_AUDIO` on API 33+ or `READ_EXTERNAL_STORAGE` on API <= 32), notification
 * permission on API 33+, and connecting
 * to `TwilightMediaSessionService` so system media notifications stay active during playback.
 */
class MainActivity : ComponentActivity() {

    private val playbackHost: AndroidPlaybackHost by lazy {
        AndroidPlaybackHost.getInstance(applicationContext)
    }

    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory(playbackHost)
    }

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Playback remains available when notifications are declined. */ }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) playerViewModel.onScanDeviceMusic()
        else playerViewModel.onScanPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            TwilightEchoTheme {
                NowPlayingScreen(
                    viewModel = playerViewModel,
                    onRequestScanMusicPermission = ::ensurePermissionAndScanDeviceMusic
                )
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences("runtime_permissions", MODE_PRIVATE)
        if (preferences.getBoolean("notifications_requested", false)) return
        // Ask once, not on every launch after a refusal. Settings retains a direct system entry.
        preferences.edit().putBoolean("notifications_requested", true).apply()
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun ensurePermissionAndScanDeviceMusic() {
        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, requiredPermission) == PackageManager.PERMISSION_GRANTED) {
            playerViewModel.onScanDeviceMusic()
        } else {
            requestAudioPermissionLauncher.launch(requiredPermission)
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(
            this,
            ComponentName(this, TwilightMediaSessionService::class.java)
        )
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
    }

    override fun onStop() {
        mediaControllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        mediaControllerFuture = null
        super.onStop()
    }
}
