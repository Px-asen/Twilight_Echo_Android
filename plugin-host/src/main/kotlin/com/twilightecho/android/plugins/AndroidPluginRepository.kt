package com.twilightecho.android.plugins

import android.content.Context
import android.graphics.Bitmap
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Settings key under which the NCM plugin keeps its account cookie (read fresh on every request). */
private const val ACCOUNT_COOKIE_KEY = "cookie"

/** Platform IO/runtime for shared Music Core's mobile plugin repository rules. */
class AndroidPluginRepository(context: Context) : Closeable {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "plugins-v1").apply { mkdirs() }
    private val stateFile = File(root, "installed.json")
    private val secrets = PluginSecrets(File(root, "settings"))
    private val preferences = app.getSharedPreferences("plugin-repository", Context.MODE_PRIVATE)
    private val http = PluginHttp()
    private val gateway = NcmGateway(http, preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "").also { preferences.edit().putString("deviceId", it).apply() })
    private val worker = PluginWorkerConnection(app)
    private val mutex = Mutex()
    private var initialized = false
    private var index = JsonObject(emptyMap())
    private val active = mutableMapOf<String, Pair<Int, MobileProvider>>()
    private val mutableState = MutableStateFlow(PluginRepositoryState(indexUrl = preferences.getString("indexUrl", DEFAULT_PLUGIN_INDEX)!!))
    val state = mutableState.asStateFlow()
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
        mutableState.update { it.copy(entries = pluginJson.decodeFromJsonElement(catalog)) }
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
        File(root, id).deleteRecursively(); secrets.delete(id); rebuildEntries()
    }
    private fun saveInstalled(records: List<InstalledPlugin>) {
        writeAtomic(stateFile, pluginJson.encodeToString(records).utf8())
        mutableState.update { it.copy(installed = records) }
    }
    private fun manifest(record: InstalledPlugin) = pluginJson.parseToJsonElement(File(root, "${record.id}/${record.version}/plugin.json").readText()).jsonObject
    private suspend fun ensureActive(record: InstalledPlugin): MobileProvider {
        worker.connect()
        if (active.values.any { it.first != worker.generation }) { active.clear(); publishProviders() }
        active[record.id]?.takeIf { it.first == worker.generation }?.let { return it.second }
        val raw = manifest(record)
        val validated = rules("manifest", "manifest" to raw).jsonObject
        val runtime = pluginJson.decodeFromJsonElement<AndroidPluginRuntime>(validated.getValue("androidRuntime"))
        val script = File(root, "${record.id}/${record.version}/${runtime.entry}").readText()
        val value = unwrap(worker.execute(record.id, script, "{\"operation\":\"activate\"}") { method, args -> callback(record.id, runtime, method, args) })
        val provider = pluginJson.decodeFromJsonElement<MobileProvider>(value).copy(pluginId = record.id)
        check(active.values.none { it.second.id == provider.id && it.second.pluginId != record.id }) { "音源 ID 已被另一插件占用" }
        active[record.id] = worker.generation to provider; publishProviders(); return provider
    }
    private fun closePlugin(id: String) {
        if (active.remove(id) != null) runCatching { worker.execute(id, null, "close") { _, _ -> "{}" } }
        publishProviders()
    }
    private fun publishProviders() { mutableState.update { it.copy(providers = active.values.map { p -> p.second }) } }
    suspend fun call(pluginId: String, method: String, args: JsonArray = JsonArray(emptyList())): JsonElement = withContext(Dispatchers.IO) {
        mutex.withLock { callLocked(pluginId, method, args) }
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
        val record = mutableState.value.installed.singleOrNull { it.id == pluginId && it.enabled } ?: error("请先安装并启用音源插件")
        val provider = ensureActive(record)
        check(method in provider.methods) { "插件不支持此方法" }
        val runtime = pluginJson.decodeFromJsonElement<AndroidPluginRuntime>(manifest(record).getValue("androidRuntime"))
        val request = buildJsonObject { put("operation", "call"); put("method", method); put("args", args) }.toString()
        try { return unwrap(worker.execute(record.id, null, request) { m, a -> callback(record.id, runtime, m, a) }) }
        catch (error: Throwable) {
            if (error is android.os.RemoteException) { active.clear(); publishProviders(); throw IllegalStateException("插件进程已退出；主播放器保持运行，请重试", error) }
            throw error
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
    private fun unwrap(raw: String): JsonElement {
        val result = pluginJson.parseToJsonElement(raw).jsonObject
        check(result["ok"]?.jsonPrimitive?.booleanOrNull == true) { result.string("error", "插件运行失败") }
        return result["value"] ?: JsonNull
    }
    override fun close() { worker.close() }
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
