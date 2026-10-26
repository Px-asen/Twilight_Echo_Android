package com.twilightecho.android.mediasession

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.twilightecho.android.coreclient.CoreClient
import com.twilightecho.android.coreclient.CoreQueueWindowView
import com.twilightecho.android.coreclient.CoreSnapshot
import com.twilightecho.android.coreclient.CoreTrackView
import com.twilightecho.android.coreclient.CoreTypedCommand
import com.twilightecho.android.coreclient.PlayMode
import com.twilightecho.android.playbackhost.AndroidPlaybackHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Custom Media3 `SimpleBasePlayer` projecting `CoreSnapshot` and `CoreQueueWindowView` as the
 * single source of truth (Architecture Plan Sections 5.3 & 11.3).
 *
 * Key invariant:
 * Media3 does NOT maintain a second independent playback queue or state machine. Every `getState()`
 * call is a pure read-only projection of `CoreClient.snapshot` and `CoreClient.queueWindow`, and
 * every system media button (`handleSetPlayWhenReady`, `handleSeek`, `handleSetRepeatMode`,
 * `handleSetShuffleModeEnabled`) dispatches typed commands to Music Core.
 */
@UnstableApi
class CoreSimpleBasePlayer(
    private val playbackHost: AndroidPlaybackHost,
    private val coreClient: CoreClient = playbackHost.mediaSessionCoreClient,
    applicationLooper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(applicationLooper) {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var latestSnapshot: CoreSnapshot? = coreClient.snapshot.value

    @Volatile
    private var latestQueueWindow: CoreQueueWindowView = coreClient.queueWindow.value

    init {
        mainScope.launch {
            combine(coreClient.snapshot, coreClient.queueWindow) { snap, queueWin ->
                snap to queueWin
            }.collect { (snap, queueWin) ->
                latestSnapshot = snap
                latestQueueWindow = queueWin
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val snap = latestSnapshot
        val playback = snap?.playback
        val activeTrack = snap?.track
        val queueItems = buildMediaItemQueue(snap, latestQueueWindow, activeTrack)

        val playbackState = when (playback?.state) {
            "playing", "paused" -> Player.STATE_READY
            "loading", "buffering" -> Player.STATE_BUFFERING
            else -> if (queueItems.isNotEmpty()) Player.STATE_READY else Player.STATE_IDLE
        }

        val isPlaying = playback?.state == "playing"
        val currentQueueIndex = (playback?.queueIndex ?: 0).coerceIn(
            0,
            (queueItems.size - 1).coerceAtLeast(0)
        )
        val positionMs = ((playback?.position ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)

        val playMode = PlayMode.fromWireValue(playback?.playMode)
        val repeatMode = when (playMode) {
            PlayMode.SINGLE -> Player.REPEAT_MODE_ONE
            PlayMode.LOOP -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        val shuffleEnabled = playMode == PlayMode.SHUFFLE

        val availableCommands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_VOLUME
            )
            .build()

        val stateBuilder = State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaylist(queueItems)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleEnabled)
            .setVolume((playback?.volume ?: 1.0).toFloat().coerceIn(0f, 1f))

        if (queueItems.isNotEmpty()) {
            stateBuilder.setCurrentMediaItemIndex(currentQueueIndex)
            stateBuilder.setContentPositionMs(positionMs)
        }

        return stateBuilder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        mainScope.future {
            if (playWhenReady) {
                playbackHost.requestFocusAndPlay()
            } else {
                coreClient.executeCommand(CoreTypedCommand.Pause)
            }
            null
        }

    override fun handleStop(): ListenableFuture<*> =
        mainScope.future {
            playbackHost.audioFocusCoordinator.abandonPlaybackFocus()
            coreClient.executeCommand(CoreTypedCommand.Stop)
            null
        }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> = mainScope.future {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                coreClient.executeCommand(CoreTypedCommand.Next)
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                coreClient.executeCommand(CoreTypedCommand.Previous)
            }
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                if (mediaItemIndex >= 0) {
                    val rev = latestSnapshot?.queue?.revision ?: 0L
                    coreClient.executeCommand(
                        CoreTypedCommand.SelectQueueIndex(
                            revision = rev,
                            index = mediaItemIndex,
                            occurrenceId = "occ-$mediaItemIndex"
                        )
                    )
                }
                if (positionMs != C.TIME_UNSET && positionMs >= 0L) {
                    coreClient.executeCommand(CoreTypedCommand.Seek(positionMs / 1000.0))
                }
            }
            else -> {
                val safeMs = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
                coreClient.executeCommand(CoreTypedCommand.Seek(safeMs / 1000.0))
            }
        }
        null
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> =
        mainScope.future {
            val mode = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> PlayMode.SINGLE
                Player.REPEAT_MODE_ALL -> PlayMode.LOOP
                else -> PlayMode.SEQUENCE
            }
            coreClient.executeCommand(CoreTypedCommand.SetPlayMode(mode))
            null
        }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        mainScope.future {
            val mode = if (shuffleModeEnabled) PlayMode.SHUFFLE else PlayMode.SEQUENCE
            coreClient.executeCommand(CoreTypedCommand.SetPlayMode(mode))
            null
        }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> =
        mainScope.future {
            coreClient.executeCommand(CoreTypedCommand.SetVolume(volume.toDouble().coerceIn(0.0, 1.0)))
            null
        }

    override fun handleRelease(): ListenableFuture<*> {
        mainScope.cancel()
        return Futures.immediateVoidFuture()
    }

    private fun buildMediaItemQueue(
        snapshot: CoreSnapshot?,
        queueWindow: CoreQueueWindowView,
        activeTrack: CoreTrackView?
    ): List<MediaItemData> {
        val tracks: List<CoreTrackView> = when {
            queueWindow.items.isNotEmpty() -> queueWindow.items.mapNotNull { it.track }
            activeTrack != null -> listOf(activeTrack)
            else -> emptyList()
        }

        return tracks.mapIndexed { idx, track ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setAlbumArtist(track.albumArtist ?: track.artist)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.source?.let { Uri.parse(it) })
                .setMediaMetadata(metadata)
                .build()

            val durationUs = if (track.duration > 0.0) {
                (track.duration * 1_000_000.0).toLong()
            } else {
                ((snapshot?.playback?.duration ?: 0.0) * 1_000_000.0).toLong().takeIf { it > 0L }
                    ?: C.TIME_UNSET
            }

            MediaItemData.Builder(track.queueEntryId ?: "uid-$idx-${track.id}")
                .setMediaItem(mediaItem)
                .setMediaMetadata(metadata)
                .setDurationUs(durationUs)
                .setIsSeekable(true)
                .build()
        }
    }
}
