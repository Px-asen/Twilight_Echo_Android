package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSettingsEditTest {
    private val json = DefaultCoreClient.DefaultJson
    private val profile = EqualizerApoText.parse("Preamp: -6.2 dB\nFilter 1: ON PK Fc 2400 Hz Gain 4 dB Q 2.4")

    /** The real client's wire contract, with a controllable Core write acknowledgement. */
    private class Host(var config: CoreEqConfigView = CoreEqConfigView()) : CoreTransport {
        override val stateEvents = MutableSharedFlow<CoreEventMessage>()
        val writes = mutableListOf<CoreEqConfigView>()
        val writeStarted = CompletableDeferred<Unit>()
        var acknowledgement: CompletableDeferred<Unit>? = null
        var readError: String? = null
        var writeError: String? = null
        var readException: Exception? = null
        var writeException: Exception? = null
        var includeCommittedConfig = true
        var normalize: (CoreEqConfigView) -> CoreEqConfigView = { it }
        private var sequence = 1L
        private val json = DefaultCoreClient.DefaultJson

        fun snapshot(config: CoreEqConfigView = this.config, sequence: Long = this.sequence) =
            CoreSnapshot(epoch = "audio-edit", sequence = sequence, eqConfig = config)

        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            fun ok(value: JsonElement) = CoreResponseEnvelope(requestId = envelope.requestId, epoch = "audio-edit", ok = true, value = value)
            fun failed(message: String) = CoreResponseEnvelope(requestId = envelope.requestId, epoch = "audio-edit", ok = false,
                error = CoreErrorView(CoreErrorCode.FAILED, message))
            return when (envelope.name) {
                "core.getSnapshot" -> ok(json.encodeToJsonElement(snapshot()))
                "audio.getEq" -> {
                    readException?.let { throw it }
                    readError?.let { return failed(it) }
                    ok(json.encodeToJsonElement(config))
                }
                "audio.setEq" -> {
                    val requested = json.decodeFromJsonElement<CoreEqConfigView>(envelope.payload)
                    writes += requested
                    writeStarted.complete(Unit)
                    acknowledgement?.await()
                    writeException?.let { throw it }
                    writeError?.let { return failed(it) }
                    config = normalize(requested)
                    sequence++
                    ok(if (includeCommittedConfig) buildJsonObject { put("snapshot", json.encodeToJsonElement(snapshot())) }
                        else buildJsonObject {})
                }
                else -> error("Unexpected request: ${envelope.name}")
            }
        }

        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }

    private fun TestScope.client(host: Host) = DefaultCoreClient(CoreClientIdentity.androidPrimaryClient(), host,
        backgroundScope, processingDispatcher = StandardTestDispatcher(testScheduler))

    private fun scene(id: String, preamp: Double = 0.0, name: String = id) = CoreDspScene(id, name,
        JsonObject(json.encodeToJsonElement(CoreEqConfigView(preampDb = preamp)).jsonObject - "scenes"))

    @Test fun importWaitsForAcknowledgementAndUsesFreshCoreBeforeAndNormalizedCommit() = runTest {
        val host = Host(CoreEqConfigView(preampDb = -1.0))
        val client = client(host)
        client.reconcileState()
        val fresh = host.config.copy(preampDb = -3.0, dsp = host.config.dsp.copy(enabled = false, outputDeviceId = "17"))
        host.config = fresh
        host.acknowledgement = CompletableDeferred()
        host.normalize = { it.copy(preampDb = -6.0) }
        var transformations = 0

        val pending = async { client.commitAudioEdit { transformations++; it.withImportedEq(profile) } }
        host.writeStarted.await()
        assertFalse(pending.isCompleted)
        assertEquals(-1.0, client.snapshot.value!!.eqConfig.preampDb, 0.0)
        assertEquals(fresh, host.config)
        assertEquals(fresh.withImportedEq(profile), host.writes.single())
        assertFalse(host.writes.single().dsp.enabled)
        host.acknowledgement!!.complete(Unit)

        val receipt = pending.await().getOrThrow()
        assertEquals(fresh, receipt.before)
        assertEquals(fresh.withImportedEq(profile).copy(preampDb = -6.0), receipt.committed)
        assertEquals(1, transformations)
    }

    @Test fun receiptComesFromItsResponseEvenWhenClientHasANewerSnapshot() = runTest {
        val host = Host()
        val client = client(host)
        client.reconcileState()
        runCurrent()
        val newer = host.snapshot(CoreEqConfigView(preampDb = -9.0), sequence = 20)
        host.stateEvents.emit(CoreEventMessage(epoch = newer.epoch, sequence = newer.sequence, snapshot = newer))
        runCurrent()

        val receipt = client.commitAudioEdit { it.withImportedEq(profile) }.getOrThrow()
        assertEquals(-6.2, receipt.committed.preampDb, 0.0)
        assertEquals(-9.0, client.snapshot.value!!.eqConfig.preampDb, 0.0)
    }

    @Test fun readFailureDoesNotTransformOrWriteEvenWithACachedSnapshot() = runTest {
        val host = Host()
        val client = client(host)
        client.reconcileState()
        host.readError = "读取失败"
        var transformed = false
        val result = client.commitAudioEdit { transformed = true; it.withImportedEq(profile) }
        assertEquals("读取失败", result.exceptionOrNull()!!.message)
        assertFalse(transformed)
        assertTrue(host.writes.isEmpty())
    }

    @Test fun rejectedWriteDoesNotProduceAReceiptOrChangeCore() = runTest {
        val before = CoreEqConfigView(preampDb = -2.0)
        val host = Host(before).apply { writeError = "配置被拒绝" }
        val result = client(host).commitAudioEdit { it.withImportedEq(profile) }
        assertEquals("配置被拒绝", result.exceptionOrNull()!!.message)
        assertEquals(before, host.config)
        assertEquals(1, host.writes.size)
    }

    @Test fun transportExceptionIsReportedAtTheImportCaller() = runTest {
        val error = IllegalStateException("连接已关闭")
        val host = Host().apply { writeException = error }
        assertSame(error, client(host).commitAudioEdit { it.withImportedEq(profile) }.exceptionOrNull())
    }

    @Test fun cancellationDuringReadPropagatesWithoutWriting() = runTest {
        val host = Host().apply { readException = CancellationException("closed") }
        try {
            client(host).commitAudioEdit { it.withImportedEq(profile) }
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("closed", expected.message)
        }
        assertTrue(host.writes.isEmpty())
    }

    @Test fun cancellationWhileAwaitingWriteIsNotConvertedToFailure() = runTest {
        val host = Host().apply { acknowledgement = CompletableDeferred() }
        val client = client(host)
        var returned = false
        val pending = async { client.commitAudioEdit { it.withImportedEq(profile) }.also { returned = true } }
        host.writeStarted.await()
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertFalse(returned)
        assertEquals(CoreEqConfigView(), host.config)
    }

    @Test fun acknowledgementWithoutCommittedConfigCannotClaimConfirmedSuccess() = runTest {
        val host = Host().apply { includeCommittedConfig = false }
        val result = client(host).commitAudioEdit { it.withImportedEq(profile) }
        assertEquals("Core 未返回已提交的音频配置", result.exceptionOrNull()!!.message)
    }

    @Test fun eqUndoRestoresActualBeforeButPreservesSubsequentNonEqEdits() = runTest {
        val before = CoreEqConfigView(preampDb = -3.0, dsp = CoreDspConfig(parametricBands = listOf(CoreParametricEqBand(500.0, -2.0))))
        val host = Host(before)
        val client = client(host)
        val imported = client.commitAudioEdit { it.withImportedEq(profile) }.getOrThrow()
        val later = host.config.copy(preampDb = -8.0, scenes = listOf(scene("later")), gapless = false,
            dsp = host.config.dsp.copy(enabled = false, outputDeviceId = "usb-direct:/dev/bus/usb/001/002", playbackRate = 1.25))
        host.config = later
        val undone = client.commitAudioEdit { it.withRestoredEq(imported.before) }.getOrThrow()
        assertEquals(later, undone.before)
        assertEquals(later.copy(enabled = before.enabled, preampDb = before.preampDb,
            dsp = later.dsp.copy(eqMode = before.dsp.eqMode, parametricBands = before.dsp.parametricBands)), undone.committed)
    }

    @Test fun confirmedSceneSaveUsesFreshSoundAndStoresNoRecursiveSceneList() = runTest {
        val host = Host(CoreEqConfigView(scenes = listOf(scene("existing"))))
        val client = client(host)
        client.reconcileState()
        val fresh = host.config.copy(preampDb = -4.0, dsp = CoreDspConfig(playbackRate = 1.25))
        host.config = fresh
        val receipt = client.commitAudioEdit { it.withSavedDspScene("  耳机校正  ") }.getOrThrow()
        assertEquals(fresh, receipt.before)
        assertEquals(fresh.scenes.first(), receipt.committed.scenes.first())
        val saved = receipt.committed.scenes.last()
        assertTrue(saved.id.isNotBlank())
        assertEquals("耳机校正", saved.name)
        assertFalse("scenes" in saved.config)
        assertEquals(fresh.copy(scenes = emptyList()), json.decodeFromJsonElement<CoreEqConfigView>(saved.config))
        assertEquals(80, fresh.withSavedDspScene("a".repeat(100)).scenes.last().name.length)
    }

    @Test fun confirmedSaveRejectsBlankNameAndFullSceneListBeforeWriting() = runTest {
        val host = Host()
        val client = client(host)
        assertEquals("请输入场景名称", client.commitAudioEdit { it.withSavedDspScene("  ") }.exceptionOrNull()!!.message)
        host.config = host.config.copy(scenes = (1..DspScenePackage.MAX_SCENES).map { scene("scene-$it") })
        assertEquals("场景已满 32 个", client.commitAudioEdit { it.withSavedDspScene("new") }.exceptionOrNull()!!.message)
        assertTrue(host.writes.isEmpty())
    }

    @Test fun packageMergeAndUndoUseActualScenesAndKeepUnrelatedLaterChanges() = runTest {
        val stale = scene("replace", -1.0)
        val host = Host(CoreEqConfigView(scenes = listOf(stale)))
        val client = client(host)
        client.reconcileState()
        val actual = stale.copy(name = "edited before import")
        val unrelated = scene("unrelated", -2.0)
        val fresh = host.config.copy(scenes = listOf(actual, unrelated))
        host.config = fresh
        val incoming = listOf(scene("replace", -5.0), scene("new", -6.0))
        val receipt = client.commitAudioEdit { it.copy(scenes = DspScenePackage.merge(it.scenes, incoming).scenes) }.getOrThrow()
        val merge = DspScenePackage.merge(receipt.before.scenes, incoming)
        assertEquals(listOf(actual), merge.replaced)
        assertEquals(fresh.copy(scenes = merge.scenes), receipt.committed)

        val editedUnrelated = unrelated.copy(name = "edited after import")
        val later = host.config.copy(preampDb = -8.0,
            scenes = host.config.scenes.map { if (it.id == unrelated.id) editedUnrelated else it } + scene("later"))
        host.config = later
        val undone = client.commitAudioEdit { it.copy(scenes = merge.undo(it.scenes)) }.getOrThrow()
        assertEquals(later, undone.before)
        assertEquals(later.copy(scenes = listOf(actual, editedUnrelated, scene("later"))), undone.committed)
    }
}
