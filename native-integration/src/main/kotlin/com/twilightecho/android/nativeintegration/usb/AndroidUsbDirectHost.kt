package com.twilightecho.android.nativeintegration.usb

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class UsbDirectOutputDevice(val id: String, val name: String, val permissionGranted: Boolean,
    val dopConfirmationKey: String = "")

/** Per-engine JNI host. Native owns claim, transfers and driver restoration, then releases this lease. */
class AndroidUsbDirectHost(context: Context, private val onDetached: (UsbDirectOutputDevice) -> Unit = {}) : AutoCloseable {
    private val context = context.applicationContext
    private val host = AndroidUsbAudioHost(context)
    private var connection: OpenUsbAudioDevice? = null
    private var session: UsbAudioPlaybackSession? = null
    private var nextLeaseId = 0L
    @Volatile var dopConfirmedDeviceKey: String = ""
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (device != null && (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == 1 && device.getInterface(it).interfaceSubclass in 1..2
            }) onDetached(UsbDirectOutputDevice(PREFIX + device.deviceName,
                device.productName ?: "USB DAC ${device.vendorId}:${device.productId}", false))
        }
    }
    init {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") this.context.registerReceiver(detachReceiver, filter)
    }

    fun devices(): List<UsbDirectOutputDevice> = host.devices().map {
        UsbDirectOutputDevice(PREFIX + it.deviceName,
            it.productName?.takeIf(String::isNotBlank) ?: "USB DAC ${it.vendorId}:${it.productId}", it.permissionGranted,
            dopConfirmationKey(it.vendorId, it.productId, it.deviceVersion))
    }

    suspend fun requestPermission(deviceId: String): Boolean = host.requestPermission(deviceName(deviceId))

    @Synchronized
    fun acquire(deviceId: String, rate: Int, channels: Int, format: String, bits: Int): String {
        return try {
            check(connection == null) { "USB 直连会话仍占用设备" }
            val opened = host.open(deviceName(deviceId))
            try {
                if (format.startsWith("dop-")) requireUsbDopConfirmation(dopConfirmedDeviceKey,
                    dopConfirmationKey(opened.device.vendorId, opened.device.productId, opened.device.version))
                val identity = UsbDsdDeviceIdentity(opened.device.vendorId, opened.device.productId, opened.descriptors.deviceRevision)
                val profiles = usbDirectProfiles(opened.activeConfiguration(), rate, channels, format, bits, identity)
                require(profiles.isNotEmpty()) { "USB 设备没有匹配 $format／$rate Hz／$channels 声道的备用接口（异步输出需显式反馈或已匹配的隐式反馈源）" }
                val lease = usbDirectLeaseJson(opened.fileDescriptor, opened.device.productName ?: "USB DAC", profiles)
                session = UsbAudioPlaybackSession(opened, profiles, ++nextLeaseId)
                connection = opened
                lease
            } catch (error: Exception) { opened.close(); throw error }
        } catch (error: Exception) { buildJsonObject { put("error", error.message ?: "USB 连接失败") }.toString() }
    }

    @Synchronized
    fun configure(profile: Int, rate: Int): String = control { checkNotNull(session).configure(profile, rate) }

    @Synchronized
    fun restore(): String = control { session?.restore() }

    @Synchronized
    fun readHardwareVolume(deviceId: String): UsbHardwareVolumeSnapshot {
        val opened = checkNotNull(connection) { "USB 接口未接管，请点播放后读取 DAC 音量" }
        check(PREFIX + opened.device.deviceName == deviceId) { "USB 输出设备已改变，请重新选择" }
        return checkNotNull(session).readHardwareVolume()
    }

    @Synchronized
    fun setHardwareVolume(id: UsbHardwareControlId, db256: Int): UsbAudioVolumeSnapshot =
        checkNotNull(session) { "USB 连接已释放，请点播放后重新读取" }.setHardwareVolume(id, db256)

    @Synchronized
    fun setHardwareMute(id: UsbHardwareControlId, muted: Boolean): Boolean =
        checkNotNull(session) { "USB 连接已释放，请点播放后重新读取" }.setHardwareMute(id, muted)

    @Synchronized
    fun release() { session = null; connection?.close(); connection = null }

    /** Called after nativeDestroyEngine has drained transfers, restored clocks and released its lease. */
    override fun close() { context.unregisterReceiver(detachReceiver); release() }

    private fun control(action: () -> Unit): String = try { action(); "" }
        catch (error: Exception) { error.message ?: "USB 时钟控制失败" }

    companion object {
        const val PREFIX = "usb-direct:"
        fun isDirect(deviceId: String): Boolean = deviceId.startsWith(PREFIX)
        fun dopConfirmationKey(vendor: Int, product: Int, version: String): String = "$vendor:$product:$version"
        private fun deviceName(deviceId: String): String {
            require(isDirect(deviceId)) { "USB 直连需要 USB Host 设备标识" }
            return deviceId.removePrefix(PREFIX)
        }
    }
}

internal fun requireUsbDopConfirmation(confirmed: String, connected: String) {
    require(confirmed.isNotBlank() && confirmed == connected) { "请先在 DSD 输出中确认当前 DAC 支持 DoP" }
}
