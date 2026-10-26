package com.twilightecho.android.runtimehost

import com.twilightecho.android.coreclient.CoreClientIdentity
import com.twilightecho.android.coreclient.CoreEqConfigView
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.coreclient.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet

class QuickJsRuntimeHostPhase3Test {

    private class FakeMemoryPorts {
        val fakeAudio = FakeAudioEngine()
        val hostPorts = CoreHostPorts(
            clock = FakeClock(),
            scheduler = FakeScheduler(),
            sessionStore = FakeSessionStore(),
            resourceAccess = FakeResourceAccess(),
            audioEngine = fakeAudio
        )
    }

    private class FakeClock : ClockPort {
        private var tick = 1_710_000_000_000L
        override fun nowUnixMs(): Long = ++tick
        override fun nowMonotonicMs(): Long = ++tick
    }

    private class FakeScheduler : SchedulerPort {
        override fun schedule(delayMs: Long, task: () -> Unit): CancelHandle {
            return CancelHandle {}
        }
    }

    private class FakeSessionStore : SessionStorePort {
        var stored: StoredSession? = null
        private var rev = 0
        override suspend fun load(): StoredSession? = stored
        override suspend fun save(payloadJson: String, expectedRevision: String?): SaveSessionResult {
            if (stored != null && expectedRevision != stored?.revision) {
                return SaveSessionResult.Conflict(actualRevision = stored?.revision)
            }
            rev += 1
            val nextRev = "rev-$rev"
            stored = StoredSession(revision = nextRev, payloadJson = payloadJson)
            return SaveSessionResult.Ok(nextRev)
        }
    }

    private class FakeResourceAccess : ResourceAccessPort {
        override suspend fun stat(resourceId: ResourceId): ResourceAccessResult<ResourceStat> =
            ResourceAccessResult.Ok(ResourceStat(resourceId = resourceId, uri = resourceId))

        override suspend fun openReadStream(resourceId: ResourceId, offsetBytes: Long): ResourceAccessResult<InputStream> =
            ResourceAccessResult.Ok(ByteArrayInputStream(ByteArray(0)))

        override suspend fun resolveNativeUri(resourceId: ResourceId): ResourceAccessResult<String> =
            ResourceAccessResult.Ok(resourceId)
    }

    private class FakeAudioEngine : AudioEnginePort {
        private val listeners = CopyOnWriteArraySet<(AudioEvent) -> Unit>()
        var snapshot = AudioSnapshot(state = "paused", outputPerfect = true)
        var lastDspConfigJson: String? = null

        override suspend fun load(source: AudioSourceDescriptor) {}
        override suspend fun play(startTimeSeconds: Double?) {
            snapshot = snapshot.copy(state = "playing")
        }
        override suspend fun pause() {
            snapshot = snapshot.copy(state = "paused")
        }
        override suspend fun stop() {
            snapshot = snapshot.copy(state = "idle")
        }
        override suspend fun seek(positionMs: Long) {
            snapshot = snapshot.copy(positionMs = positionMs)
        }
        override suspend fun setVolume(volume: Double) {
            snapshot = snapshot.copy(volume = volume)
        }
        override suspend fun setPlayMode(mode: PlayMode) {
            snapshot = snapshot.copy(playMode = mode.toWireValue())
        }
        override suspend fun loadQueue(tracks: List<CoreTrackView>, startIndex: Int) {
            snapshot = snapshot.copy(queueIndex = startIndex)
        }
        override suspend fun selectQueueIndex(index: Int) {
            snapshot = snapshot.copy(queueIndex = index)
        }
        override suspend fun next() {}
        override suspend fun previous() {}
        override suspend fun setDspConfig(configJson: String) {
            lastDspConfigJson = configJson
        }
        override suspend fun getSnapshot(): AudioSnapshot = snapshot
        override fun subscribe(listener: (AudioEvent) -> Unit): CancelHandle {
            listeners.add(listener)
            return CancelHandle { listeners.remove(listener) }
        }
        override fun close() {}
    }

    @Test
    fun `QuickJsRuntimeHost executes Phase 3 collections, playlists, EQ, and persists across restart`() = runBlocking {
        val ports = FakeMemoryPorts()
        val host1 = QuickJsRuntimeHost(appContext = null, ports = ports.hostPorts)
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client1 = DefaultCoreClient(
            identity = CoreClientIdentity.androidPrimaryClient(),
            transport = host1,
            scope = scope1
        )

        host1.start()
        val initRes = client1.reconcileState()
        assertTrue(initRes.isSuccess)
        assertEquals(3, client1.snapshot.value?.albums?.size)
        assertEquals(3, client1.snapshot.value?.artists?.size)

        // Create playlist and add tracks
        val createPlRes = client1.createPlaylist("Audiophile Night", listOf("track-twilight-overture"))
        assertTrue(createPlRes.isSuccess)
        val createdPl = client1.snapshot.value?.playlists?.firstOrNull()
        assertNotNull(createdPl)
        assertEquals("Audiophile Night", createdPl!!.name)
        assertEquals(1, createdPl.trackCount)

        val addTrackRes = client1.addTracksToPlaylist(createdPl.id, listOf("track-nocturne-in-cobalt"))
        assertTrue(addTrackRes.isSuccess)
        assertEquals(2, client1.snapshot.value?.playlists?.first()?.trackCount)

        // Configure EQ / DSP
        val bassBoostBands = CoreEqConfigView.PRESETS.getValue("Bass Boost")
        val setEqRes = client1.setEqConfig(
            CoreEqConfigView(
                enabled = true,
                preset = "Bass Boost",
                preampDb = -2.0,
                bandsDb = bassBoostBands,
                bitPerfect = true,
                gapless = true
            )
        )
        assertTrue(setEqRes.isSuccess)
        assertEquals(true, client1.snapshot.value?.eqConfig?.enabled)
        assertEquals("Bass Boost", client1.snapshot.value?.eqConfig?.preset)
        assertFalse(client1.snapshot.value?.playback?.outputPerfect ?: true)
        assertNotNull(ports.fakeAudio.lastDspConfigJson)

        host1.close()
        scope1.cancel()

        // Restart a second QuickJsRuntimeHost sharing the same SessionStorePort
        val host2 = QuickJsRuntimeHost(appContext = null, ports = ports.hostPorts)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client2 = DefaultCoreClient(
            identity = CoreClientIdentity.androidPrimaryClient(),
            transport = host2,
            scope = scope2
        )

        host2.start()
        val restoredRes = client2.reconcileState()
        assertTrue(restoredRes.isSuccess)
        assertEquals(1, client2.snapshot.value?.playlists?.size)
        assertEquals("Audiophile Night", client2.snapshot.value?.playlists?.first()?.name)
        assertEquals(2, client2.snapshot.value?.playlists?.first()?.trackCount)
        assertEquals(true, client2.snapshot.value?.eqConfig?.enabled)
        assertEquals("Bass Boost", client2.snapshot.value?.eqConfig?.preset)

        host2.close()
        scope2.cancel()
    }
}
