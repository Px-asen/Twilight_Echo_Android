package com.twilightecho.android.coreclient

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DesktopArtistMetadataTest {
    private val json = DefaultCoreClient.DefaultJson
    private fun entity(id: String) = CatalogEntity(id, "artist", "北岸")

    private class Transport(var details: Map<String, EntityMetadataDetail>) : CoreTransport {
        override val stateEvents = MutableSharedFlow<CoreEventMessage>()
        val requests = mutableListOf<CoreRequestEnvelope>()
        override suspend fun cancelRequest(envelope: CoreCancelEnvelope) {}
        override suspend fun sendRequest(envelope: CoreRequestEnvelope): CoreResponseEnvelope {
            requests += envelope
            val id = envelope.payload.getValue("entityId").jsonPrimitive.content
            return CoreResponseEnvelope(requestId = envelope.requestId, epoch = "desktop", ok = true,
                value = DefaultCoreClient.DefaultJson.encodeToJsonElement(details.getValue(id)))
        }
    }

    @Test fun aSingleSourceKeepsItsPersonalPortraitAndDuplicateReferencesCostOneRead() = runTest {
        val detail = EntityMetadataDetail("a", 1, "北岸", manualArtwork = PersonalArtworkPreview("manual"))
        val transport = Transport(mapOf("a" to detail))
        val client = DesktopLibraryClient(transport) { "desktop" }
        assertEquals(detail, client.artistMetadata(listOf(entity("a"), entity("a"))).getOrThrow())
        assertEquals(1, transport.requests.size)
        assertFalse(transport.requests.single().payload.containsKey("includeAutomaticArtwork"))
        assertNull(client.artistMetadata(emptyList()).getOrThrow())
    }

    @Test fun sameTargetSourcesShareAutomaticPortraitsNotAnotherSourcesPersonalEdits() = runTest {
        val detail = EntityMetadataDetail("a", 1, "北岸", introduction = MetadataIntroduction("个人简介", origin = "manual"),
            artwork = listOf(MetadataArtwork("public", usage = MetadataUsage(crossSource = true))), manualArtwork = PersonalArtworkPreview("manual"),
            artworkOverride = ArtworkOverride("clear"), targetId = "person-1",
            introductionOverride = MetadataIntroductionOverride("value", "个人简介", "zh-Hans"),
            sourcePreference = MetadataSourcePreference("ncm", true), sourceOptions = listOf("ncm"))
        val transport = Transport(mapOf("a" to detail, "b" to detail.copy(entityId = "b")))
        val result = DesktopLibraryClient(transport) { "desktop" }.artistMetadata(listOf(entity("a"), entity("b"))).getOrThrow()!!
        assertEquals("public", result.artworkPreview())
        assertNull(result.manualArtwork)
        assertNull(result.artworkOverride)
        assertNull(result.introduction)
        assertNull(result.introductionOverride)
        assertNull(result.sourcePreference)
        assertTrue(result.sourceOptions.isEmpty())
        assertTrue(transport.requests.all { it.payload.getValue("includeAutomaticArtwork").jsonPrimitive.boolean })
    }

    @Test fun sharedMetadataRequiresACrossSourceGrantAndNeverUsesSourceOnlyExternalText() = runTest {
        val detail = EntityMetadataDetail("a", 1, "北岸", targetId = "person-1",
            introduction = MetadataIntroduction("来源限定的简介"),
            facts = listOf(MetadataFact("introduction", "来源限定的简介", usage = MetadataUsage(crossSource = false))),
            artwork = listOf(MetadataArtwork("source-only", usage = MetadataUsage(crossSource = false))))
        val transport = Transport(mapOf("a" to detail, "b" to detail.copy(entityId = "b")))
        val client = DesktopLibraryClient(transport) { "desktop" }
        val entities = listOf(entity("a"), entity("b"))
        val blocked = client.artistMetadata(entities).getOrThrow()!!
        assertNull(blocked.artworkPreview())
        assertNull(blocked.introduction)
        val granted = MetadataUsage(crossSource = true)
        transport.details = transport.details.mapValues { (_, value) -> value.copy(
            facts = value.facts.map { it.copy(usage = granted) }, artwork = value.artwork.map { it.copy(usage = granted) }) }
        val shared = client.artistMetadata(entities).getOrThrow()!!
        assertEquals("source-only", shared.artworkPreview())
        assertEquals(detail.introduction, shared.introduction)
    }

    @Test fun homonymsAndUnconfirmedSourcesDoNotPublishAGuessedPortrait() = runTest {
        val detail = EntityMetadataDetail("a", 1, "北岸", targetId = "person-1")
        val transport = Transport(mapOf("a" to detail, "b" to detail.copy(entityId = "b", targetId = "person-2")))
        val client = DesktopLibraryClient(transport) { "desktop" }
        val entities = listOf(entity("a"), entity("b"))
        assertNull(client.artistMetadata(entities).getOrThrow())
        transport.details = transport.details.mapValues { it.value.copy(targetId = null) }
        assertNull(client.artistMetadata(entities).getOrThrow())
    }

    @Test fun bridgeTargetIdentityIsDecodedWithoutChangingOlderDesktopResponses() {
        val old = """{"entityId":"a","revision":1,"name":"北岸"}"""
        assertNull(json.decodeFromString<EntityMetadataDetail>(old).targetId)
        assertEquals("person-1", json.decodeFromString<EntityMetadataDetail>(old.dropLast(1) + ",\"targetId\":\"person-1\"}").targetId)
    }
}
