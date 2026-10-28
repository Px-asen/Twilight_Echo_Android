package com.twilightecho.android.app

import com.twilightecho.android.coreclient.CueRange
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** A 24-second album image cut the way [CUE_ALBUM_SHEET] cuts it, one tone per song: `seconds to hertz`. */
internal val CUE_ALBUM_PARTS = listOf(8.0 to 220.0, (8 + 37 / 75.0) to 330.0, (8 - 37 / 75.0) to 440.0)

/** The sheet's songs as ranges of the image; the second song's INDEX 00 sits a third of a second before it. */
internal val CUE_ALBUM_RANGES = listOf(
    CueRange(0.0, 8.0),
    CueRange(8.0, 16 + 37 / 75.0, sourcePregapSeconds = 8 - (7 + 50 / 75.0)),
    CueRange(16 + 37 / 75.0, 24.0)
)

/** Written in GBK, as many Chinese rips are; track 2 has its own performer. */
internal val CUE_ALBUM_SHEET = """
    REM DATE 2026
    PERFORMER "暮色乐团"
    TITLE "CUE 验收"
    FILE "Album.wav" WAVE
      TRACK 01 AUDIO
        TITLE "晨光"
        INDEX 01 00:00:00
      TRACK 02 AUDIO
        TITLE "午后"
        PERFORMER "客座歌手"
        INDEX 00 00:07:50
        INDEX 01 00:08:00
      TRACK 03 AUDIO
        TITLE "夜色"
        INDEX 01 00:16:37
""".trimIndent() + "\n"

/** A 16-bit stereo PCM WAV holding one steady tone per part, so each song a sheet cuts from it sounds different. */
internal fun toneWave(parts: List<Pair<Double, Double>>, rate: Int = 44100): ByteArray {
    val frames = parts.map { (seconds, _) -> (seconds * rate).roundToInt() }
    val dataBytes = frames.sum() * 4
    val wave = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    wave.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
    wave.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
    wave.put("data".toByteArray()).putInt(dataBytes)
    parts.forEachIndexed { part, (_, hertz) ->
        repeat(frames[part]) { frame ->
            val sample = (sin(2 * PI * hertz * frame / rate) * 6000).roundToInt().toShort()
            wave.putShort(sample).putShort(sample)
        }
    }
    return wave.array()
}
