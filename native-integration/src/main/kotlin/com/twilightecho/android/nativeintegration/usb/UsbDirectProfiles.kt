package com.twilightecho.android.nativeintegration.usb

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class UsbDirectProfile(val function: UsbAudioFunction, val stream: UsbAudioStreamingAlternate,
    val format: String, val clockDivisor: Int = 1, val implicitFeedback: UsbAudioStreamingAlternate? = null) {
    fun json(index: Int) = buildJsonObject {
        put("index", index)
        put("uac", if (function.version == UsbAudioVersion.UAC1) 1 else 2)
        put("control", function.controlInterface)
        put("interface", stream.interfaceNumber)
        put("alternate", stream.alternateSetting)
        put("endpoint", stream.dataEndpoint.address)
        put("feedback", stream.feedbackEndpoint?.address ?: 0)
        put("channels", stream.channels)
        put("bits", stream.bitResolution)
        put("format", format)
        put("clockDivisor", clockDivisor)
        implicitFeedback?.let { source -> put("implicitFeedback", buildJsonObject {
            put("interface", source.interfaceNumber); put("alternate", source.alternateSetting)
            put("endpoint", source.dataEndpoint.address); put("frameBytes", source.frameBytes)
        }) }
    }
}

internal fun UsbAudioRateRange.contains(rate: Long): Boolean = rate in minimum..maximum &&
    (resolution == 0L || (rate - minimum) % resolution == 0L)

/** Rank whole alternates, never a Cartesian product of rates and sample containers. */
internal fun usbDirectProfiles(configuration: UsbAudioConfiguration, rate: Int, channels: Int,
    requestedFormat: String, requestedBits: Int, device: UsbDsdDeviceIdentity? = null): List<UsbDirectProfile> {
    val nativeDsd = requestedFormat.startsWith("dsd-int8-")
    val dop = requestedFormat.startsWith("dop-")
    val clockDivisor = if (nativeDsd) 32 else 1
    if (rate <= 0 || rate % clockDivisor != 0) return emptyList()
    val clockRate = rate / clockDivisor
    val candidates = configuration.functions.flatMap { function ->
        function.playbackAlternates.filter { stream ->
            stream.channels == channels && channels in 1..2 &&
                stream.dataEndpoint.sync != UsbIsoSync.NONE &&
                (function.version == UsbAudioVersion.UAC2 || stream.descriptorRates.any { it.contains(clockRate.toLong()) })
        }.flatMap { stream ->
            val needsImplicit = stream.dataEndpoint.sync == UsbIsoSync.ASYNCHRONOUS && stream.feedbackEndpoint == null
            val sources = if (needsImplicit) function.implicitFeedbackSources(stream, clockRate.toLong()) else emptyList()
            if (needsImplicit && sources.isEmpty()) return@flatMap emptyList()
            val formats = buildList {
                if (nativeDsd) {
                    device?.nativeFormat(function, stream)?.let { add(UsbDirectProfile(function, stream, it, clockDivisor)) }
                    return@buildList
                }
                // A known native-DSD alternate must not receive ordinary PCM or DoP words.
                if (device?.nativeFormat(function, stream) != null) return@buildList
                if (dop) {
                    if (UsbPcmEncoding.SIGNED_INTEGER in stream.encodings && stream.bitResolution >= 24) {
                        val format = when (stream.subslotBytes) { 3 -> "int24"; 4 -> "int24-in32"; else -> null }
                        if (format != null) add(UsbDirectProfile(function, stream, format))
                    }
                    return@buildList
                }
                if (UsbPcmEncoding.SIGNED_INTEGER in stream.encodings) {
                    val format = when (stream.subslotBytes to stream.bitResolution) {
                        2 to 16 -> "int16"
                        3 to 24 -> "int24"
                        4 to 24 -> "int24-in32"
                        4 to 32 -> "int32"
                        else -> null
                    }
                    if (format != null) add(UsbDirectProfile(function, stream, format))
                }
                if (UsbPcmEncoding.IEEE_FLOAT in stream.encodings && stream.subslotBytes == 4 && stream.bitResolution == 32)
                    add(UsbDirectProfile(function, stream, "float32"))
            }
            if (needsImplicit) formats.flatMap { profile -> sources.map { profile.copy(implicitFeedback = it) } } else formats
        }
    }
    val pcmFormat = requestedFormat.removePrefix("dop-")
    return candidates.sortedWith(compareBy<UsbDirectProfile> {
        when {
            it.format == pcmFormat -> 0
            pcmFormat.startsWith("int") && it.format.startsWith("int") && it.stream.bitResolution >= requestedBits -> 1
            else -> 2
        }
    }.thenByDescending { it.stream.bitResolution }.thenBy { it.stream.subslotBytes })
}

/** UAC1 bSynchAddress or the UAC2 adjacent, same-alt usage-2 source; no vendor/capture guessing. */
private fun UsbAudioFunction.implicitFeedbackSources(stream: UsbAudioStreamingAlternate, rate: Long) =
    captureAlternates.filter { source ->
        source.interfaceNumber != stream.interfaceNumber && source.dataEndpoint.sync == UsbIsoSync.ASYNCHRONOUS &&
            source.dataEndpoint.interval == stream.dataEndpoint.interval &&
            if (version == UsbAudioVersion.UAC1) stream.dataEndpoint.syncAddress == source.dataEndpoint.address &&
                source.descriptorRates.any { it.contains(rate) }
            else source.interfaceNumber == stream.interfaceNumber + 1 && source.alternateSetting == stream.alternateSetting &&
                source.dataEndpoint.usage == 2 && inputTerminals[stream.terminalLink]?.clockId != null &&
                inputTerminals[stream.terminalLink]?.clockId == outputTerminals[source.terminalLink]?.clockId
    }

internal fun usbDirectLeaseJson(fd: Int, name: String, profiles: List<UsbDirectProfile>): String = buildJsonObject {
    put("fd", fd); put("name", name)
    put("profiles", buildJsonArray { profiles.forEachIndexed { index, profile -> add(profile.json(index)) } })
}.toString()

/** One native-owned lease. Native claims interfaces before calling configure and restores before release. */
internal class UsbAudioPlaybackSession(private val io: UsbAudioControlTransfer, val profiles: List<UsbDirectProfile>,
    private val leaseId: Long = 0) {
    private val restoreRates = mutableListOf<() -> Unit>()
    private var activeProfile: UsbDirectProfile? = null

    fun configure(index: Int, requestedRate: Int) {
        check(restoreRates.isEmpty() && activeProfile == null) { "上一 USB 播放配置尚未释放" }
        val profile = profiles[index]
        val (function, stream) = profile
        require(requestedRate % profile.clockDivisor == 0) { "USB DSD 位时钟不能整除传输字宽" }
        val rate = requestedRate / profile.clockDivisor
        val controls = UsbAudioControls(io, function)
        if (function.version == UsbAudioVersion.UAC1) {
            configureUac1(controls, stream, rate.toLong())
            profile.implicitFeedback?.let { configureUac1(controls, it, rate.toLong()) }
        } else {
            val original = controls.readClock(stream)
            val sourceRate = requireNotNull(original.sourceRateFor(rate.toLong())) { "USB 倍频时钟无法精确产生 $rate Hz" }
            require(original.sourceRanges.any { it.contains(sourceRate) }) { "USB 当前时钟源不支持 $rate Hz" }
            if (original.sourceCurrentRate != sourceRate) {
                val source = function.clocks[original.sourceId] as UsbAudioClock.Source
                require(source.controls and 3 == 3) { "USB 当前时钟源频率为只读" }
                restoreRates += { controls.setClockSourceRate(original.sourceId, original.sourceCurrentRate) }
                controls.setClockSourceRate(original.sourceId, sourceRate)
            }
            val actual = controls.readClock(stream)
            check(actual.playbackRate == rate.toLong() && actual.sourceId == original.sourceId &&
                actual.selectedInputs == original.selectedInputs && actual.valid != false) {
                "USB 播放时钟未锁定到请求频率或时钟路径改变"
            }
            profile.implicitFeedback?.let { source ->
                val feedbackClock = controls.readCaptureClock(source)
                check(feedbackClock.playbackRate == rate.toLong() && feedbackClock.sourceId == actual.sourceId &&
                    feedbackClock.numerator == actual.numerator && feedbackClock.denominator == actual.denominator &&
                    feedbackClock.selectedInputs == actual.selectedInputs && feedbackClock.valid != false) {
                    "USB 隐式反馈源未使用同一播放时钟"
                }
            }
        }
        activeProfile = profile
    }

    private fun configureUac1(controls: UsbAudioControls, stream: UsbAudioStreamingAlternate, rate: Long) {
        require(stream.descriptorRates.any { it.contains(rate) }) { "USB 备用接口不支持 $rate Hz" }
        if (stream.dataEndpoint.samplingFrequencyControl) {
            val original = controls.readUac1CurrentRate(stream)
            if (original != rate) {
                restoreRates += { controls.setUac1Rate(stream, original) }
                controls.setUac1Rate(stream, rate)
            }
        } else require(stream.descriptorRates.all { it.minimum == rate && it.maximum == rate }) {
            "USB 备用接口有多个采样率但未声明采样率控制"
        }
    }

    fun readHardwareVolume(): UsbHardwareVolumeSnapshot {
        val profile = checkNotNull(activeProfile) { "USB 播放接口尚未接管或已释放" }
        val controls = UsbAudioControls(io, profile.function)
        val channels = controls.playbackFeatureUnits(profile.stream).flatMap { unit ->
            unit.channels.mapIndexedNotNull { channel, capability ->
                if (!capability.volumeReadable && !capability.muteReadable) null
                else UsbHardwareChannel(UsbHardwareControlId(leaseId, unit.id, channel),
                    if (capability.volumeReadable) controls.readVolume(unit.id, channel) else null, capability.volumeWritable,
                    if (capability.muteReadable) controls.readMute(unit.id, channel) else null, capability.muteWritable)
            }
        }
        return UsbHardwareVolumeSnapshot(channels)
    }

    fun setHardwareVolume(id: UsbHardwareControlId, db256: Int): UsbAudioVolumeSnapshot =
        controlsFor(id).setVolume(id.unitId, id.channel, db256)

    fun setHardwareMute(id: UsbHardwareControlId, muted: Boolean): Boolean =
        controlsFor(id).setMute(id.unitId, id.channel, muted)

    private fun controlsFor(id: UsbHardwareControlId): UsbAudioControls {
        check(id.leaseId == leaseId) { "USB 连接已改变，请重新读取 DAC 音量" }
        val profile = checkNotNull(activeProfile) { "USB 播放接口已释放，请点播放后重新读取" }
        return UsbAudioControls(io, profile.function).also { controls ->
            require(controls.playbackFeatureUnits(profile.stream).any { it.id == id.unitId }) {
                "此音量控制不在当前 USB 播放路径上，请重新读取"
            }
        }
    }

    fun restore() {
        activeProfile = null
        val actions = restoreRates.reversed()
        restoreRates.clear()
        var failure: Exception? = null
        actions.forEach { action ->
            try { action() } catch (error: Exception) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
