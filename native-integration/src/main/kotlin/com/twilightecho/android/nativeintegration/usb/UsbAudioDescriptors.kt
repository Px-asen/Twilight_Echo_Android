package com.twilightecho.android.nativeintegration.usb

/** USB wire formats, not AudioManager's independently enumerated capability lists. */
internal enum class UsbAudioVersion { UAC1, UAC2 }
internal enum class UsbPcmEncoding { SIGNED_INTEGER, IEEE_FLOAT }
internal enum class UsbIsoSync { NONE, ASYNCHRONOUS, ADAPTIVE, SYNCHRONOUS }

internal data class UsbAudioRateRange(val minimum: Long, val maximum: Long, val resolution: Long)

internal data class UsbSuperSpeedCompanion(val maxBurst: Int, val attributes: Int, val bytesPerInterval: Int)

internal data class UsbAudioEndpoint(
    val address: Int,
    val attributes: Int,
    val rawMaxPacketSize: Int,
    val interval: Int,
    val refresh: Int = 0,
    val syncAddress: Int = 0,
    val samplingFrequencyControl: Boolean = false,
    val classAttributes: Int = 0,
    val classControls: Int = 0,
    val lockDelayUnits: Int = 0,
    val lockDelay: Int = 0,
    val superSpeed: UsbSuperSpeedCompanion? = null,
) {
    val isInput: Boolean get() = address and 0x80 != 0
    val isIsochronous: Boolean get() = attributes and 3 == 1
    val usage: Int get() = (attributes ushr 4) and 3
    val sync: UsbIsoSync get() = UsbIsoSync.entries[(attributes ushr 2) and 3]
}

/** One alternate's complete format. Rates for UAC2 come from its clock, not its descriptor. */
internal data class UsbAudioStreamingAlternate(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val terminalLink: Int,
    val encodings: Set<UsbPcmEncoding>,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val channelConfig: Long,
    val descriptorRates: List<UsbAudioRateRange>,
    val dataEndpoint: UsbAudioEndpoint,
    val feedbackEndpoint: UsbAudioEndpoint?,
    val rawData: Boolean = false,
) {
    val frameBytes: Int get() = channels * subslotBytes
}

internal sealed interface UsbAudioClock { val id: Int
    data class Source(override val id: Int, val attributes: Int, val controls: Int) : UsbAudioClock
    data class Selector(override val id: Int, val inputs: List<Int>, val controls: Int) : UsbAudioClock
    data class Multiplier(override val id: Int, val sourceId: Int, val controls: Int) : UsbAudioClock
}

internal data class UsbAudioInputTerminal(val id: Int, val type: Int, val clockId: Int?, val channels: Int,
    val channelConfig: Long)

internal data class UsbFeatureChannel(val muteReadable: Boolean, val muteWritable: Boolean,
    val volumeReadable: Boolean, val volumeWritable: Boolean)

internal data class UsbAudioFeatureUnit(val id: Int, val sourceId: Int, val channels: List<UsbFeatureChannel>)

internal data class UsbAudioOutputTerminal(val id: Int, val type: Int, val sourceId: Int, val clockId: Int? = null)

internal sealed interface UsbAudioSignalUnit {
    val id: Int
    val sources: List<Int>
    data class Junction(override val id: Int, override val sources: List<Int>) : UsbAudioSignalUnit
    data class Selector(override val id: Int, override val sources: List<Int>, val readable: Boolean) : UsbAudioSignalUnit
}

internal data class UsbAudioFunction(
    val controlInterface: Int,
    val version: UsbAudioVersion,
    val inputTerminals: Map<Int, UsbAudioInputTerminal>,
    val clocks: Map<Int, UsbAudioClock>,
    val featureUnits: List<UsbAudioFeatureUnit>,
    val playbackAlternates: List<UsbAudioStreamingAlternate>,
    val outputTerminals: Map<Int, UsbAudioOutputTerminal> = emptyMap(),
    val signalUnits: Map<Int, UsbAudioSignalUnit> = emptyMap(),
    val captureAlternates: List<UsbAudioStreamingAlternate> = emptyList(),
)

internal data class UsbAudioConfiguration(val value: Int, val functions: List<UsbAudioFunction>)
internal data class UsbAudioDescriptors(val configurations: List<UsbAudioConfiguration>, val deviceRevision: Int = 0) {
    companion object {
        fun parse(raw: ByteArray): UsbAudioDescriptors = UsbAudioDescriptorParser(raw).parse()
    }
}

private class Descriptor(val offset: Int, val bytes: ByteArray) {
    val size get() = bytes.size
    fun requireSize(minimum: Int) = require(size >= minimum) { "USB 描述符 @$offset 长度不足（需 $minimum，实际 $size）" }
    fun u8(index: Int): Int { requireSize(index + 1); return bytes[index].toInt() and 0xff }
    fun uint(index: Int, length: Int): Long {
        requireSize(index + length)
        return (0 until length).fold(0L) { value, n -> value or (u8(index + n).toLong() shl (8 * n)) }
    }
    fun u16(index: Int) = uint(index, 2).toInt()
    fun u24(index: Int) = uint(index, 3)
    fun u32(index: Int) = uint(index, 4)
}

private data class InterfaceDescriptors(val number: Int, val alternate: Int, val audioClass: Int,
    val subclass: Int, val protocol: Int, val descriptors: MutableList<Descriptor> = mutableListOf())
private data class Association(val first: Int, val count: Int, val audioClass: Int, val protocol: Int)
private class ConfigurationDescriptors(val value: Int, val end: Int) {
    val interfaces = mutableListOf<InterfaceDescriptors>()
    val associations = mutableListOf<Association>()
}

/** UAC1 Audio 1.0 §§4.3/4.5; UAC2 Audio 2.0 §§4.6–4.10 and Formats 2.0 §2.3.1. */
private class UsbAudioDescriptorParser(private val raw: ByteArray) {
    fun parse(): UsbAudioDescriptors {
        val configurations = mutableListOf<ConfigurationDescriptors>()
        var configuration: ConfigurationDescriptors? = null
        var current: InterfaceDescriptors? = null
        var deviceRevision = 0
        var offset = 0
        while (offset < raw.size) {
            require(offset + 2 <= raw.size) { "USB 描述符 @$offset 缺少长度／类型" }
            val length = raw[offset].toInt() and 0xff
            require(length >= 2 && offset + length <= raw.size) { "USB 描述符 @$offset 长度越界：$length" }
            val d = Descriptor(offset, raw.copyOfRange(offset, offset + length))
            if (d.u8(1) != 2 && configuration != null) {
                require(offset + length <= configuration.end) { "USB 描述符 @$offset 超出配置长度" }
            }
            when (d.u8(1)) {
                1 -> { d.requireSize(18); deviceRevision = d.u16(12) }
                2 -> {
                    d.requireSize(9)
                    require(configuration == null || offset == configuration.end) { "USB 配置长度与下一个配置不一致" }
                    val total = d.u16(2)
                    require(total >= length && offset + total <= raw.size) { "USB 配置总长度越界" }
                    configuration = ConfigurationDescriptors(d.u8(5), offset + total).also(configurations::add)
                    current = null
                }
                4 -> {
                    d.requireSize(9)
                    val config = requireNotNull(configuration) { "USB 接口缺少所属配置" }
                    current = InterfaceDescriptors(d.u8(2), d.u8(3), d.u8(5), d.u8(6), d.u8(7))
                        .also(config.interfaces::add)
                }
                11 -> {
                    d.requireSize(8)
                    requireNotNull(configuration) { "USB IAD 缺少所属配置" }.associations +=
                        Association(d.u8(2), d.u8(3), d.u8(4), d.u8(6))
                    current = null
                }
                else -> current?.descriptors?.add(d)
            }
            offset += length
        }
        require(configuration == null || offset == configuration.end) { "USB 配置未完整读取" }
        return UsbAudioDescriptors(configurations.map(::buildConfiguration), deviceRevision)
    }

    private fun buildConfiguration(config: ConfigurationDescriptors): UsbAudioConfiguration {
        val functions = config.interfaces.filter { it.audioClass == 1 && it.subclass == 1 && it.alternate == 0 }
            .mapNotNull { control ->
                val version = when (control.protocol) { 0 -> UsbAudioVersion.UAC1; 0x20 -> UsbAudioVersion.UAC2; else -> return@mapNotNull null }
                val classDescriptors = control.descriptors.filter { it.u8(1) == 0x24 }
                val header = classDescriptors.firstOrNull { it.u8(2) == 1 } ?: return@mapNotNull null
                header.requireSize(if (version == UsbAudioVersion.UAC1) 8 else 9)
                val streamInterfaces = if (version == UsbAudioVersion.UAC1) {
                    val count = header.u8(7)
                    header.requireSize(8 + count)
                    (0 until count).map { header.u8(8 + it) }.toSet()
                } else {
                    // Entity IDs are scoped to an audio function. Never bind another IAD's clock.
                    val iad = config.associations.singleOrNull { it.audioClass == 1 && it.protocol == 0x20 &&
                        control.number in it.first until (it.first + it.count) } ?: return@mapNotNull null
                    (iad.first until (iad.first + iad.count)).toSet()
                }
                val terminals = classDescriptors.filter { it.u8(2) == 2 }.map { inputTerminal(it, version) }
                val clocks = if (version == UsbAudioVersion.UAC2) classDescriptors.mapNotNull(::clock) else emptyList()
                val features = classDescriptors.filter { it.u8(2) == 6 }.map { featureUnit(it, version) }
                val outputs = classDescriptors.filter { it.u8(2) == 3 }.map { d ->
                    d.requireSize(if (version == UsbAudioVersion.UAC1) 9 else 12)
                    UsbAudioOutputTerminal(d.u8(3), d.u16(4), d.u8(7), if (version == UsbAudioVersion.UAC2) d.u8(8) else null)
                }
                val signalUnits = classDescriptors.mapNotNull { signalUnit(it, version) }
                val streams = config.interfaces.filter { it.audioClass == 1 && it.subclass == 2 &&
                    it.protocol == control.protocol && it.number in streamInterfaces }
                val inputsById = terminals.associateBy { it.id }
                val outputsById = outputs.associateBy { it.id }
                val alternates = streams.mapNotNull { streamingAlternate(it, version, inputsById, outputsById, false) }
                val captures = streams.mapNotNull { streamingAlternate(it, version, inputsById, outputsById, true) }
                UsbAudioFunction(control.number, version, terminals.associateBy { it.id },
                    clocks.associateBy { it.id }, features, alternates,
                    outputsById, signalUnits.associateBy { it.id }, captures)
            }
        return UsbAudioConfiguration(config.value, functions)
    }

    private fun inputTerminal(d: Descriptor, version: UsbAudioVersion): UsbAudioInputTerminal {
        d.requireSize(if (version == UsbAudioVersion.UAC1) 12 else 17)
        return if (version == UsbAudioVersion.UAC1) UsbAudioInputTerminal(d.u8(3), d.u16(4), null, d.u8(7), d.u16(8).toLong())
        else UsbAudioInputTerminal(d.u8(3), d.u16(4), d.u8(7), d.u8(8), d.u32(9))
    }

    private fun clock(d: Descriptor): UsbAudioClock? = when (d.u8(2)) {
        0x0a -> { d.requireSize(8); UsbAudioClock.Source(d.u8(3), d.u8(4), d.u8(5)) }
        0x0b -> {
            d.requireSize(5)
            val count = d.u8(4)
            d.requireSize(7 + count)
            UsbAudioClock.Selector(d.u8(3), (0 until count).map { d.u8(5 + it) }, d.u8(5 + count))
        }
        0x0c -> { d.requireSize(7); UsbAudioClock.Multiplier(d.u8(3), d.u8(4), d.u8(5)) }
        else -> null
    }

    private fun featureUnit(d: Descriptor, version: UsbAudioVersion): UsbAudioFeatureUnit {
        d.requireSize(if (version == UsbAudioVersion.UAC1) 8 else 10)
        val width = if (version == UsbAudioVersion.UAC1) d.u8(5) else 4
        val start = if (version == UsbAudioVersion.UAC1) 6 else 5
        require(width in 1..4 && (d.size - start - 1) % width == 0) { "USB Feature Unit 控制位图长度无效" }
        val controls = (start until d.size - 1 step width).map { at ->
            val bits = d.uint(at, width)
            if (version == UsbAudioVersion.UAC1) UsbFeatureChannel(bits and 1L != 0L, bits and 1L != 0L,
                bits and 2L != 0L, bits and 2L != 0L)
            else UsbFeatureChannel(bits and 1L != 0L, bits and 3L == 3L,
                bits and 4L != 0L, bits and 12L == 12L)
        }
        return UsbAudioFeatureUnit(d.u8(3), d.u8(4), controls)
    }

    private fun signalUnit(d: Descriptor, version: UsbAudioVersion): UsbAudioSignalUnit? {
        val subtype = d.u8(2)
        val uac1 = version == UsbAudioVersion.UAC1
        // UAC2 Effect and Sample Rate Converter units have one source; they are not clocks.
        if (!uac1 && subtype in listOf(7, 0x0d)) {
            d.requireSize(8)
            return UsbAudioSignalUnit.Junction(d.u8(3), listOf(d.u8(if (subtype == 7) 6 else 4)))
        }
        val countAt = when {
            subtype == 4 || subtype == 5 -> 4 // Mixer / Selector
            uac1 && subtype in 7..8 || !uac1 && subtype in 8..9 -> 6 // Processing / Extension
            else -> return null
        }
        val count = d.u8(countAt)
        d.requireSize(countAt + 2 + count)
        val sources = (0 until count).map { d.u8(countAt + 1 + it) }
        return if (subtype == 5) UsbAudioSignalUnit.Selector(d.u8(3), sources,
            uac1 || d.u8(countAt + 1 + count) and 1 != 0)
        else UsbAudioSignalUnit.Junction(d.u8(3), sources)
    }

    private fun streamingAlternate(i: InterfaceDescriptors, version: UsbAudioVersion,
        terminals: Map<Int, UsbAudioInputTerminal>, outputs: Map<Int, UsbAudioOutputTerminal>, capture: Boolean): UsbAudioStreamingAlternate? {
        val classDescriptors = i.descriptors.filter { it.u8(1) == 0x24 }
        val general = classDescriptors.firstOrNull { it.u8(2) == 1 } ?: return null
        general.requireSize(if (version == UsbAudioVersion.UAC1) 7 else 16)
        val terminalLink = general.u8(3)
        val terminal = terminals[terminalLink]
        if ((if (capture) outputs[terminalLink]?.type else terminal?.type) != 0x0101) return null
        val format = classDescriptors.firstOrNull { it.u8(2) == 2 } ?: return null
        format.requireSize(4)
        if (format.u8(3) != 1 || (version == UsbAudioVersion.UAC2 && general.u8(5) != 1)) return null
        format.requireSize(if (version == UsbAudioVersion.UAC1) 8 else 6)
        val encodings = if (version == UsbAudioVersion.UAC1) when (general.u16(5)) {
            1 -> setOf(UsbPcmEncoding.SIGNED_INTEGER)
            3 -> setOf(UsbPcmEncoding.IEEE_FLOAT)
            else -> emptySet()
        } else buildSet {
            if (general.u32(6) and 1L != 0L) add(UsbPcmEncoding.SIGNED_INTEGER)
            if (general.u32(6) and 4L != 0L) add(UsbPcmEncoding.IEEE_FLOAT)
        }
        val rawData = version == UsbAudioVersion.UAC2 && general.u32(6) and 0x80000000L != 0L
        if (encodings.isEmpty() && !rawData) return null
        val channels = if (version == UsbAudioVersion.UAC1) format.u8(4) else general.u8(10)
        val subslot = format.u8(if (version == UsbAudioVersion.UAC1) 5 else 4)
        val bits = format.u8(if (version == UsbAudioVersion.UAC1) 6 else 5)
        require(channels > 0 && subslot in 1..4 && bits in 1..(subslot * 8)) { "USB PCM 声道／样本容器无效" }
        val rates = if (version == UsbAudioVersion.UAC1) {
            val count = format.u8(7)
            if (count == 0) {
                format.requireSize(14)
                listOf(UsbAudioRateRange(format.u24(8), format.u24(11), 1))
            } else {
                format.requireSize(8 + count * 3)
                (0 until count).map { format.u24(8 + it * 3).let { rate -> UsbAudioRateRange(rate, rate, 0) } }
            }
        } else emptyList()
        require(rates.all { it.minimum > 0 && it.maximum >= it.minimum }) { "USB PCM 采样率范围无效" }
        val endpoints = endpoints(i, version)
        val data = endpoints.singleOrNull { it.isIsochronous && it.isInput == capture && it.usage != 1 } ?: return null
        val feedback = if (capture) null
        else if (data.syncAddress != 0) endpoints.singleOrNull { it.address == data.syncAddress && it.isInput && it.isIsochronous && it.usage != 2 }
        else endpoints.singleOrNull { it.isIsochronous && it.isInput && it.usage == 1 }
        return UsbAudioStreamingAlternate(i.number, i.alternate, terminalLink, encodings, channels, subslot, bits,
            if (version == UsbAudioVersion.UAC1) terminal?.channelConfig ?: 0 else general.u32(11), rates, data, feedback, rawData)
    }

    private fun endpoints(i: InterfaceDescriptors, version: UsbAudioVersion): List<UsbAudioEndpoint> {
        val endpoints = mutableListOf<UsbAudioEndpoint>()
        for (d in i.descriptors) when (d.u8(1)) {
            5 -> {
                d.requireSize(7)
                endpoints += UsbAudioEndpoint(d.u8(2), d.u8(3), d.u16(4), d.u8(6),
                    if (d.size >= 9) d.u8(7) else 0, if (d.size >= 9) d.u8(8) else 0)
            }
            0x25 -> if (endpoints.isNotEmpty() && d.u8(2) == 1) {
                val uac1 = version == UsbAudioVersion.UAC1
                d.requireSize(if (uac1) 7 else 8)
                endpoints[endpoints.lastIndex] = endpoints.last().copy(
                    samplingFrequencyControl = uac1 && d.u8(3) and 1 != 0,
                    classAttributes = d.u8(3),
                    classControls = if (uac1) 0 else d.u8(4),
                    lockDelayUnits = d.u8(if (uac1) 4 else 5),
                    lockDelay = d.u16(if (uac1) 5 else 6),
                )
            }
            0x30 -> if (endpoints.isNotEmpty()) {
                d.requireSize(6)
                endpoints[endpoints.lastIndex] = endpoints.last().copy(superSpeed = UsbSuperSpeedCompanion(d.u8(2), d.u8(3), d.u16(4)))
            }
        }
        return endpoints
    }
}
