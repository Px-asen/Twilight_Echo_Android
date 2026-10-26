package com.twilightecho.android.playbackhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

enum class PlatformInterruptionReason(
    val wireReason: String,
    val shouldImmediateBrakePause: Boolean,
    val isTransient: Boolean
) {
    AUDIOFOCUS_LOSS("android.audiofocus.loss", shouldImmediateBrakePause = true, isTransient = false),
    AUDIOFOCUS_LOSS_TRANSIENT(
        "android.audiofocus.loss_transient",
        shouldImmediateBrakePause = true,
        isTransient = true
    ),
    AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK(
        "android.audiofocus.duck",
        shouldImmediateBrakePause = false,
        isTransient = true
    ),
    AUDIO_BECOMING_NOISY(
        "android.audio.becoming_noisy",
        shouldImmediateBrakePause = true,
        isTransient = false
    )
}

/**
 * Coordinates Android system `AudioFocusRequest` and `AudioManager.ACTION_AUDIO_BECOMING_NOISY`
 * (headphone / Bluetooth route disconnect) events for `AndroidPlaybackHost` (Sections 5.4 & 11.6).
 */
class AudioFocusCoordinator(
    private val context: Context,
    private val onInterruption: (PlatformInterruptionReason) -> Unit,
    private val onFocusRegained: (shouldResumeTransient: Boolean) -> Unit,
    private val onDuckVolumeChange: (ducked: Boolean) -> Unit
) : Closeable {

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val receiverRegistered = AtomicBoolean(false)

    @Volatile
    private var pausedByTransientFocusLoss: Boolean = false

    @Volatile
    private var duckingActive: Boolean = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByTransientFocusLoss = false
                if (duckingActive) {
                    duckingActive = false
                    onDuckVolumeChange(false)
                }
                onInterruption(PlatformInterruptionReason.AUDIOFOCUS_LOSS)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedByTransientFocusLoss = true
                onInterruption(PlatformInterruptionReason.AUDIOFOCUS_LOSS_TRANSIENT)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckingActive = true
                onDuckVolumeChange(true)
                onInterruption(PlatformInterruptionReason.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (duckingActive) {
                    duckingActive = false
                    onDuckVolumeChange(false)
                }
                val shouldResume = pausedByTransientFocusLoss
                pausedByTransientFocusLoss = false
                onFocusRegained(shouldResume)
            }
        }
    }

    private val audioFocusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAcceptsDelayedFocusGain(true)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausedByTransientFocusLoss = false
                onInterruption(PlatformInterruptionReason.AUDIO_BECOMING_NOISY)
            }
        }
    }

    fun requestPlaybackFocus(): Boolean {
        registerNoisyReceiverIfNeeded()
        val manager = audioManager ?: return true
        val res = manager.requestAudioFocus(audioFocusRequest)
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonPlaybackFocus() {
        pausedByTransientFocusLoss = false
        unregisterNoisyReceiverIfNeeded()
        audioManager?.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun registerNoisyReceiverIfNeeded() {
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(becomingNoisyReceiver, filter)
                }
            }
        }
    }

    private fun unregisterNoisyReceiverIfNeeded() {
        if (receiverRegistered.compareAndSet(true, false)) {
            runCatching {
                context.unregisterReceiver(becomingNoisyReceiver)
            }
        }
    }

    override fun close() {
        abandonPlaybackFocus()
    }
}
