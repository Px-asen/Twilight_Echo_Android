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
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
            else java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
        // The isolated plugin worker must not construct the Core, audio engine or app storage.
        if (processName != packageName) return
        playbackHost = AndroidPlaybackHost.getInstance(this)
    }
}
