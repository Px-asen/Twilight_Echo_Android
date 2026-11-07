package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopEntityEditorPresenterTest {
    private val recording = CatalogEntity("recording-a", "recording", "夜航", "北岸")
    private val json = DefaultCoreClient.DefaultJson
    private inner class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override val isRemote = true
        var route = 1L
        override val generation get() = route
        val requests = mutableListOf<CoreRequestEnvelope>()
        var targets = listOf(recording)
        var metadataRevision = 2L
        var intro = "电脑简介"
        var steps: Int? = null
        var preference = "none"
        var tagsRevision = 1L
        val tags = (0 until 205).map { tag(it) }.toMutableList()
        val assigned = (0 until 151).mapTo(linkedSetOf()) { "tag-$it" }
        fun tag(index: Int) = DesktopTag("tag-$index", "标签 $index", 1, 1, 1, 0)
        fun token() = buildJsonObject { put("tagsRevision", tagsRevision); put("catalogRevision", 3); put("linkRevision", 4); put("contentRevision", 5) }
        var respond: suspend (CoreRequestEnvelope) -> CoreResponseEnvelope = { request -> answer(request) }
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            return respond(envelope)
        }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
        fun answer(request: CoreRequestEnvelope): CoreResponseEnvelope {
            val p = request.payload
            fun text(key: String) = p.getValue(key).jsonPrimitive.content
            val value = when (request.name) {
                "metadata.entity.detail" -> json.encodeToJsonElement(EntityMetadataDetail(text("entityId"), metadataRevision,
                    "夜航", MetadataIntroduction(intro), sourceOptions = listOf("musicbrainz", "wikidata")))
                "preferences.targets" -> json.encodeToJsonElement(DesktopEntityPage(0, targets.size, targets))
                "ratings.detail", "ratings.set" -> {
                    if (request.kind == "command") steps = p.getValue("steps").jsonPrimitive.intOrNull
                    json.encodeToJsonElement(DesktopRatingDetail(text("entityId"), "recording", "entity",
                        steps?.let { DesktopRatingValue(it, 1, 0) }, buildJsonObject { put("ratingsRevision", 1) }))
                }
                "preferences.detail", "preferences.set" -> {
                    if (request.kind == "command") preference = text("state")
                    json.encodeToJsonElement(DesktopPreferenceDetail(text("entityId"), "recording", "entity",
                        DesktopPreferenceValue(preference, 1, 0), buildJsonObject { put("preferencesRevision", 1) }))
                }
                "tags.entity", "tags.list" -> {
                    val offset = p.getValue("offset").jsonPrimitive.int
                    val items = if (request.name == "tags.entity") tags.filter { it.id in assigned } else tags
                    json.encodeToJsonElement(DesktopTagsPage(token(), offset, items.size, items.drop(offset).take(100)))
                }
                "tags.member.set" -> {
                    val index = tags.indexOfFirst { it.id == text("tagId") }
                    val tag = tags[index].copy(membersRevision = tags[index].membersRevision + 1)
                    tags[index] = tag; tagsRevision++
                    if (p.getValue("present").jsonPrimitive.boolean) assigned += tag.id else assigned -= tag.id
                    json.encodeToJsonElement(DesktopTagWrite(token(), tag))
                }
                "tags.create" -> {
                    val tag = tag(tags.size).copy(name = text("name"), membersRevision = 0, memberCount = 0)
                    tags += tag; tagsRevision++
                    json.encodeToJsonElement(DesktopTagWrite(token(), tag))
                }
                "metadata.entity.override", "metadata.entity.artworkOverride", "metadata.entity.sourcePreference" -> {
                    metadataRevision++
                    if (request.name == "metadata.entity.override") intro = p["value"]?.jsonPrimitive?.content ?: "自动简介"
                    buildJsonObject { put("entityId", text("entityId")) }
                }
                else -> error("Unexpected ${request.name}")
            }
            return CoreResponseEnvelope(requestId = request.requestId, epoch = request.epoch, ok = true,
                value = if (request.kind == "command") buildJsonObject { put("result", value) } else value)
        }
        fun failure(request: CoreRequestEnvelope, code: CoreErrorCode, message: String) = CoreResponseEnvelope(
            requestId = request.requestId, epoch = request.epoch, ok = false, error = CoreErrorView(code, message))
    }

    @Test fun editsUseExactIdsAndFreshCasWithoutOverwritingAnUnrelatedIntroductionDraft() = runTest {
        val transport = Transport()
        var refreshed = 0
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" }) { refreshed++ }
        editor.open(recording, "书房"); runCurrent()
        assertEquals(recording, editor.state.value.selectedTarget)
        editor.introductionDraft("我的草稿")
        val reads = transport.requests.size
        editor.rate(9); runCurrent()
        assertEquals(9, editor.state.value.rating!!.value!!.steps)
        assertEquals(reads + 1, transport.requests.size)
        editor.prefer("favorite"); runCurrent()
        assertEquals("favorite", editor.state.value.preference!!.value!!.state)
        editor.refresh(); runCurrent()
        assertEquals("我的草稿", editor.state.value.introductionDraft)
        editor.introduction("value"); runCurrent()
        assertEquals(1, refreshed)
        assertEquals(3L, editor.state.value.metadata!!.revision)
        assertFalse(editor.state.value.introductionDirty)
        assertEquals("我的草稿", editor.state.value.introductionDraft)
        assertTrue(transport.requests.filter { it.kind == "command" }.all { it.payload["entityId"]?.jsonPrimitive?.content == recording.id })
        assertEquals(2L, transport.requests.first { it.name == "metadata.entity.override" }.payload.getValue("revision").jsonPrimitive.long)
    }

    @Test fun aggregateVersionsRequireAnExplicitChoiceAndNeverUseTheSharedTitleAsIdentity() = runTest {
        val transport = Transport().apply { targets = listOf(recording, recording.copy(id = "recording-b")) }
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        editor.open(recording.copy(id = "aggregate"), "书房"); runCurrent()
        assertNull(editor.state.value.selectedTarget)
        assertFalse(transport.requests.any { it.name in setOf("ratings.detail", "preferences.detail", "tags.entity") })
        editor.rate(9); runCurrent()
        assertFalse(transport.requests.any { it.kind == "command" })
        editor.selectTarget("recording-b"); runCurrent()
        editor.rate(8); runCurrent()
        assertEquals("recording-b", transport.requests.last().payload.getValue("entityId").jsonPrimitive.content)
        assertEquals("aggregate", editor.state.value.metadata!!.entityId)
    }

    @Test fun allAssignedTagsAndAllAvailablePagesShareTheirTokenAndMemberRevisionIsUpdated() = runTest {
        val transport = Transport()
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        editor.open(recording, "书房"); runCurrent()
        assertEquals(205, editor.state.value.tags.size)
        assertEquals(151, editor.state.value.assignedTags.size)
        assertTrue("tag-150" in editor.state.value.assignedTags)
        assertEquals(listOf(0, 100), transport.requests.filter { it.name == "tags.entity" }.map { it.payload.getValue("offset").jsonPrimitive.int })
        assertTrue(transport.requests.filter { it.name == "tags.list" || it.name == "tags.entity" && it.payload.getValue("offset").jsonPrimitive.int > 0 }
            .all { it.payload["token"] == transport.token() })
        editor.tag("tag-150", false); runCurrent()
        assertFalse("tag-150" in editor.state.value.assignedTags)
        editor.tag("tag-150", true); runCurrent()
        assertEquals(2L, transport.requests.last().payload.getValue("membersRevision").jsonPrimitive.long)
        assertTrue("tag-150" in editor.state.value.assignedTags)
    }

    @Test fun successfulTagCreationIsKeptWhenAssignmentFailsAndCreationIsNotReplayed() = runTest {
        val transport = Transport()
        transport.respond = { request -> if (request.name == "tags.member.set")
            transport.failure(request, CoreErrorCode.STALE_REVISION, "成员已更新") else transport.answer(request) }
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        editor.open(recording, "书房"); runCurrent()
        editor.tagNameDraft("夜间"); editor.createTag(); runCurrent()
        assertEquals("夜间", editor.state.value.tags.last { it.name == "夜间" }.name)
        assertTrue(editor.state.value.notice!!.contains("已创建，尚未添加"))
        assertEquals("成员已更新", editor.state.value.tagsError)
        assertFalse(editor.state.value.tagsWritable)
        editor.createTag(); runCurrent()
        assertEquals(1, transport.requests.count { it.name == "tags.create" })
    }

    @Test fun unconfirmedArtistCanUseExactSourceTagsButNotRatingsOrPreferencesAndAlbumProjectsDoNotQueryThem() = runTest {
        val transport = Transport().apply { targets = emptyList() }
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        val artist = recording.copy(id = "artist-source", kind = "artist")
        editor.open(artist, "书房"); runCurrent()
        assertEquals(artist, editor.state.value.selectedTarget)
        assertTrue(editor.state.value.tagsWritable)
        assertFalse(transport.requests.any { it.name == "ratings.detail" || it.name == "preferences.detail" })
        transport.requests.clear()
        editor.open(artist.copy(kind = "album_project"), "书房"); runCurrent()
        assertEquals(listOf("metadata.entity.detail"), transport.requests.map { it.name })
        assertNull(editor.state.value.selectedTarget)
    }

    @Test fun submittedMutationCannotBeDismissedOrDuplicatedAndLateResultsCannotEnterANewSession() = runTest {
        val transport = Transport()
        val gate = CompletableDeferred<Unit>()
        transport.respond = { request ->
            if (request.kind == "command") withContext(NonCancellable) { gate.await() }
            transport.answer(request)
        }
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        editor.open(recording, "书房"); runCurrent()
        editor.rate(9); runCurrent()
        editor.close(); editor.rate(7); runCurrent()
        assertNotNull(editor.state.value.entity)
        assertEquals(1, transport.requests.count { it.kind == "command" })
        editor.reset(); transport.route++
        editor.open(recording.copy(id = "new"), "客厅"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("客厅", editor.state.value.host)
        assertNull(editor.state.value.notice)
        assertNull(editor.state.value.rating!!.value)
    }

    @Test fun stalePickerTicketAndComputerSwitchDuringImageConversionCannotUploadToANewTarget() = runTest {
        val transport = Transport()
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        editor.open(recording.copy(kind = "release"), "书房"); runCurrent()
        val old = editor.state.value.ticket!!
        editor.close(); editor.open(recording.copy(id = "other", kind = "release"), "书房"); runCurrent()
        var conversions = 0
        editor.importArtwork(old, "content://image") { conversions++; "bitmap" }; runCurrent()
        assertEquals(0, conversions)
        editor.importArtwork(editor.state.value.ticket!!, "content://image") {
            conversions++; transport.route++; "data:image/webp;base64,AA=="
        }; runCurrent()
        assertEquals(1, conversions)
        assertFalse(transport.requests.any { it.kind == "command" })
        assertFalse(editor.state.value.metadataWritable)
    }

    @Test fun unknownMutationIsNotRetriedAndSuccessfulSaveWithFailedRefreshKeepsItsReceipt() = runTest {
        val transport = Transport()
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        editor.open(recording, "书房"); runCurrent()
        transport.respond = { request -> if (request.name == "ratings.set")
            transport.failure(request, CoreErrorCode.OUTCOME_UNKNOWN, "连接已断开") else transport.answer(request) }
        editor.rate(9); runCurrent(); editor.rate(9); runCurrent()
        assertEquals(1, transport.requests.count { it.name == "ratings.set" })
        assertTrue(editor.state.value.personalError!!.contains("结果未确认"))
        editor.introductionDraft("已保存的文字")
        transport.respond = { request -> if (request.name == "metadata.entity.detail")
            transport.failure(request, CoreErrorCode.FAILED, "暂时读不到资料") else transport.answer(request) }
        editor.introduction("value"); runCurrent()
        assertTrue(editor.state.value.notice!!.contains("已保存"))
        assertEquals("暂时读不到资料", editor.state.value.metadataError)
        assertFalse(editor.state.value.metadataWritable)
        assertEquals(1, transport.requests.count { it.name == "metadata.entity.override" })
        transport.respond = transport::answer
        editor.refresh(); runCurrent()
        assertEquals("已保存的文字", editor.state.value.introductionDraft)
        assertTrue(editor.state.value.metadataWritable)
    }

    @Test fun uncancellableReadAfterResetAndCreationAfterResetCannotPublishOrStartAnotherCommit() = runTest {
        val transport = Transport()
        val readGate = CompletableDeferred<Unit>()
        transport.respond = { request ->
            if (request.name == "metadata.entity.detail" && request.payload["entityId"]?.jsonPrimitive?.content == "old")
                withContext(NonCancellable) { readGate.await() }
            transport.answer(request)
        }
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        editor.open(recording.copy(id = "old"), "书房"); runCurrent()
        editor.open(recording, "书房"); runCurrent()
        readGate.complete(Unit); runCurrent()
        assertEquals(recording.id, editor.state.value.metadata!!.entityId)
        val createGate = CompletableDeferred<Unit>()
        transport.respond = { request ->
            if (request.name == "tags.create") withContext(NonCancellable) { createGate.await() }
            transport.answer(request)
        }
        editor.tagNameDraft("新标签"); editor.createTag(); runCurrent()
        editor.reset(); createGate.complete(Unit); runCurrent()
        assertNull(editor.state.value.entity)
        assertFalse(transport.requests.any { it.name == "tags.member.set" })
    }

    @Test fun aLargeTagListDoesNotPreventSavingAlreadyLoadedMetadataOrRatings() = runTest {
        val transport = Transport()
        val gate = CompletableDeferred<Unit>()
        transport.respond = { request ->
            if (request.name == "tags.entity") gate.await()
            transport.answer(request)
        }
        val editor = DesktopEntityEditorPresenter(backgroundScope, DesktopLibraryManagementClient(transport) { "e1" })
        editor.open(recording, "书房"); runCurrent()
        assertTrue(editor.state.value.tagsLoading)
        assertFalse(editor.state.value.personalLoading)
        editor.rate(9); runCurrent()
        assertEquals(9, editor.state.value.rating!!.value!!.steps)
        editor.introductionDraft("不必等标签读完")
        editor.introduction("value"); runCurrent()
        assertEquals("不必等标签读完", transport.intro)
        assertTrue(editor.state.value.tagsLoading)
        gate.complete(Unit); runCurrent()
        assertFalse(editor.state.value.tagsLoading)
    }
}
