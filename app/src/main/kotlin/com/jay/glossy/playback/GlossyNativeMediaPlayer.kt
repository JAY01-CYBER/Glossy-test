package com.jay.glossy.playback

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import timber.log.Timber

/**
 * Media3 Player facade for the real Glossy native audio path.
 *
 * The audible clock, decoding and rendering live in NativePlayer/Oboe. ExoPlayer is only
 * mirrored here for application queue compatibility while native mode is selected.
 */
class GlossyNativeMediaPlayer(
    private val service: MusicService,
    looper: Looper,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(looper) {
    private companion object { const val TAG = "GlossyNativeMediaPlayer" }

    @Volatile private var released = false
    @Volatile private var playWhenReadyState = false
    @Volatile private var repeatModeState = Player.REPEAT_MODE_OFF
    @Volatile private var shuffleState = false
    @Volatile private var volumeState = 1f
    @Volatile private var playbackParametersState = PlaybackParameters.DEFAULT
    @Volatile private var errorState: PlaybackException? = null
    @Volatile private var state = Player.STATE_IDLE

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = invalidateState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null && playWhenReadyState) {
                scope.launch { service.startGlossyNativeForCurrentItem() }
            }
            invalidateState()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playWhenReadyState = playWhenReady
            invalidateState()
        }
        override fun onRepeatModeChanged(repeatMode: Int) { repeatModeState = repeatMode; invalidateState() }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffleState = shuffleModeEnabled; invalidateState() }
    }

    init {
        service.player.addListener(listener)
    }

    private fun exo(): androidx.media3.exoplayer.ExoPlayer = service.player

    override fun getState(): State {
        if (released) {
            return State.Builder()
                .setAvailableCommands(Commands.EMPTY)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }

        val p = exo()
        val items = List(p.mediaItemCount) { i ->
            MediaItemData.Builder("glossy-native-${p.getMediaItemAt(i).mediaId}-$i")
                .setMediaItem(p.getMediaItemAt(i))
                .setIsPlaceholder(false)
                .setIsDynamic(true)
                .build()
        }
        val currentIndex = p.currentMediaItemIndex.takeIf { it >= 0 } ?: C.INDEX_UNSET
        val nativePosition = service.glossyNativePlayer.position()
        val nativeDuration = service.glossyNativePlayer.duration().takeIf { it >= 0 } ?: p.duration
        val nativePlaying = service.glossyNativePlayer.isPlaying()
        val effectiveState = when {
            items.isEmpty() -> Player.STATE_IDLE
            errorState != null -> Player.STATE_IDLE
            nativePlaying -> Player.STATE_READY
            playWhenReadyState -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        val commands = Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_RELEASE,
            )
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReadyState, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(effectiveState)
            .setPlayerError(errorState)
            .setRepeatMode(repeatModeState)
            .setShuffleModeEnabled(shuffleState)
            .setIsLoading(effectiveState == Player.STATE_BUFFERING)
            .setPlaybackParameters(playbackParametersState)
            .setTrackSelectionParameters(TrackSelectionParameters.DEFAULT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setVolume(volumeState)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(currentIndex)
            .setContentPositionMs(nativePosition.coerceAtLeast(0L))
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(nativePosition.coerceAtLeast(0L)))
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier.ZERO)
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        errorState = null
        state = Player.STATE_BUFFERING
        val index = exo().currentMediaItemIndex
        if (index >= 0) {
            scope.launch { service.startGlossyNativeForCurrentItem() }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playWhenReadyState = playWhenReady
        if (playWhenReady) {
            scope.launch { service.startGlossyNativeForCurrentItem() }
        } else {
            service.glossyNativePlayer.pause()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReadyState = false
        service.glossyNativePlayer.stop()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        service.player.removeListener(listener)
        service.glossyNativePlayer.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeatModeState = repeatMode
        exo().repeatMode = repeatMode
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffleState = shuffleModeEnabled
        exo().shuffleModeEnabled = shuffleModeEnabled
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        playbackParametersState = playbackParameters
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        volumeState = volume.coerceIn(0f, 1f)
        service.glossyNativePlayer.setVolume(volumeState)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        exo().setMediaItems(mediaItems, index, startPositionMs)
        if (mediaItems.isNotEmpty()) {
            state = Player.STATE_BUFFERING
            if (playWhenReadyState) scope.launch { service.startGlossyNativeForCurrentItem() }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val p = exo()
        val target = if (mediaItemIndex != C.INDEX_UNSET) mediaItemIndex else p.currentMediaItemIndex
        if (target >= 0 && target != p.currentMediaItemIndex) {
            p.seekTo(target, positionMs.coerceAtLeast(0L))
            if (playWhenReadyState) scope.launch { service.startGlossyNativeForCurrentItem() }
        } else {
            val pos = positionMs.coerceAtLeast(0L)
            service.glossyNativePlayer.seekTo(pos)
            p.seekTo(pos)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        exo().addMediaItems(index, mediaItems)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        exo().removeMediaItems(fromIndex, toIndex)
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
