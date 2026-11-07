package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPluginsPresenterTest {
    private val plugin = DesktopManagedPlugin("radio", "Radio", "1.0.0", token = "version-1")
    private inner class Transport : CoreTransport {
        override val stateEvents = emptyFlow<CoreEventMessage>()
        override val isRemote = true
        var route = 1L; override val generation get() = route
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<CoreRequestEnvelope>()
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            if (envelope.kind == "command") { gate?.await(); if (fail) error("reply lost") }
            val value = if (envelope.kind == "query") DefaultCoreClient.DefaultJson.encodeToJsonElement(DesktopPluginsPage(true, 1, items = listOf(plugin)))
                else buildJsonObject { put("result", JsonNull) }
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "e1", ok = true, value = value)
        }
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) = Unit
    }
    @Test fun unconfirmedInstallCannotReplayAndWritingCannotRetarget() = runTest {
        val transport = Transport(); val presenter = DesktopPluginsPresenter(backgroundScope, DesktopPluginsClient(transport) { "e1" })
        presenter.open("书房"); runCurrent()
        transport.gate = CompletableDeferred(); transport.fail = true
        presenter.actions.edit(plugin, "enable", false); runCurrent()
        presenter.close(); presenter.open("客厅")
        assertEquals("书房", presenter.state.value.host)
        transport.gate!!.complete(Unit); runCurrent()
        assertTrue(presenter.state.value.needsRefresh)
        presenter.actions.edit(plugin, "enable", false); runCurrent()
        assertEquals(1, transport.requests.count { it.kind == "command" })
        transport.fail = false; presenter.actions.refresh(); runCurrent()
        assertFalse(presenter.state.value.needsRefresh)
        presenter.actions.edit(plugin, "enable", false); runCurrent()
        assertEquals(2, transport.requests.count { it.kind == "command" })
    }
    @Test fun stalePickerResultIsDeletedWithoutSendingToAnotherComputer() = runTest {
        val transport = Transport(); val presenter = DesktopPluginsPresenter(backgroundScope, DesktopPluginsClient(transport) { "e1" })
        presenter.open("书房"); runCurrent(); val ticket = presenter.state.value.sessionId
        presenter.close(); presenter.open("客厅"); runCurrent()
        val file = File.createTempFile("plugin-picker", ".tep").apply { writeBytes(byteArrayOf(1)) }
        presenter.actions.upload(ticket, "radio.tep", file); runCurrent()
        assertFalse(file.exists())
        assertTrue(transport.requests.none { it.kind == "command" })
        transport.route++
        presenter.actions.edit(plugin, "disable", false); runCurrent()
        assertTrue(presenter.state.value.needsRefresh)
        assertTrue(transport.requests.none { it.kind == "command" })
    }
}
