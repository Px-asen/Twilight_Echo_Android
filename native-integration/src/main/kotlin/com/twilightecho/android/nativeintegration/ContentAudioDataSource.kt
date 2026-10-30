package com.twilightecho.android.nativeintegration

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** One independent SAF descriptor per decoder, including lookahead/seek reopen. */
internal class ContentAudioDataSource(context: Context, source: String) : MediaDataSource() {
    private val asset = context.contentResolver.openAssetFileDescriptor(Uri.parse(source), "r")
        ?: throw IOException("无法打开音频文件")
    private val closed = AtomicBoolean(false)
    private val length = if (asset.declaredLength >= 0) asset.declaredLength
        else (Os.fstat(asset.fileDescriptor).st_size - asset.startOffset).coerceAtLeast(0)

    override fun getSize(): Long { checkOpen(); return length }
    // Transfer a duplicate, not a /proc path: SAF may deny reopening that path.
    // Cancellation closes the Java reader, but the native duplicate stays valid
    // until native closeInput; duplication and close are serialized here.
    fun duplicateLocalFileDescriptor(): Int = synchronized(asset) {
        checkOpen()
        val stat = Os.fstat(asset.fileDescriptor)
        if (asset.startOffset == 0L && length == stat.st_size && OsConstants.S_ISREG(stat.st_mode))
            ParcelFileDescriptor.dup(asset.fileDescriptor).detachFd() else -1
    }
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        checkOpen()
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= length) return -1
        val count = minOf(size.toLong(), length - position).toInt()
        val read = Os.pread(asset.fileDescriptor, buffer, offset, count, asset.startOffset + position)
        checkOpen()
        return if (read == 0) -1 else read
    }
    override fun close() { synchronized(asset) { if (closed.compareAndSet(false, true)) asset.close() } }
    private fun checkOpen() { if (closed.get()) throw IOException("音频读取已取消") }
}
