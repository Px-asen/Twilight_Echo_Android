package com.twilightecho.android.coreclient

import java.math.BigDecimal
import java.math.RoundingMode

/** Ranges kept without clamping by the Kotlin/JS validation and the shared C++ biquads together. */
object EqLimits {
    const val MAX_PARAMETRIC_BANDS = 32
    const val PREAMP_DB = 12.0
    const val GAIN_DB = 24.0
    const val MIN_FREQUENCY_HZ = 10.0
    const val MAX_FREQUENCY_HZ = 96000.0
    const val MIN_Q = 0.1
    /** Stored bands accept Q up to 30, but `ParametricEqProcessor.cpp` clamps it to 20. */
    const val MAX_Q = 20.0
    const val ALL_CHANNELS = 0xFFFFFFFFL
    const val GRAPHIC_Q = 1.4
    val GRAPHIC_FREQUENCIES_HZ = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    /** Filter types whose gain the engine applies; the others ignore it. */
    val GAIN_FILTER_TYPES = setOf("peak", "lowShelf", "highShelf")
}

/** The bands the engine runs: parametric bands, or the ten graphic sliders as Q 1.4 peaks. */
fun CoreEqConfigView.effectiveEqBands(): List<CoreParametricEqBand> =
    if (dsp.eqMode == "parametric") dsp.parametricBands
    else EqLimits.GRAPHIC_FREQUENCIES_HZ.mapIndexed { index, frequency ->
        CoreParametricEqBand(frequency, bandsDb.getOrElse(index) { 0.0 }, EqLimits.GRAPHIC_Q)
    }

/** Import keeps the EQ switch on and leaves the DSP bypass to the user. */
fun CoreEqConfigView.withImportedEq(profile: EqTextProfile): CoreEqConfigView = copy(
    enabled = true,
    preampDb = profile.preampDb,
    dsp = dsp.copy(eqMode = "parametric", parametricBands = profile.bands)
)

enum class EqTextFormat(val label: String) { EQUALIZER_APO("EqualizerAPO"), AUTOEQ("AutoEQ"), REW("REW") }

data class EqTextProfile(val format: EqTextFormat, val preampDb: Double, val bands: List<CoreParametricEqBand>)

data class EqTextIssue(val line: Int, val message: String) {
    override fun toString(): String = if (line > 0) "第 $line 行：$message" else message
}

class EqTextImportException(val issues: List<EqTextIssue>) : IllegalArgumentException(issues.joinToString("\n"))

/**
 * Parametric EQ text in Equalizer APO filter syntax, which AutoEQ and REW also write. Every
 * parameter is kept as written; anything the phone cannot reproduce exactly is reported with its
 * line instead of being adjusted.
 */
object EqualizerApoText {
    private const val MAX_CHARS = 256 * 1024
    private const val MAX_LINES = 4096
    private const val BUTTERWORTH_Q = 0.7071067811865476
    private val NUMBER = Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")
    private val FILTER_KEY = Regex("(?i)Filter\\s*\\d*")
    private val SLOPE = Regex("(?i)\\d+(?:\\.\\d+)?dB")
    private val KNOWN_HEADER = Regex("(?i)Equalizer APO.*|Filter Settings file|Room EQ.*|Parametric EQ.*|AutoEq.*")
    private val REW_HEADER = Regex("(?i)^Filter Settings file$|^Room EQ\\b")
    private val UNSUPPORTED_DIRECTIVES = setOf(
        "GRAPHICEQ", "INCLUDE", "COPY", "DELAY", "CONVOLUTION", "STAGE", "IF", "ELSEIF", "ELSE", "ENDIF", "EVAL",
        "LOUDNESSCORRECTION"
    )

    private class Shape(val type: String, val usesGain: Boolean, val fixedQ: Double? = null)

    private val SHAPES = mapOf(
        "PK" to Shape("peak", true), "PEQ" to Shape("peak", true),
        "LSC" to Shape("lowShelf", true), "HSC" to Shape("highShelf", true),
        "LP" to Shape("lowPass", false, BUTTERWORTH_Q), "HP" to Shape("highPass", false, BUTTERWORTH_Q),
        "LPQ" to Shape("lowPass", false), "HPQ" to Shape("highPass", false),
        "BP" to Shape("bandPass", false), "NO" to Shape("notch", false), "AP" to Shape("allPass", false)
    )
    private val CODES = mapOf(
        "peak" to "PK", "lowShelf" to "LSC", "highShelf" to "HSC", "lowPass" to "LPQ", "highPass" to "HPQ",
        "bandPass" to "BP", "notch" to "NO", "allPass" to "AP"
    )

    /** Enough for [parse]'s character limit when a Windows tool saved the file as UTF-16. */
    const val MAX_FILE_BYTES = 2 * MAX_CHARS + 2

    /** Reads UTF-16 when a byte order mark says so, otherwise UTF-8; [parse] drops a UTF-8 mark. */
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }

    fun parse(text: String, fileName: String? = null): EqTextProfile {
        if (text.length > MAX_CHARS) throw EqTextImportException(listOf(EqTextIssue(0, "文件超过 256 KB")))
        if ('\u0000' in text) throw EqTextImportException(listOf(EqTextIssue(0, "不是文本文件")))
        val lines = text.removePrefix("﻿").lines()
        if (lines.size > MAX_LINES) throw EqTextImportException(listOf(EqTextIssue(0, "文件超过 $MAX_LINES 行")))
        val format = detectFormat(lines, fileName)
        // REW puts free-form notes and the measurement name above its first filter.
        val firstFilter = lines.indexOfFirst { FILTER_KEY.matches(it.substringBefore(':', "").trim()) }
        val issues = mutableListOf<EqTextIssue>()
        val bands = mutableListOf<CoreParametricEqBand>()
        var preampDb = 0.0
        var preampLine = 0
        var mask = EqLimits.ALL_CHANNELS
        var devices = 0
        lines.forEachIndexed { index, raw ->
            val number = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//") ||
                KNOWN_HEADER.matches(line)) return@forEachIndexed
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim()
            val directive = key.uppercase()
            if (format == EqTextFormat.REW && index < firstFilter && directive != "PREAMP" && directive != "CHANNEL") {
                return@forEachIndexed
            }
            when {
                directive == "PREAMP" -> {
                    if (mask != EqLimits.ALL_CHANNELS) issues += EqTextIssue(number, "Preamp 只能作用于全部声道")
                    readValue(value, "dB")?.let { preampDb += it; preampLine = number }
                        ?: run { issues += EqTextIssue(number, "Preamp 数值无效") }
                }
                directive == "CHANNEL" -> parseChannels(value, number, issues)?.let { mask = it }
                directive == "DEVICE" -> if (++devices == 2) issues += EqTextIssue(number, "包含多个 Device 段，请分别导入")
                FILTER_KEY.matches(key) -> parseFilter(value, number, mask, issues)?.let { bands += it }
                directive in UNSUPPORTED_DIRECTIVES -> issues += EqTextIssue(number, "不支持 $key 指令")
                else -> issues += EqTextIssue(number, "无法识别的内容")
            }
        }
        if (preampLine > 0 && preampDb !in -EqLimits.PREAMP_DB..EqLimits.PREAMP_DB) {
            issues += EqTextIssue(preampLine, "前级 ${formatNumber(preampDb)} dB 超出 ±12 dB")
        }
        if (bands.size > EqLimits.MAX_PARAMETRIC_BANDS) {
            issues += EqTextIssue(0, "共 ${bands.size} 个滤波器，最多支持 ${EqLimits.MAX_PARAMETRIC_BANDS} 个")
        }
        if (bands.isEmpty() && issues.isEmpty()) issues += EqTextIssue(0, "未找到参数均衡滤波器")
        if (issues.isNotEmpty()) throw EqTextImportException(issues.sortedBy { it.line })
        return EqTextProfile(format, preampDb, bands)
    }

    /** Writes the bands the engine runs, in the AutoEQ layout; channel lines appear only when the mask changes. */
    fun format(config: CoreEqConfigView): String = buildString {
        appendLine("Preamp: ${formatNumber(config.preampDb)} dB")
        var mask = EqLimits.ALL_CHANNELS
        config.effectiveEqBands().forEachIndexed { index, band ->
            val bandMask = band.channelMask and EqLimits.ALL_CHANNELS
            if (bandMask != 0L && bandMask != mask) {
                appendLine("Channel: ${channelNames(bandMask)}")
                mask = bandMask
            }
            // A band without channels is silent, which an OFF filter reproduces.
            val state = if (band.enabled && bandMask != 0L) "ON" else "OFF"
            append("Filter ${index + 1}: $state ${CODES.getValue(band.filterType)} Fc ${formatNumber(band.frequency)} Hz")
            if (band.filterType in EqLimits.GAIN_FILTER_TYPES) append(" Gain ${formatNumber(band.gain)} dB")
            appendLine(" Q ${formatNumber(band.q.coerceIn(EqLimits.MIN_Q, EqLimits.MAX_Q))}")
        }
    }

    fun formatNumber(value: Double): String =
        BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()

    private fun detectFormat(lines: List<String>, fileName: String?): EqTextFormat {
        val head = lines.asSequence().map { it.trim().trimStart('#', ';', '/').trim() }.filter { it.isNotEmpty() }.take(16).toList()
        return when {
            head.any { REW_HEADER.containsMatchIn(it) } -> EqTextFormat.REW
            head.any { it.contains("AutoEq", ignoreCase = true) } ||
                fileName?.contains("ParametricEQ", ignoreCase = true) == true -> EqTextFormat.AUTOEQ
            else -> EqTextFormat.EQUALIZER_APO
        }
    }

    private fun parseChannels(value: String, line: Int, issues: MutableList<EqTextIssue>): Long? {
        val tokens = value.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null.also { issues += EqTextIssue(line, "Channel 未指定声道") }
        if (tokens.size == 1 && (tokens[0].equals("all", ignoreCase = true) || tokens[0] == "*")) return EqLimits.ALL_CHANNELS
        var mask = 0L
        for (token in tokens) {
            val bit = when (token.uppercase()) {
                "L", "FL" -> 0
                "R", "FR" -> 1
                else -> token.toIntOrNull()?.takeIf { it in 1..32 }?.minus(1)
            }
            if (bit == null) return null.also { issues += EqTextIssue(line, "声道 $token 的位置取决于设备布局，请使用声道序号") }
            mask = mask or (1L shl bit)
        }
        return mask
    }

    private fun channelNames(mask: Long): String =
        if (mask == EqLimits.ALL_CHANNELS) "all"
        else (0 until 32).filter { mask and (1L shl it) != 0L }.joinToString(" ") {
            when (it) { 0 -> "L"; 1 -> "R"; else -> "${it + 1}" }
        }

    private fun parseFilter(value: String, line: Int, mask: Long, issues: MutableList<EqTextIssue>): CoreParametricEqBand? {
        val tokens = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val enabled = when (tokens.getOrNull(0)?.uppercase()) {
            "ON" -> true
            "OFF" -> false
            else -> return null.also { issues += EqTextIssue(line, "滤波器缺少 ON／OFF") }
        }
        val code = tokens.getOrNull(1)?.uppercase() ?: return null.also { issues += EqTextIssue(line, "滤波器缺少类型") }
        if (code == "NONE") return null
        if (code == "LS" || code == "HS") {
            return null.also { issues += EqTextIssue(line, "$code 搁架按拐点频率定义，请改用 ${code}C（中心频率与 Q）") }
        }
        val shape = SHAPES[code] ?: return null.also { issues += EqTextIssue(line, "不支持 $code 滤波器") }
        val slope = tokens.getOrNull(2)?.takeIf { SLOPE.matches(it) }
        if (slope != null) return null.also { issues += EqTextIssue(line, "$code $slope 斜率写法暂不支持，请使用 Q") }
        val values = mutableMapOf<String, Double>()
        var index = 2
        while (index < tokens.size) {
            val key = tokens[index].uppercase()
            val unit = when (key) {
                "FC" -> "Hz"
                "GAIN" -> "dB"
                "Q" -> null
                "BW" -> return null.also { issues += EqTextIssue(line, "BW 带宽写法暂不支持，请使用 Q") }
                else -> return null.also { issues += EqTextIssue(line, "无法识别的参数 ${tokens[index]}") }
            }
            val token = tokens.getOrNull(index + 1).orEmpty()
            val number = readNumber(token, unit)
                ?: return null.also { issues += EqTextIssue(line, "${tokens[index]} 数值无效") }
            if (values.put(key, number) != null) return null.also { issues += EqTextIssue(line, "重复的 ${tokens[index]}") }
            index += 2
            // Units are optional and may be attached ("105Hz") or separate ("105 Hz").
            if (unit != null && !token.endsWith(unit, ignoreCase = true) &&
                tokens.getOrNull(index).equals(unit, ignoreCase = true)) index++
        }
        val before = issues.size
        val frequency = values["FC"]
        val gain = values["GAIN"]
        val q = values["Q"]
        if (frequency == null) issues += EqTextIssue(line, "$code 缺少 Fc")
        else if (frequency !in EqLimits.MIN_FREQUENCY_HZ..EqLimits.MAX_FREQUENCY_HZ) {
            issues += EqTextIssue(line, "Fc ${formatNumber(frequency)} Hz 超出 10–96000 Hz")
        }
        if (shape.usesGain) {
            if (gain == null) issues += EqTextIssue(line, "$code 缺少 Gain")
            else if (gain !in -EqLimits.GAIN_DB..EqLimits.GAIN_DB) {
                issues += EqTextIssue(line, "Gain ${formatNumber(gain)} dB 超出 ±24 dB")
            }
        } else if (gain != null && gain != 0.0) {
            issues += EqTextIssue(line, "$code 不使用 Gain（当前 ${formatNumber(gain)} dB）")
        }
        if (shape.fixedQ != null) {
            if (q != null && kotlin.math.abs(q - shape.fixedQ) > 0.0005) {
                issues += EqTextIssue(line, "$code 的 Q 固定为 0.7071，其他 Q 请使用 ${code}Q")
            }
        } else if (q == null) {
            issues += EqTextIssue(line, "$code 缺少 Q")
        } else if (q !in EqLimits.MIN_Q..EqLimits.MAX_Q) {
            issues += EqTextIssue(line, "Q ${formatNumber(q)} 超出 0.1–20")
        }
        if (issues.size > before) return null
        return CoreParametricEqBand(
            frequency = frequency!!,
            gain = if (shape.usesGain) gain!! else 0.0,
            q = if (shape.fixedQ != null) shape.fixedQ else q!!,
            filterType = shape.type,
            enabled = enabled,
            channelMask = mask
        )
    }

    private fun readValue(value: String, unit: String): Double? {
        val tokens = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            tokens.size == 1 -> readNumber(tokens[0], unit)
            tokens.size == 2 && tokens[1].equals(unit, ignoreCase = true) -> readNumber(tokens[0], null)
            else -> null
        }
    }

    private fun readNumber(token: String, unit: String?): Double? {
        val digits = if (unit != null && token.endsWith(unit, ignoreCase = true)) token.dropLast(unit.length) else token
        return digits.takeIf { NUMBER.matches(it) }?.toDouble()?.takeIf { it.isFinite() }
    }
}
