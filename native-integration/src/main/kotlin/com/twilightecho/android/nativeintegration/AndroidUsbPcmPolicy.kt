package com.twilightecho.android.nativeintegration

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.annotation.TargetApi

/** JNI-owned per-engine policy. Synchronous Binder calls never wait for the adapter dispatcher. */
class AndroidUsbPcmPolicy(context: Context) : AutoCloseable {
    private val platform = if (Build.VERSION.SDK_INT >= 34) Api34(context.applicationContext) else null
    fun acquire(device: String, rate: Int, channels: Int, format: String): String =
        platform?.lease?.acquire(device, rate, channels, format) ?: "USB 原样 PCM 需要 Android 14 或更新版本"
    fun valid(): Boolean = platform?.lease?.valid() == true
    fun release(): Boolean = platform?.lease?.release() ?: true
    override fun close() { platform?.close() }

    @TargetApi(34)
    private class Api34(context: Context) : UsbMixerPlatform, AutoCloseable {
        private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val lease = UsbPcmLease(this)
        private val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { lease.valid() }
        }
        private val listener = AudioManager.OnPreferredMixerAttributesChangedListener { _, _, _ -> lease.valid() }
        init {
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            manager.addOnPreferredMixerAttributesChangedListener(context.mainExecutor, listener)
        }
        private fun devices() = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        private fun device(id: Int) = devices().single { it.id == id }
        override fun outputs() = devices().map { UsbOutput(it.id, it.type in setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)) }
        override fun mediaRoute() = manager.getAudioDevicesForAttributes(attributes).map { it.id }
        override fun supported(id: Int) = manager.getSupportedMixerAttributes(device(id)).map(::tuple)
        override fun preferred(id: Int) = manager.getPreferredMixerAttributes(attributes, device(id))?.let(::tuple)
        override fun set(id: Int, tuple: UsbMixerTuple): Boolean {
            val output = device(id)
            val original = manager.getSupportedMixerAttributes(output).firstOrNull { tuple(it) == tuple } ?: return false
            return manager.setPreferredMixerAttributes(attributes, output, original)
        }
        override fun clear(id: Int) = manager.clearPreferredMixerAttributes(attributes, device(id))
        private fun tuple(mixer: AudioMixerAttributes) = UsbMixerTuple(mixer.format.sampleRate,
            when (mixer.format.encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> "int16"
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> "int24"
                AudioFormat.ENCODING_PCM_32BIT -> "int32"
                AudioFormat.ENCODING_PCM_FLOAT -> "float32"
                else -> "unsupported:${mixer.format.encoding}"
            }, mixer.format.channelMask, mixer.format.channelIndexMask,
            mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
        override fun close() {
            lease.retire()
            manager.unregisterAudioDeviceCallback(callback)
            manager.removeOnPreferredMixerAttributesChangedListener(listener)
        }
    }
}
