package com.twilightecho.android.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 * requests (`READ_MEDIA_AUDIO` on API 33+ or `READ_EXTERNAL_STORAGE` on API <= 32), and connecting
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

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed with MediaStore scan whether newly granted or partial access granted
        playerViewModel.onScanDeviceMusic()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TwilightEchoTheme {
                NowPlayingScreen(
                    viewModel = playerViewModel,
                    onRequestScanMusicPermission = ::ensurePermissionAndScanDeviceMusic
                )
            }
        }
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
