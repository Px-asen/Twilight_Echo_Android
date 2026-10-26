package com.twilightecho.android.app

import android.app.Application
import com.twilightecho.android.playbackhost.AndroidPlaybackHost

/**
 * Application entry point owning the process-wide `AndroidPlaybackHost` singleton
 * (Section 11.4: UI exit or Activity recreation never destroys the background playback host).
 */
class TwilightEchoApplication : Application() {

    lateinit var playbackHost: AndroidPlaybackHost
        private set

    override fun onCreate() {
        super.onCreate()
        playbackHost = AndroidPlaybackHost.getInstance(this)
    }
}
