package com.twilightecho.android.nativeintegration

import android.media.MediaDataSource
import com.twilightecho.android.coreclient.DesktopMediaRangeReader

/** Shared FFmpeg pulls authenticated ranges on its decode worker, not the audio callback. */
class DesktopStreamDataSource(private val reader: DesktopMediaRangeReader) : MediaDataSource() {
    override fun getSize(): Long = reader.size()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)
    override fun close() = reader.close()
}
