package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopQueuePresenterTest {
    private val json = DefaultCoreClient.DefaultJson
    private inner class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override val isRemote = true
        var route = 1L; override val generation get() = route
        var revision = 2L
        val requests = mutableListOf<CoreRequestEnvelope>()
        var hold: CompletableDeferred<Unit>? = null
        var loseWrite = false
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            val value: JsonElement = when (envelope.name) {
                "zones.list" -> json.encodeToJsonElement(DesktopZoneSessions(1, listOf(
                    DesktopZoneSession("local", "书房", revision, queue = DesktopZoneQueue("q-a", revision, "one")),
                    DesktopZoneSession("zone-b", "卧室", revision, queue = DesktopZoneQueue("q-b", revision, "two")))))
                "groups.list" -> buildJsonObject { put("revision", 0); put("groups", JsonArray(emptyList())) }
                "zones.queue.page" -> json.encodeToJsonElement(DesktopSessionQueuePage(revision, 2, 0, listOf(
                    DesktopSessionQueueItem(0, "one", CoreTrackView(id = "same", title = "夜航")), DesktopSessionQueueItem(1, "two", CoreTrackView(id = "same", title = "夜航")))))
                "catalog.query" -> json.encodeToJsonElement(CatalogPage(5, 1, items = listOf(CatalogEntity("recording", "recording", "夜航"))))
                "catalog.sources" -> json.encodeToJsonElement(CatalogSourcePage(5, 1, items = listOf(CatalogSource("source", "local", "recording", track = CatalogSourceTrack("夜航")))))
                else -> {
                    hold?.await()
                    if (loseWrite) throw IllegalStateException("reply lost")
                    revision++
                    buildJsonObject { put("snapshot", buildJsonObject {}) }
                }
            }
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "e1", ok = true, value = value)
        }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }
    @Test fun sourcePresetCanChooseAnotherZoneAndOnlyExplicitConfirmationReplacesItsObservedQueue() = runTest {
        val t = Transport(); val presenter = DesktopQueuePresenter(backgroundScope, DesktopSessionQueueClient(t) { "e1" })
        val selection = catalogQueueSelection(5, listOf("source"))
        presenter.open(selection = selection); runCurrent()
        assertTrue(t.requests.none { it.kind == "command" })
        presenter.actions.destination(presenter.state.value.destinations[1]); runCurrent()
        presenter.actions.prepare(); runCurrent()
        assertEquals(selection, presenter.state.value.pending)
        presenter.actions.confirm(false); runCurrent()
        val command = t.requests.single { it.kind == "command" }
        assertEquals("zone-b", command.payload["target"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("q-b", command.payload["queue"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse(command.payload["play"]!!.jsonPrimitive.boolean)
        assertNull(presenter.state.value.pending)
        assertNotNull(presenter.state.value.notice)
    }
    @Test fun writeOwnsTheSheetUntilReceiptAndUnknownOutcomeCannotBeReplayedByConfirm() = runTest {
        val t = Transport(); val presenter = DesktopQueuePresenter(backgroundScope, DesktopSessionQueueClient(t) { "e1" })
        presenter.open("zone", "zone-b", catalogQueueSelection(5, listOf("source"))); runCurrent()
        presenter.actions.prepare(); runCurrent()
        t.hold = CompletableDeferred(); t.loseWrite = true
        presenter.actions.confirm(true); runCurrent()
        assertTrue(presenter.state.value.writing)
        presenter.close(); presenter.open("zone", "local")
        assertEquals("zone-b", presenter.state.value.destination!!.target.id)
        t.hold!!.complete(Unit); runCurrent()
        assertFalse(presenter.state.value.writing)
        assertNotNull(presenter.state.value.error)
        assertTrue(presenter.state.value.needsRefresh)
        presenter.actions.prepare(); runCurrent()
        assertNull(presenter.state.value.pending)
        presenter.actions.confirm(true); runCurrent()
        assertEquals(1, t.requests.count { it.kind == "command" })
        t.loseWrite = false
        presenter.actions.refresh(); runCurrent()
        assertFalse(presenter.state.value.needsRefresh)
        presenter.actions.prepare(); presenter.actions.confirm(false); runCurrent()
        assertEquals(2, t.requests.count { it.kind == "command" })
    }
    @Test fun staleSourceScopeNeverOpensTargetsOnAnotherComputer() = runTest {
        val t = Transport(); val client = DesktopSessionQueueClient(t) { "e1" }; val old = client.scope(); t.route++
        val presenter = DesktopQueuePresenter(backgroundScope, client)
        presenter.open(selection = catalogQueueSelection(5, listOf("source")), expected = old); runCurrent()
        assertTrue(t.requests.isEmpty()); assertNotNull(presenter.state.value.error)
    }
    @Test fun movesAddressOccurrencesAndNeverTreatALoadedPageBoundaryAsTheEndOfTheQueue() {
        val rows = (0..2).map { DesktopSessionQueueItem(it, "occ-$it", CoreTrackView(id = "same", title = "夜航")) }
        assertNull(sessionQueueMove(rows, 20, 1, true))
        assertNull(sessionQueueMove(rows, 20, 2, true))
        val move = sessionQueueMove(rows, 3, 1, true)!!
        assertEquals("occ-1", move["entryId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, move["beforeEntryId"])
        assertEquals("occ-0", sessionQueueMove(rows, 3, 1, false)!!["beforeEntryId"]!!.jsonPrimitive.content)
    }
    @Test fun addingAFacetPreservesTheEntireSavedBooleanTreeAndOtherQueryFields() {
        val original = json.parseToJsonElement("""{"version":1,"entity":"release","scope":"catalog","where":{"not":{"any":[{"text":"live"},{"rating":{"target":"self","mode":"effective","gteStars":2}}]}},"sort":[{"field":"releaseYear","direction":"desc"}]}""").jsonObject
        val node = buildJsonObject { put("source", buildJsonObject { put("provider", "local") }) }
        val changed = appendFocusCondition(original, node)
        assertEquals(original["sort"], changed["sort"])
        assertEquals(original["entity"], changed["entity"])
        assertEquals(original["where"], changed["where"]!!.jsonObject["all"]!!.jsonArray[0])
        assertEquals(node, changed["where"]!!.jsonObject["all"]!!.jsonArray[1])
    }
}
