package com.twilightecho.android.plugins

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/** Settings key under which the NCM plugin keeps its account cookie (read fresh on every request). */
private const val ACCOUNT_COOKIE_KEY = "cookie"
/** ABI v2 invocations: the SDK's own ceiling. Host calls get two seconds more, and the worker kills at 60 s. */
private const val CALL_DEADLINE_MS = 50_000
private val HOST_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(52)
private const val MEDIA_TIMEOUT_MS = 20_000L
private fun ownerKey(providerId: String) = "owner:$providerId"
private fun userServerKey(pluginId: String) = "userServer:$pluginId"

/** Platform IO/runtime for shared Music Core's mobile plugin repository rules. */
class AndroidPluginRepository(context: Context) : Closeable {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "plugins-v1").apply { mkdirs() }
    private val stateFile = File(root, "installed.json")
    private val secrets = PluginSecrets(File(root, "settings"))
    private val preferences = app.getSharedPreferences("plugin-repository", Context.MODE_PRIVATE)
    private val http = PluginHttp()
    private val gateway = NcmGateway(http, preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "").also { preferences.edit().putString("deviceId", it).apply() })
    private val network = PluginNetwork()
    private val spoolDirectory = File(app.cacheDir, "provider-media")
    private val worker = PluginWorkerConnection(app)
    private val mutex = Mutex()
    private var initialized = false
    private var index = JsonObject(emptyMap())
    /** A running plugin: the worker it was loaded into, what it registered, and (ABI v2) its host API. */
    private class ActivePlugin(val generation: Int, val provider: MobileProvider, val runtime: AndroidPluginRuntime, val host: PluginHostApi?)
    /** Written under [mutex]; read without it to open media for a stream that is already playing. */
    private val active = ConcurrentHashMap<String, ActivePlugin>()
    private val mutableState = MutableStateFlow(PluginRepositoryState(indexUrl = preferences.getString("indexUrl", DEFAULT_PLUGIN_INDEX)!!))
    val state = mutableState.asStateFlow()
    private val mutableNotices = MutableSharedFlow<Pair<String, PluginNotice>>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** `twilight.ui.notify()` messages from v2 plugins, by plugin id. */
    val notices: SharedFlow<Pair<String, PluginNotice>> = mutableNotices.asSharedFlow()
    private val revalidating = AtomicBoolean(false)
    @Volatile private var lastRevalidation = 0L

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try { mutex.withLock { initializeLocked() } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // Nothing readable is installed; say so, rather than leave callers waiting for a list that will not come.
            mutableState.update { it.copy(message = error.message ?: "插件仓库初始化失败，请重试", ready = true) }; throw error
        }
    }
    private fun initializeLocked() {
        if (initialized) return
        // Spools of an earlier process; an open file outlives its name, so a live stream keeps reading.
        spoolDirectory.listFiles()?.forEach { it.delete() }
        val installed = readAtomic(stateFile)?.let { pluginJson.decodeFromString<List<InstalledPlugin>>(it.toString(Charsets.UTF_8)) } ?: emptyList()
        mutableState.update { it.copy(installed = installed, ready = true) }
        val cached = File(root, "catalog.json")
        val cachedIndex = readAtomic(cached)
        index = mergeIndex(cachedIndex?.let { pluginJson.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject })
        val fetchedAt = cachedIndex?.let { preferences.getLong("indexFetchedAt", cached.lastModified()) }
        mutableState.update { it.copy(sourceLabel = indexSourceLabel(it.indexUrl, fetchedAt, live = false)) }
        rebuildEntries(); initialized = true
    }
    private fun rules(operation: String, vararg fields: Pair<String, JsonElement>): JsonElement {
        val input = buildJsonObject { put("operation", operation); fields.forEach { (k, v) -> put(k, v) } }.toString()
        val response = QuickJs.create().use { q ->
            q.evaluate(app.assets.open("plugins/repository.js").bufferedReader().use { it.readText() }, "plugin-repository.js")
            q.evaluate("__TWILIGHT_PLUGIN_REPOSITORY__(${org.json.JSONObject.quote(input)})") as String
        }
        return unwrap(response)
    }
    private fun mergeIndex(remote: JsonObject?): JsonObject {
        val bundled = pluginJson.parseToJsonElement(app.assets.open("plugins/catalog.json").bufferedReader().use { it.readText() }).jsonObject
        if (remote != null) rules("catalog", "index" to remote)
        // A remote index cannot shadow the APK's bundled NCM identity or grant access to asset: URLs.
        val local = bundled.getValue("plugins").jsonArray
        val localIds = local.map { it.jsonObject.string("id") }.toSet()
        val entries = remote?.get("plugins")?.jsonArray?.filter { it.jsonObject.string("id") !in localIds } ?: emptyList()
        require(entries.none { it.jsonObject.string("sourceUrl").startsWith("asset:") }) { "远程仓库不能引用应用内插件包" }
        return buildJsonObject { put("schemaVersion", 1); put("plugins", JsonArray(local + entries)) }
    }
    private fun rebuildEntries() {
        val installed = mutableState.value.installed
        val known = index.getValue("plugins").jsonArray.toMutableList()
        val ids = known.map { it.jsonObject.string("id") }.toSet()
        for (record in installed.filter { it.id !in ids }) {
            known += manifest(record).let { JsonObject(it + mapOf("sourceUrl" to JsonPrimitive(record.origin), "checksumSha256" to JsonPrimitive(record.checksumSha256))) }
        }
        val catalog = rules("catalog", "index" to buildJsonObject { put("schemaVersion", 1); put("plugins", JsonArray(known)) }, "installed" to pluginJson.encodeToJsonElement(installed))
        // The rules pass a desktop-only plugin's androidRuntime through unchecked; it is never read, so it cannot break the list.
        val entries = catalog.jsonArray.map { entry ->
            entry.jsonObject.let { if ((it["unsupportedReason"] as? JsonPrimitive)?.contentOrNull != null) JsonObject(it - "androidRuntime") else it }
        }
        mutableState.update { it.copy(entries = pluginJson.decodeFromJsonElement(JsonArray(entries))) }
    }
    suspend fun refresh(indexUrl: String = state.value.indexUrl) = operation {
        val url = repositoryUri(indexUrl)
        applyIndex(url, fetchIndex(url))
    }

    /**
     * Desktop parity for the shared CDN index: the cached (or bundled) list shows at once, then the configured
     * index is revalidated in the background. The download never holds the provider lock, and a failure keeps
     * the current list; "刷新仓库" is the path that reports errors.
     */
    suspend fun revalidate() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastRevalidation < INDEX_REVALIDATE_INTERVAL_MS || !revalidating.compareAndSet(false, true)) return@withContext
        try {
            lastRevalidation = now
            val url = repositoryUri(state.value.indexUrl)
            val bytes = fetchIndex(url)
            // A manual refresh may have switched repositories meanwhile; never overwrite it with the old source.
            mutex.withLock { initializeLocked(); if (state.value.indexUrl == url.toString()) applyIndex(url, bytes) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Offline or rejected: the cached or bundled list stays in use. */ }
        finally { revalidating.set(false) }
    }
    private fun repositoryUri(value: String) = URI(value.trim()).also { url ->
        require(url.scheme == "https" && url.host != null && url.userInfo == null && url.fragment == null) { "仓库地址必须使用 HTTPS" }
    }
    private fun fetchIndex(url: URI): ByteArray {
        val response = http.request(url.toString(), listOf(url.host), maxBytes = 1024 * 1024, downloadRedirects = 5)
        check(response.status == 200) { "插件仓库返回 HTTP ${response.status}" }
        return response.bytes
    }
    private fun applyIndex(url: URI, bytes: ByteArray) {
        val merged = mergeIndex(pluginJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        writeAtomic(File(root, "catalog.json"), bytes)
        val fetchedAt = System.currentTimeMillis()
        preferences.edit().putString("indexUrl", url.toString()).putLong("indexFetchedAt", fetchedAt).apply()
        index = merged
        mutableState.update { it.copy(indexUrl = url.toString(), sourceLabel = indexSourceLabel(url.toString(), fetchedAt, live = true)) }
        rebuildEntries()
    }
    suspend fun install(id: String) = operation {
        val entry = mutableState.value.entries.single { it.id == id }
        check(entry.unsupportedReason == null) { entry.unsupportedReason ?: "插件不兼容" }
        val rawEntry = index.getValue("plugins").jsonArray.first { it.jsonObject.string("id") == id }.jsonObject
        val origin = if (entry.sourceUrl.startsWith("asset:")) "bundled" else mutableState.value.indexUrl
        val old = mutableState.value.installed.find { it.id == id }
        check(old == null || old.origin == origin) { "此插件来自其他仓库；请先卸载，再确认新的来源" }
        val bytes = if (entry.sourceUrl == "asset:ncm.tep") app.assets.open("plugins/ncm.tep").use { it.readBytes() } else {
            val url = URI(mutableState.value.indexUrl).resolve(entry.sourceUrl)
            val reply = http.request(url.toString(), listOf(url.host), maxBytes = 5 * 1024 * 1024, downloadRedirects = 5)
            check(reply.status == 200) { "下载插件返回 HTTP ${reply.status}" }; reply.bytes
        }
        check(digest("SHA-256", bytes).hex().equals(entry.checksumSha256, true)) { "插件包校验失败，已停止安装" }
        val staged = File(root, "stage-${UUID.randomUUID()}").apply { mkdirs() }
        val target = File(root, "$id/${entry.version}")
        var moved = false
        val previousSettings = secrets.read(id)
        try {
            extractPlugin(bytes, staged)
            val raw = pluginJson.parseToJsonElement(File(staged, "plugin.json").readText()).jsonObject
            rules("verifyIdentity", "manifest" to raw, "expected" to rawEntry)
            val runtime = pluginJson.decodeFromJsonElement<AndroidPluginRuntime>(raw.getValue("androidRuntime"))
            require(File(staged, runtime.entry).isFile) { "插件缺少 Android 入口" }
            check(!target.exists()) { "此版本已经安装" }
            target.parentFile!!.mkdirs(); check(staged.renameTo(target)); moved = true
            val record = InstalledPlugin(id, entry.version, enabled = true, origin = origin, checksumSha256 = entry.checksumSha256)
            closePlugin(id)
            ensureActive(record)
            saveInstalled(mutableState.value.installed.filterNot { it.id == id } + record)
            rebuildEntries()
        } catch (error: Throwable) {
            closePlugin(id); secrets.write(id, previousSettings)
            if (moved) target.deleteRecursively()
            if (old?.enabled == true) runCatching { ensureActive(old) }
            throw error
        } finally { staged.deleteRecursively() }
    }
    suspend fun setEnabled(id: String, enabled: Boolean) = operation {
        val record = mutableState.value.installed.single { it.id == id }
        try { if (enabled) ensureActive(record) else closePlugin(id) }
        catch (error: Throwable) {
            saveInstalled(mutableState.value.installed.map { if (it.id == id) it.copy(error = error.message) else it })
            throw error
        }
        saveInstalled(mutableState.value.installed.map { if (it.id == id) it.copy(enabled = enabled, error = null) else it })
        rebuildEntries()
    }
    suspend fun uninstall(id: String) = operation {
        check(mutableState.value.installed.any { it.id == id })
        closePlugin(id)
        saveInstalled(mutableState.value.installed.filterNot { it.id == id })
        File(root, id).deleteRecursively(); secrets.delete(id)
        preferences.edit().apply {
            remove(userServerKey(id))
            preferences.all.filter { (key, owner) -> key.startsWith("owner:") && owner == id }.keys.forEach { remove(it) }
        }.apply()
        rebuildEntries()
    }
    private fun saveInstalled(records: List<InstalledPlugin>) {
        writeAtomic(stateFile, pluginJson.encodeToString(records).utf8())
        mutableState.update { it.copy(installed = records) }
    }
    private fun manifest(record: InstalledPlugin) = pluginJson.parseToJsonElement(File(root, "${record.id}/${record.version}/plugin.json").readText()).jsonObject
    private suspend fun ensureActive(record: InstalledPlugin): ActivePlugin {
        worker.connect()
        if (active.values.any { it.generation != worker.generation }) resetActive()
        active[record.id]?.let { return it }
        val validated = rules("manifest", "manifest" to manifest(record)).jsonObject
        val runtime = pluginJson.decodeFromJsonElement<AndroidPluginRuntime>(validated.getValue("androidRuntime"))
        val script = File(root, "${record.id}/${record.version}/${runtime.entry}").readText()
        val host = if (runtime.apiVersion != 2) null else PluginHostApi(runtime, object : PluginSettingsStore {
            override fun read() = secrets.read(record.id)
            override fun write(value: String) = secrets.write(record.id, value)
        }, network) { level, message ->
            if (level == "warn" || level == "error") android.util.Log.w("TwilightPlugin", "${record.id}: $message")
        }.apply { if (runtime.userServer) userServerOrigin = preferences.getString(userServerKey(record.id), null) }
        try {
            val request = buildJsonObject {
                put("operation", "activate")
                if (host != null) { put("manifest", validated); put("deadlineMs", CALL_DEADLINE_MS) }
            }
            val raw = execute(record.id, script, runtime, host, request.toString()) ?: error("插件运行环境内存不足，请稍后重试")
            val described = unwrap(raw, record.id) as? JsonObject ?: error("插件没有注册音源")
            // Absent and null fields both take the model's defaults.
            val provider = pluginJson.decodeFromJsonElement<MobileProvider>(JsonObject(described.filterValues { it !is JsonNull })).copy(pluginId = record.id)
            check(provider.apiVersion == runtime.apiVersion) { "插件运行接口与清单声明不一致" }
            check(host == null || isPluginProviderId(provider.id)) { "音源 ID 无效" }
            check(active.values.none { it.provider.id == provider.id && it.provider.pluginId != record.id }) { "音源 ID 已被另一插件占用" }
            val plugin = ActivePlugin(worker.generation, provider, runtime, host)
            active[record.id] = plugin
            if (host != null) {
                ProviderMediaGrants.register(record.id, host)
                // Queued songs name their provider; this finds the plugin again after a restart.
                preferences.edit().putString(ownerKey(provider.id), record.id).apply()
            }
            publishProviders(); return plugin
        } catch (error: Throwable) {
            host?.close()
            if (error !is IllegalStateException || error.cause !is android.os.RemoteException)
                runCatching { worker.execute(record.id, null, "close") { _, _ -> "{}" } }
            throw error
        }
    }
    private fun closePlugin(id: String) {
        val plugin = active.remove(id)
        if (plugin != null) {
            ProviderMediaGrants.unregister(id)
            plugin.host?.let { host ->
                val request = buildJsonObject { put("operation", "deactivate"); put("deadlineMs", 5_000) }.toString()
                runCatching { execute(id, null, plugin.runtime, host, request) }
            }
            runCatching { worker.execute(id, null, "close") { _, _ -> "{}" } }
            plugin.host?.close()
        }
        publishProviders()
    }
    /** The worker is gone or was replaced: every engine went with it. */
    private fun resetActive() {
        for (id in active.keys.toList()) { active.remove(id)?.host?.close(); ProviderMediaGrants.unregister(id) }
        publishProviders()
    }
    private fun publishProviders() { mutableState.update { it.copy(providers = active.values.map { p -> p.provider }.sortedBy { p -> p.pluginId }) } }

    /** Null when the worker unloaded the engine; a dead worker took every engine with it. */
    private fun execute(id: String, script: String?, runtime: AndroidPluginRuntime, host: PluginHostApi?, input: String): String? {
        val deadline = System.nanoTime() + HOST_DEADLINE_NANOS
        return try {
            worker.execute(id, script, input) { method, args -> host?.call(method, args, deadline) ?: callback(id, runtime, method, args) }
        } catch (error: android.os.RemoteException) {
            resetActive(); throw IllegalStateException("插件进程已退出；主播放器保持运行，请重试", error)
        }
    }
    /** Runs [request] in [record]'s engine; one the worker unloaded meanwhile is activated again, once. */
    private suspend fun invoke(record: InstalledPlugin, request: (ActivePlugin) -> JsonObject): JsonElement {
        repeat(2) {
            val plugin = ensureActive(record)
            execute(record.id, null, plugin.runtime, plugin.host, request(plugin).toString())?.let { return unwrap(it, record.id) }
            active.remove(record.id)?.host?.close(); ProviderMediaGrants.unregister(record.id); publishProviders()
        }
        error("插件运行环境内存不足，请稍后重试")
    }
    private fun enabledRecord(pluginId: String) =
        mutableState.value.installed.singleOrNull { it.id == pluginId && it.enabled } ?: error("请先安装并启用音源插件")

    suspend fun call(pluginId: String, method: String, args: JsonArray = JsonArray(emptyList())): JsonElement = withContext(Dispatchers.IO) {
        mutex.withLock { callLocked(pluginId, method, args) }
    }

    /** What [pluginId] registered: its methods, `ui` declaration and settings panels. Activates the plugin. */
    suspend fun provider(pluginId: String): MobileProvider = withContext(Dispatchers.IO) {
        mutex.withLock { initializeLocked(); ensureActive(enabledRecord(pluginId)).provider }
    }

    /** Runs a v2 plugin's UI command: its settings panels and the forms they return. */
    suspend fun command(pluginId: String, command: String, args: JsonArray = JsonArray(emptyList())): JsonElement = withContext(Dispatchers.IO) {
        mutex.withLock {
            initializeLocked()
            invoke(enabledRecord(pluginId)) { plugin ->
                check(plugin.host != null) { "插件不支持此操作" }
                buildJsonObject { put("operation", "command"); put("command", command); put("args", args); put("deadlineMs", CALL_DEADLINE_MS) }
            }
        }
    }

    /**
     * Desktop's server sign-in (Jellyfin, Navidrome). The typed server is granted to this plugin alone, LAN and plain
     * HTTP included, and is kept only when the sign-in succeeds.
     */
    suspend fun loginWithServer(pluginId: String, address: String, username: String, password: String): JsonElement = withContext(Dispatchers.IO) {
        mutex.withLock {
            initializeLocked()
            val record = enabledRecord(pluginId)
            val plugin = ensureActive(record)
            check(plugin.runtime.userServer && plugin.host != null && "loginWithServer" in plugin.provider.methods) { "此音源不支持服务器登录" }
            val url = serverAddress(address)
            val key = userServerKey(record.id)
            val previous = preferences.getString(key, null)
            // Saved first, so an engine activated again during the call is granted the same server.
            fun grant(origin: String?) {
                preferences.edit().apply { if (origin == null) remove(key) else putString(key, origin) }.commit()
                active[record.id]?.host?.userServerOrigin = origin
            }
            grant(serverOriginOf(url))
            try {
                callLocked(pluginId, "loginWithServer", buildJsonArray { add(url); add(username); add(password) }).also { result ->
                    check(((result as? JsonObject)?.get("loggedIn") as? JsonPrimitive)?.booleanOrNull == true) { "登录未成功，请检查服务器地址和账号" }
                }
            } catch (error: Throwable) { grant(previous); throw error }
        }
    }

    /** A queued v2 track's audio for the player. The stream URL is resolved on the first read, never here. */
    fun openStream(source: String): MediaDataSource {
        val ref = parseProviderSource(source) ?: throw IllegalArgumentException("音源引用无效")
        val resolved = AtomicReference<ActivePlugin?>()
        return ProviderMediaDataSource(ProviderMediaReader(
            resolve = { force -> runBlocking { resolvePlayback(ref, force) }.let { (plugin, playback) -> resolved.set(plugin); playback } },
            open = { playback, range, onCall ->
                // The grants of the plugin that resolved the URL; a worker restart does not revoke them, disabling does.
                val plugin = resolved.get()?.takeIf { plugin -> state.value.installed.any { it.id == plugin.provider.pluginId && it.enabled } }
                    ?: throw IOException("音源插件已停用")
                plugin.host!!.openMedia(playback.url, playback.headers, range, MEDIA_TIMEOUT_MS, onCall)
            },
            spoolDirectory = spoolDirectory))
    }
    private suspend fun resolvePlayback(ref: ProviderTrackRef, force: Boolean): Pair<ActivePlugin, ProviderPlayback> = withContext(Dispatchers.IO) {
        mutex.withLock {
            initializeLocked()
            val record = ownerOf(ref.providerId)
            // Desktop's sourceResolver: `[track, options]`, with `force` when a signed URL expired.
            val value = callLocked(record.id, "getPlaybackUrl", buildJsonArray { add(ref.track); add(buildJsonObject { if (force) put("force", true) }) })
            val plugin = active[record.id] ?: error("音源插件已停用")
            plugin to (providerPlayback(value) ?: error("此歌曲暂时无法播放"))
        }
    }

    /** A queued v2 track's timed lyrics as LRC; null when its provider has none. */
    suspend fun providerLyrics(source: String): String? {
        val ref = parseProviderSource(source) ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                initializeLocked()
                val record = ownerOf(ref.providerId)
                if ("getLyrics" !in active.getValue(record.id).provider.methods) null
                else providerLrc(callLocked(record.id, "getLyrics", buildJsonArray { add(ref.track) }))
            }
        }
    }

    /** The enabled v2 plugin registering [providerId]: the one active now or remembered, else the first that does. */
    private suspend fun ownerOf(providerId: String): InstalledPlugin {
        val enabled = mutableState.value.installed.filter { it.enabled }
        val known = active.values.firstOrNull { it.provider.id == providerId }?.provider?.pluginId ?: preferences.getString(ownerKey(providerId), null)
        val owner = enabled.firstOrNull { it.id == known }?.takeIf { ensureActive(it).provider.id == providerId }
            ?: enabled.filter { it.id != known }.firstOrNull { record ->
                try { ensureActive(record).provider.id == providerId }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { false }
            } ?: error("请先安装并启用这首歌的音源插件")
        check(active.getValue(owner.id).host != null) { "此音源插件不支持在 Android 播放" }
        return owner
    }

    /**
     * Signs the phone's provider in with an account cookie handed over by the user's own paired desktop.
     * A phone that is already signed in keeps its account; returns whether the cookie was adopted.
     * The cookie is stored only in the encrypted plugin settings and is never logged.
     */
    suspend fun adoptAccountCookie(pluginId: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        require(cookie.length in 1..16_000 && "MUSIC_U=" in cookie && cookie.none { it.isISOControl() }) { "账号凭据无效" }
        mutex.withLock {
            val login = callLocked(pluginId, "checkLogin", JsonArray(emptyList())) as? JsonObject
            if (login?.get("loggedIn")?.jsonPrimitive?.booleanOrNull == true) return@withLock false
            val settings = pluginJson.parseToJsonElement(secrets.read(pluginId)).jsonObject.toMutableMap()
            settings[ACCOUNT_COOKIE_KEY] = JsonPrimitive(cookie)
            secrets.write(pluginId, JsonObject(settings).toString())
            true
        }
    }

    private suspend fun callLocked(pluginId: String, method: String, args: JsonArray): JsonElement {
        initializeLocked()
        return invoke(enabledRecord(pluginId)) { plugin ->
            check(method in plugin.provider.methods) { "插件不支持此方法" }
            buildJsonObject {
                put("operation", "call"); put("method", method); put("args", args)
                if (plugin.host != null) put("deadlineMs", CALL_DEADLINE_MS)
            }
        }
    }
    private suspend fun operation(action: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(busy = true, message = null) }
            try { initializeLocked(); action() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { mutableState.update { it.copy(message = error.message ?: "插件操作失败") }; throw error }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    /** ABI v1 (the bundled NCM plugin) host calls. */
    private fun callback(id: String, runtime: AndroidPluginRuntime, method: String, argsJson: String): String = try {
        require(argsJson.length <= 500_000)
        val args = pluginJson.parseToJsonElement(argsJson).jsonObject
        val value: JsonElement = when (method) {
            "settings.get", "settings.set", "settings.delete" -> {
                check("settings" in runtime.permissions)
                val settings = pluginJson.parseToJsonElement(secrets.read(id)).jsonObject.toMutableMap(); val key = args.string("key")
                when (method) {
                    "settings.get" -> if (key.isEmpty()) JsonObject(settings) else settings[key] ?: JsonNull
                    else -> { require(key.isNotBlank() && key.length <= 128)
                        if (method == "settings.set") settings[key] = args["value"] ?: JsonNull else settings.remove(key)
                        val text = JsonObject(settings).toString(); require(text.length <= 256_000); secrets.write(id, text); JsonNull }
                }
            }
            "ncm" -> { check(id == "com.twilightecho.provider.ncm" && "network" in runtime.permissions)
                check(runtime.networkHosts.containsAll(listOf("music.163.com", "interface.music.163.com", "interface3.music.163.com")))
                gateway.request(args) }
            "network" -> {
                check("network" in runtime.permissions)
                val reply = http.request(args.string("url"), runtime.networkHosts, args["body"]?.jsonPrimitive?.contentOrNull)
                buildJsonObject { put("status", reply.status); put("text", reply.bytes.toString(Charsets.UTF_8)) }
            }
            "md5" -> JsonPrimitive(digest("MD5", args.string("value").utf8()).hex())
            "sleep" -> { Thread.sleep(args["ms"]?.jsonPrimitive?.longOrNull?.coerceIn(0, 2000) ?: 0); JsonNull }
            "url" -> {
                val uri = URI(args.string("value"))
                buildJsonObject { put("protocol", (uri.scheme ?: "") + ":"); put("host", uri.rawAuthority ?: ""); put("hostname", uri.host ?: "")
                    put("username", uri.userInfo?.substringBefore(':') ?: ""); put("password", uri.userInfo?.substringAfter(':', "") ?: "")
                    put("port", if (uri.port == -1) "" else uri.port.toString()); put("pathname", uri.rawPath ?: "/")
                    put("search", uri.rawQuery?.let { "?$it" } ?: ""); put("hash", uri.rawFragment?.let { "#$it" } ?: "") }
            }
            "qrImage" -> {
                val size = args.getValue("size").jsonPrimitive.int; require(size in 21..177)
                val data = args.getValue("data").jsonArray; require(data.size == size * size)
                val pixels = IntArray((size + 8) * (size + 8)) { -1 }
                for (y in 0 until size) for (x in 0 until size) if (data[y * size + x].jsonPrimitive.int != 0) pixels[(y + 4) * (size + 8) + x + 4] = android.graphics.Color.BLACK
                val bitmap = Bitmap.createBitmap(pixels, size + 8, size + 8, Bitmap.Config.ARGB_8888)
                val output = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
                JsonPrimitive("data:image/png;base64," + b64(output.toByteArray()))
            }
            else -> error("插件未获准调用宿主能力：$method")
        }
        buildJsonObject { put("ok", true); put("value", value) }.toString().also {
            require(it.length <= 500_000) { "插件宿主返回数据过大，请减小分页大小" }
        }
    } catch (error: Throwable) { buildJsonObject { put("ok", false); put("error", error.message ?: "插件宿主调用失败") }.toString() }
    /** A v2 envelope's notices are published whether the call succeeded or not. */
    private fun unwrap(raw: String, pluginId: String? = null): JsonElement {
        val result = pluginJson.parseToJsonElement(raw).jsonObject
        if (pluginId != null) (result["notices"] as? JsonArray)?.take(8)?.forEach { item ->
            runCatching { pluginJson.decodeFromJsonElement<PluginNotice>(item) }.getOrNull()?.let { mutableNotices.tryEmit(pluginId to it) }
        }
        check(result["ok"]?.jsonPrimitive?.booleanOrNull == true) { result.string("error", "插件运行失败") }
        return result["value"] ?: JsonNull
    }
    override fun close() { resetActive(); worker.close() }
}

/** Safe .tep/ZIP extraction is independent of the runtime and accepts no paths outside staging. */
internal fun extractPlugin(bytes: ByteArray, destination: File) {
    require(bytes.size <= 5 * 1024 * 1024) { "插件包过大" }
    val names = hashSetOf<String>(); var total = 0
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            require(name.isNotBlank() && name.length <= 240 && !name.contains('\\') && !name.contains(':') && !name.startsWith('/') &&
                name.trimEnd('/').split('/').none { it == ".." || it == "." || it.isEmpty() } && names.add(name) && names.size <= 128) { "插件包包含不安全的路径" }
            val file = File(destination, name)
            require(file.canonicalPath.startsWith(destination.canonicalPath + File.separator))
            if (entry.isDirectory) file.mkdirs() else {
                file.parentFile!!.mkdirs(); file.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= 20 * 1024 * 1024) { "插件解压内容过大" }; output.write(buffer, 0, n) }
                }
            }
        }
    }
    require(File(destination, "plugin.json").isFile) { "插件包缺少 plugin.json" }
}
