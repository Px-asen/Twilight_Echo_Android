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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.twilightecho.android.feature.player.NowPlayingScreen
import com.twilightecho.android.feature.player.PlayerViewModel
import com.twilightecho.android.mediasession.TwilightMediaSessionService
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import com.twilightecho.android.ui.theme.TwilightEchoTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Main Activity hosting Jetpack Compose UI (`NowPlayingScreen`), handling notification permission
 * on API 33+, and connecting to `TwilightMediaSessionService` so system media notifications stay
 * active during playback. Local music comes exclusively from user-selected SAF folders.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Reapply the saved choice after returning from another app or recreating this Activity.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                playbackHost.userPreferences.state.map { it.appearance.immersiveMode }
                    .distinctUntilChanged().collect { immersive ->
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            systemBarsBehavior = if (immersive)
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                            if (immersive) hide(WindowInsetsCompat.Type.statusBars())
                            else show(WindowInsetsCompat.Type.statusBars())
                        }
                    }
            }
        }
        setContent {
            TwilightEchoTheme {
                NowPlayingScreen(
                    viewModel = playerViewModel
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
