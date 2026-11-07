package com.twilightecho.android.coreclient

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DesktopClockObservationTest {
    @Test fun adaptivePcmShowsRealCorrectionAndFractionalMediaPosition() {
        assertTrue(desktopAdaptiveObservation(JsonObject(emptyMap())).isEmpty())
        val path = Json.parseToJsonElement("""{"receiver":{"adaptive":{"inputFrames":48000,"requestedPpm":200,"effectivePpm":199.7},"timedStart":{"state":"running","presentedMediaFrames":39000.5}}}""").jsonObject
        assertEquals(listOf("FFmpeg SWR · Kaiser FIR · 运行中", "时钟适配会重采样，不是位完美输出",
            "修正请求 200.0 ppm · 已生成 PCM 199.7 ppm", "媒体呈现 39000.50 帧"), desktopAdaptiveObservation(path))
        val prepared = Json.parseToJsonElement("""{"receiver":{"adaptive":{"inputFrames":0},"timedStart":{"state":"prepared","presentedMediaFrames":null}}}""").jsonObject
        assertEquals(listOf("FFmpeg SWR · Kaiser FIR · 静音准备", "时钟适配会重采样，不是位完美输出"), desktopAdaptiveObservation(prepared))
    }
    @Test fun absentAndUnsupportedClocksDoNotBecomeZeroDrift() {
        assertEquals(listOf("输出桥未提供时钟观测"), desktopClockObservation(JsonObject(emptyMap())))
        val path = Json.parseToJsonElement("""{"timing":{"output":{"state":"unsupported"},"network":{"state":"stale"}}}""").jsonObject
        assertEquals(listOf("此输出未提供设备播放时钟", "网络时钟采样已过期"), desktopClockObservation(path))
    }
    @Test fun onlyCurrentEstimatedClocksShowDriftAndPresentationPosition() {
        val timing = """{"output":{"state":"ready","driftPpm":100.12,"windowMs":60000,"samples":241,"positionFrames":96000,"ageMs":12},"network":{"state":"ready","roundTripMs":2.4,"uncertaintyMs":1.2}}"""
        fun path(value: String) = buildJsonObject { put("timing", Json.parseToJsonElement(value)) }
        assertEquals(listOf("漂移已估计", "漂移 +100.1 ppm · 60.0 s / 241 次", "呈现位置 96000 帧 · 观测年龄 12 ms",
            "网络往返 2.4 ms · 时差估计 ±1.2 ms"), desktopClockObservation(path(timing)))
        assertEquals(listOf("观测已过期", "网络时钟采样已过期"), desktopClockObservation(path(timing.replace("ready", "stale"))))
        val warming = desktopClockObservation(path(timing.replace("ready", "warming")))
        assertFalse(warming.any { it.contains("ppm") })
    }
}
