package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.CoreLyricLine
import com.twilightecho.android.coreclient.CoreLyricsView
import com.twilightecho.android.coreclient.CoreTrackView

/**
 * Pure Kotlin LRC parser supporting `[mm:ss.xx]` and `[mm:ss.xxx]` timestamps,
 * bilingual translation lines, built-in poetic LRC lyrics for the 3 demo tracks,
 * and dynamic time-synced lyrics fallback for scanned local tracks.
 */
object LrcParser {

    private val TIME_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(rawLrc: String): List<CoreLyricLine> {
        if (rawLrc.isBlank()) return emptyList()
        val timedEntries = mutableListOf<CoreLyricLine>()

        for (rawLine in rawLrc.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val matches = TIME_TAG_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val lyricBody = trimmed.substring(matches.last().range.last + 1).trim()
            if (lyricBody.isEmpty()) continue

            val parts = lyricBody.split(" / ", limit = 2)
            val primaryText = parts[0].trim()
            val translationText = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
                val millis = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> (fractionRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fractionRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fractionRaw.take(3).toLongOrNull() ?: 0L
                }
                val totalMs = (minutes * 60_000L) + (seconds * 1_000L) + millis
                timedEntries.add(
                    CoreLyricLine(
                        timeMs = totalMs,
                        text = primaryText,
                        translation = translationText
                    )
                )
            }
        }

        return timedEntries.sortedBy { it.timeMs }
    }

    fun resolveLyricsForTrack(track: CoreTrackView): CoreLyricsView {
        val presetLrc = DEMO_LRC_MAP[track.id]
        if (presetLrc != null) {
            return CoreLyricsView(
                trackId = track.id,
                source = "embedded-lrc",
                synced = true,
                lines = parseLrc(presetLrc)
            )
        }

        val totalSec = track.duration.coerceAtLeast(30.0)
        val stepSec = (totalSec / 8.0).coerceAtLeast(4.0)
        val generatedLines = listOf(
            CoreLyricLine(
                timeMs = 0L,
                text = "♪ ${track.title} — ${track.artist}",
                translation = "专辑：${track.album} (${track.format ?: "Hi-Res"})"
            ),
            CoreLyricLine(
                timeMs = (stepSec * 1000).toLong(),
                text = "Listening through the Twilight Echo native audio pipeline",
                translation = "通过 Twilight Echo 原生音频管线聆听"
            ),
            CoreLyricLine(
                timeMs = (stepSec * 2000).toLong(),
                text = "Drifting across the quiet horizon of sound",
                translation = "漫游在静谧的声音地平线"
            ),
            CoreLyricLine(
                timeMs = (stepSec * 3000).toLong(),
                text = "Every harmonic preserved in crystal clarity",
                translation = "每一缕泛音都清晰留存"
            ),
            CoreLyricLine(
                timeMs = (stepSec * 4000).toLong(),
                text = "Tap any lyric line to seek seamlessly",
                translation = "轻触任意歌词行即可精准跳转播放进度"
            ),
            CoreLyricLine(
                timeMs = (stepSec * 5000).toLong(),
                text = "Resonance lingering in the midnight air",
                translation = "夜色中回荡的余韵"
            ),
            CoreLyricLine(
                timeMs = (stepSec * 6000).toLong(),
                text = "Echoes fading into starlight",
                translation = "回声渐隐于星光深处"
            )
        )

        return CoreLyricsView(
            trackId = track.id,
            source = "generated-sync",
            synced = true,
            lines = generatedLines
        )
    }

    private val DEMO_LRC_MAP: Map<String, String> = mapOf(
        "track-twilight-overture" to """
            [00:00.00]Twilight Overture — Aether Ensemble / 暮光序曲 — 以太合奏团
            [00:06.50]When the amber sun sinks beneath the glass horizon / 当琥珀色的夕阳沉入琉璃地平线
            [00:14.00]Strings awaken in the hush of the concert hall / 弦音在音乐厅的静谧中苏醒
            [00:22.40]A distant echo answers from the northern ridge / 远方的回声自北岭应和
            [00:31.00]Twenty-four bits of dynamic breath and shadow / 二十四比特的动态呼吸与光影
            [00:40.20]Every bow stroke shimmers in the twilight air / 每一次运弓都在暮色中闪烁
            [00:50.00]Rising through the velvet nocturnal tide / 穿过天鹅绒般的夜潮缓缓升起
            [01:02.50]Until only starlight and resonance remain / 直至唯有星光与共鸣长存
        """.trimIndent(),
        "track-nocturne-in-cobalt" to """
            [00:00.00]Nocturne in Cobalt — Lumina Quartet / 钴蓝夜曲 — 流光四重奏
            [00:08.00]Cobalt rain traces silver lines on the windowpane / 钴蓝色的雨在窗棂勾勒银线
            [00:17.20]Piano notes fall like ripples across still water / 琴音如涟漪落入静水
            [00:27.50]In the stillness between two heartbeats / 在两次心跳之间的寂静里
            [00:38.00]Memory unfolds its quiet lantern / 记忆悄然点亮幽微的灯火
            [00:49.50]Cello whispers beneath the midnight arch / 大提琴在午夜拱廊下低语
            [01:01.00]Carrying our unspoken song into the dawn / 将未竟之歌带向黎明
        """.trimIndent(),
        "track-solstice-drift" to """
            [00:00.00]Solstice Drift — Kairo & Sorel / 至日漂流 — Kairo & Sorel
            [00:05.80]Warm analog waves meet the winter solstice wind / 温暖的模拟声浪邂逅冬至的微风
            [00:13.60]Drifting weightless above the sleeping city lights / 失重般漂浮于沉睡的城市灯火之上
            [00:22.00]Time slows down to forty-eight thousand pulses / 时间放缓为每秒四万八千次脉动
            [00:31.50]Hold this fleeting moment in a golden bookmark / 将这转瞬即逝的时刻珍藏进金色书签
            [00:42.00]Where the horizon line meets the endless sea / 在地平线与无垠大海交汇之处
        """.trimIndent()
    )
}
