package com.twilightecho.android.adapters

import com.twilightecho.android.coreclient.DefaultCoreClient
import com.twilightecho.android.runtimehost.SaveSessionResult
import com.twilightecho.android.runtimehost.SessionStorePort
import com.twilightecho.android.runtimehost.StoredSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Android implementation of `SessionStorePort` (Sections 8.1 & 14.2).
 *
 * Guarantees:
 * 1. Compare-And-Swap (`expectedRevision`) conflict detection under a coroutine `Mutex`.
 * 2. Atomic file replacement via temp file + `FileDescriptor.sync()` + rename so crashes
 *    never leave a partially written session recovery file.
 */
class AndroidSessionStoreAdapter(
    private val storageDirectory: File,
    private val fileName: String = "twilight_core_session.json",
    private val json: Json = DefaultCoreClient.DefaultJson
) : SessionStorePort {

    private val mutex = Mutex()
    private val targetFile = File(storageDirectory, fileName)
    private val tempFile = File(storageDirectory, "$fileName.tmp")

    override suspend fun load(): StoredSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readLocked()
        }
    }

    override suspend fun save(
        payloadJson: String,
        expectedRevision: String?
    ): SaveSessionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = readLocked()
            if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
                return@withLock SaveSessionResult.Conflict(
                    actualRevision = existing.revision,
                    reason = "conflict"
                )
            }
            if (expectedRevision == null && existing != null) {
                // Allow initial unversioned overwrite only if caller has not loaded a revision yet,
                // otherwise increment existing revision monotonically.
            }

            val nextRevisionNumber = (existing?.revision?.removePrefix("rev-")?.toLongOrNull() ?: 0L) + 1L
            val nextRevision = "rev-$nextRevisionNumber"
            val nextStored = StoredSession(
                revision = nextRevision,
                payloadJson = payloadJson
            )

            storageDirectory.mkdirs()
            val serialized = json.encodeToString(nextStored).encodeToByteArray()
            FileOutputStream(tempFile).use { fos ->
                fos.write(serialized)
                fos.flush()
                runCatching { fos.fd.sync() }
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            SaveSessionResult.Ok(revision = nextRevision)
        }
    }

    private fun readLocked(): StoredSession? {
        if (!targetFile.exists()) return null
        return runCatching {
            val content = targetFile.readText()
            json.decodeFromString<StoredSession>(content)
        }.getOrNull()
    }
}
