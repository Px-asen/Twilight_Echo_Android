package com.twilightecho.android.adapters

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.twilightecho.android.coreclient.CoreErrorCode
import com.twilightecho.android.runtimehost.NativeFdResource
import com.twilightecho.android.runtimehost.ResourceAccessPort
import com.twilightecho.android.runtimehost.ResourceAccessResult
import com.twilightecho.android.runtimehost.ResourceId
import com.twilightecho.android.runtimehost.ResourceStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of `ResourceAccessPort` (Sections 8.2, 8.3, 8.4, 14.3).
 *
 * Handles `content://` (MediaStore / Storage Access Framework DocumentFile) and `file://` URIs.
 * Explicitly verifies `ContentResolver.persistedUriPermissions` and catches `SecurityException`
 * so revoked or expired URI grants return `CoreErrorCode.PERMISSION_EXPIRED` instead of
 * `CoreErrorCode.NOT_FOUND` (preventing accidental track deletion from the library).
 */
class AndroidResourceAccessAdapter(
    private val context: Context
) : ResourceAccessPort, Closeable {

    private val contentResolver: ContentResolver
        get() = context.contentResolver

    private val activeFdLeases = ConcurrentHashMap<ResourceId, ParcelFileDescriptor>()

    override suspend fun stat(resourceId: ResourceId): ResourceAccessResult<ResourceStat> =
        withContext(Dispatchers.IO) {
            if (resourceId.isBlank()) {
                return@withContext ResourceAccessResult.Error(
                    code = CoreErrorCode.INVALID_REQUEST,
                    message = "Empty resourceId"
                )
            }

            val uri = parseResourceUri(resourceId)
            when (uri.scheme?.lowercase()) {
                "content" -> statContentUri(resourceId, uri)
                "file", null, "" -> statLocalFile(resourceId, uri.path ?: resourceId)
                else -> ResourceAccessResult.Error(
                    code = CoreErrorCode.UNSUPPORTED_CAPABILITY,
                    message = "Unsupported URI scheme: ${uri.scheme}"
                )
            }
        }

    override suspend fun openReadStream(
        resourceId: ResourceId,
        offsetBytes: Long
    ): ResourceAccessResult<InputStream> = withContext(Dispatchers.IO) {
        val uri = parseResourceUri(resourceId)
        try {
            val stream: InputStream = when (uri.scheme?.lowercase()) {
                "content" -> {
                    if (!hasReadUriPermission(uri)) {
                        return@withContext ResourceAccessResult.Error(
                            code = CoreErrorCode.PERMISSION_EXPIRED,
                            message = "Read permission expired or revoked for URI: $uri",
                            retryable = false
                        )
                    }
                    contentResolver.openInputStream(uri)
                        ?: return@withContext ResourceAccessResult.Error(
                            code = CoreErrorCode.NOT_FOUND,
                            message = "ContentResolver returned null stream for $uri"
                        )
                }
                else -> {
                    val file = File(uri.path ?: resourceId)
                    if (!file.exists()) {
                        return@withContext ResourceAccessResult.Error(
                            code = CoreErrorCode.NOT_FOUND,
                            message = "File does not exist: ${file.path}"
                        )
                    }
                    if (!file.canRead()) {
                        return@withContext ResourceAccessResult.Error(
                            code = CoreErrorCode.PERMISSION_EXPIRED,
                            message = "Read permission denied for file: ${file.path}"
                        )
                    }
                    FileInputStream(file)
                }
            }

            if (offsetBytes > 0L) {
                var remaining = offsetBytes
                while (remaining > 0L) {
                    val skipped = stream.skip(remaining)
                    if (skipped <= 0L) break
                    remaining -= skipped
                }
            }
            ResourceAccessResult.Ok(stream)
        } catch (sec: SecurityException) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.PERMISSION_EXPIRED,
                message = "SecurityException opening resource $resourceId: ${sec.message}",
                retryable = false
            )
        } catch (fnf: FileNotFoundException) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.NOT_FOUND,
                message = "Resource not found ($resourceId): ${fnf.message}",
                retryable = false
            )
        } catch (t: Throwable) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.FAILED,
                message = "Failed to open stream for $resourceId: ${t.message}",
                retryable = true
            )
        }
    }

    override suspend fun resolveNativeUri(resourceId: ResourceId): ResourceAccessResult<String> =
        withContext(Dispatchers.IO) {
            if (resourceId.isBlank()) {
                return@withContext ResourceAccessResult.Error(
                    code = CoreErrorCode.INVALID_REQUEST,
                    message = "Cannot resolve blank resourceId"
                )
            }
            val uri = parseResourceUri(resourceId)
            if (uri.scheme.equals("content", ignoreCase = true)) {
                if (!hasReadUriPermission(uri)) {
                    return@withContext ResourceAccessResult.Error(
                        code = CoreErrorCode.PERMISSION_EXPIRED,
                        message = "URI read grant expired for $uri"
                    )
                }
                return@withContext ResourceAccessResult.Ok(uri.toString())
            }
            ResourceAccessResult.Ok(resourceId)
        }

    override suspend fun acquirePlayableResource(
        resourceId: ResourceId
    ): ResourceAccessResult<NativeFdResource> = withContext(Dispatchers.IO) {
        if (resourceId.isBlank()) {
            return@withContext ResourceAccessResult.Error(
                code = CoreErrorCode.INVALID_REQUEST,
                message = "Cannot acquire playable resource for blank resourceId"
            )
        }
        val uri = parseResourceUri(resourceId)
        if (!uri.scheme.equals("content", ignoreCase = true)) {
            val filePath = uri.path ?: resourceId
            val file = File(filePath)
            if (file.exists() && !file.canRead()) {
                return@withContext ResourceAccessResult.Error(
                    code = CoreErrorCode.PERMISSION_EXPIRED,
                    message = "Local file read permission denied: $filePath"
                )
            }
            return@withContext ResourceAccessResult.Ok(
                NativeFdResource(
                    resourceId = resourceId,
                    nativePath = filePath,
                    detachedFd = null,
                    mimeType = guessAudioMimeType(file.extension)
                )
            )
        }

        if (!hasReadUriPermission(uri)) {
            return@withContext ResourceAccessResult.Error(
                code = CoreErrorCode.PERMISSION_EXPIRED,
                message = "URI read grant expired for $uri"
            )
        }

        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: return@withContext ResourceAccessResult.Error(
                    code = CoreErrorCode.NOT_FOUND,
                    message = "ContentResolver returned null ParcelFileDescriptor for $uri"
                )
            activeFdLeases.put(resourceId, pfd)?.let { old ->
                runCatching { old.close() }
            }
            val fd = pfd.fd
            ResourceAccessResult.Ok(
                NativeFdResource(
                    resourceId = resourceId,
                    nativePath = "/proc/self/fd/$fd",
                    detachedFd = fd,
                    mimeType = contentResolver.getType(uri)
                )
            )
        } catch (sec: SecurityException) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.PERMISSION_EXPIRED,
                message = "SecurityException opening descriptor for $uri: ${sec.message}",
                retryable = false
            )
        } catch (fnf: FileNotFoundException) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.NOT_FOUND,
                message = "Content URI descriptor not found for $uri: ${fnf.message}",
                retryable = false
            )
        } catch (t: Throwable) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.FAILED,
                message = "Failed to acquire playable descriptor for $uri: ${t.message}",
                retryable = true
            )
        }
    }

    override suspend fun releasePlayableResource(resourceId: ResourceId) {
        withContext(Dispatchers.IO) {
            activeFdLeases.remove(resourceId)?.let { pfd ->
                runCatching { pfd.close() }
            }
        }
    }

    override fun close() {
        val keys = activeFdLeases.keys.toList()
        for (key in keys) {
            activeFdLeases.remove(key)?.let { pfd ->
                runCatching { pfd.close() }
            }
        }
    }

    private fun statContentUri(resourceId: ResourceId, uri: Uri): ResourceAccessResult<ResourceStat> {
        if (!hasReadUriPermission(uri)) {
            return ResourceAccessResult.Error(
                code = CoreErrorCode.PERMISSION_EXPIRED,
                message = "Persisted URI permission missing or expired for $uri"
            )
        }

        return try {
            val docFile = DocumentFile.fromSingleUri(context, uri)
            val mimeType = contentResolver.getType(uri) ?: docFile?.type
            var displayName: String? = docFile?.name
            var sizeBytes: Long = docFile?.length() ?: -1L

            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }

            if (docFile != null && !docFile.exists() && sizeBytes < 0L) {
                return ResourceAccessResult.Error(
                    code = CoreErrorCode.NOT_FOUND,
                    message = "Document does not exist at $uri"
                )
            }

            ResourceAccessResult.Ok(
                ResourceStat(
                    resourceId = resourceId,
                    uri = uri.toString(),
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    canSeek = true,
                    lastModifiedUnixMs = docFile?.lastModified()?.takeIf { it > 0L }
                )
            )
        } catch (sec: SecurityException) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.PERMISSION_EXPIRED,
                message = "SecurityException querying $uri: ${sec.message}"
            )
        } catch (fnf: FileNotFoundException) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.NOT_FOUND,
                message = "Content URI not found: $uri"
            )
        } catch (t: Throwable) {
            ResourceAccessResult.Error(
                code = CoreErrorCode.FAILED,
                message = "Failed to stat $uri: ${t.message}",
                retryable = true
            )
        }
    }

    private fun statLocalFile(resourceId: ResourceId, path: String): ResourceAccessResult<ResourceStat> {
        val file = File(path)
        if (!file.exists()) {
            return ResourceAccessResult.Error(
                code = CoreErrorCode.NOT_FOUND,
                message = "Local file not found: $path"
            )
        }
        if (!file.canRead()) {
            return ResourceAccessResult.Error(
                code = CoreErrorCode.PERMISSION_EXPIRED,
                message = "Local file read permission denied: $path"
            )
        }
        return ResourceAccessResult.Ok(
            ResourceStat(
                resourceId = resourceId,
                uri = file.toURI().toString(),
                displayName = file.name,
                mimeType = guessAudioMimeType(file.extension),
                sizeBytes = file.length(),
                canSeek = true,
                lastModifiedUnixMs = file.lastModified()
            )
        )
    }

    /**
     * Checks whether the app holds active or persisted read permission for a `content://` URI.
     * MediaStore public audio URIs (`content://media/...`) are treated as accessible unless
     * a `SecurityException` is thrown during query/open; SAF document URIs verify both
     * `checkUriPermission` and `ContentResolver.persistedUriPermissions`.
     */
    private fun hasReadUriPermission(uri: Uri): Boolean {
        val authority = uri.authority.orEmpty()
        if (authority == "media") {
            return true
        }
        val pidGranted = context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        if (pidGranted) return true

        val persistedGrants = runCatching { contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        return persistedGrants.any { perm ->
            perm.isReadPermission && uri.toString().startsWith(perm.uri.toString())
        }
    }

    private fun parseResourceUri(resourceId: ResourceId): Uri = runCatching {
        Uri.parse(resourceId)
    }.getOrElse {
        Uri.fromFile(File(resourceId))
    }

    private fun guessAudioMimeType(extension: String): String = when (extension.lowercase()) {
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "dsf", "dff" -> "audio/dsd"
        else -> "application/octet-stream"
    }
}
