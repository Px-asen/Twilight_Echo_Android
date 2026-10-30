package com.twilightecho.android.feature.player

internal fun autoMixStatus(d: AudioDiagnosticsUiModel, playing: Boolean): String {
    if (d.autoMixMode == "off") return "已关闭 · 使用无缝播放或普通交叉淡化"
    if (!playing && d.autoMixState !in setOf("ready", "mixing")) return "播放后准备下一次过渡"
    val strategy = when (d.autoMixStrategy) {
        "beat-aligned" -> "节拍对齐"
        "content-boundary" -> "内容边界"
        "tail-overlap" -> "曲尾重叠"
        "release-boundary" -> "尾音衔接"
        else -> "智能过渡"
    }
    return when (d.autoMixState) {
        "preparing" -> "正在分析与准备 · 普通播放继续"
        "ready" -> "下一次过渡已就绪 · $strategy"
        "mixing" -> "正在过渡 · $strategy"
        "completed" -> "过渡完成"
        "degraded" -> when (d.autoMixReason) {
            "actual_source_identity_unavailable", "actual_online_source_identity_unavailable" -> "本音源无法取得稳定的本地文件身份 · 使用普通播放"
            "source_changed_or_identity_unavailable", "provider_source_resolution_changed" -> "文件已变化 · 本次使用普通播放"
            "public_assets_unavailable" -> "分析模型不可用 · 请重新安装包含 AutoMix 模型的版本"
            "manual_transport_or_dsd", "manual_transport_processing", "pcm_path_required" -> "需要原速 PCM 播放 · 本次使用普通播放"
            "continuous_album_gapless" -> "连续专辑分轨 · 保持无缝播放"
            "cue_pregap_preserved", "unsupported_source_range" -> "保留分轨间隙 · 本次使用普通播放"
            "preparation_missed_boundary", "native_preparation_timeout", "analysis_timeout" -> "未赶上过渡时间 · 本次使用普通播放"
            "next_source_not_ready" -> "下一首尚未就绪 · 本次使用普通播放"
            "source_timeline_not_exact" -> "当前时间轴无法精确对齐 · 本次使用普通播放"
            "mode_changed", "transport_changed", "source_or_queue_changed", "native_plan_invalidated" -> "等待下一次准备"
            else -> "未找到可用的安全过渡 · 本次使用普通播放"
        }
        else -> "等待可分析的下一首"
    }
}
