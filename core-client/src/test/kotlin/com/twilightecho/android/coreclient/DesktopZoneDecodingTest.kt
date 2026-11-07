package com.twilightecho.android.coreclient

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 声区 tab decodes the computer's full `zones.list` / `groups.list` views, not the trimmed fixtures. */
class DesktopZoneDecodingTest {
    private val json = DefaultCoreClient.DefaultJson

    // Shapes follow the desktop's ZonesView / ZoneView / BridgeRuntimeState / BridgeReceiverFacts.
    private val zonesList = """
        {"revision":11,"limit":4,"error":null,"zones":[
          {"id":"local","name":"本机","groupId":null,"revision":5,
           "playback":{"state":"playing","position":61.5,"duration":245.2,"volume":0.62,"playbackRate":1,"requestedConfigRevision":3,
             "appliedConfigRevision":3,"queueIndex":2,"playMode":"sequence","source":"local","codec":"flac","nativePlaybackActive":true,
             "bitrate":2304,"sourceSampleRate":96000,"sourceBitDepth":24,"decodedSampleRate":96000,"decodedBitDepth":32,
             "decodedChannels":2,"decodedSampleFormat":"f32","outputBackend":"wasapi-exclusive","outputDevice":"default",
             "outputInfo":{"deviceName":"扬声器"},"actualBackend":"wasapi","actualOutputFormat":"s24le","actualSampleRate":96000,
             "actualBitDepth":24,"actualChannels":2,"latencyMs":12.5,"naturalBoundaries":{"ended":3},"upcomingTrack":null},
           "track":{"id":"remote-track:eyJ0IjoxfQ","queueEntryId":"q-1","title":"Clair de lune","artist":"Debussy",
             "artists":[{"id":"a-1","name":"Debussy"}],"album":"Suite bergamasque","albumId":"al-1","source":"local","duration":245.2,
             "size":41200000,"format":"flac","sampleRate":96000,"bitrate":2304,"bitDepth":24,"trackNumber":3,"genre":null,
             "cueRange":{"start":0,"end":245.2}},
           "queue":{"id":"main","revision":9,"total":12,"index":2,"occurrenceId":"occ-1","canUndo":false,"undoLabel":null},
           "output":{"supported":true,"hosting":false,"receiving":false,"selected":false,"connected":false,"peerName":null,
             "error":null,"receiver":null,"timing":null,"synchronization":null},
           "error":null},
          {"id":"zone-11111111-1111-4111-8111-111111111111","name":"书房","groupId":"group-22222222-2222-4222-8222-222222222222",
           "revision":3,"playback":null,"track":null,"queue":null,
           "output":{"supported":true,"hosting":true,"receiving":false,"selected":true,"connected":true,"peerName":"书房音箱",
             "error":null,
             "receiver":{"sampleRate":96000,"channels":2,"bufferedFrames":9600,"capacityFrames":96000,"prebufferFrames":4800,
               "consumedFrames":192000,"underruns":0,"started":true,"failed":false,"backend":"wasapi","deviceName":"Topping E50",
               "actualSampleRate":48000,"actualBitDepth":24,"actualChannels":2,"actualOutputFormat":"s24le","exclusive":true,
               "clock":{"state":"ready"},"timedStart":null,"adaptiveSupported":true,
               "adaptive":{"requestedPpm":1.2,"effectivePpm":1.1,"inputFrames":1,"pendingInputFrames":0,"resamplerDelayFrames":32,
                 "sourceConsumedFrames":1,"inputCreditFrames":0}},
             "timing":{"output":{"state":"ready"}},"synchronization":{"state":"locked"}},
           "error":null},
          {"id":"zone-33333333-3333-4333-8333-333333333333","name":"客厅","revision":1,"playback":null,"track":null,"queue":null,
           "output":null,"error":"输出桥配置已失效"}
        ]}
    """.trimIndent()

    @Test
    fun fullZonesViewDecodesWithEveryFactTheTabShows() {
        val sessions = json.decodeFromString<DesktopZoneSessions>(zonesList)
        assertEquals(11L, sessions.revision)
        assertNull(sessions.error)
        assertEquals(listOf("local", "zone-11111111-1111-4111-8111-111111111111", "zone-33333333-3333-4333-8333-333333333333"),
            sessions.zones.map { it.id })

        val local = sessions.zones[0]
        assertNull(local.groupId)
        assertNull(local.error)
        val track = assertNotNullValue(local.track)
        assertEquals("Clair de lune", track.title)
        assertEquals("Debussy", track.artist)
        assertEquals("Suite bergamasque", track.album)
        assertEquals("remote-track:eyJ0IjoxfQ", track.id)
        assertEquals("local", track.source)
        assertEquals("flac", track.format)
        assertEquals(96000.0, track.sampleRate!!, 0.0)
        assertEquals(24.0, track.bitDepth!!, 0.0)
        assertEquals(245.2, track.duration!!, 0.0)
        val playback = assertNotNullValue(local.playback)
        assertEquals("playing", playback.state)
        assertEquals(61.5, playback.position, 0.0)
        assertEquals(245.2, playback.duration, 0.0)
        assertEquals(0.62, playback.volume, 0.0)
        assertEquals("flac", playback.codec)
        assertEquals(96000.0, playback.sourceSampleRate!!, 0.0)
        assertEquals(24.0, playback.sourceBitDepth!!, 0.0)
        assertEquals(96000.0, playback.actualSampleRate!!, 0.0)
        assertEquals(24.0, playback.actualBitDepth!!, 0.0)
        assertEquals("wasapi-exclusive", playback.outputBackend)
        assertEquals(DesktopZoneQueue("main", 9, "occ-1"), local.queue)
        val localOutput = assertNotNullValue(local.output)
        assertFalse(localOutput.selected)
        assertFalse(localOutput.connected)
        assertNull(localOutput.peerName)
        assertNull(localOutput.receiver)

        val study = sessions.zones[1]
        assertEquals("书房", study.name)
        assertEquals("group-22222222-2222-4222-8222-222222222222", study.groupId)
        assertNull(study.track)
        assertNull(study.playback)
        assertNull(study.queue)
        val output = assertNotNullValue(study.output)
        assertTrue(output.connected && output.selected)
        assertEquals("书房音箱", output.peerName)
        assertEquals(DesktopBridgeReceiver(adaptiveSupported = true, deviceName = "Topping E50", backend = "wasapi", exclusive = true,
            actualSampleRate = 48000.0, actualBitDepth = 24.0, failed = false), output.receiver)

        val livingRoom = sessions.zones[2]
        assertNull(livingRoom.output)
        assertEquals("输出桥配置已失效", livingRoom.error)
    }

    @Test
    fun olderComputersAndMinimalViewsFallBackToDefaults() {
        val sessions = json.decodeFromString<DesktopZoneSessions>("""
            {"revision":1,"zones":[{"id":"local","name":"本机","revision":0},
              {"id":"zone-44444444-4444-4444-8444-444444444444","name":"卧室","revision":2,"track":{"title":"Song"},
               "playback":{"state":"paused"},"queue":{"id":"q","revision":1},
               "output":{"connected":true,"selected":true,"receiver":{"deviceName":"USB DAC"}}}]}
        """.trimIndent())
        assertNull(sessions.error)
        val local = sessions.zones[0]
        assertNull(local.track)
        assertNull(local.playback)
        assertNull(local.output)
        assertNull(local.queue)
        val bedroom = sessions.zones[1]
        assertEquals(DesktopZoneTrack(title = "Song"), bedroom.track)
        assertEquals(DesktopZonePlayback(state = "paused"), bedroom.playback)
        // A queue without an occurrence cannot be handed off or supply a group queue.
        assertNull(bedroom.queue?.occurrenceId)
        // A receiver that predates clock adaptation reports no support rather than failing to decode.
        assertFalse(bedroom.output?.receiver?.adaptiveSupported ?: true)
        assertEquals("USB DAC", bedroom.output?.receiver?.deviceName)
    }

    @Test
    fun syncGroupsKeepCoordinationAsCoreReportedIt() {
        val groups = json.decodeFromString<DesktopSyncGroups>("""
            {"revision":6,"error":null,"groups":[{"id":"group-22222222-2222-4222-8222-222222222222","name":"客厅与书房","revision":4,
              "members":[{"zoneId":"zone-11111111-1111-4111-8111-111111111111","downstreamDelayMs":12},
                         {"zoneId":"zone-33333333-3333-4333-8333-333333333333","downstreamDelayMs":0}],
              "playback":{"state":"paused","position":0,"duration":200,"volume":0.5},"track":{"title":"Song","artist":"A","album":"B"},
              "queue":{"id":"shared","revision":2,"total":4,"index":0,"occurrenceId":"occ-g"},
              "coordination":{"phase":"preparing","error":null,"epoch":7,"audibleAtCoreMs":null,"estimatedSkewMs":null,
                "observationRangeMs":null,"members":[{"id":"zone-11111111-1111-4111-8111-111111111111","dspDelayMs":2.5,
                "downstreamDelayMs":12,"startAtCoreMs":null}]},
              "error":null}]}
        """.trimIndent())
        val group = groups.groups.single()
        assertEquals(listOf(12.0, 0.0), group.members.map { it.downstreamDelayMs })
        assertEquals("occ-g", group.queue?.occurrenceId)
        val coordination = assertNotNullValue(group.coordination)
        assertEquals("preparing", coordination["phase"]?.jsonPrimitive?.content)
        assertNull(coordination["estimatedSkewMs"]?.jsonPrimitive?.content?.toDoubleOrNull())
        assertEquals(7.0, coordination["epoch"]!!.jsonPrimitive.double, 0.0)
        assertNull(group.error)
    }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
