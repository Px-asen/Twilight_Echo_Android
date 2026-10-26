package com.twilightecho.android.adapters

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.twilightecho.android.runtimehost.AudioDevicePort
import com.twilightecho.android.runtimehost.AudioDeviceRouteInfo
import com.twilightecho.android.runtimehost.CancelHandle
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android hardware audio route & DAC capability adapter (Plan Sections 11.7 & 13.6).
 *
 * Inspects `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` and registers `AudioDeviceCallback`
 * to track active output routes (Built-in Speaker, Wired Headset, Bluetooth A2DP/LE, USB DAC),
 * real hardware sample rates, and bit depth capabilities without fabricating desktop Hi-Fi labels.
 */
class AndroidAudioDeviceAdapter(
    context: Context,
    private val onPhysicalDisconnectBrake: (() -> Unit)? = null
) : AudioDevicePort {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(AudioDeviceRouteInfo) -> Unit>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var cachedActiveRoute: AudioDeviceRouteInfo = queryActiveRouteInternal()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshAndNotify(wasRemoved = false, removedDevices = emptyList())
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshAndNotify(wasRemoved = true, removedDevices = removedDevices?.toList().orEmpty())
        }
    }

    init {
        runCatching {
            audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }
    }

    override fun getActiveRoute(): AudioDeviceRouteInfo {
        val current = queryActiveRouteInternal()
        cachedActiveRoute = current
        return current
    }

    override fun listOutputDevices(): List<AudioDeviceRouteInfo> {
        val manager = audioManager ?: return listOf(AudioDeviceRouteInfo())
        val rawDevices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull().orEmpty()

        val mapped = rawDevices.map { mapDeviceInfo(it) }
        return if (mapped.isNotEmpty()) mapped else listOf(AudioDeviceRouteInfo())
    }

    override fun subscribeRouteChanges(listener: (AudioDeviceRouteInfo) -> Unit): CancelHandle {
        listeners.add(listener)
        return CancelHandle {
            listeners.remove(listener)
        }
    }

    private fun refreshAndNotify(wasRemoved: Boolean, removedDevices: List<AudioDeviceInfo>) {
        if (closed.get()) return
        if (wasRemoved && removedDevices.any { isExternalHeadsetOrDac(it.type) }) {
            // Section 5.4 & 11.7: Unplugging wired headphones, USB DAC, or Bluetooth headset brakes output
            onPhysicalDisconnectBrake?.invoke()
        }
        val updated = queryActiveRouteInternal()
        cachedActiveRoute = updated
        for (listener in listeners) {
            runCatching { listener(updated) }
        }
    }

    private fun queryActiveRouteInternal(): AudioDeviceRouteInfo {
        val devices = listOutputDevices()
        // Priority order: USB DAC -> Wired Headset -> Bluetooth -> Built-in Speaker
        return devices.firstOrNull { it.routeType == "usb_dac" }
            ?: devices.firstOrNull { it.routeType == "wired_headset" }
            ?: devices.firstOrNull { it.routeType == "bluetooth" }
            ?: devices.firstOrNull()
            ?: AudioDeviceRouteInfo()
    }

    private fun mapDeviceInfo(info: AudioDeviceInfo): AudioDeviceRouteInfo {
        val routeType = classifyRouteType(info.type)
        val rates = info.sampleRates?.toList()?.sorted()?.takeIf { it.isNotEmpty() }
            ?: listOf(48000)
        val encodings = info.encodings?.toList().orEmpty()
        val maxBitDepth = when {
            encodings.contains(AudioFormat.ENCODING_PCM_32BIT) ||
                encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) -> 32
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> 24
            routeType == "usb_dac" && rates.any { it >= 96000 } -> 24
            else -> 16
        }
        val activeSampleRate = when {
            routeType == "usb_dac" -> rates.maxOrNull() ?: 96000
            rates.contains(48000) -> 48000
            else -> rates.firstOrNull() ?: 48000
        }
        val productName = info.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultDeviceLabel(routeType)

        return AudioDeviceRouteInfo(
            deviceId = info.id,
            deviceName = productName,
            routeType = routeType,
            outputBackend = if (routeType == "usb_dac") "oboe-usb" else "oboe",
            supportedSampleRates = rates,
            activeSampleRate = activeSampleRate,
            maxBitDepth = maxBitDepth,
            isUsbHiResCapable = routeType == "usb_dac" && (maxBitDepth >= 24 || activeSampleRate >= 96000)
        )
    }

    private fun classifyRouteType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_dac"

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "wired_headset"

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bluetooth"

        else -> "speaker"
    }

    private fun isExternalHeadsetOrDac(type: Int): Boolean {
        val route = classifyRouteType(type)
        return route == "usb_dac" || route == "wired_headset" || route == "bluetooth"
    }

    private fun defaultDeviceLabel(routeType: String): String = when (routeType) {
        "usb_dac" -> "USB Hi-Res DAC"
        "wired_headset" -> "Wired Headphones"
        "bluetooth" -> "Bluetooth Audio"
        else -> "Android Speaker"
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            listeners.clear()
            runCatching {
                audioManager?.unregisterAudioDeviceCallback(deviceCallback)
            }
        }
    }
}
