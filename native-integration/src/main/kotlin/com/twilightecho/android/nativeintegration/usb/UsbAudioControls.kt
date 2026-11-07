package com.twilightecho.android.nativeintegration.usb

import java.io.IOException
import java.math.BigInteger

internal fun interface UsbAudioControlTransfer {
    /** Same fields and return convention as UsbDeviceConnection.controlTransfer; no implicit retries. */
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int
}

internal data class UsbAudioClockSnapshot(
    val sourceId: Int,
    val sourceCurrentRate: Long,
    val sourceRanges: List<UsbAudioRateRange>,
    val numerator: BigInteger,
    val denominator: BigInteger,
    val valid: Boolean?,
    /** Selector ID -> currently selected one-based input pin. Inspection never changes it. */
    val selectedInputs: Map<Int, Int>,
) {
    val playbackRate: Long? get() = scaledInteger(sourceCurrentRate.toBigInteger() * numerator, denominator)

    fun sourceRateFor(playbackRate: Long): Long? =
        scaledInteger(playbackRate.toBigInteger() * denominator, numerator)

    private fun scaledInteger(value: BigInteger, divisor: BigInteger): Long? {
        val (quotient, remainder) = value.divideAndRemainder(divisor)
        return if (remainder == BigInteger.ZERO && quotient.bitLength() <= 63) quotient.toLong() else null
    }
}

/** Values stay in signed 1/256 dB units; -32768 is the USB volume -infinity value. */
data class UsbAudioVolumeRange(val minimumDb256: Int, val maximumDb256: Int, val resolutionDb256: Int) {
    fun contains(value: Int): Boolean = value in minimumDb256..maximumDb256 &&
        (resolutionDb256 == 0 || (value - minimumDb256) % resolutionDb256 == 0)
}
data class UsbAudioVolumeSnapshot(val currentDb256: Int, val ranges: List<UsbAudioVolumeRange>)

/** Class controls. Volume and mute writes are explicit user actions, separate from stream setup. */
internal class UsbAudioControls(private val io: UsbAudioControlTransfer, private val function: UsbAudioFunction) {
    /** Follow this playback terminal to physical outputs, resolving signal selectors without changing them. */
    fun playbackFeatureUnits(stream: UsbAudioStreamingAlternate): List<UsbAudioFeatureUnit> {
        require(stream in function.playbackAlternates) { "USB 备用接口不属于此音频功能" }
        val features = function.featureUnits.associateBy { it.id }
        fun sources(id: Int): List<Int> = features[id]?.let { listOf(it.sourceId) }
            ?: function.signalUnits[id]?.sources ?: function.outputTerminals[id]?.let { listOf(it.sourceId) }.orEmpty()
        fun canReach(id: Int, visited: Set<Int>): Boolean = id == stream.terminalLink ||
            (id !in visited && sources(id).any { canReach(it, visited + id) })
        val selected = mutableMapOf<Int, List<Int>>()
        fun path(id: Int, visited: Set<Int>): Set<Int>? {
            if (id == stream.terminalLink) return emptySet()
            if (!canReach(id, emptySet())) return null
            require(id !in visited) { "USB 音频信号拓扑存在循环：$id" }
            val unit = function.signalUnits[id]
            val inputs = if (unit is UsbAudioSignalUnit.Selector && unit.sources.size > 1) selected.getOrPut(id) {
                require(unit.readable) { "USB 信号选择器 $id 未声明可读控制，无法确定当前音量路径" }
                // UAC1 Selector Unit uses wValue=0; UAC2 has SU_SELECTOR_CONTROL=1.
                val uac1 = function.version == UsbAudioVersion.UAC1
                val pin = entityRead(id, if (uac1) 0 else 1, if (uac1) 0x81 else 1, 1)[0].toInt() and 0xff
                require(pin in 1..unit.sources.size) { "USB 信号选择器 $id 返回无效输入：$pin" }
                listOf(unit.sources[pin - 1])
            } else sources(id)
            val branches = inputs.mapNotNull { path(it, visited + id) }
            return if (branches.isEmpty()) null else branches.flatten().toSet() + if (id in features) setOf(id) else emptySet()
        }
        val activeIds = function.outputTerminals.values.filter { it.type ushr 8 != 1 && canReach(it.id, emptySet()) }
            .mapNotNull { path(it.id, emptySet()) }.flatten().toSet()
        return function.featureUnits.filter { it.id in activeIds }
    }

    fun readClock(stream: UsbAudioStreamingAlternate): UsbAudioClockSnapshot {
        require(function.version == UsbAudioVersion.UAC2) { "UAC1 的支持采样率来自格式描述符" }
        require(stream in function.playbackAlternates) { "USB 备用接口不属于此音频功能" }
        val terminal = requireNotNull(function.inputTerminals[stream.terminalLink]) { "USB 输入 Terminal 不存在" }
        return readClock(requireNotNull(terminal.clockId) { "USB Terminal 缺少时钟" })
    }

    fun readCaptureClock(stream: UsbAudioStreamingAlternate): UsbAudioClockSnapshot {
        require(function.version == UsbAudioVersion.UAC2 && stream in function.captureAlternates)
        val terminal = function.outputTerminals.getValue(stream.terminalLink)
        return readClock(requireNotNull(terminal.clockId) { "USB 反馈源 Terminal 缺少时钟" })
    }

    private fun readClock(clockId: Int): UsbAudioClockSnapshot {
        val visited = mutableSetOf<Int>()
        val selected = linkedMapOf<Int, Int>()
        fun follow(id: Int): UsbAudioClockSnapshot {
            require(visited.add(id)) { "USB 时钟拓扑存在循环：$id" }
            return when (val clock = function.clocks[id] ?: error("USB 时钟实体不存在：$id")) {
                is UsbAudioClock.Source -> {
                    require(clock.controls and 1 != 0) { "USB 时钟 $id 未声明可读的频率控制" }
                    val current = entityRead(id, 1, 1, 4).uint(0, 4)
                    val ranges = readRanges(id, 1, 0, 12).let { data ->
                        (0 until data.uint(0, 2).toInt()).map { n ->
                            val at = 2 + n * 12
                            UsbAudioRateRange(data.uint(at, 4), data.uint(at + 4, 4), data.uint(at + 8, 4))
                        }
                    }
                    val valid = if (clock.controls and 4 != 0) entityRead(id, 2, 1, 1)[0].toInt() != 0 else null
                    UsbAudioClockSnapshot(id, current, ranges, BigInteger.ONE, BigInteger.ONE, valid, emptyMap())
                }
                is UsbAudioClock.Selector -> {
                    require(clock.controls and 1 != 0) { "USB 时钟选择器 $id 未声明可读控制" }
                    val pin = entityRead(id, 1, 1, 1).uint(0, 1).toInt()
                    require(pin in 1..clock.inputs.size) { "USB 时钟选择器 $id 返回无效输入：$pin" }
                    selected[id] = pin
                    follow(clock.inputs[pin - 1])
                }
                is UsbAudioClock.Multiplier -> {
                    require(clock.controls and 5 == 5) { "USB 时钟倍频器 $id 未声明可读的分子／分母" }
                    val p = entityRead(id, 1, 1, 2).uint(0, 2).toBigInteger()
                    val q = entityRead(id, 2, 1, 2).uint(0, 2).toBigInteger()
                    require(p > BigInteger.ZERO && q > BigInteger.ZERO) { "USB 时钟倍频器 $id 返回零分子／分母" }
                    val parent = follow(clock.sourceId)
                    val numerator = parent.numerator * p
                    val denominator = parent.denominator * q
                    val gcd = numerator.gcd(denominator)
                    parent.copy(numerator = numerator / gcd, denominator = denominator / gcd)
                }
            }
        }
        return follow(clockId).copy(selectedInputs = selected.toMap())
    }

    fun readUac1CurrentRate(stream: UsbAudioStreamingAlternate): Long {
        require(function.version == UsbAudioVersion.UAC1 && (stream in function.playbackAlternates || stream in function.captureAlternates))
        require(stream.dataEndpoint.samplingFrequencyControl) { "USB 端点未声明采样率控制" }
        return read(0xa2, 0x81, 0x0100, stream.dataEndpoint.address, 3).uint(0, 3)
    }

    fun setUac1Rate(stream: UsbAudioStreamingAlternate, rate: Long) {
        require(function.version == UsbAudioVersion.UAC1 && (stream in function.playbackAlternates || stream in function.captureAlternates))
        require(stream.dataEndpoint.samplingFrequencyControl && rate in 1..0xffffffL)
        write(0x22, 1, 0x0100, stream.dataEndpoint.address, rate, 3)
        check(readUac1CurrentRate(stream) == rate) { "USB 端点采样率回读与请求不一致" }
    }

    fun setClockSourceRate(sourceId: Int, rate: Long) {
        require(function.version == UsbAudioVersion.UAC2)
        val source = function.clocks[sourceId] as? UsbAudioClock.Source ?: error("USB 频率控制不是时钟源")
        require(source.controls and 3 == 3 && rate in 1..0xffffffffL) { "USB 时钟源未声明可写频率" }
        write(0x21, 1, 0x0100, (sourceId shl 8) or function.controlInterface, rate, 4)
        check(entityRead(sourceId, 1, 1, 4).uint(0, 4) == rate) { "USB 时钟源采样率回读与请求不一致" }
    }

    fun readVolume(unitId: Int, channel: Int): UsbAudioVolumeSnapshot {
        val unit = function.featureUnits.single { it.id == unitId }
        require(channel in unit.channels.indices && unit.channels[channel].volumeReadable) {
            "USB Feature Unit $unitId 的声道 $channel 未声明可读音量"
        }
        val currentRequest = if (function.version == UsbAudioVersion.UAC1) 0x81 else 1
        val current = entityRead(unitId, 2, currentRequest, 2, channel).signed16(0)
        val ranges = if (function.version == UsbAudioVersion.UAC1) listOf(UsbAudioVolumeRange(
            entityRead(unitId, 2, 0x82, 2, channel).signed16(0),
            entityRead(unitId, 2, 0x83, 2, channel).signed16(0),
            entityRead(unitId, 2, 0x84, 2, channel).uint(0, 2).toInt(),
        )) else readRanges(unitId, 2, channel, 6).let { data ->
            (0 until data.uint(0, 2).toInt()).map { n ->
                val at = 2 + n * 6
                UsbAudioVolumeRange(data.signed16(at), data.signed16(at + 2), data.uint(at + 4, 2).toInt())
            }
        }
        return UsbAudioVolumeSnapshot(current, ranges)
    }

    fun readMute(unitId: Int, channel: Int): Boolean {
        val unit = function.featureUnits.single { it.id == unitId }
        require(channel in unit.channels.indices && unit.channels[channel].muteReadable) {
            "USB Feature Unit $unitId 的声道 $channel 未声明可读静音"
        }
        return entityRead(unitId, 1, if (function.version == UsbAudioVersion.UAC1) 0x81 else 1, 1, channel)[0].toInt() != 0
    }

    /** Returns the device's readback, including a value the device rounded or rejected. */
    fun setVolume(unitId: Int, channel: Int, db256: Int): UsbAudioVolumeSnapshot {
        val unit = function.featureUnits.single { it.id == unitId }
        require(channel in unit.channels.indices && unit.channels[channel].volumeWritable) {
            "USB Feature Unit $unitId 的声道 $channel 未声明可写音量"
        }
        val original = readVolume(unitId, channel)
        require(original.ranges.any { it.contains(db256) }) { "请求音量不在 DAC 声明的范围或步进上" }
        write(0x21, 1, (2 shl 8) or channel, (unitId shl 8) or function.controlInterface, db256.toLong(), 2)
        val current = entityRead(unitId, 2, if (function.version == UsbAudioVersion.UAC1) 0x81 else 1, 2, channel).signed16(0)
        return original.copy(currentDb256 = current)
    }

    fun setMute(unitId: Int, channel: Int, muted: Boolean): Boolean {
        val unit = function.featureUnits.single { it.id == unitId }
        require(channel in unit.channels.indices && unit.channels[channel].muteWritable) {
            "USB Feature Unit $unitId 的声道 $channel 未声明可写静音"
        }
        write(0x21, 1, (1 shl 8) or channel, (unitId shl 8) or function.controlInterface, if (muted) 1 else 0, 1)
        return readMute(unitId, channel)
    }

    private fun readRanges(entityId: Int, selector: Int, channel: Int, entrySize: Int): ByteArray {
        val count = entityRead(entityId, selector, 2, 2, channel).uint(0, 2).toInt()
        val length = 2 + count * entrySize
        require(length <= 0xffff) { "USB RANGE 超出控制传输长度：$length" }
        if (count == 0) return byteArrayOf(0, 0)
        return entityRead(entityId, selector, 2, length, channel).also {
            require(it.uint(0, 2).toInt() == count) { "USB RANGE 在两次查询之间改变" }
        }
    }

    private fun entityRead(id: Int, selector: Int, request: Int, length: Int, channel: Int = 0) =
        read(0xa1, request, (selector shl 8) or channel, (id shl 8) or function.controlInterface, length)

    private fun write(type: Int, request: Int, value: Int, index: Int, payload: Long, length: Int) {
        val data = ByteArray(length) { (payload ushr (8 * it)).toByte() }
        val count = io.transfer(type, request, value, index, data)
        if (count != length) throw IOException("USB 控制写入失败：request=$request, value=$value, index=$index，需 $length 字节，写入 $count")
    }

    private fun read(type: Int, request: Int, value: Int, index: Int, length: Int): ByteArray {
        val data = ByteArray(length)
        val count = io.transfer(type, request, value, index, data)
        if (count != length) throw IOException("USB 控制读取失败：request=$request, value=$value, index=$index，需 $length 字节，收到 $count")
        return data
    }
}

private fun ByteArray.uint(at: Int, size: Int): Long =
    (0 until size).fold(0L) { result, n -> result or ((this[at + n].toLong() and 0xff) shl (8 * n)) }
private fun ByteArray.signed16(at: Int): Int = uint(at, 2).toShort().toInt()

internal fun readActiveUsbAudioConfiguration(io: UsbAudioControlTransfer,
    descriptors: UsbAudioDescriptors): UsbAudioConfiguration {
    val data = ByteArray(1)
    val received = io.transfer(0x80, 8, 0, 0, data) // USB standard GET_CONFIGURATION, not the first descriptor.
    if (received != 1) throw IOException("无法读取 USB 当前配置：收到 $received 字节")
    val value = data[0].toInt() and 0xff
    return descriptors.configurations.singleOrNull { it.value == value }
        ?: throw IOException("USB 当前配置 $value 不在已读取的配置描述符中")
}
