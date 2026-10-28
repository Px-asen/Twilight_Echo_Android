package com.twilightecho.android.adapters

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.twilightecho.android.coreclient.OutputDeviceIdentity
import com.twilightecho.android.runtimehost.AudioDevicePort
import com.twilightecho.android.runtimehost.AudioDeviceRouteInfo
import com.twilightecho.android.runtimehost.AudioSnapshot
import com.twilightecho.android.runtimehost.CancelHandle
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android hardware audio route & DAC capability adapter (Plan Sections 11.7 & 13.6).
 *
 * Inspects `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` and registers `AudioDeviceCallback`
 * for the device inventory. The engine reports the routed ID and negotiated stream format.
 */
class AndroidAudioDeviceAdapter(
    context: Context,
    private val onPhysicalDisconnectBrake: (() -> Unit)? = null
) : AudioDevicePort {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(AudioDeviceRouteInfo) -> Unit>()
    private val removalListeners = CopyOnWriteArraySet<(List<OutputDeviceIdentity>) -> Unit>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var playbackOutput: AudioSnapshot? = null
    @Volatile
    private var cachedActiveRoute = AudioDeviceRouteInfo()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshAndNotify(wasRemoved = false, removedDevices = emptyList())
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshAndNotify(wasRemoved = true, removedDevices = removedDevices?.toList().orEmpty())
            // An output still listed under the same identity (another profile of one headset) stays connected.
            val present = outputDevices().mapTo(HashSet()) { identityOf(it) }
            val gone = removedDevices.orEmpty().map { identityOf(it) }.distinct().filterNot { it in present }
            if (gone.isNotEmpty() && !closed.get()) removalListeners.forEach { runCatching { it(gone) } }
        }
    }

    init {
        runCatching {
            audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }
    }

    override fun getActiveRoute(): AudioDeviceRouteInfo {
        return resolvePlaybackRoute(listOutputDevices(), playbackOutput)
    }

    fun reportPlaybackOutput(snapshot: AudioSnapshot) {
        if (closed.get()) return
        playbackOutput = snapshot
        publishRoute()
    }

    override fun listOutputDevices(): List<AudioDeviceRouteInfo> {
        val manager = audioManager ?: return emptyList()
        val rawDevices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull().orEmpty()

        return rawDevices.map { mapDeviceInfo(it) }
    }

    /**
     * The persistent identity of the routed output, for presets. Kept out of [AudioDeviceRouteInfo] so
     * product names stay on the phone; null while nothing plays or once the device is gone.
     */
    fun outputIdentity(route: AudioDeviceRouteInfo): OutputDeviceIdentity? {
        if (route.deviceId <= 0 && route.outputBackend.isEmpty()) return null
        val devices = outputDevices()
        val device = if (route.deviceId > 0) devices.firstOrNull { it.id == route.deviceId } else mediaRouteDevice(devices)
        return device?.let(::identityOf)
    }

    fun subscribeRemovedOutputs(listener: (List<OutputDeviceIdentity>) -> Unit): CancelHandle {
        removalListeners.add(listener)
        return CancelHandle { removalListeners.remove(listener) }
    }

    private fun outputDevices(): List<AudioDeviceInfo> =
        runCatching { audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull().orEmpty().toList()

    private fun identityOf(info: AudioDeviceInfo) =
        OutputDeviceIdentity(classifyRouteType(info.type), info.productName?.toString()?.trim().orEmpty())

    /** Some outputs report no stream device ID; Android 13+ still names the media route. */
    private fun mediaRouteDevice(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val routed = runCatching { audioManager?.getAudioDevicesForAttributes(media)?.firstOrNull() }.getOrNull() ?: return null
        return devices.firstOrNull { it.type == routed.type && it.address == routed.address }
    }

    override fun subscribeRouteChanges(listener: (AudioDeviceRouteInfo) -> Unit): CancelHandle {
        listeners.add(listener)
        return CancelHandle {
            listeners.remove(listener)
        }
    }

    private fun refreshAndNotify(wasRemoved: Boolean, removedDevices: List<AudioDeviceInfo>) {
        if (closed.get()) return
        if (wasRemoved && shouldPauseForDeviceRemoval(cachedActiveRoute, removedDevices.map { it.id }.toSet())) {
            onPhysicalDisconnectBrake?.invoke()
        }
        publishRoute()
    }

    private fun publishRoute() {
        val updated = getActiveRoute()
        val changed = synchronized(this) {
            if (updated == cachedActiveRoute) false else { cachedActiveRoute = updated; true }
        }
        if (changed) listeners.forEach { listener -> runCatching { listener(updated) } }
    }

    private fun mapDeviceInfo(info: AudioDeviceInfo): AudioDeviceRouteInfo {
        val routeType = classifyRouteType(info.type)
        val rates = info.sampleRates?.toList()?.sorted()?.takeIf { it.isNotEmpty() }
            ?: emptyList()
        val encodings = info.encodings?.toList().orEmpty()
        val maxBitDepth = when {
            encodings.contains(AudioFormat.ENCODING_PCM_32BIT) ||
                encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) -> 32
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> 24
            encodings.contains(AudioFormat.ENCODING_PCM_16BIT) -> 16
            encodings.contains(AudioFormat.ENCODING_PCM_8BIT) -> 8
            else -> 0
        }
        val productName = info.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultDeviceLabel(routeType)

        return AudioDeviceRouteInfo(
            deviceId = info.id,
            deviceName = productName,
            routeType = routeType,
            supportedSampleRates = rates,
            maxBitDepth = maxBitDepth,
            isUsbHiResCapable = routeType == "usb_dac" && (maxBitDepth >= 24 || rates.any { it >= 96000 }),
            bluetoothProfile = bluetoothProfile(info.type)
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

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "speaker"
        else -> "unknown"
    }

    private fun defaultDeviceLabel(routeType: String): String = when (routeType) {
        "usb_dac" -> "USB 解码器"
        "wired_headset" -> "有线耳机"
        "bluetooth" -> "蓝牙音频"
        "speaker" -> "手机扬声器"
        else -> "系统输出"
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            listeners.clear()
            removalListeners.clear()
            runCatching {
                audioManager?.unregisterAudioDeviceCallback(deviceCallback)
            }
        }
    }
}
