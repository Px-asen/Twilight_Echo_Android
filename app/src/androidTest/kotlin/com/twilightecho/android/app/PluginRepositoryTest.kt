package com.twilightecho.android.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twilightecho.android.plugins.AndroidPluginRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginRepositoryTest {
    private val id = "com.twilightecho.provider.ncm"
    @Test fun bundledNcmInstallDisableEnableAndRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidPluginRepository(context).use { repository ->
            repository.initialize()
            val entry = repository.state.value.entries.single { it.id == id }
            assertNull(entry.unsupportedReason)
            if (entry.installedVersion != entry.version) repository.install(id)
            repository.setEnabled(id, true)
            assertEquals("ncm", repository.state.value.providers.single { it.pluginId == id }.id)
            val login = repository.call(id, "checkLogin").jsonObject
            assertNotNull(login["loggedIn"]?.jsonPrimitive?.booleanOrNull)
            repository.setEnabled(id, false)
            assertTrue(repository.state.value.providers.none { it.pluginId == id })
            assertTrue(runCatching { repository.call(id, "searchSongs", buildJsonArray { add("fixture") }) }.isFailure)
            repository.setEnabled(id, true)
        }
        AndroidPluginRepository(context).use { restored ->
            restored.initialize()
            assertTrue(restored.state.value.installed.single { it.id == id }.enabled)
            assertNotNull(restored.call(id, "checkLogin").jsonObject["loggedIn"])
            assertTrue(runCatching { restored.call(id, "__proto__") }.isFailure)
        }
    }
    @Test fun liveNcmSearchAndQrGateway() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidPluginRepository(context).use { repository ->
            repository.initialize()
            if (repository.state.value.entries.single { it.id == id }.let { it.installedVersion != it.version }) repository.install(id)
            repository.setEnabled(id, true)
            val result = repository.call(id, "searchSongs", buildJsonArray { add("Twilight"); add(2); add(0) }).jsonObject
            assertTrue(result.getValue("total").jsonPrimitive.int > 0)
            assertTrue(result.getValue("items").jsonArray.isNotEmpty())
            val cover = result.getValue("items").jsonArray.first().jsonObject["cover"]?.jsonPrimitive?.contentOrNull
            assertFalse("NCM search must carry its real album artwork", cover.isNullOrBlank())
            assertTrue("NCM artwork CDN must produce a decoded thumbnail",
                com.twilightecho.android.plugins.providerArtworkPreview(cover!!).orEmpty().startsWith("data:image/jpeg;base64,"))
            val qr = repository.call(id, "getQrLogin").jsonObject
            assertTrue(qr.getValue("key").jsonPrimitive.content.isNotBlank())
            assertTrue(qr.getValue("imageDataUrl").jsonPrimitive.content.startsWith("data:image/png;base64,"))
        }
    }
}
