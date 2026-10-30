package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.runtimehost.AudioSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackOutputTest {
    private val old = AudioSnapshot(state = "playing", actualSampleRate = 192000, actualBitDepth = 24,
        actualDeviceId = "9", actualDeviceName = "USB DAC", hardwareSampleRate = 192000, exclusive = true)

    @Test fun streamHardwareAndRequestKeepTheirOwnFormats() {
        val result = parseNativePlaybackSnapshot("""{"state":"playing","volume":0.1,
            "actualSampleRate":44100,"actualBitDepth":32,"actualChannels":2,
            "outputInfo":{"exclusive":true,"actualDeviceId":"1","actualOutputFormat":"float32",
                "requestedSampleRate":44100,"requestedSampleFormat":"float32",
                "hardwareSampleRate":48000,"hardwareBitDepth":16,"hardwareChannels":2,"hardwareSampleFormat":"int16"}}
            """, old, 0.4, DefaultCoreClient.DefaultJson)!!
        assertEquals(44100, result.actualSampleRate)
        assertEquals("float32", result.actualSampleFormat)
        assertEquals(48000, result.hardwareSampleRate)
        assertEquals("int16", result.hardwareSampleFormat)
        assertEquals(44100, result.requestedSampleRate)
        assertEquals(0.4, result.volume, 0.0)
        assertTrue(result.exclusive)
        assertFalse(result.outputPerfect)
    }

    @Test fun absentFactsAndReleasedOutputNeverKeepThePreviousDac() {
        val absent = parseNativePlaybackSnapshot("""{"state":"paused"}""", old, 1.0, DefaultCoreClient.DefaultJson)!!
        assertNull(absent.actualSampleRate)
        assertNull(absent.hardwareSampleRate)
        assertNull(absent.actualDeviceId)
        val released = parseNativePlaybackSnapshot("""{"state":"paused","actualSampleRate":96000,
            "outputInfo":{"actualDeviceId":"9","outputReleased":true}}""", old, 1.0, DefaultCoreClient.DefaultJson)!!
        assertNull(released.actualSampleRate)
        assertNull(released.actualDeviceId)
        assertFalse(released.exclusive)
    }

    @Test fun duckingUsesTheLatestUserVolumeAndRestoresIt() {
        val gain = PlaybackGain()
        gain.volume = 0.4
        gain.ducked = true
        assertEquals(0.1, gain.effective, 0.0001)
        gain.volume = 0.6
        assertEquals(0.15, gain.effective, 0.0001)
        gain.ducked = false
        assertEquals(0.6, gain.effective, 0.0001)
    }

    @Test fun autoMixFactsAreNativeObservationsAndClearWhenAbsent() {
        val ready = parseNativePlaybackSnapshot("""{"state":"playing","queueIndex":0,"upcomingQueueIndex":1,
            "autoMixMode":"natural","autoMixState":"ready","autoMixStrategy":"content-boundary",
            "autoMixPlanId":91,"autoMixDurationSeconds":6,"autoMixDecodeMs":24,"autoMixPrepareMs":7,
            "crossfadeMixActive":false,"isDsd":false}""", old, 1.0, DefaultCoreClient.DefaultJson)!!
        assertEquals("natural", ready.autoMixMode)
        assertEquals("ready", ready.autoMixState)
        assertEquals("content-boundary", ready.autoMixStrategy)
        assertEquals(91L, ready.autoMixPlanId)
        assertEquals(6.0, ready.autoMixDurationSeconds, 0.0)
        assertEquals(24L, ready.autoMixDecodeMs)
        assertEquals(7L, ready.autoMixPrepareMs)
        assertEquals(1, ready.upcomingQueueIndex)
        assertFalse(ready.crossfadeMixActive)
        assertFalse(ready.outputPerfect)
        val stopped = parseNativePlaybackSnapshot("""{"state":"stopped"}""", ready, 1.0, DefaultCoreClient.DefaultJson)!!
        assertEquals("off", stopped.autoMixState)
        assertEquals("", stopped.autoMixStrategy)
        assertEquals(0L, stopped.autoMixPlanId)
        assertEquals(-1, stopped.upcomingQueueIndex)
    }

    @Test fun successfulPlaybackClearsThePreviousRecoverableError() {
        val refused = old.copy(state = "paused", errorMessage = "USB preference rejected")
        val paused = parseNativePlaybackSnapshot("""{"state":"paused"}""", refused, 1.0, DefaultCoreClient.DefaultJson)!!
        assertEquals(refused.errorMessage, paused.errorMessage)
        val resumed = parseNativePlaybackSnapshot("""{"state":"playing"}""", paused, 1.0, DefaultCoreClient.DefaultJson)!!
        assertNull(resumed.errorMessage)
    }

    @Test fun restoreSeekIsCachedAndSafetyBrakeIsNotAUserPause() = runBlocking {
        var userPauses = 0
        val adapter = NativeAudioEngineAdapter(onUserPause = { userPauses++ })
        try {
            adapter.loadQueue(listOf(CoreTrackView("a", title = "A", duration = 42.0)), 0)
            adapter.seek(12500)
            adapter.setVolume(0.4)
            adapter.setDucked(true)
            assertEquals(12500L, adapter.getSnapshot().positionMs)
            assertEquals(0.4, adapter.getSnapshot().volume, 0.0)
            val beforeBrake = userPauses
            adapter.emergencyBrakePause()
            assertEquals(beforeBrake, userPauses)
            adapter.pause()
            assertEquals(beforeBrake + 1, userPauses)
            adapter.stop()
            assertEquals(0L, adapter.getSnapshot().positionMs)
            assertNull(adapter.getSnapshot().actualDeviceId)
        } finally { adapter.close() }
    }
}
