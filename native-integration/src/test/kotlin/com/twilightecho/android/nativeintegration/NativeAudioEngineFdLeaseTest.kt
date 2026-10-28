package com.twilightecho.android.nativeintegration

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.runtimehost.NativeFdResource
import com.twilightecho.android.runtimehost.ResourceAccessPort
import com.twilightecho.android.runtimehost.ResourceAccessResult
import com.twilightecho.android.runtimehost.ResourceId
import com.twilightecho.android.runtimehost.ResourceStat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class NativeAudioEngineFdLeaseTest {

    private class FakeFdResourceAccessPort : ResourceAccessPort {
        val acquiredIds = mutableListOf<String>()
        val releasedIds = mutableListOf<String>()
        private var nextFd = 42

        override suspend fun stat(resourceId: ResourceId): ResourceAccessResult<ResourceStat> =
            ResourceAccessResult.Ok(ResourceStat(resourceId = resourceId, uri = resourceId))

        override suspend fun openReadStream(resourceId: ResourceId, offsetBytes: Long): ResourceAccessResult<InputStream> =
            ResourceAccessResult.Ok(ByteArrayInputStream(ByteArray(0)))

        override suspend fun resolveNativeUri(resourceId: ResourceId): ResourceAccessResult<String> =
            ResourceAccessResult.Ok(resourceId)

        override suspend fun acquirePlayableResource(resourceId: ResourceId): ResourceAccessResult<NativeFdResource> {
            acquiredIds.add(resourceId)
            val fd = nextFd++
            return ResourceAccessResult.Ok(
                NativeFdResource(
                    resourceId = resourceId,
                    nativePath = "/proc/self/fd/$fd",
                    detachedFd = fd,
                    mimeType = "audio/flac"
                )
            )
        }

        override suspend fun releasePlayableResource(resourceId: ResourceId) {
            releasedIds.add(resourceId)
        }
    }

    @Test
    fun `NativeAudioEngineAdapter resolves content URI to proc self fd and releases previous descriptor on track switch and stop`() = runBlocking {
        val fakeResourceAccess = FakeFdResourceAccessPort()
        val adapter = NativeAudioEngineAdapter(resourceAccess = fakeResourceAccess)

        val tracks = listOf(
            CoreTrackView(
                id = "track-1",
                title = "Track One",
                source = "content://media/external/audio/media/501",
                duration = 180.0
            ),
            CoreTrackView(
                id = "track-2",
                title = "Track Two",
                source = "content://media/external/audio/media/502",
                duration = 210.0
            )
        )

        adapter.loadQueue(tracks, startIndex = 0)
        adapter.play(0.0)
        assertEquals("/proc/self/fd/42", adapter.currentResolvedNativePath)
        assertEquals(listOf("content://media/external/audio/media/501"), fakeResourceAccess.acquiredIds)
        assertTrue(fakeResourceAccess.releasedIds.isEmpty())

        // Switch to track 2: must release track 1's FD lease and acquire track 2's FD lease
        adapter.selectQueueIndex(1)
        assertEquals("/proc/self/fd/43", adapter.currentResolvedNativePath)
        assertEquals(listOf("content://media/external/audio/media/501"), fakeResourceAccess.releasedIds)

        // A request to bypass DSP is not confirmation of a measured bit-perfect output.
        adapter.setDspConfig("""{"enabled":true,"bitPerfect":true}""")
        assertFalse(adapter.getSnapshot().outputPerfect)

        adapter.setDspConfig("""{"enabled":false,"bitPerfect":true}""")
        assertFalse(adapter.getSnapshot().outputPerfect)

        // Stop playback: must release active track 2 FD lease
        adapter.stop()
        assertEquals(
            listOf(
                "content://media/external/audio/media/501",
                "content://media/external/audio/media/502"
            ),
            fakeResourceAccess.releasedIds
        )

        adapter.close()
    }
}
