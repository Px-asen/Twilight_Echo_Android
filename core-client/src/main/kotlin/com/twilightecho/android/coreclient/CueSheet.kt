package com.twilightecho.android.coreclient

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/**
 * One song inside a single audio file. Positions in the song start at 0; the decoder reads from [startSeconds]
 * in the file. The parsing and splitting rules are the desktop's (`src/shared/cue.ts`, `cueLibrary.ts`), so a
 * sheet splits the same way on both.
 */
@Serializable
data class CueRange(
    /** INDEX 01 in the audio file. */
    val startSeconds: Double,
    /** The next song's INDEX 01, or the end of the file. */
    val endSeconds: Double,
    /** Silence before INDEX 01; only an explicit PREGAP creates it. */
    val virtualPregapSeconds: Double = 0.0,
    /** INDEX 00 to INDEX 01. That audio stays at the end of the previous song. */
    val sourcePregapSeconds: Double = 0.0,
    /** Legacy presentation value; it does not create synthetic silence. */
    val pregapSeconds: Double = if (virtualPregapSeconds != 0.0) virtualPregapSeconds else sourcePregapSeconds
) {
    val durationSeconds: Double get() = virtualPregapSeconds + endSeconds - startSeconds

    /** The engine refuses a whole queue that holds any other range. */
    val isValid: Boolean get() = startSeconds.isFinite() && endSeconds.isFinite() && virtualPregapSeconds.isFinite() &&
        sourcePregapSeconds.isFinite() && startSeconds >= 0 && endSeconds > startSeconds && endSeconds <= MAX_SAFE_INTEGER &&
        virtualPregapSeconds >= 0 && sourcePregapSeconds >= 0 && sourcePregapSeconds <= startSeconds &&
        pregapSeconds.isFinite() && pregapSeconds >= 0

    private companion object { const val MAX_SAFE_INTEGER = 9007199254740991.0 }
}

class CueParseException(message: String) : Exception(message)

/** A sheet before the audio file's length is known. [encoding] is `utf-8-bom`, `utf-8`, `gbk` or `gb18030`. */
data class CueDefinition(
    val encoding: String,
    val fileName: String,
    val title: String? = null,
    val performer: String? = null,
    val tracks: List<CueDefinitionTrack>
)

data class CueDefinitionTrack(
    val number: Int,
    val title: String? = null,
    val performer: String? = null,
    val index00: Double? = null,
    val index01: Double,
    val pregapSeconds: Double = 0.0
)

data class CueSheet(
    val encoding: String,
    val fileName: String,
    val title: String?,
    val performer: String?,
    val tracks: List<CueTrack>
)

data class CueTrack(val number: Int, val title: String?, val performer: String?, val range: CueRange)

/** A file in one folder of a library scan. [key] identifies it across scans; [name] is its listed name. */
data class FolderFile(val key: String, val name: String)

/** A sheet that did not split anything, and why, in words the library page shows. */
data class CueSkip(val sheetName: String, val reason: String)

data class CueCandidate(val sheet: FolderFile, val definition: CueDefinition)

/** Each audio file named by a sheet in its folder, with those sheets; [skipped] names the sheets that fit no file. */
data class CueClaims(val byAudio: Map<String, List<CueCandidate>>, val skipped: List<CueSkip>)

/** [tracks] is empty when the file stays one song. */
data class CueSplit(val tracks: List<CoreTrackView>, val skipped: List<CueSkip>)

object CueSheets {
    const val MAX_BYTES = 2 * 1024 * 1024

    /** What a sheet may split: the desktop's formats that the phone's decoder plays. */
    val AUDIO_EXTENSIONS = setOf("flac", "wav", "wave", "mp3", "m4a", "mp4", "alac", "aac", "ogg", "opus", "aiff", "aif",
        "ape", "wv")

    private const val FRAME_RATE = 75
    /** JavaScript's `\s`, which the desktop parser matches with. */
    private const val SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val REM = Regex("^REM(?:[$SPACE]|$)", RegexOption.IGNORE_CASE)
    private val FILE = Regex("^FILE[$SPACE]+(?:\"([^\"]+)\"|([^$SPACE]+))[$SPACE]+[^$SPACE]+[$SPACE]*$", RegexOption.IGNORE_CASE)
    private val TRACK = Regex("^TRACK[$SPACE]+(\\d{1,3})[$SPACE]+([^$SPACE]+)[$SPACE]*$", RegexOption.IGNORE_CASE)
    private val TITLE = textDirective("TITLE")
    private val PERFORMER = textDirective("PERFORMER")
    private val INDEX = Regex("^INDEX[$SPACE]+(00|01)[$SPACE]+(\\d{1,3}:\\d{1,2}:\\d{1,2})[$SPACE]*$", RegexOption.IGNORE_CASE)
    private val PREGAP = Regex("^PREGAP[$SPACE]+(\\d{1,3}:\\d{1,2}:\\d{1,2})[$SPACE]*$", RegexOption.IGNORE_CASE)
    private val TIME = Regex("^(\\d{1,3}):(\\d{1,2}):(\\d{1,2})$")
    /** A legacy decoding counts only if the text then reads as a sheet. */
    private val SHEET_LINE = Regex("^[$SPACE]*(FILE|TRACK|TITLE|PERFORMER)(?![A-Za-z0-9_])",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private fun textDirective(name: String) =
        Regex("^$name[$SPACE]+(?:\"([^\"]*)\"|(.*?))[$SPACE]*$", RegexOption.IGNORE_CASE)

    /**
     * The sheet's text and encoding. Chinese sheets are often GBK or GB18030; a decoding is never guessed
     * silently, so text that fits none of the supported encodings is refused.
     */
    fun decode(bytes: ByteArray): Pair<String, String> {
        if (bytes.isEmpty()) fail("CUE 文件是空的")
        if (bytes.size > MAX_BYTES) fail("CUE 文件超过 2 MB")
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return (strict(bytes, 3, Charsets.UTF_8) ?: fail("CUE 文件不是有效的 UTF-8")) to "utf-8-bom"
        }
        strict(bytes, 0, Charsets.UTF_8)?.let { return it to "utf-8" }
        // Four-byte sequences exist only in GB18030, so the reported encoding stays truthful.
        val legacy = if (hasGb18030FourByteSequence(bytes)) listOf("gb18030") else listOf("gbk", "gb18030")
        for (name in legacy) {
            val text = strict(bytes, 0, Charset.forName(name)) ?: continue
            if (SHEET_LINE.containsMatchIn(text)) return text to name
        }
        fail("无法识别 CUE 文件编码，支持 UTF-8、GBK 和 GB18030")
    }

    fun parse(bytes: ByteArray): CueDefinition {
        val (text, encoding) = decode(bytes)
        var fileName = ""
        var title: String? = null
        var performer: String? = null
        var current: Draft? = null
        val tracks = mutableListOf<CueDefinitionTrack>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trim(::isSpace)
            if (line.isEmpty() || REM.containsMatchIn(line)) continue
            val file = FILE.find(line)
            if (file != null) {
                val next = (file.groups[1]?.value ?: file.groups[2]?.value ?: "").trim(::isSpace)
                if (next.isEmpty()) fail("FILE 没有文件名")
                if (fileName.isNotEmpty() && !sameFileName(fileName, next)) fail("只支持引用单个音频文件的 CUE")
                fileName = next
                continue
            }
            val track = TRACK.find(line)
            if (track != null) {
                if (fileName.isEmpty()) fail("TRACK 出现在 FILE 之前")
                if (current != null) tracks += current.finish()
                if (!track.groupValues[2].equals("AUDIO", ignoreCase = true)) fail("只支持 AUDIO 音轨")
                current = Draft(track.groupValues[1].toInt())
                continue
            }
            val trackTitle = text(line, TITLE)
            if (trackTitle != null) {
                if (current != null) current.title = trackTitle else title = trackTitle
                continue
            }
            val trackPerformer = text(line, PERFORMER)
            if (trackPerformer != null) {
                if (current != null) current.performer = trackPerformer else performer = trackPerformer
                continue
            }
            val index = INDEX.find(line)
            if (index != null) {
                val draft = current ?: fail("INDEX 不在任何 TRACK 内")
                val seconds = parseTime(index.groupValues[2])
                if (index.groupValues[1] == "00") draft.index00 = seconds else draft.index01 = seconds
                continue
            }
            val pregap = PREGAP.find(line)
            if (pregap != null) {
                val draft = current ?: fail("PREGAP 不在任何 TRACK 内")
                draft.pregap = parseTime(pregap.groupValues[1])
            }
        }
        if (current != null) tracks += current.finish()
        if (fileName.isEmpty()) fail("CUE 缺少 FILE")
        if (tracks.isEmpty()) fail("CUE 中没有 AUDIO 音轨")
        val numbers = HashSet<Int>()
        for (track in tracks) {
            if (track.number !in 1..99 || !numbers.add(track.number)) fail("音轨编号 ${track.number} 无效或重复")
        }
        return CueDefinition(encoding, fileName, title, performer, tracks)
    }

    /**
     * Ranges against the audio file's length. A song ends at the next song's INDEX 01, so INDEX 00 audio stays
     * at the end of the previous song and an album plays without holes; PREGAP adds silence instead.
     */
    fun resolve(definition: CueDefinition, durationSeconds: Double): CueSheet {
        if (!durationSeconds.isFinite() || durationSeconds <= 0) fail("无法读取音频时长")
        val tracks = definition.tracks.mapIndexed { index, track ->
            val start = track.index01
            val end = definition.tracks.getOrNull(index + 1)?.index01 ?: durationSeconds
            if (end <= start) fail("第 ${track.number} 轨的时间为空或与下一轨重叠")
            if (start < 0 || end > durationSeconds + 0.05) fail("第 ${track.number} 轨超出音频文件长度")
            val sourcePregap = track.index00?.let { maxOf(0.0, start - it) } ?: 0.0
            CueTrack(track.number, track.title, track.performer,
                CueRange(start, minOf(end, durationSeconds), track.pregapSeconds, sourcePregap))
        }
        return CueSheet(definition.encoding, definition.fileName, definition.title, definition.performer, tracks)
    }

    /** `mm:ss:ff`, with 75 frames a second. */
    fun parseTime(value: String): Double {
        val match = TIME.find(value) ?: fail("时间格式无效：$value")
        val (minutes, seconds, frames) = match.destructured.toList().map { it.toInt() }
        if (seconds >= 60 || frames >= FRAME_RATE) fail("时间格式无效：$value")
        return minutes * 60 + seconds + frames / FRAME_RATE.toDouble()
    }

    /**
     * The file a sheet's FILE names inside the sheet's own folder; null for a path into any other folder. The
     * desktop only splits audio that sits beside its sheet as well.
     */
    fun siblingName(fileName: String): String? {
        var name = fileName.trim(::isSpace).replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        return name.takeIf { it.isNotEmpty() && '/' !in it && it != "." && it != ".." }
    }

    /**
     * Which audio file each sheet in a folder names. Names compare without case, as on the desktop's Windows
     * file system, unless two files differ only by case.
     */
    fun claims(audio: List<FolderFile>, sheets: List<FolderFile>, read: (FolderFile) -> ByteArray): CueClaims {
        val byName = audio.groupBy { it.name.lowercase(Locale.ROOT) }
        val claimed = LinkedHashMap<String, MutableList<CueCandidate>>()
        val skipped = mutableListOf<CueSkip>()
        for (sheet in sheets) {
            val definition = try {
                parse(read(sheet))
            } catch (error: CueParseException) {
                skipped += CueSkip(sheet.name, error.message.orEmpty())
                continue
            } catch (_: Exception) {
                skipped += CueSkip(sheet.name, "无法读取 CUE 文件")
                continue
            }
            val name = siblingName(definition.fileName)
            if (name == null) {
                skipped += CueSkip(sheet.name, "引用的音频不在同一文件夹：${definition.fileName}")
                continue
            }
            if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in AUDIO_EXTENSIONS) {
                skipped += CueSkip(sheet.name, "不支持引用的音频格式：$name")
                continue
            }
            val candidates = byName[name.lowercase(Locale.ROOT)].orEmpty()
            val target = candidates.firstOrNull { it.name == name } ?: candidates.singleOrNull()
            if (target == null) {
                skipped += CueSkip(sheet.name, "找不到引用的音频“$name”")
                continue
            }
            claimed.getOrPut(target.key) { mutableListOf() } += CueCandidate(sheet, definition)
        }
        return CueClaims(claimed, skipped)
    }

    /**
     * Splits [base], the audio file's own song, by the sheets naming it. Exactly one sheet must fit the file's
     * length, as on the desktop; otherwise the file stays one song.
     */
    fun split(base: CoreTrackView, audioKey: String, candidates: List<CueCandidate>): CueSplit {
        val skipped = mutableListOf<CueSkip>()
        val fitting = candidates.mapNotNull { candidate ->
            try {
                candidate to resolve(candidate.definition, base.duration)
            } catch (error: CueParseException) {
                skipped += CueSkip(candidate.sheet.name, error.message.orEmpty())
                null
            }
        }
        if (fitting.size > 1) {
            return CueSplit(emptyList(), skipped + fitting.map { CueSkip(it.first.sheet.name, "多个 CUE 引用同一音频文件") })
        }
        val (candidate, sheet) = fitting.singleOrNull() ?: return CueSplit(emptyList(), skipped)
        return CueSplit(sheet.project(base, audioKey, candidate.sheet.key), skipped)
    }

    private fun text(line: String, directive: Regex): String? =
        directive.find(line)?.let { (it.groups[1]?.value ?: it.groups[2]?.value ?: "").trim(::isSpace) }

    private fun sameFileName(left: String, right: String) =
        left.replace('\\', '/').lowercase(Locale.ROOT) == right.replace('\\', '/').lowercase(Locale.ROOT)

    private fun strict(bytes: ByteArray, offset: Int, charset: Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun hasGb18030FourByteSequence(bytes: ByteArray): Boolean {
        fun at(index: Int) = bytes[index].toInt() and 0xFF
        for (index in 0 until bytes.size - 3) {
            if (at(index) in 0x81..0xFE && at(index + 1) in 0x30..0x39 && at(index + 2) in 0x81..0xFE &&
                at(index + 3) in 0x30..0x39) return true
        }
        return false
    }

    private fun isSpace(char: Char) = when (char) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        '　', '﻿' -> true
        else -> char in ' '..' '
    }

    private class Draft(val number: Int) {
        var title: String? = null
        var performer: String? = null
        var index00: Double? = null
        var index01: Double? = null
        var pregap = 0.0

        fun finish(): CueDefinitionTrack {
            val start = index01
            if (start == null || start < 0) fail("第 $number 轨缺少 INDEX 01")
            val lead = index00
            if (lead != null && lead > start) fail("第 $number 轨的 INDEX 00 晚于 INDEX 01")
            return CueDefinitionTrack(number, title, performer, lead, start, pregap)
        }
    }
}

private fun fail(message: String): Nothing = throw CueParseException(message)

/**
 * The sheet's songs as library songs over [base], the audio file's own song, projected as the desktop does.
 * [audioKey] and [sheetKey] identify the two files, so a song keeps its ID across scans.
 */
fun CueSheet.project(base: CoreTrackView, audioKey: String, sheetKey: String): List<CoreTrackView> {
    val audio = base.source ?: base.id
    return tracks.map { track ->
        val performer = track.performer.orEmpty().ifEmpty { performer.orEmpty() }
        base.copy(
            id = cueTrackId(audioKey, sheetKey, track.number),
            queueEntryId = null,
            title = track.title.orEmpty().ifEmpty { "${track.number.toString().padStart(2, '0')}. ${base.title}" },
            artist = performer.ifEmpty { base.artist },
            artists = if (performer.isEmpty()) base.artists else listOf(performer),
            album = title.orEmpty().ifEmpty { base.album },
            // MediaStore's album ID names the whole file's album, which a sheet with its own title replaces.
            albumId = base.albumId.takeIf { title.isNullOrEmpty() },
            source = audio,
            cueRange = track.range,
            duration = track.range.durationSeconds,
            trackNumber = track.number,
            // A file's ISRC names the whole recording, not one song in it.
            isrc = null
        )
    }
}

/** The desktop's ID scheme for a song in a sheet: stable while neither file moves. */
fun cueTrackId(audioKey: String, sheetKey: String, number: Int): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$audioKey\u0000$sheetKey\u0000$number".toByteArray(Charsets.UTF_8))
    return "local:cue:" + digest.joinToString("") { "%02x".format(it) }.take(24)
}

/**
 * Compatibility with saved sources in `<audio>#cue=<start>,<end>[,<pregap>,<source pregap>]` form.
 * New library rows use the shared `cueRange` field and keep the audio URI unchanged.
 */
object CueSource {
    private const val MARK = "#cue="
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?(?:E-?\\d+)?")

    fun encode(source: String, range: CueRange): String {
        val pregaps = if (range.virtualPregapSeconds == 0.0 && range.sourcePregapSeconds == 0.0) emptyList()
            else listOf(range.virtualPregapSeconds, range.sourcePregapSeconds)
        return source + MARK + (listOf(range.startSeconds, range.endSeconds) + pregaps).joinToString(",")
    }

    /** The audio file and range of an [encode]d source; any other source is the file itself, with no range. */
    fun decode(source: String): Pair<String, CueRange?> {
        val at = source.lastIndexOf(MARK)
        if (at <= 0) return source to null
        val parts = source.substring(at + MARK.length).split(',')
        if ((parts.size != 2 && parts.size != 4) || parts.any { !NUMBER.matches(it) }) return source to null
        val values = parts.map { it.toDouble() }
        val range = CueRange(values[0], values[1], values.getOrElse(2) { 0.0 }, values.getOrElse(3) { 0.0 })
        return if (range.isValid) source.substring(0, at) to range else source to null
    }

    /** The file to open for [source]. */
    fun audio(source: String): String = decode(source).first
}
