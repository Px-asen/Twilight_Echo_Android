package com.twilightecho.android.coreclient

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DesktopSyncMember(val zoneId: String, val downstreamDelayMs: Double = 0.0)
@Serializable
data class DesktopSyncGroup(val id: String, val name: String, val revision: Long,
    val members: List<DesktopSyncMember>, val playback: DesktopZonePlayback? = null,
    val track: DesktopZoneTrack? = null, val queue: DesktopZoneQueue? = null,
    val coordination: JsonObject? = null, val error: String? = null,
    val autoMixMode: String = "off", val autoMixAvailable: Boolean = false)
@Serializable
data class DesktopSyncGroups(val revision: Long, val groups: List<DesktopSyncGroup> = emptyList(), val error: String? = null)
data class DesktopSyncSelection(val session: DesktopZoneSession, val downstreamDelayMs: Double = 0.0)
data class DesktopSyncGroupCreate(val revision: Long, val name: String, val source: DesktopZoneSession,
    val members: List<DesktopSyncSelection>, val targetGeneration: Long)
