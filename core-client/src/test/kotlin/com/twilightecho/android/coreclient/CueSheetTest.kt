package com.twilightecho.android.coreclient

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset
import java.security.MessageDigest

class CueSheetTest {
    private fun bytes(text: String, charset: Charset = Charsets.UTF_8) = text.toByteArray(charset)
    private fun refused(block: () -> Unit) = assertThrows(CueParseException::class.java) { block() }.message

    private val album = """
        REM GENRE Classical
        REM COMMENT "ExactAudioCopy v1.6"
        PERFORMER "乐团"
        TITLE "夜曲集"
        FILE "Album.flac" WAVE
          TRACK 01 AUDIO
            TITLE "第一首"
            PERFORMER "独奏"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "第二首"
            FLAGS DCP
            ISRC USABC1234567
            INDEX 00 03:58:50
            INDEX 01 04:00:00
          TRACK 03 AUDIO
            PREGAP 00:02:00
            INDEX 01 07:30:37
    """.trimIndent()

    @Test
    fun `an EAC sheet splits into continuous ranges with source and virtual pregaps`() {
        val definition = CueSheets.parse(bytes("\uFEFF" + album))
        assertEquals("utf-8-bom", definition.encoding)
        assertEquals("Album.flac", definition.fileName)
        assertEquals("夜曲集", definition.title)
        assertEquals("乐团", definition.performer)
        assertEquals(listOf(1, 2, 3), definition.tracks.map { it.number })

        val sheet = CueSheets.resolve(definition, 600.0)
        val (first, second, third) = sheet.tracks
        assertEquals(CueRange(0.0, 240.0), first.range)
        assertEquals("独奏", first.performer)
        // INDEX 00 audio stays on the first song; the second still starts at its INDEX 01.
        assertEquals(240.0, second.range.startSeconds, 0.0)
        assertEquals(240.0 - (3 * 60 + 58 + 50 / 75.0), second.range.sourcePregapSeconds, 0.0)
        assertEquals(second.range.sourcePregapSeconds, second.range.pregapSeconds, 0.0)
        assertEquals(0.0, second.range.virtualPregapSeconds, 0.0)
        // One song's end is exactly the next song's start, so playback continues without a gap.
        assertEquals(first.range.endSeconds, second.range.startSeconds, 0.0)
        assertEquals(second.range.endSeconds, third.range.startSeconds, 0.0)
        assertEquals(450 + 37 / 75.0, third.range.startSeconds, 0.0)
        // PREGAP is silence before the third song, counted in its length.
        assertEquals(2.0, third.range.virtualPregapSeconds, 0.0)
        assertEquals(600.0, third.range.endSeconds, 0.0)
        assertEquals(2.0 + 600.0 - third.range.startSeconds, third.range.durationSeconds, 0.0)
        assertNull(third.title)
        assertTrue(sheet.tracks.all { it.range.isValid })
    }

    @Test
    fun `Chinese sheets decode as UTF-8, GBK or GB18030 and say which`() {
        assertEquals("utf-8", CueSheets.parse(bytes(album)).encoding)
        val gbk = CueSheets.parse(bytes(album, Charset.forName("GBK")))
        assertEquals("gbk", gbk.encoding)
        assertEquals("夜曲集", gbk.title)
        assertEquals("第二首", gbk.tracks[1].title)
        // 𠀀 exists only in GB18030, as a four-byte sequence.
        val gb18030 = CueSheets.parse(bytes(album.replace("夜曲集", "夜曲𠀀"), Charset.forName("GB18030")))
        assertEquals("gb18030", gb18030.encoding)
        assertEquals("夜曲𠀀", gb18030.title)

        assertEquals("CUE 文件是空的", refused { CueSheets.parse(ByteArray(0)) })
        assertEquals("CUE 文件超过 2 MB", refused { CueSheets.parse(ByteArray(CueSheets.MAX_BYTES + 1) { 'A'.code.toByte() }) })
        assertEquals("CUE 文件不是有效的 UTF-8", refused { CueSheets.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0xC3.toByte())) })
        // Bytes that decode in GBK but do not read as a sheet are not guessed at.
        assertEquals("无法识别 CUE 文件编码，支持 UTF-8、GBK 和 GB18030", refused { CueSheets.parse(bytes("夜曲", Charset.forName("GBK"))) })
    }

    @Test
    fun `sheets the desktop refuses are refused with the reason`() {
        fun sheet(vararg lines: String) = bytes(lines.joinToString("\r\n"))
        assertEquals("只支持引用单个音频文件的 CUE",
            refused { CueSheets.parse(sheet("FILE a.flac WAVE", "TRACK 01 AUDIO", "INDEX 01 00:00:00", "FILE b.flac WAVE")) })
        // The same file, written with another case or separator, is still one file.
        assertEquals("CD\\A.flac", CueSheets.parse(sheet("FILE \"cd/a.flac\" WAVE", "TRACK 01 AUDIO", "INDEX 01 00:00:00",
            "FILE \"CD\\A.flac\" WAVE")).fileName)
        assertEquals("TRACK 出现在 FILE 之前", refused { CueSheets.parse(sheet("TRACK 01 AUDIO", "INDEX 01 00:00:00")) })
        assertEquals("只支持 AUDIO 音轨", refused { CueSheets.parse(sheet("FILE a.bin BINARY", "TRACK 01 MODE1/2352", "INDEX 01 00:00:00")) })
        assertEquals("第 1 轨缺少 INDEX 01", refused { CueSheets.parse(sheet("FILE a.flac WAVE", "TRACK 01 AUDIO", "INDEX 00 00:00:00")) })
        assertEquals("第 2 轨的 INDEX 00 晚于 INDEX 01", refused {
            CueSheets.parse(sheet("FILE a.flac WAVE", "TRACK 01 AUDIO", "INDEX 01 00:00:00", "TRACK 02 AUDIO", "INDEX 00 01:00:01",
                "INDEX 01 01:00:00"))
        })
        assertEquals("音轨编号 1 无效或重复", refused {
            CueSheets.parse(sheet("FILE a.flac WAVE", "TRACK 01 AUDIO", "INDEX 01 00:00:00", "TRACK 1 AUDIO", "INDEX 01 00:01:00"))
        })
        assertEquals("音轨编号 100 无效或重复", refused { CueSheets.parse(sheet("FILE a.flac WAVE", "TRACK 100 AUDIO", "INDEX 01 00:00:00")) })
        assertEquals("INDEX 不在任何 TRACK 内", refused { CueSheets.parse(sheet("FILE a.flac WAVE", "INDEX 01 00:00:00")) })
        assertEquals("CUE 缺少 FILE", refused { CueSheets.parse(sheet("TITLE x")) })
        assertEquals("CUE 中没有 AUDIO 音轨", refused { CueSheets.parse(sheet("FILE a.flac WAVE")) })
        assertEquals("FILE 没有文件名", refused { CueSheets.parse(sheet("FILE \"  \" WAVE")) })
        assertEquals("时间格式无效：00:60:00", refused { CueSheets.parseTime("00:60:00") })
        assertEquals("时间格式无效：00:00:75", refused { CueSheets.parseTime("00:00:75") })
        assertEquals(62 + 3 / 75.0, CueSheets.parseTime("01:02:03"), 0.0)
        assertEquals(999 * 60 + 59 + 74 / 75.0, CueSheets.parseTime("999:59:74"), 0.0)
    }

    @Test
    fun `ranges must fit the audio file`() {
        val definition = CueSheets.parse(bytes("FILE a.flac WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 01:40:00"))
        assertEquals("无法读取音频时长", refused { CueSheets.resolve(definition, 0.0) })
        assertEquals("无法读取音频时长", refused { CueSheets.resolve(definition, Double.NaN) })
        assertEquals("第 1 轨超出音频文件长度", refused { CueSheets.resolve(definition, 90.0) })
        // A start that rounding puts just past the end leaves the last song empty.
        assertEquals("第 2 轨的时间为空或与下一轨重叠", refused { CueSheets.resolve(definition, 99.98) })
        // Each song ends where the next starts; the last one at the end of the file.
        val three = CueSheets.parse(bytes("FILE a.flac WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 01:40:00\nTRACK 03 AUDIO\nINDEX 01 01:50:00"))
        assertEquals(listOf(100.0, 110.0, 110.02), CueSheets.resolve(three, 110.02).tracks.map { it.range.endSeconds })
    }

    @Test
    fun `a sheet splits only audio beside it, and a file named by two fitting sheets stays whole`() {
        assertEquals("Album.flac", CueSheets.siblingName(".\\Album.flac"))
        assertEquals("a b.flac", CueSheets.siblingName("  a b.flac "))
        assertNull(CueSheets.siblingName("CD1/a.flac"))
        assertNull(CueSheets.siblingName("..\\a.flac"))
        assertNull(CueSheets.siblingName("C:\\Music\\a.flac"))

        val audio = listOf(FolderFile("doc:Album.flac", "Album.flac"), FolderFile("doc:Live.ape", "Live.ape"))
        val texts = mapOf(
            "album.cue" to "FILE \"album.FLAC\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 01:00:00",
            "gone.cue" to "FILE \"Gone.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00",
            "sub.cue" to "FILE \"CD1\\a.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00",
            "wma.cue" to "FILE \"a.wma\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00",
            "bad.cue" to "FILE a.flac WAVE\nTRACK 01 AUDIO",
            "live-a.cue" to "FILE Live.ape WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 00:30:00",
            "live-b.cue" to "FILE live.ape WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 00:40:00",
            "live-long.cue" to "FILE Live.ape WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 59:00:00"
        )
        val sheets = (texts.keys + "unreadable.cue").map { FolderFile("doc:$it", it) }
        val claims = CueSheets.claims(audio, sheets) { file -> texts[file.name]?.let(::bytes) ?: throw java.io.IOException() }
        assertEquals(listOf("album.cue"), claims.byAudio["doc:Album.flac"]!!.map { it.sheet.name })
        assertEquals(listOf("live-a.cue", "live-b.cue", "live-long.cue"), claims.byAudio["doc:Live.ape"]!!.map { it.sheet.name })
        assertEquals(listOf(
            CueSkip("gone.cue", "找不到引用的音频“Gone.wav”"),
            CueSkip("sub.cue", "引用的音频不在同一文件夹：CD1\\a.flac"),
            CueSkip("wma.cue", "不支持引用的音频格式：a.wma"),
            CueSkip("bad.cue", "第 1 轨缺少 INDEX 01"),
            CueSkip("unreadable.cue", "无法读取 CUE 文件")
        ), claims.skipped)

        val live = CoreTrackView("mediastore-7", title = "Live", source = "content://live", duration = 3000.0)
        val ambiguous = CueSheets.split(live, "doc:Live.ape", claims.byAudio.getValue("doc:Live.ape"))
        assertEquals(emptyList<CoreTrackView>(), ambiguous.tracks)
        assertEquals(listOf(
            CueSkip("live-long.cue", "第 1 轨超出音频文件长度"),
            CueSkip("live-a.cue", "多个 CUE 引用同一音频文件"),
            CueSkip("live-b.cue", "多个 CUE 引用同一音频文件")
        ), ambiguous.skipped)
        // A sheet that does not fit the file's length drops out first; the one left splits it.
        val shorter = CueSheets.split(live.copy(duration = 2000.0), "doc:Live.ape",
            claims.byAudio.getValue("doc:Live.ape").filter { it.sheet.name != "live-b.cue" })
        assertEquals(listOf("01. Live", "02. Live"), shorter.tracks.map { it.title })
        assertEquals(listOf(CueSkip("live-long.cue", "第 1 轨超出音频文件长度")), shorter.skipped)
    }

    @Test
    fun `split songs carry stable IDs, the sheet's tags and the file's own facts`() {
        val base = CoreTrackView("mediastore-42", queueEntryId = "occ-ms-42", title = "Album", artist = "File Artist",
            artists = listOf("File Artist"), album = "File Album", albumId = "9", source = "content://doc/Album.flac",
            cover = "content://doc/cover.jpg", duration = 600.0, size = 123_456_789, format = "FLAC", sampleRate = 44100,
            bitDepth = 16, bitrate = 900.0, discNumber = 1, releaseDate = "1990", genre = "Classical", isrc = "USABC1234567")
        val definition = CueSheets.parse(bytes(album))
        val split = CueSheets.split(base, "primary:Music/Album.flac", listOf(CueCandidate(FolderFile("primary:Music/Album.cue",
            "Album.cue"), definition)))
        val (first, second, third) = split.tracks
        val sha = MessageDigest.getInstance("SHA-256").digest("primary:Music/Album.flac\u0000primary:Music/Album.cue\u00002".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("local:cue:${sha.take(24)}", second.id)
        assertEquals(3, split.tracks.map { it.id }.toSet().size)
        assertEquals(listOf("第一首", "第二首", "03. Album"), split.tracks.map { it.title })
        assertEquals(listOf("独奏", "乐团", "乐团"), split.tracks.map { it.artist })
        assertEquals(listOf("独奏"), first.artists)
        assertTrue(split.tracks.all { it.album == "夜曲集" && it.queueEntryId == null && it.isrc == null })
        assertEquals(listOf(1, 2, 3), split.tracks.map { it.trackNumber })
        assertEquals(240.0, first.duration, 0.0)
        assertEquals(CueSheets.resolve(definition, 600.0).tracks[2].range.durationSeconds, third.duration, 0.0)
        // The file's own facts stay: format, album art, rate, disc, date and genre. Its album is the sheet's now.
        assertTrue(split.tracks.all {
            it.format == "FLAC" && it.albumId == null && it.cover == base.cover && it.sampleRate == 44100 && it.discNumber == 1 &&
                it.releaseDate == "1990" && it.genre == "Classical" && it.size == base.size
        })
        assertEquals("content://doc/Album.flac" to CueRange(0.0, 240.0), CueSource.decode(first.source!!))

        // Without sheet tags the file's own tags remain.
        val bare = CueSheets.split(base, "a", listOf(CueCandidate(FolderFile("c", "c.cue"),
            CueSheets.parse(bytes("FILE Album.flac WAVE\nTRACK 05 AUDIO\nINDEX 01 00:00:00"))))).tracks.single()
        assertEquals("05. Album", bare.title)
        assertEquals("File Artist", bare.artist)
        assertEquals(listOf("File Artist"), bare.artists)
        assertEquals("File Album", bare.album)
        assertEquals("9", bare.albumId)
    }

    @Test
    fun `sources carry ranges exactly and leave other sources alone`() {
        val ranges = listOf(
            CueRange(0.0, 240.0),
            CueRange(240.0, 450 + 37 / 75.0, 0.0, 240.0 - (3 * 60 + 58 + 50 / 75.0)),
            CueRange(450 + 37 / 75.0, 600.0, 2.0, 0.0),
            CueRange(1.0E-5, 9.0E15)
        )
        for (range in ranges) {
            val encoded = CueSource.encode("content://doc/a.flac", range)
            assertEquals("content://doc/a.flac" to range, CueSource.decode(encoded))
        }
        assertEquals("content://doc/a.flac#cue=0.0,240.0", CueSource.encode("content://doc/a.flac", ranges[0]))
        assertEquals("content://doc/a.flac#cue=${450 + 37 / 75.0},600.0,2.0,0.0", CueSource.encode("content://doc/a.flac", ranges[2]))
        // A '#' in a file path is part of the path.
        assertEquals("/sdcard/#1 Hits/a.flac" to CueRange(0.0, 1.5), CueSource.decode("/sdcard/#1 Hits/a.flac#cue=0.0,1.5"))
        for (plain in listOf("content://media/external/audio/media/5", "/sdcard/#1 Hits/a.flac", "#cue=0.0,1.0",
                "a.flac#cue=1.0,1.0", "a.flac#cue=2.0,3.0,0.0,2.5", "a.flac#cue=0.0,1.0,2.0", "a.flac#cue=0x1,2.0",
                "a.flac#cue=NaN,2.0", "a.flac#cue=-1.0,2.0", "a.flac#cue=1.0,2.0d")) {
            assertEquals(plain to null, CueSource.decode(plain))
            assertEquals(plain, CueSource.audio(plain))
        }
    }
}
