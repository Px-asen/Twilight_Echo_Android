package com.twilightecho.android.playbackhost

/** A user pause, stop, or physical disconnect always wins over a pending focus resume. */
internal class FocusResumeState {
    private var pending = false

    @Synchronized fun transientLoss(wasPlaying: Boolean) { pending = pending || wasPlaying }
    @Synchronized fun cancel() { pending = false }
    @Synchronized fun gain(): Boolean = pending.also { pending = false }
}
