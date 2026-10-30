package com.twilightecho.android.nativeintegration

/** Whole advertised tuple; never synthesize a Cartesian product of device capabilities. */
internal data class UsbMixerTuple(val rate: Int, val encoding: String, val mask: Int,
    val indexMask: Int = 0, val bitPerfect: Boolean = true)
internal data class UsbOutput(val id: Int, val usb: Boolean)

internal interface UsbMixerPlatform {
    fun outputs(): List<UsbOutput>
    fun mediaRoute(): List<Int>
    fun supported(id: Int): List<UsbMixerTuple>
    fun preferred(id: Int): UsbMixerTuple?
    fun set(id: Int, tuple: UsbMixerTuple): Boolean
    fun clear(id: Int): Boolean
}

/** System preferences belong to a UID, not an engine. Serialize leases across engine instances. */
internal class UsbPcmArbiter {
    var owner: UsbPcmLease? = null
}

internal class UsbPcmLease(private val platform: UsbMixerPlatform,
    private val arbiter: UsbPcmArbiter = processArbiter) {
    private var device: Int? = null
    private var tuple: UsbMixerTuple? = null
    private var invalid = false
    private var retired = false

    fun acquire(deviceId: String, rate: Int, channels: Int, encoding: String): String = synchronized(arbiter) {
        try {
            check(!retired) { "USB 播放会话已关闭" }
            release()
            check(arbiter.owner == null) { "另一个播放会话持有 USB 混音偏好，或上次偏好尚未清除" }
            require(channels in 1..2 && encoding in setOf("int16", "int24", "int32", "float32")) {
                "USB 原样 PCM 首版支持单／双声道 16、24、32 位整数及 Float32"
            }
            val outputs = platform.outputs()
            val route = if (deviceId == "auto" || deviceId == "default") platform.mediaRoute().singleOrNull()
                else deviceId.toIntOrNull()
            val selected = outputs.singleOrNull { it.id == route && it.usb }
                ?: error("当前输出不是单一 USB 设备；请连接并选择 USB DAC（需要 Android 14+）")
            val wanted = UsbMixerTuple(rate, encoding, if (channels == 1) 4 else 12)
            val advertised = platform.supported(selected.id).firstOrNull { it == wanted }
                ?: error("USB 设备未声明此音源格式的 Bit-Perfect 混音元组，或能力查询失败")
            check(platform.preferred(selected.id) == null) { "设备已有混音偏好；不会覆盖未知归属的设置" }
            // Claim before set: even an exception/readback failure must clean up our attempted write.
            device = selected.id; tuple = advertised; arbiter.owner = this; invalid = false
            if (!platform.set(selected.id, advertised)) {
                // A returned rejection did not install our preference. Exceptions remain
                // ambiguous and go through the attempted-write cleanup in catch below.
                device = null; tuple = null; arbiter.owner = null; invalid = true
                error("系统拒绝 USB Bit-Perfect 混音偏好")
            }
            check(platform.preferred(selected.id) == advertised) { "USB 混音偏好回读不匹配或查询失败" }
            "OK:${selected.id}"
        } catch (error: Exception) {
            release()
            error.message ?: "USB 混音偏好设置失败"
        }
    }

    /** Called on non-RT native control/clock threads and by Android listeners. Never calls JNI. */
    fun valid(): Boolean = synchronized(arbiter) {
        if (invalid || arbiter.owner !== this) return@synchronized false
        val id = device ?: return@synchronized false
        val matches = runCatching {
            platform.outputs().any { it.id == id && it.usb } && platform.preferred(id) == tuple
        }.getOrDefault(false)
        if (!matches) invalid = true // loss is sticky: do not repeatedly reclaim the preference
        matches
    }

    fun release(): Boolean = synchronized(arbiter) {
        if (arbiter.owner !== this) {
            // A failed clear must not permanently strand a lease after its engine is destroyed.
            // Only retired owners may be cleaned by the next explicit output attempt.
            arbiter.owner?.takeIf { it.retired }?.release()
            return@synchronized arbiter.owner == null
        }
        val id = device ?: return@synchronized false
        val cleared = runCatching {
            // Do not restore a previous UID's preference, or clear a replacement we do not own.
            if (platform.outputs().none { it.id == id }) true
            else {
                val current = platform.preferred(id)
                // AudioManager also returns null on a native query failure. It is not
                // sufficient evidence to permit ordinary output without clearing first.
                if (current != null && current != tuple) true else platform.clear(id)
            }
        }.getOrDefault(false)
        invalid = true
        if (cleared) { device = null; tuple = null; arbiter.owner = null }
        // Failed cleanup retains ownership; the next explicit attempt retries clear, not set.
        cleared
    }

    fun retire() = synchronized(arbiter) { retired = true; release() }

    companion object { private val processArbiter = UsbPcmArbiter() }
}
