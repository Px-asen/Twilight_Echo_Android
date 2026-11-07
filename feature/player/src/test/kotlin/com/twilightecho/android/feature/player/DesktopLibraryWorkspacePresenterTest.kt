package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLibraryWorkspacePresenterTest {
    private val json = DefaultCoreClient.DefaultJson
    private inner class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override val isRemote = true
        var route = 1L; override val generation get() = route
        var revision = 3L
        var hold: CompletableDeferred<Unit>? = null
        var loseWrite = false
        val requests = mutableListOf<CoreRequestEnvelope>()
        fun token() = buildJsonObject { put("tagsRevision", revision); put("catalogRevision", 5); put("contentRevision", 2); put("linkRevision", 4) }
        fun tag(i: Int) = DesktopTag("tag-$i", "标签 $i", 2, revision, 1, 0)
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            val p = envelope.payload
            val offset = p["offset"]?.jsonPrimitive?.int ?: 0
            val value = if (envelope.kind == "command") {
                hold?.await(); if (loseWrite) throw IllegalStateException("reply lost")
                revision++
                val result = when (envelope.name) {
                    "tags.member.set", "tags.create" -> json.encodeToJsonElement(DesktopTagWrite(token(), tag(0)))
                    "focus.saved.create" -> json.encodeToJsonElement(DesktopSavedFocusWrite(revision, DesktopSavedFocus("saved-new", p["name"]!!.jsonPrimitive.content, p["query"]!!.jsonObject, 1, 1, 0)))
                    else -> json.encodeToJsonElement(DesktopPlaylistMutation(revision))
                }
                buildJsonObject { put("result", result) }
            } else when (envelope.name) {
                "focus.saved.list" -> json.encodeToJsonElement(DesktopSavedFocusPage(revision, offset, 105, (offset until minOf(offset + 100, 105)).map {
                    DesktopSavedFocus("saved-$it", "条件 $it", buildJsonObject { put("version", 1); put("entity", "recording") }, 1, 2, 0)
                }))
                "focus.query" -> json.encodeToJsonElement(DesktopFocusPage(token(), offset, 105, (offset until minOf(offset + 100, 105)).map {
                    DesktopFocusItem("recording-$it", "recording", "夜航 $it", "北岸", 1, true, true, "source-$it")
                }))
                "tags.list" -> json.encodeToJsonElement(DesktopTagsPage(token(), offset, 105, (offset until minOf(offset + 100, 105)).map(::tag)))
                "tags.members" -> json.encodeToJsonElement(DesktopTagMembers(token(), tag(p["tagId"]!!.jsonPrimitive.content.substringAfter('-').toInt()), offset, 1,
                    listOf(CatalogEntity("recording-a", "recording", "夜航"))))
                "playlists.catalog" -> json.encodeToJsonElement(DesktopServerPlaylists(revision, offset, 105, (offset until minOf(offset + 100, 105)).map {
                    DesktopServerPlaylist("playlist-$it", "歌单 $it", 1)
                }))
                "playlists.members" -> json.encodeToJsonElement(DesktopServerPlaylistMembers(revision, offset, 1,
                    listOf(DesktopServerPlaylistMember("remote-member:a", 0, CoreTrackView(id = "remote-track:a", title = "夜航")))))
                "core.getSnapshot" -> buildJsonObject { put("versionsRevision", 8) }
                else -> error("Unexpected request ${envelope.name}")
            }
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "e1", ok = true, value = value)
        }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }
    @Test fun focusPagesUseFrozenTokensAndPlaybackCarriesTheOriginalScopeAndWholeQuery() = runTest {
        val t = Transport(); var played: Pair<DesktopLibraryScope, JsonObject>? = null
        val workspace = DesktopLibraryWorkspacePresenter(backgroundScope, DesktopLibraryManagementClient(t) { "e1" }, DesktopSessionQueueClient(t) { "e1" },
            { captured, selection -> played = captured to selection }, { _, _, _ -> })
        workspace.open("书房"); runCurrent()
        workspace.actions.moreFocus(); runCurrent()
        assertEquals(105, workspace.state.value.focus!!.items.size)
        assertEquals(workspace.state.value.focus!!.token, t.requests.last().payload["token"])
        workspace.actions.moreSaved(); runCurrent()
        assertEquals(105, workspace.state.value.saved!!.items.size)
        val draft = json.parseToJsonElement("""{"version":1,"entity":"release","where":{"not":{"any":[{"text":"live"},{"preference":"ban"}]}},"sort":[{"field":"releaseYear","direction":"desc"}]}""").jsonObject
        workspace.actions.query(draft); workspace.actions.focus(); runCurrent()
        workspace.actions.playFocus("source-1"); runCurrent()
        assertEquals(DesktopLibraryScope(1, "e1"), played!!.first)
        assertEquals(draft, played!!.second["input"]!!.jsonObject["query"])
        assertTrue(t.requests.none { it.kind == "command" })
        workspace.actions.save("夜间筛选"); runCurrent()
        assertEquals(draft, t.requests.single { it.kind == "command" }.payload["query"])
        assertEquals(draft, workspace.state.value.query)
    }
    @Test fun tagDirectoryAndMembersPastPageOneKeepTheirOwnCasAndWritesCannotFollowRouteChanges() = runTest {
        val t = Transport()
        val workspace = DesktopLibraryWorkspacePresenter(backgroundScope, DesktopLibraryManagementClient(t) { "e1" }, DesktopSessionQueueClient(t) { "e1" }, { _, _ -> }, { _, _, _ -> })
        workspace.open("书房"); runCurrent(); workspace.actions.tab(DesktopWorkspaceTab.TAGS); runCurrent()
        workspace.actions.moreTags(); runCurrent(); assertEquals(105, workspace.state.value.tags!!.items.size)
        workspace.actions.tag(workspace.state.value.tags!!.items.last()); runCurrent()
        assertEquals("tag-104", workspace.state.value.tag!!.tag.id)
        val revision = workspace.state.value.tag!!.tag.membersRevision
        workspace.actions.removeTagMember("recording-a"); runCurrent()
        assertEquals(revision, t.requests.single { it.kind == "command" }.payload["membersRevision"]!!.jsonPrimitive.long)
        t.route++
        workspace.actions.removeTagMember("recording-a"); runCurrent()
        assertEquals(1, t.requests.count { it.kind == "command" })
        assertNotNull(workspace.state.value.error)
    }
    @Test fun playlistMembershipAndSourcePickerStayOnTheLibraryComputerWhileWritingOwnsItsOriginalSheet() = runTest {
        val t = Transport(); var add: Triple<DesktopServerPlaylist, Long, DesktopLibraryScope>? = null
        val workspace = DesktopLibraryWorkspacePresenter(backgroundScope, DesktopLibraryManagementClient(t) { "e1" }, DesktopSessionQueueClient(t) { "e1" }, { _, _ -> },
            { playlist, revision, scope -> add = Triple(playlist, revision, scope) })
        workspace.open("书房"); runCurrent(); workspace.actions.tab(DesktopWorkspaceTab.PLAYLISTS); runCurrent()
        workspace.actions.morePlaylists(); runCurrent(); assertEquals(105, workspace.state.value.playlists!!.items.size)
        workspace.actions.playlist(workspace.state.value.playlists!!.items.last()); runCurrent()
        workspace.actions.addSources()
        assertEquals("playlist-104", add!!.first.id); assertEquals(DesktopLibraryScope(1, "e1"), add!!.third)
        t.hold = CompletableDeferred(); t.loseWrite = true
        workspace.actions.playlistEdit(buildJsonObject { put("kind", "remove"); put("id", "playlist-104"); put("ids", buildJsonArray { add("remote-member:a") }) })
        runCurrent(); workspace.close(); workspace.actions.tab(DesktopWorkspaceTab.FOCUS)
        assertTrue(workspace.state.value.open); assertEquals(DesktopWorkspaceTab.PLAYLISTS, workspace.state.value.tab)
        t.hold!!.complete(Unit); runCurrent()
        assertNotNull(workspace.state.value.error)
        assertEquals(1, t.requests.count { it.kind == "command" })
    }
}
