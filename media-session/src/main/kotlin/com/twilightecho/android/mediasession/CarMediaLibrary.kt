package com.twilightecho.android.mediasession

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext
import java.util.UUID

/** Browser IDs name server-owned selections; a controller never supplies an audio URI or Track. */
@UnstableApi
internal class CarMediaLibrary(private val host: AndroidPlaybackHost, private val scope: CoroutineScope) : MediaLibrarySession.Callback {
    private data class Song(val track: CoreTrackView, val serverId: String? = null, val libraryId: String? = null)
    private data class Page(val total: Int, val items: List<Song>)
    private val songs = LinkedHashMap<String, Song>()
    private val folders = LinkedHashMap<String, String>()
    private fun folder(id: String, title: String, subtitle: String? = null) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    private val root = folder("car:root", "Twilight Echo", "在此手机播放")
    private val params = LibraryParams.Builder().setExtras(Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }).build()
    private fun item(song: Song): MediaItem {
        val id = songs.entries.firstOrNull { it.value == song }?.key ?: "car:song:${UUID.randomUUID()}".also {
            songs[it] = song
            while (songs.size > 2000) songs.remove(songs.keys.first())
        }
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(song.track.title).setArtist(song.track.artist).setAlbumTitle(song.track.album)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
    }
    private fun <T : Any> result(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = scope.future<LibraryResult<T>> {
        try { host.awaitStarted(); return@future block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { return@future LibraryResult.ofError<T>(SessionError(SessionError.ERROR_IO, error.message ?: "曲库暂时不可用，请重试")) }
    }
    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
        Futures.immediateFuture(LibraryResult.ofItem(root, this.params))

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(when {
            mediaId == root.mediaId -> LibraryResult.ofItem(root, params)
            songs[mediaId] != null -> LibraryResult.ofItem(item(songs.getValue(mediaId)), params)
            else -> LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        })

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
        page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = result {
        require(page >= 0 && pageSize > 0) { "无效分页" }
        if (parentId == root.mediaId) {
            val rows = mutableListOf(folder("car:phone:0", "此手机的音乐"))
            host.desktopLibraries.value.filter { it.serverId in host.connectedDesktops.value }.forEach { desktop ->
                val id = folders.entries.firstOrNull { it.value == desktop.serverId }?.key
                    ?: UUID.randomUUID().toString().also { folders[it] = desktop.serverId }
                rows += folder("car:desktop:$id:0", desktop.hostLabel, "串流到此手机")
            }
            val offset = page.toLong() * pageSize
            return@result LibraryResult.ofItemList(if (offset >= rows.size) emptyList() else rows.drop(offset.toInt()).take(pageSize), this.params)
        }
        val parts = parentId.split(':')
        val desktop = parts.size == 4 && parts.take(2) == listOf("car", "desktop")
        require(desktop || parts.size == 3 && parts.take(2) == listOf("car", "phone")) { "曲库入口已失效" }
        val base = parts.last().toIntOrNull()?.takeIf { it >= 0 } ?: error("无效分页")
        val limit = if (pageSize > 100) 99 else pageSize
        val offset = base.toLong() + page.toLong() * limit
        require(offset <= Int.MAX_VALUE) { "无效分页" }
        val entries = if (desktop) {
            val serverId = folders[parts[2]] ?: error("请重新打开电脑曲库")
            check(serverId in host.connectedDesktops.value) { "电脑未连接，请先在手机连接" }
            val library = host.desktopLibraries.value.firstOrNull { it.serverId == serverId } ?: error("电脑曲库已移除")
            withContext(Dispatchers.Default) {
                val eligible = library.tracks.filter { it.catalog != null }
                Page(eligible.size, eligible.drop(offset.toInt()).take(limit).map { Song(it, serverId, library.libraryId) })
            }
        } else {
            val rows = host.phoneLibraryClient.searchTracksPage("", offset.toInt(), limit).getOrThrow()
            Page(rows.total, rows.items.map { Song(it) })
        }
        val items = entries.items.map(::item).toMutableList()
        if (pageSize > 100 && offset + entries.items.size < entries.total) {
            items += folder(parts.dropLast(1).joinToString(":") + ":${offset + entries.items.size}", "更多歌曲")
        }
        LibraryResult.ofItemList(items, this.params)
    }

    private suspend fun search(query: String, offset: Int, limit: Int): Page {
        require(query.isNotBlank() && query.length <= 256) { "请输入歌曲、艺人或专辑名称" }
        val local = host.phoneLibraryClient.searchTracksPage(query, offset, limit).getOrThrow()
        val desktops = host.desktopLibraries.value.filter { it.serverId in host.connectedDesktops.value }
        val remote = withContext(Dispatchers.Default) { desktops.flatMap { library ->
            library.tracks.filter { it.catalog != null && listOf(it.title, it.artist, it.album).any { value -> value.contains(query.trim(), true) } }
                .map { Song(it, library.serverId, library.libraryId) }
        } }
        return Page(local.total + remote.size, local.items.map { Song(it) } + remote.drop((offset - local.total).coerceAtLeast(0)).take((limit - local.items.size).coerceAtLeast(0)))
    }
    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?) = result<Void> {
        val result = search(query, 0, 1)
        session.notifySearchResultChanged(browser, query, result.total, this.params)
        LibraryResult.ofVoid(this.params)
    }
    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
        page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = result {
        require(page >= 0 && pageSize > 0 && page.toLong() * pageSize <= Int.MAX_VALUE) { "无效分页" }
        LibraryResult.ofItemList(search(query, page * pageSize, pageSize.coerceAtMost(100)).items.map(::item), this.params)
    }

    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
        try { Futures.immediateFuture(mediaItems.map { item(songs[it.mediaId] ?: error("歌曲已失效，请重新浏览")) }.toMutableList()) }
        catch (error: Exception) { Futures.immediateFailedFuture(error) }

    suspend fun select(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        require(mediaItems.isNotEmpty() && mediaItems.size <= 100) { "请选择 1 至 100 首歌曲" }
        val selected = mediaItems.map { songs[it.mediaId] ?: error("歌曲已失效，请重新浏览") }
        val tracks = selected.map { song ->
            if (song.serverId == null) song.track else {
                val current = host.desktopLibraries.value.firstOrNull { it.serverId == song.serverId }
                check(current?.libraryId == song.libraryId && song.serverId in host.connectedDesktops.value) { "电脑曲库已改变，请重新浏览" }
                host.desktopStreamTracks(song.serverId, listOf(song.track)).single()
            }
        }
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        require(index in tracks.indices && (startPositionMs == C.TIME_UNSET || startPositionMs >= 0)) { "无效播放位置" }
        host.selectPlaybackTarget(phone = true).getOrThrow()
        val client = host.phoneLibraryClient
        val snapshot = client.reconcileState().getOrNull() ?: error("手机播放状态尚未就绪，请重试")
        val result = client.executeCommand(CoreTypedCommand.ReplaceQueue(revision = snapshot.queue?.revision, tracks = tracks, startIndex = index, play = false))
        check(result.isSuccess) { result.errorOrNull()?.message ?: "无法更新播放队列" }
        if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) {
            val seek = client.executeCommand(CoreTypedCommand.Seek(startPositionMs / 1000.0))
            check(seek.isSuccess) { seek.errorOrNull()?.message ?: "无法跳转播放位置" }
        }
    }
}
