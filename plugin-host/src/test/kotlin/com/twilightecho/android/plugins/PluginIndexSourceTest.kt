package com.twilightecho.android.plugins

import org.junit.Assert.*
import org.junit.Test

class PluginIndexSourceTest {
    @Test fun bundledCatalogueWaitsForTheNetwork() {
        assertEquals("随应用分发 · 联网后自动更新", indexSourceLabel(DEFAULT_PLUGIN_INDEX, null, live = false))
        assertEquals(indexSourceLabel(DEFAULT_PLUGIN_INDEX, null, live = false), PluginRepositoryState().sourceLabel)
    }
    @Test fun officialIndexIsTheDesktopCdn() {
        assertEquals("https://plugins.aaapi.fun/plugins.json", DEFAULT_PLUGIN_INDEX)
        val live = indexSourceLabel(DEFAULT_PLUGIN_INDEX, 0L, live = true)
        assertTrue(live, live.startsWith("官方索引 · 与桌面端同一 CDN · ") && live.endsWith(" 已更新"))
        assertTrue(indexSourceLabel(DEFAULT_PLUGIN_INDEX, 0L, live = false).contains(" · 缓存于 "))
    }
    @Test fun customRepositoriesShowTheirHost() {
        assertTrue(indexSourceLabel("https://mirror.example.org/plugins.json", 0L, live = true).startsWith("mirror.example.org · "))
    }
}
