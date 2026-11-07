package com.twilightecho.android.nativeintegration.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

internal data class UsbAudioDeviceRef(val deviceName: String, val vendorId: Int, val productId: Int,
    val productName: String?, val permissionGranted: Boolean, val deviceVersion: String)

/** USB Host handles are separate from AudioManager route IDs and never inferred from product names. */
internal class AndroidUsbAudioHost(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(UsbManager::class.java)

    fun devices(): List<UsbAudioDeviceRef> = manager?.deviceList?.values.orEmpty().filter { device ->
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 1 &&
            device.getInterface(it).interfaceSubclass in 1..2 }
    }.map { device -> UsbAudioDeviceRef(device.deviceName, device.vendorId, device.productId,
        device.productName, manager!!.hasPermission(device), device.version) }.sortedBy { it.deviceName }

    /** Called by an explicit USB output selection, not enumeration or application startup. */
    suspend fun requestPermission(deviceName: String): Boolean {
        val usb = requireNotNull(manager) { "此设备未提供 USB Host 服务" }
        val device = connected(deviceName)
        if (usb.hasPermission(device)) return true
        return suspendCancellableCoroutine { continuation ->
            val requestId = permissionIds.incrementAndGet()
            val action = "${context.packageName}.USB_AUDIO_PERMISSION.$requestId"
            val intent = PendingIntent.getBroadcast(context, requestId, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
            val finished = AtomicBoolean(false)
            lateinit var receiver: BroadcastReceiver
            fun cleanup() { context.unregisterReceiver(receiver); intent.cancel() }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, result: Intent) {
                    if (result.action != action) return
                    val returnedDevice = if (Build.VERSION.SDK_INT >= 33)
                        result.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") result.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (returnedDevice?.deviceName != deviceName || !finished.compareAndSet(false, true)) return
                    cleanup()
                    continuation.resume(result.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(action))
            continuation.invokeOnCancellation { if (finished.compareAndSet(false, true)) cleanup() }
            if (continuation.isActive) try {
                usb.requestPermission(device, intent)
            } catch (error: Exception) {
                if (finished.compareAndSet(false, true)) cleanup()
                throw error
            }
        }
    }

    /** Opens only an already authorized connection. Does not claim interfaces or modify clocks/volume. */
    fun open(deviceName: String): OpenUsbAudioDevice {
        val usb = requireNotNull(manager) { "此设备未提供 USB Host 服务" }
        val device = connected(deviceName)
        check(usb.hasPermission(device)) { "请先授权所选 USB 音频设备" }
        val connection = usb.openDevice(device) ?: throw IOException("无法打开 USB 音频设备：$deviceName")
        return try {
            OpenUsbAudioDevice(device, connection, UsbAudioDescriptors.parse(connection.rawDescriptors))
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    private fun connected(name: String): UsbDevice = manager?.deviceList?.get(name)
        ?: throw IOException("USB 音频设备已断开：$name")

    companion object { private val permissionIds = AtomicInteger() }
}

/** The later native transfer session must finish and release its borrowed/duplicated fd before close. */
internal class OpenUsbAudioDevice(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    val descriptors: UsbAudioDescriptors,
) : AutoCloseable, UsbAudioControlTransfer {
    private var closed = false
    val fileDescriptor: Int get() { check(!closed) { "USB 连接已关闭" }; return connection.fileDescriptor }

    fun activeConfiguration(): UsbAudioConfiguration = readActiveUsbAudioConfiguration(this, descriptors)

    fun controls(function: UsbAudioFunction): UsbAudioControls {
        require(function in activeConfiguration().functions) { "USB 音频功能不属于设备当前配置" }
        return UsbAudioControls(this, function)
    }

    override fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int {
        check(!closed) { "USB 连接已关闭" }
        return connection.controlTransfer(requestType, request, value, index, data, data.size, 1000)
    }

    override fun close() {
        if (!closed) { closed = true; connection.close() }
    }
}
