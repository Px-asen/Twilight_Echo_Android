package com.twilightecho.android.feature.player

import org.junit.Assert.*
import org.junit.Test

class AutoMixPresentationTest {
    private fun status(state: String, reason: String = "", strategy: String = "", playing: Boolean = true) =
        autoMixStatus(AudioDiagnosticsUiModel(autoMixMode = "natural", autoMixState = state,
            autoMixReason = reason, autoMixStrategy = strategy), playing)

    @Test fun defaultOffAndPausedDoNotClaimAnalysisOrPreparedPlans() {
        assertEquals("已关闭 · 使用无缝播放或普通交叉淡化", autoMixStatus(AudioDiagnosticsUiModel(), true))
        assertEquals("播放后准备下一次过渡", status("degraded", playing = false))
        assertEquals("下一次过渡已就绪 · 内容边界", status("ready", strategy = "content-boundary", playing = false))
    }
    @Test fun phasesAndStrategiesRemainSeparateFromOrdinaryCrossfade() {
        assertEquals("正在分析与准备 · 普通播放继续", status("preparing"))
        assertEquals("正在过渡 · 节拍对齐", status("mixing", strategy = "beat-aligned"))
        assertEquals("过渡完成", status("completed"))
        assertEquals("下一次过渡已就绪 · 尾音衔接", status("ready", strategy = "release-boundary"))
    }
    @Test fun sourceAndDeadlineFailuresExplainTheOrdinaryPlaybackFallback() {
        assertEquals("本音源无法取得稳定的本地文件身份 · 使用普通播放", status("degraded", "actual_source_identity_unavailable"))
        assertEquals("文件已变化 · 本次使用普通播放", status("degraded", "source_changed_or_identity_unavailable"))
        assertEquals("未赶上过渡时间 · 本次使用普通播放", status("degraded", "analysis_timeout"))
        assertEquals("连续专辑分轨 · 保持无缝播放", status("degraded", "continuous_album_gapless"))
        assertEquals("未找到可用的安全过渡 · 本次使用普通播放", status("degraded", "low_beat_confidence"))
    }
}
