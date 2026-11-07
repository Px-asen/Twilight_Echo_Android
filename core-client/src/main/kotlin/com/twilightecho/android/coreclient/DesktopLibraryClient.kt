package com.twilightecho.android.coreclient

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable
data class DesktopCapabilities(val queries: List<String> = emptyList(), val commands: List<String> = emptyList())

@Serializable
data class CatalogEntity(
    val id: String, val kind: String, val title: String, val artist: String = "",
    val sourceCount: Int = 0
)

@Serializable
data class CatalogPage(val revision: Long, val total: Int, val offset: Int = 0,
    val items: List<CatalogEntity> = emptyList())

@Serializable
data class CatalogTrackReferences(val revision: Long, val sourceId: String,
    val items: List<CatalogEntity> = emptyList(), val total: Int? = null)

@Serializable
data class CatalogEntityDetail(val revision: Long, val entity: CatalogEntity? = null)

@Serializable
data class CatalogSource(
    val id: String, val provider: String, val recordingId: String, val releaseId: String? = null,
    val availability: String = "unknown", val inLibrary: Boolean = false,
    val track: CatalogSourceTrack
)

@Serializable
data class CatalogSourceTrack(val title: String, val artist: String = "", val album: String = "",
    val duration: Double = 0.0, val format: String? = null, val sampleRate: Int? = null,
    val bitDepth: Int? = null)

@Serializable
data class CatalogSourcePage(val revision: Long, val total: Int, val offset: Int = 0,
    val items: List<CatalogSource> = emptyList(),
    @kotlinx.serialization.Transient val scope: DesktopLibraryScope? = null)

@Serializable
data class MetadataAttribution(val provider: String = "", val license: String = "",
    val attribution: String = "", val url: String = "", val licenseUrl: String = "")

@Serializable
data class MetadataUsage(val display: Boolean = true, val cache: Boolean = false,
    val export: Boolean = false, val expiresAt: Long? = null, val crossSource: Boolean = false) {
    fun canDisplay(now: Long = System.currentTimeMillis()) = display && (expiresAt == null || expiresAt > now)
}

@Serializable
data class MetadataIntroduction(val value: String, val language: String = "",
    val origin: String = "external", val attribution: MetadataAttribution? = null)

@Serializable
data class MetadataFact(val field: String, val value: String, val language: String = "",
    val attribution: MetadataAttribution? = null, val usage: MetadataUsage? = null)

@Serializable
data class MetadataArtwork(val dataUrl: String, val attribution: MetadataAttribution? = null,
    val usage: MetadataUsage? = null)

@Serializable
data class PersonalArtworkPreview(val dataUrl: String? = null)

@Serializable
data class ArtworkOverride(val mode: String)
@Serializable
data class MetadataIntroductionOverride(val mode: String, val value: String = "", val language: String)
@Serializable
data class MetadataSourcePreference(val provider: String? = null, val available: Boolean = false)

@Serializable
data class EntityMetadataDetail(val entityId: String, val revision: Long, val name: String,
    val introduction: MetadataIntroduction? = null, val facts: List<MetadataFact> = emptyList(),
    val artwork: List<MetadataArtwork> = emptyList(), val manualArtwork: PersonalArtworkPreview? = null,
    val artworkOverride: ArtworkOverride? = null, val targetId: String? = null,
    @kotlinx.serialization.SerialName("override") val introductionOverride: MetadataIntroductionOverride? = null, val sourcePreference: MetadataSourcePreference? = null,
    val sourceOptions: List<String> = emptyList())

@Serializable
data class TrackArtworkPreview(val id: String, val cover: String? = null)

@Serializable
data class TrackArtworkPage(val revision: Long, val items: List<TrackArtworkPreview> = emptyList())

/** The desktop's sign-in for a streaming provider. [toString] never prints the cookie. */
@Serializable
data class DesktopProviderSession(val providerId: String, val loggedIn: Boolean = false, val cookie: String? = null) {
    override fun toString() = "DesktopProviderSession(providerId=$providerId, loggedIn=$loggedIn)"
}

class CoreRequestException(val error: CoreErrorView) : IllegalStateException(error.message)

/** Desktop-only reads. Local library contracts continue to use the embedded Core unchanged. */
class DesktopLibraryClient(private val transport: CoreTransport, private val epoch: () -> String) {
    private val json = DefaultCoreClient.DefaultJson

    suspend fun info(): Result<DesktopLibraryInfo> = query("library.info", JsonObject(emptyMap()))

    suspend fun resolveMedia(reference: DesktopMediaReference): Result<DesktopMediaPlan> = query("media.resolve", buildJsonObject {
        put("libraryId", reference.libraryId); put("sourceId", reference.sourceId)
        reference.sourceVersion?.let { put("sourceVersion", it) }
    })

    suspend fun resolveLyrics(reference: DesktopMediaReference): Result<CoreLyricsView> = query("media.lyrics", buildJsonObject {
        put("libraryId", reference.libraryId); put("sourceId", reference.sourceId)
    })

    suspend fun catalog(kind: String, offset: Int = 0, revision: Long? = null): Result<CatalogPage> = query(
        "catalog.query", buildJsonObject {
            put("kind", JsonPrimitive(kind)); put("offset", JsonPrimitive(offset)); put("limit", JsonPrimitive(100))
            revision?.let { put("revision", JsonPrimitive(it)) }
        })

    suspend fun metadata(entityId: String, includeAutomaticArtwork: Boolean = false): Result<EntityMetadataDetail> = query(
        "metadata.entity.detail", buildJsonObject {
            put("entityId", JsonPrimitive(entityId)); put("language", JsonPrimitive("zh-Hans"))
            if (includeAutomaticArtwork) put("includeAutomaticArtwork", true)
        })

    /** Source artists can repeat across recordings. Share only a single confirmed person's public data. */
    suspend fun artistMetadata(entities: List<CatalogEntity>): Result<EntityMetadataDetail?> = try {
        val members = entities.distinctBy { it.id }
        val details = members.map { metadata(it.id, includeAutomaticArtwork = members.size > 1).getOrThrow() }
        val selected = if (members.size == 1) details.single() else {
            val confirmed = details.filter { !it.targetId.isNullOrBlank() }
            if (confirmed.map { it.targetId }.distinct().size != 1) null else confirmed.map {
                // A personal picture or biography belongs to its source, not every same-name source.
                val now = System.currentTimeMillis()
                val facts = it.facts.filter { fact -> fact.usage == null || fact.usage.crossSource && fact.usage.canDisplay(now) }
                it.copy(manualArtwork = null, artworkOverride = null, introductionOverride = null,
                    sourcePreference = null, sourceOptions = emptyList(),
                    facts = facts,
                    artwork = it.artwork.filter { image -> image.usage?.let { grant -> grant.crossSource && grant.canDisplay(now) } == true },
                    introduction = it.introduction?.takeIf { intro -> intro.origin == "external" && facts.any { fact ->
                        fact.field in setOf("introduction", "description") && fact.value == intro.value
                    } })
            }.let { shared -> shared.firstOrNull { it.artworkPreview() != null }
                ?: shared.firstOrNull { it.introduction != null } ?: shared.first() }
        }
        Result.success(selected)
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { Result.failure(error) }

    suspend fun sources(entityId: String, offset: Int = 0, revision: Long? = null): Result<CatalogSourcePage> {
        val scope = DesktopLibraryScope(transport.generation, epoch())
        return query<CatalogSourcePage>("catalog.sources", buildJsonObject {
            put("entityId", JsonPrimitive(entityId)); put("offset", JsonPrimitive(offset)); put("limit", JsonPrimitive(100))
            revision?.let { put("revision", JsonPrimitive(it)) }
        }).mapCatching {
            check(transport.generation == scope.generation && epoch() == scope.epoch) { "电脑曲库已改变，请重新读取" }
            it.copy(scope = scope)
        }
    }

    suspend fun entity(entityId: String, revision: Long): Result<CatalogEntityDetail> = query(
        "catalog.detail", buildJsonObject { put("id", entityId); put("revision", revision) })

    suspend fun artwork(ids: List<String>): Result<TrackArtworkPage> = query("library.artwork", buildJsonObject {
        put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    })

    /**
     * Streaming sign-in shared by the user's own desktop. Only answered over a pinned TLS link; any
     * error (older desktop, plain HTTP, not allowed) means sharing is unavailable and is not retried.
     */
    suspend fun providerSession(providerId: String): Result<DesktopProviderSession> = query("providers.session",
        buildJsonObject { put("providerId", JsonPrimitive(providerId)) })

    private suspend inline fun <reified T> query(name: String, payload: JsonObject): Result<T> = try {
        val response = transport.sendRequest(CoreRequestEnvelope(requestId = "desktop-${UUID.randomUUID()}",
            epoch = epoch(), kind = "query", name = name, payload = payload))
        if (!response.ok || response.value == null) Result.failure(CoreRequestException(
            response.error ?: CoreErrorView(CoreErrorCode.FAILED, "电脑资料读取失败")))
        else Result.success(json.decodeFromJsonElement<T>(response.value))
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Result.failure(error)
    }
}
