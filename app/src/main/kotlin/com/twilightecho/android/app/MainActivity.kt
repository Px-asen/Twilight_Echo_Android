package com.twilightecho.android.app

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.twilightecho.android.feature.player.NowPlayingScreen
import com.twilightecho.android.feature.player.PlayerViewModel
import com.twilightecho.android.mediasession.TwilightMediaSessionService
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.ui.theme.TwilightEchoTheme

/**
 * Main Activity hosting Jetpack Compose UI (`NowPlayingScreen`) and connecting to
 * `TwilightMediaSessionService` so system media notifications stay active during playback.
 */
class MainActivity : ComponentActivity() {

    private val playbackHost: AndroidPlaybackHost by lazy {
        AndroidPlaybackHost.getInstance(applicationContext)
    }

    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory(playbackHost)
    }

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TwilightEchoTheme {
                NowPlayingScreen(viewModel = playerViewModel)
            }
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
