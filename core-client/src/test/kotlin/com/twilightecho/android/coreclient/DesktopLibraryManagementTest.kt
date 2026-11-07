package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DesktopLibraryManagementTest {
    private val json = DefaultCoreClient.DefaultJson
    // Captured from the real Node Server + paired TLS + production SQLite software test.
    private val samples = javaClass.getResourceAsStream("/desktop-library-wire.json")!!.bufferedReader().use {
        json.parseToJsonElement(it.readText()).jsonObject.getValue("samples").jsonObject
    }
    private inner class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        var remote = true
        override val isRemote get() = remote
        var route = 3L
        override val generation get() = route
        var respond: (CoreRequestEnvelope) -> CoreResponseEnvelope = { request ->
            val sample = samples.getValue(request.name)
            CoreResponseEnvelope(requestId = request.requestId, epoch = request.epoch, ok = true,
                value = if (request.kind == "command") buildJsonObject { put("result", sample) } else sample)
        }
        val requests = mutableListOf<CoreRequestEnvelope>()
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            return respond(envelope)
        }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }

    @Test fun realServerSamplesDecodeWithoutLosingCasTokensAndMusicMeaning() = runTest {
        val transport = Transport()
        val client = DesktopLibraryManagementClient(transport) { "server-a" }
        val scope = client.scope()
        val before = client.rating(scope, "recording").getOrThrow()
        val rated = client.rate(scope, before, 9).getOrThrow()
        assertEquals(9, rated.value!!.steps)
        assertEquals(before.token, transport.requests.last().payload["token"])
        val preference = client.preference(scope, before.entityId).getOrThrow()
        assertEquals("favorite", client.prefer(scope, preference, "favorite").getOrThrow().value!!.state)
        val tags = client.tags(scope).getOrThrow()
        val tag = client.createTag(scope, "手机 · 夜航", tags.token.getValue("tagsRevision").jsonPrimitive.long).getOrThrow().tag
        assertEquals(0, tag.membersRevision)
        assertEquals(1, client.tagMember(scope, tag, before.entityId, true).getOrThrow().tag.membersRevision)
        val entityTags = client.tags(scope, before.entityId).getOrThrow()
        assertEquals(tag.id, entityTags.items.single().id)
        val focusSample = json.decodeFromJsonElement<DesktopFocusPage>(samples.getValue("focus.query"))
        val query = json.decodeFromJsonElement<DesktopSavedFocusWrite>(samples.getValue("focus.saved.create")).item.query
        val page = client.focus(scope, query).getOrThrow()
        assertEquals(1, page.total)
        assertEquals(4.5, page.items.single().personalRating!!, 0.0)
        assertEquals(focusSample.token, page.token)
        client.focus(scope, query, 100, page.token).getOrThrow()
        assertEquals(100, transport.requests.last().payload.getValue("offset").jsonPrimitive.int)
        assertEquals(page.token, transport.requests.last().payload["token"])
        assertTrue(client.facets(scope, query, "personalRating", page.token).getOrThrow().items.isNotEmpty())
        val saved = client.savedFocus(scope).getOrThrow()
        val created = client.saveFocus(scope, "从手机保存", query, saved.savedRevision).getOrThrow().item
        val renamed = client.renameFocus(scope, created, "夜航筛选").getOrThrow().item
        assertEquals("夜航筛选", renamed.name)
        client.updateFocus(scope, renamed, query).getOrThrow()
        assertEquals(renamed.queryRevision, transport.requests.last().payload.getValue("queryRevision").jsonPrimitive.long)
        val playlists = client.playlists(scope).getOrThrow()
        assertTrue(playlists.items.single().isDefault)
        assertEquals(1, playlists.items.single().count)
        val populated = json.decodeFromJsonElement<DesktopServerPlaylistMembers>(samples.getValue("playlists.members.first"))
        assertEquals(1, populated.total)
        assertTrue(populated.items.single().id.startsWith("remote-member:"))
        assertEquals("静默测试", populated.items.single().track!!.title)
        val metadata = json.decodeFromJsonElement<EntityMetadataDetail>(samples.getValue("metadata.entity.detail"))
        assertEquals(metadata, client.metadata(scope, metadata.entityId).getOrThrow())
        assertEquals("在手机整理，保存在 Server。", metadata.introductionOverride!!.value)
        client.introduction(scope, metadata, "value", "在手机整理，保存在 Server。").getOrThrow()
        assertEquals(metadata.revision, transport.requests.last().payload.getValue("revision").jsonPrimitive.long)
        client.editPlaylist(scope, playlists.revision, buildJsonObject { put("kind", "pin"); put("id", "playlist"); put("pinned", true) }).getOrThrow()
        client.addSources(scope, "playlist", playlists.revision, 7, listOf("source-id")).getOrThrow()
        assertEquals(JsonArray(listOf(JsonPrimitive("source-id"))), transport.requests.last().payload["sourceIds"])
        assertTrue(transport.requests.filter { it.kind == "command" }.all { it.commandId == it.requestId })
        assertFalse(json.encodeToString(JsonArray.serializer(), JsonArray(transport.requests.map { json.encodeToJsonElement(it) })).contains("filePath"))
    }

    @Test fun oldLibraryViewsNeverEditThePhoneAnotherComputerOrANewCoreEpoch() = runTest {
        val transport = Transport()
        var epoch = "server-a"
        val client = DesktopLibraryManagementClient(transport) { epoch }
        val scope = client.scope()
        val detail = client.rating(scope, "recording").getOrThrow()
        transport.route++
        assertTrue(client.rate(scope, detail, 8).isFailure)
        assertEquals(1, transport.requests.size)
        transport.route = scope.generation; epoch = "restarted"
        assertTrue(client.rate(scope, detail, 8).isFailure)
        assertEquals(1, transport.requests.size)
        epoch = scope.epoch; transport.remote = false
        assertTrue(client.rate(scope, detail, 8).isFailure)
        assertEquals(1, transport.requests.size)
    }

    @Test fun aComputerSwitchDuringTheReadDiscardsTheResponse() = runTest {
        val transport = Transport()
        val client = DesktopLibraryManagementClient(transport) { "server-a" }
        val scope = client.scope()
        val reply = transport.respond
        transport.respond = { request -> transport.route++; reply(request) }
        assertTrue(client.tags(scope).isFailure)
        assertEquals(1, transport.requests.size)
    }

    @Test fun uncertainFocusPlaybackIsInspectedUnderItsOriginalCommandIdentityWithoutReplay() = runTest {
        val transport = Transport()
        val client = DesktopLibraryManagementClient(transport) { "server-a" }
        val command = DesktopFocusPlay(client.scope(), buildJsonObject { put("version", 1); put("entity", "recording") },
            json.decodeFromJsonElement<DesktopFocusPage>(samples.getValue("focus.query")).token)
        transport.respond = { request -> if (request.kind == "command")
            CoreResponseEnvelope(requestId = request.requestId, epoch = request.epoch, ok = false,
                error = CoreErrorView(CoreErrorCode.OUTCOME_UNKNOWN, "先查询结果"))
            else CoreResponseEnvelope(requestId = request.requestId, epoch = request.epoch, ok = true,
                value = Json.parseToJsonElement("""{"status":"committed","result":{"total":205,"selectedIndex":204},"playback":"failed"}"""))
        }
        assertEquals(CoreErrorCode.OUTCOME_UNKNOWN,
            (client.playFocus(command).exceptionOrNull() as CoreRequestException).error.code)
        val receipt = client.focusPlayStatus(command).getOrThrow()
        assertEquals(205, receipt.result!!.total)
        assertEquals("failed", receipt.playback)
        assertEquals(2, transport.requests.size)
        assertEquals(command.requestId, transport.requests.first().commandId)
        assertEquals(command.requestId, transport.requests.last().payload.getValue("requestId").jsonPrimitive.content)
        assertEquals(1, transport.requests.count { it.kind == "command" })
    }

    @Test fun staleTokensAndCancellationAreNotTurnedIntoDefaultsOrRetries() = runTest {
        val transport = Transport()
        val client = DesktopLibraryManagementClient(transport) { "server-a" }
        val scope = client.scope()
        transport.respond = { request -> CoreResponseEnvelope(requestId = request.requestId, epoch = request.epoch, ok = false,
            error = CoreErrorView(CoreErrorCode.STALE_REVISION, "请刷新")) }
        assertEquals(CoreErrorCode.STALE_REVISION, (client.tags(scope).exceptionOrNull() as CoreRequestException).error.code)
        transport.respond = { throw CancellationException("leave screen") }
        try { client.tags(scope); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(2, transport.requests.size)
    }
}
