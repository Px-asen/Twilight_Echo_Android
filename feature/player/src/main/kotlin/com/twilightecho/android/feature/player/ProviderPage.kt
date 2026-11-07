package com.twilightecho.android.feature.player

import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.plugins.MobileProvider
import com.twilightecho.android.plugins.ProviderPanel
import com.twilightecho.android.plugins.providerTrackView
import kotlinx.serialization.json.*

// Plugins-repository providers (ABI v2) describe their page with Desktop's provider `ui` declaration.

/** A streaming section the provider declared: `method(...args)` returns tracks. */
internal data class ProviderSection(val id: String, val title: String, val method: String, val args: JsonArray,
    val requiresLogin: Boolean, val icon: String = "", val eyebrow: String = "", val description: String = "")

/** Provider home presentation metadata from `ui.streamingHome` and `ui.color`. */
internal data class ProviderHomeMeta(val requiresLogin: Boolean, val subtitle: String, val colorHex: String?)

/** A required disclaimer/consent field discovered in one of the provider's settings panels. */
internal data class ProviderDisclaimer(
    val panel: ProviderPanel,
    val form: ProviderForm,
    val fieldKey: String,
    val notice: String,
    val acceptLabel: String,
    val acceptValue: String,
    val accepted: Boolean
) {
    /** Submission payload preserving sibling field values while confirming the disclaimer. */
    fun acceptedSubmission(): JsonObject = JsonObject(form.fields.associate { field ->
        val next = if (field.key == fieldKey) acceptValue else field.value
        field.key to JsonPrimitive(next.take(4096))
    })
}

/** An open settings panel: the form its command returned, or the message it returned instead. */
internal data class ProviderPanelState(val panel: ProviderPanel, val form: ProviderForm? = null, val message: String? = null)
internal data class ProviderForm(val submitCommand: String, val notice: String, val fields: List<ProviderField>)
internal data class ProviderField(val key: String, val label: String, val type: String, val value: String,
    val required: Boolean = false, val readonly: Boolean = false, val placeholder: String = "", val group: String = "",
    /** `(label, value)` choices of a select field. */
    val options: List<Pair<String, String>> = emptyList())

/** `ui.qrStatusCodes`; `denied` counts as expired, as on Desktop. */
internal class QrStatusCodes(val waiting: Int, val scanned: Int?, val expired: Int, val denied: Int?, val success: Int) {
    fun expired(code: Int) = code == expired || code == denied
}

/** `checkQrLogin` codes Desktop treats as a failed sign-in rather than a state of the code. */
internal val QR_FAILURES = setOf(-1, 301, 502, 503, 460)

internal fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true

/** The provider's QR codes, else Desktop's defaults (NCM's). */
internal fun MobileProvider.qrStatusCodes(): QrStatusCodes {
    val codes = ui["qrStatusCodes"] as? JsonObject ?: return QrStatusCodes(801, 802, 800, null, 803)
    fun code(key: String) = (codes[key] as? JsonPrimitive)?.intOrNull
    return QrStatusCodes(code("waiting") ?: 801, code("scanned"), code("expired") ?: 800, code("denied"), code("success") ?: 803)
}

internal fun MobileProvider.searchable() = "search" in capabilities && "searchSongs" in methods

internal fun MobileProvider.homeMeta(): ProviderHomeMeta {
    val home = ui["streamingHome"] as? JsonObject
    val requiresLogin = (home?.get("requiresLogin") as? JsonPrimitive)?.booleanOrNull != false
    val subtitle = home?.text("subtitle").orEmpty().ifBlank { ui.text("description") }
    return ProviderHomeMeta(requiresLogin, subtitle, ui.text("color").trim().ifBlank { null })
}

internal fun MobileProvider.homeEligible(): Boolean =
    sections().isNotEmpty() || "fetchUserLibrary" in methods || "fetchRecommendPlaylists" in methods

internal fun MobileProvider.sections(): List<ProviderSection> = (ui["streamingSections"] as? JsonArray).orEmpty().mapNotNull { raw ->
    val section = raw as? JsonObject ?: return@mapNotNull null
    val id = section.text("id"); val method = section.text("method")
    if (id.isBlank() || method !in methods) return@mapNotNull null
    ProviderSection(
        id = id,
        title = section.text("title").ifBlank { id },
        method = method,
        args = section["args"] as? JsonArray ?: JsonArray(emptyList()),
        requiresLogin = section.flag("requiresLogin"),
        icon = section.text("icon"),
        eyebrow = section.text("eyebrow"),
        description = section.text("description")
    )
}.distinctBy { it.id }

/**
 * The page's tabs as `(page kind, label)`: the sections Desktop's online home would offer, recommended playlists,
 * then the account's playlists. Signed out, a home that does not declare `requiresLogin: false` shows no sections.
 */
internal fun MobileProvider.tabs(loggedIn: Boolean): List<Pair<String, String>> {
    val home = loggedIn || !homeMeta().requiresLogin
    val sections = if (!home) emptyList() else sections().filter { loggedIn || !it.requiresLogin }.map { "section:${it.id}" to it.title }
    val recommended = if (home && "fetchRecommendPlaylists" in methods) listOf("recommend" to "推荐歌单") else emptyList()
    return sections + recommended + if (loggedIn && "fetchUserLibrary" in methods) listOf("library" to "我的歌单") else emptyList()
}

/** A provider's track list: the array itself, or the `tracks`, `items` or `songs` of a page. */
internal fun providerTrackArray(value: JsonElement?): JsonArray = value as? JsonArray
    ?: (value as? JsonObject)?.let { page -> listOf("tracks", "items", "songs").firstNotNullOfOrNull { page[it] as? JsonArray } }
    ?: JsonArray(emptyList())

/** Queue entries with ids unique to this listing; tracks without a usable id are left out. */
internal fun JsonArray.providerTracks(providerId: String, listing: String): List<CoreTrackView> = mapIndexedNotNull { index, item ->
    (item as? JsonObject)?.let { runCatching { providerTrackView(providerId, it).copy(queueEntryId = "$listing-$index") }.getOrNull() }
}

private fun parseProviderPlaylist(item: JsonObject): ProviderPlaylist? {
    val id = item.text("id").ifBlank { return null }
    val title = item.text("name").ifBlank { item.text("title").ifBlank { "未命名歌单" } }
    val count = (item["trackCount"] as? JsonPrimitive)?.intOrNull ?: 0
    val cover = listOf("cover", "coverSmall", "coverImgUrl", "picUrl")
        .firstNotNullOfOrNull { key -> item.text(key).trim().takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) } }
    val creator = item.text("creatorName").ifBlank { (item["creator"] as? JsonObject)?.text("nickname").orEmpty() }.trim()
    val playCount = (item["playCount"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }?.let(::formatPlayCount).orEmpty()
    val subtitle = listOf(creator, playCount).filter { it.isNotBlank() }.joinToString(" · ")
    return ProviderPlaylist(id, title, count, cover, subtitle)
}

private fun formatPlayCount(value: Long): String = when {
    value >= 100_000_000L -> String.format(java.util.Locale.ROOT, "%.1f 亿次播放", value / 100_000_000.0)
    value >= 10_000L -> String.format(java.util.Locale.ROOT, "%.1f 万次播放", value / 10_000.0)
    else -> "$value 次播放"
}

internal fun ProviderPlaylist.toStreamingCollection(owned: Boolean? = null): StreamingCollection =
    StreamingCollection(id = id, kind = "playlist", title = title, subtitle = subtitle, cover = cover, count = count, owned = owned)

/** `fetchRecommendPlaylists` / `fetchDiscoveryPlaylists`: array or `{ items / playlists }`. */
internal fun providerPlaylists(value: JsonElement?): List<ProviderPlaylist> {
    val array = value as? JsonArray
        ?: (value as? JsonObject)?.let { obj -> listOf("items", "playlists").firstNotNullOfOrNull { obj[it] as? JsonArray } }
        ?: JsonArray(emptyList())
    return array.mapNotNull { (it as? JsonObject)?.let(::parseProviderPlaylist) }.distinctBy { it.id }
}

/** `fetchUserLibrary` split into `(likedPlaylist, playlists)` for the Home page. */
internal fun providerUserLibrarySplit(value: JsonElement?): Pair<ProviderPlaylist?, List<ProviderPlaylist>> {
    val library = value as? JsonObject ?: return null to emptyList()
    val liked = (library["likedPlaylist"] as? JsonObject)?.let(::parseProviderPlaylist)
    val lists = (library["playlists"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.let(::parseProviderPlaylist) }
        .filter { it.id != liked?.id }
        .distinctBy { it.id }
    return liked to lists
}

/** `fetchUserLibrary`'s `{likedPlaylist, playlists}`, the liked list first. */
internal fun providerLibrary(value: JsonElement?): List<ProviderPlaylist> {
    val (liked, lists) = providerUserLibrarySplit(value)
    return listOfNotNull(liked) + lists
}

/** `checkLogin`'s account, named by its nickname, else its user id, as Desktop shows it. */
internal fun providerAccount(login: JsonElement?): NcmProfile? {
    val result = login as? JsonObject ?: return null
    if (!result.flag("loggedIn")) return null
    val profile = (result["profile"] as? JsonObject)?.let { runCatching { it.ncmProfile() }.getOrNull() } ?: NcmProfile("", "")
    return profile.copy(nickname = profile.nickname.ifBlank { profile.userId.ifBlank { "已登录" } })
}

private val FORM_FIELD_KEY = Regex("[A-Za-z0-9_.:-]{1,80}")
/** Desktop's range, color and button fields are left out; no provider uses them. */
private val FORM_FIELD_TYPES = setOf("text", "password", "url", "select", "toggle")
private val DISCLAIMER_FIELD_KEYS = setOf("disclaimer", "consent")

/** A `settings-form` under Desktop's rules: bounded text and known field types, or null. */
internal fun providerForm(value: JsonElement?): ProviderForm? {
    val form = value as? JsonObject ?: return null
    val submit = form.text("submitCommand").trim()
    if (form.text("kind") != "settings-form" || submit.length !in 1..160) return null
    val fields = (form["fields"] as? JsonArray).orEmpty().take(40).mapNotNull { (it as? JsonObject)?.let(::providerField) }
    return if (fields.isEmpty()) null else ProviderForm(submit, form.text("notice").take(1000), fields)
}

/** Extracts a required disclaimer confirmation gate from a settings panel's form, when one exists. */
internal fun providerDisclaimer(panel: ProviderPanel, form: ProviderForm): ProviderDisclaimer? {
    val field = form.fields.firstOrNull {
        it.required && (it.key.lowercase(java.util.Locale.ROOT) in DISCLAIMER_FIELD_KEYS || it.label.contains("免责声明"))
    } ?: return null
    val notice = form.notice.trim().ifBlank { "使用此音源前，请仔细阅读并确认免责声明。" }
    return when (field.type) {
        "select" -> {
            val accept = field.options.firstOrNull { it.second.isNotBlank() } ?: return null
            val accepted = field.value.isNotBlank() && field.options.any { it.second == field.value && it.second.isNotBlank() }
            ProviderDisclaimer(panel, form, field.key, notice, accept.first, accept.second, accepted)
        }
        "toggle" -> ProviderDisclaimer(panel, form, field.key, notice, field.label, "true", field.value == "true")
        else -> null
    }
}

internal fun isDisclaimerError(message: String?): Boolean =
    message != null && (message.contains("免责声明") || message.contains("插件设置"))

/** Replaces Desktop-specific `"打开“设置 → 插件设置 → …”"` instructions with mobile-accurate guidance. */
internal fun friendlyProviderError(message: String?, providerName: String): String? {
    if (message.isNullOrBlank()) return null
    if (message.contains("免责声明") && (message.contains("插件设置") || message.contains("设置 →"))) {
        return "首次使用${providerName}前，请在登录页阅读并确认免责声明。"
    }
    return message
}

private fun providerField(field: JsonObject): ProviderField? {
    val key = field.text("key").trim(); val label = field.text("label").trim(); val type = field.text("type")
    if (!FORM_FIELD_KEY.matches(key) || label.isEmpty() || label.length > 100 || type !in FORM_FIELD_TYPES) return null
    val options = (field["options"] as? JsonArray).orEmpty().take(30).mapNotNull { raw ->
        val option = raw as? JsonObject ?: return@mapNotNull null
        val text = option.text("label").trim()
        val choice = (option["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        (text to choice).takeIf { text.isNotEmpty() && text.length <= 100 && choice.length <= 200 }
    }
    if (type == "select" && options.isEmpty()) return null
    val current = field["value"] as? JsonPrimitive
    val value = when (type) {
        "password" -> "" // a stored secret is never shown again
        "toggle" -> (current?.booleanOrNull == true).toString()
        else -> current?.takeIf { it.isString }?.content?.take(4096).orEmpty()
    }
    return ProviderField(key, label, type, value, field.flag("required"), field.flag("readonly"),
        field.text("placeholder").take(200), field.text("group").trim().take(60), options)
}

/** The first required field left blank. */
internal fun ProviderForm.missing(values: Map<String, String>) = fields.firstOrNull { it.required && values[it.key].isNullOrBlank() }

/** What a command reported: its text, else its `message`. */
internal fun commandMessage(result: JsonElement?, fallback: String): String =
    (result as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(500)
        ?: ((result as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(500)
        ?: fallback
