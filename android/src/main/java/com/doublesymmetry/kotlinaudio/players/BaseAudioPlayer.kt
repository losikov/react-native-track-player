package com.doublesymmetry.kotlinaudio.players

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.CallSuper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLoadControl.Builder
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.doublesymmetry.kotlinaudio.event.EventHolder
import com.doublesymmetry.kotlinaudio.event.PlayerEventHolder
import com.doublesymmetry.kotlinaudio.models.AAMediaSessionCallBack
import com.doublesymmetry.kotlinaudio.models.AudioContentType
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.AudioItemHolder
import com.doublesymmetry.kotlinaudio.models.AudioItemTransitionReason
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.BufferConfig
import com.doublesymmetry.kotlinaudio.models.CacheConfig
import com.doublesymmetry.kotlinaudio.models.DefaultPlayerOptions
import com.doublesymmetry.kotlinaudio.models.PlayWhenReadyChangeData
import com.doublesymmetry.kotlinaudio.models.PlaybackError
import com.doublesymmetry.kotlinaudio.models.PlayerConfig
import com.doublesymmetry.kotlinaudio.models.PlayerOptions
import com.doublesymmetry.kotlinaudio.models.PlayerSnapshot
import com.doublesymmetry.kotlinaudio.models.PositionChangedReason
import com.doublesymmetry.kotlinaudio.models.Readiness
import com.doublesymmetry.kotlinaudio.models.RepeatMode
import com.doublesymmetry.kotlinaudio.models.Suppression
import com.doublesymmetry.kotlinaudio.models.Transport
import com.doublesymmetry.kotlinaudio.models.TransportReason
import com.doublesymmetry.kotlinaudio.models.WakeMode
import com.doublesymmetry.kotlinaudio.players.components.MediaFactory
import com.doublesymmetry.kotlinaudio.players.components.PlayerCache
import com.doublesymmetry.kotlinaudio.players.components.getAudioItemHolder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The audio engine: an ExoPlayer (AndroidX Media3) and the state derived from it.
 *
 * What it deliberately does **not** own any more, compared with the ExoPlayer 2.19.1 version:
 * the media session (`MediaSessionCompat` + `MediaSessionConnector`), the notification
 * (`NotificationManager`, 903 lines) and the hand-rolled audio-focus request. Those belong to
 * `MusicService` and to media3 now. What it gained is [state]: a [PlayerSnapshot] that answers
 * "should the button say play or pause" without going through raw ExoPlayer readiness.
 *
 * The legacy [playerState] / [AudioPlayerState] derivation below is preserved *bit for bit* from
 * the ExoPlayer 2 build, because it is what `event.stateChange` carries and what the `state` string
 * in JS's `onPlaybackState` is made of. Step 2 changes no JS behaviour; the snapshot rides alongside.
 */
@UnstableApi
abstract class BaseAudioPlayer internal constructor(
    internal val context: Context,
    playerConfig: PlayerConfig,
    bufferConfig: BufferConfig?,
    cacheConfig: CacheConfig?,
    mediaSessionCallback: AAMediaSessionCallBack,
) {
    internal var exoPlayer: ExoPlayer
        private set

    private var cache: SimpleCache? = null
    private val scope = MainScope()
    private var playerConfig: PlayerConfig = playerConfig
    private var bufferConfig: BufferConfig? = bufferConfig
    private var cacheConfig: CacheConfig? = cacheConfig
    internal val mediaFactory: MediaFactory
    var mediaSessionCallBack: AAMediaSessionCallBack = mediaSessionCallback

    open val playerOptions: PlayerOptions = DefaultPlayerOptions()

    open val currentItem: AudioItem?
        get() = exoPlayer.currentMediaItem?.getAudioItemHolder()?.audioItem

    var playbackError: PlaybackError? = null
    var playerState: AudioPlayerState = AudioPlayerState.IDLE
        private set(value) {
            if (value != field) {
                field = value
                playerEventHolder.updateAudioPlayerState(value)
            }
        }

    var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
            publishSnapshot(if (value) TransportReason.USER else TransportReason.USER)
        }

    val duration: Long
        get() = if (exoPlayer.duration == C.TIME_UNSET) 0 else exoPlayer.duration

    val isCurrentMediaItemLive: Boolean
        get() = exoPlayer.isCurrentMediaItemLive

    private var oldPosition = 0L

    val position: Long
        get() = if (exoPlayer.currentPosition == C.POSITION_UNSET.toLong()) 0 else exoPlayer.currentPosition

    val bufferedPosition: Long
        get() = if (exoPlayer.bufferedPosition == C.POSITION_UNSET.toLong()) 0 else exoPlayer.bufferedPosition

    var volume: Float
        get() = exoPlayer.volume
        set(value) {
            exoPlayer.volume = value
            publishSnapshot()
        }

    var playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed
        set(value) {
            exoPlayer.setPlaybackSpeed(value)
            publishSnapshot()
        }

    /**
     * Kept for API compatibility with the ExoPlayer 2 build. media3 derives the notification and
     * every controller's metadata from `Player.getMediaMetadata()`, i.e. from the current
     * `MediaItem`, so "automatic" is now the only cheap option; setting this to false only stops
     * [QueuedAudioPlayer.replaceItem] from pushing the replacement into the timeline.
     */
    var automaticallyUpdateNotificationMetadata: Boolean = true

    val isPlaying
        get() = exoPlayer.isPlaying

    private val playerEventHolder = PlayerEventHolder()

    val event = EventHolder(playerEventHolder)

    private val _state = MutableStateFlow(PlayerSnapshot())

    /** The engine state model step 3 puts on the bridge and step 4 binds the play button to. */
    val state: StateFlow<PlayerSnapshot> = _state.asStateFlow()

    private var transportReason: TransportReason = TransportReason.SYSTEM

    /** The armed [setStopAt], or null. Both fields are written only on Main. */
    private var stopAtMessage: PlayerMessage? = null
    private var stopAtPositionMs: Long? = null
    private var stopAtIndex: Int = C.INDEX_UNSET

    private var isReinitializingAudioSession = false

    init {
        if (cacheConfig != null) {
            cache = PlayerCache.getInstance(context, cacheConfig)
        }
        mediaFactory = MediaFactory(context, cacheConfig, cache)
        exoPlayer = buildExoPlayer()
        playerEventHolder.updateAudioPlayerState(AudioPlayerState.IDLE)
        publishSnapshot()
    }

    private fun buildExoPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaFactory)
            .setHandleAudioBecomingNoisy(playerConfig.handleAudioBecomingNoisy)
            .setWakeMode(
                when (playerConfig.wakeMode) {
                    WakeMode.NONE -> C.WAKE_MODE_NONE
                    WakeMode.LOCAL -> C.WAKE_MODE_LOCAL
                    WakeMode.NETWORK -> C.WAKE_MODE_NETWORK
                }
            )
            .apply {
                bufferConfig?.let { setLoadControl(setupBuffer(it)) }
            }
            .build()
        player.addListener(PlayerListener())
        // Audio focus is ExoPlayer's job now — it requests it on play and abandons it on pause,
        // which is what closes #249's "why is focus requested at READY" and makes a transient loss
        // (a phone call) resume by itself instead of turning into a pause.
        player.setAudioAttributes(audioAttributes(), playerConfig.handleAudioFocus)
        return player
    }

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(
            when (playerConfig.audioContentType) {
                AudioContentType.MUSIC -> C.AUDIO_CONTENT_TYPE_MUSIC
                AudioContentType.SPEECH -> C.AUDIO_CONTENT_TYPE_SPEECH
                AudioContentType.SONIFICATION -> C.AUDIO_CONTENT_TYPE_SONIFICATION
                AudioContentType.MOVIE -> C.AUDIO_CONTENT_TYPE_MOVIE
                AudioContentType.UNKNOWN -> C.AUDIO_CONTENT_TYPE_UNKNOWN
            }
        )
        .build()

    /**
     * Apply options that arrived after the engine was built.
     *
     * media3's `MediaLibraryService.onGetSession` has to hand a session — and therefore a player —
     * to the first controller that connects, which on an Android Auto cold start happens before
     * JS exists to call `setupPlayer`. Everything except the [DefaultLoadControl] can be set on a
     * live ExoPlayer; the buffer sizes cannot, so the player is rebuilt for them, which is safe
     * exactly while nothing is loaded. Returns true when the caller must re-point the session at
     * [exoPlayer].
     */
    @CallSuper
    internal open fun applyConfig(
        playerConfig: PlayerConfig,
        bufferConfig: BufferConfig?,
        cacheConfig: CacheConfig?,
    ): Boolean {
        val needsRebuild = bufferConfig != this.bufferConfig && exoPlayer.mediaItemCount == 0
        this.playerConfig = playerConfig
        this.bufferConfig = bufferConfig
        if (cacheConfig != null && cacheConfig != this.cacheConfig) {
            this.cacheConfig = cacheConfig
            cache = PlayerCache.getInstance(context, cacheConfig)
            mediaFactory.cacheConfig = cacheConfig
            mediaFactory.cache = cache
        }
        if (needsRebuild) {
            Timber.d("Rebuilding ExoPlayer for a late buffer config (%s)", bufferConfig)
            val volume = exoPlayer.volume
            val speed = exoPlayer.playbackParameters.speed
            val repeat = exoPlayer.repeatMode
            exoPlayer.release()
            exoPlayer = buildExoPlayer()
            exoPlayer.volume = volume
            exoPlayer.setPlaybackSpeed(speed)
            exoPlayer.repeatMode = repeat
            publishSnapshot()
            return true
        }
        exoPlayer.setAudioAttributes(audioAttributes(), playerConfig.handleAudioFocus)
        return false
    }

    private fun setupBuffer(bufferConfig: BufferConfig): DefaultLoadControl {
        bufferConfig.apply {
            val multiplier =
                DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val minBuffer =
                if (minBuffer != null && minBuffer != 0) minBuffer else DEFAULT_MIN_BUFFER_MS
            val maxBuffer =
                if (maxBuffer != null && maxBuffer != 0) maxBuffer else DEFAULT_MAX_BUFFER_MS
            val playBuffer =
                if (playBuffer != null && playBuffer != 0) playBuffer else DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val backBuffer =
                if (backBuffer != null && backBuffer != 0) backBuffer else DEFAULT_BACK_BUFFER_DURATION_MS

            return Builder()
                .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, playBuffer * multiplier)
                .setBackBuffer(backBuffer, false)
                .build()
        }
    }

    // region snapshot

    private fun readinessOf(): Readiness = when (exoPlayer.playbackState) {
        Player.STATE_BUFFERING -> Readiness.BUFFERING
        Player.STATE_READY -> Readiness.READY
        Player.STATE_ENDED -> Readiness.ENDED
        else -> if (playerState == AudioPlayerState.LOADING) Readiness.LOADING else Readiness.IDLE
    }

    private fun suppressionOf(): Suppression = when (exoPlayer.playbackSuppressionReason) {
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> Suppression.TRANSIENT_AUDIO_FOCUS_LOSS
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE -> Suppression.UNSUITABLE_OUTPUT
        else -> Suppression.NONE
    }

    internal fun publishSnapshot(reason: TransportReason? = null) {
        if (reason != null) transportReason = reason
        val readiness = readinessOf()
        val error = playbackError
        val transport = when {
            error != null && playerState == AudioPlayerState.ERROR -> Transport.ERROR
            readiness == Readiness.ENDED -> Transport.ENDED
            exoPlayer.playWhenReady -> Transport.PLAYING
            else -> Transport.PAUSED
        }
        _state.value = PlayerSnapshot(
            transport = transport,
            transportReason = transportReason,
            playWhenReady = exoPlayer.playWhenReady,
            readiness = readiness,
            isPlaying = exoPlayer.isPlaying,
            suppression = suppressionOf(),
            index = if (exoPlayer.mediaItemCount == 0) null else exoPlayer.currentMediaItemIndex,
            queueSize = exoPlayer.mediaItemCount,
            positionMs = position,
            durationMs = duration,
            bufferedMs = bufferedPosition,
            rate = exoPlayer.playbackParameters.speed,
            volume = exoPlayer.volume,
            repeatMode = when (exoPlayer.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
            error = error,
        )
    }

    // endregion

    // region loading

    internal fun mediaItemOf(item: AudioItem): MediaItem = MediaItem.Builder()
        .setUri(item.audioUrl)
        .setTag(AudioItemHolder(item))
        .setMediaMetadata(metadataOf(item))
        .build()

    private fun metadataOf(item: AudioItem): MediaMetadata = MediaMetadata.Builder()
        .setTitle(item.title)
        .setDisplayTitle(item.title)
        .setArtist(item.artist)
        .setSubtitle(item.artist)
        .setAlbumTitle(item.albumTitle)
        .setArtworkUri(item.artwork?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
        .setDurationMs(item.duration)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    /**
     * Load a whole queue in one call: `setMediaItems(items, startIndex, startPositionMs)` then
     * `prepare()` then the intent.
     *
     * The point is atomicity. Loading used to be two calls — `add`/`load` and then
     * `skip(index, position)` — so ExoPlayer emitted a media-item transition at position 0 before
     * the seek landed and JS wrote that 0 to stored progress. Nothing in JS calls this yet (step 3
     * puts it on the bridge); it exists here so the engine has one correct way to load.
     */
    open fun loadQueue(
        items: List<AudioItem>,
        startIndex: Int = 0,
        startPositionMs: Long = C.TIME_UNSET,
        playWhenReady: Boolean = false,
    ) {
        clearStopAt()
        playbackError = null
        exoPlayer.setMediaItems(items.map { mediaItemOf(it) }, startIndex, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
        publishSnapshot(TransportReason.USER)
        emitProgressDiscontinuity()
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     */
    open fun load(item: AudioItem, playWhenReady: Boolean = true) {
        exoPlayer.playWhenReady = playWhenReady
        load(item)
    }

    open fun load(item: AudioItem) {
        clearStopAt()
        exoPlayer.addMediaItem(mediaItemOf(item))
        exoPlayer.prepare()
    }

    // endregion

    fun togglePlaying() {
        if (exoPlayer.isPlaying) pause() else play()
    }

    var skipSilence: Boolean
        get() = exoPlayer.skipSilenceEnabled
        set(value) {
            exoPlayer.skipSilenceEnabled = value
        }

    /**
     * `playWhenReady` flips synchronously (ExoPlayer masks it) and so does the snapshot, so a JS
     * button bound to [state] never shows the stale value. A play after an error re-prepares first,
     * otherwise ExoPlayer sits in `STATE_IDLE` and the intent goes nowhere.
     *
     * [reason] is recorded *before* the player is touched, not after: `exoPlayer.play()` runs the
     * listeners synchronously and each of them publishes, so a reason set afterwards would leave the
     * first event JS sees still saying `user`. This is what carries `reason=remote` to JS when
     * [com.doublesymmetry.kotlinaudio.players.InterceptingPlayer] applies a lock-screen, Bluetooth
     * or Android Auto command natively (step 4).
     */
    fun play(reason: TransportReason = TransportReason.USER) {
        transportReason = reason
        if (exoPlayer.playerError != null) {
            exoPlayer.prepare()
        }
        exoPlayer.play()
        if (currentItem != null) {
            exoPlayer.prepare()
        }
        publishSnapshot(reason)
    }

    fun prepare() {
        if (currentItem != null) {
            exoPlayer.prepare()
        }
        publishSnapshot()
    }

    /** @see play for why [reason] is recorded before the player is touched. */
    fun pause(reason: TransportReason = TransportReason.USER) {
        transportReason = reason
        exoPlayer.pause()
        publishSnapshot(reason)
    }

    // region stopAt

    /** Whether a [setStopAt] is currently armed. */
    val stopAtPosition: Long?
        get() = stopAtPositionMs

    /**
     * Arm a pending stop: pause exactly at [positionMs] of the current item, with reason
     * [TransportReason.STOP_AT], and never auto-advance past it.
     *
     * The mechanism is ExoPlayer's own `PlayerMessage`, delivered by the playback thread at a
     * timeline position, so the stop lands on the sample and not on whenever a JS timer next ran.
     * It is cleared by [clearStopAt] and by any seek, skip, load or [loadQueue] — those all run
     * [clearStopAt] *before* touching the player, because ExoPlayer delivers a pending message that
     * a seek jumps over rather than dropping it.
     *
     * The delivery handler re-checks the index and position anyway: a message can still be in
     * flight when something clears it on the Main thread.
     */
    fun setStopAt(positionMs: Long) {
        clearStopAt()
        if (exoPlayer.mediaItemCount == 0) return
        val index = exoPlayer.currentMediaItemIndex
        val target = positionMs.coerceAtLeast(0)
        stopAtPositionMs = target
        stopAtIndex = index
        stopAtMessage = exoPlayer.createMessage { _, _ -> deliverStopAt(index, target) }
            .setPosition(index, target)
            .setLooper(Looper.getMainLooper())
            .setDeleteAfterDelivery(true)
            .send()
    }

    /** Disarm a pending [setStopAt]. A no-op when nothing is armed. */
    fun clearStopAt() {
        stopAtMessage?.cancel()
        stopAtMessage = null
        stopAtPositionMs = null
        stopAtIndex = C.INDEX_UNSET
    }

    private fun deliverStopAt(index: Int, target: Long) {
        // Cleared, re-armed elsewhere, or the queue moved on while the message was in flight.
        if (stopAtPositionMs != target || stopAtIndex != index) return
        if (exoPlayer.currentMediaItemIndex != index) {
            clearStopAt()
            return
        }
        stopAtMessage = null
        stopAtPositionMs = null
        stopAtIndex = C.INDEX_UNSET
        // Set before the pause, not after: `exoPlayer.pause()` runs the listeners synchronously and
        // each of them publishes, so the reason has to be in place or the first event JS sees would
        // still say `user`.
        transportReason = TransportReason.STOP_AT
        exoPlayer.pause()
        publishSnapshot(TransportReason.STOP_AT)
        playerEventHolder.updateStopAtReached(position)
        emitProgressDiscontinuity()
    }

    // endregion

    /**
     * Re-apply the audio attributes so the output is routed to whatever just connected or
     * disconnected (Android Auto), then bounce play to make ExoPlayer act on it.
     *
     * Kept from KotlinAudio `2c6300b`. On media3 the pause/play bounce is the part that still
     * matters; the focus juggling that surrounded it on ExoPlayer 2 is gone because media3's focus
     * manager owns that now.
     */
    fun ensureAudioSessionInitialized() {
        if (isReinitializingAudioSession) return

        scope.launch {
            val currentState = exoPlayer.playbackState
            val playWhenReady = exoPlayer.playWhenReady

            if (playWhenReady && (currentState == Player.STATE_READY || currentState == Player.STATE_BUFFERING)) {
                isReinitializingAudioSession = true
                try {
                    exoPlayer.setAudioAttributes(audioAttributes(), playerConfig.handleAudioFocus)
                    val wasPlaying = exoPlayer.isPlaying
                    if (wasPlaying) {
                        exoPlayer.pause()
                        delay(50)
                    }
                    exoPlayer.play()
                    delay(200)
                } finally {
                    isReinitializingAudioSession = false
                }
            }
        }
    }

    /** @see play for why [reason] is recorded before the player is touched. */
    @CallSuper
    open fun stop(reason: TransportReason = TransportReason.USER) {
        transportReason = reason
        clearStopAt()
        playerState = AudioPlayerState.STOPPED
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
        publishSnapshot(reason)
    }

    @CallSuper
    open fun clear() {
        clearStopAt()
        exoPlayer.clearMediaItems()
        publishSnapshot()
    }

    fun setPauseAtEndOfItem(pause: Boolean) {
        exoPlayer.pauseAtEndOfMediaItems = pause
    }

    @CallSuper
    open fun destroy() {
        stop()
        exoPlayer.release()
        cache?.release()
        cache = null
        mediaFactory.cache = null
    }

    open fun seek(duration: Long, unit: TimeUnit, reason: TransportReason = TransportReason.USER) {
        transportReason = reason
        // Disarmed *before* the seek, not after: ExoPlayer delivers a pending message whose
        // position a seek jumps over, so a clear that ran afterwards would arrive too late.
        clearStopAt()
        val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
        exoPlayer.seekTo(positionMs)
        publishSnapshot(reason)
    }

    open fun seekBy(offset: Long, unit: TimeUnit, reason: TransportReason = TransportReason.USER) {
        transportReason = reason
        clearStopAt()
        val positionMs = exoPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
        exoPlayer.seekTo(positionMs)
        publishSnapshot(reason)
    }

    /**
     * Replace the metadata shown for the currently playing item without disturbing playback.
     *
     * `MediaSource.canUpdateMediaItem` is true when only metadata changed, so media3 swaps it in
     * place. This is what `NotificationManager.overrideMetadata` used to do by holding an override
     * that the description adapter consulted.
     */
    fun overrideMetadata(item: AudioItem) {
        val index = exoPlayer.currentMediaItemIndex
        val current = exoPlayer.currentMediaItem ?: return
        exoPlayer.replaceMediaItem(index, current.buildUpon().setMediaMetadata(metadataOf(item)).build())
    }

    internal fun emitProgressDiscontinuity() {
        playerEventHolder.updateProgressDiscontinuity()
    }

    companion object {
        const val APPLICATION_NAME = MediaFactory.APPLICATION_NAME

        /** @see MediaFactory.progressiveExtractorsFactory */
        fun progressiveExtractorsFactory() = MediaFactory.progressiveExtractorsFactory()
    }

    inner class PlayerListener : Listener {
        override fun onMetadata(metadata: Metadata) {
            playerEventHolder.updateOnTimedMetadata(metadata)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            playerEventHolder.updateOnCommonMetadata(mediaMetadata)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            this@BaseAudioPlayer.oldPosition = oldPosition.positionMs

            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.AUTO(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK -> {
                    playerEventHolder.updatePositionChangedReason(
                        PositionChangedReason.SEEK(oldPosition.positionMs, newPosition.positionMs)
                    )
                    // A seek while paused produces no interval tick, so JS would keep the stale
                    // position until playback resumed. One progress event per seek fixes that.
                    playerEventHolder.updateProgressDiscontinuity()
                }
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> {
                    playerEventHolder.updatePositionChangedReason(
                        PositionChangedReason.SEEK_FAILED(oldPosition.positionMs, newPosition.positionMs)
                    )
                    playerEventHolder.updateProgressDiscontinuity()
                }
                Player.DISCONTINUITY_REASON_REMOVE -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.QUEUE_CHANGED(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SKIPPED_PERIOD(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_INTERNAL -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )
            }
            publishSnapshot()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.AUTO(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.QUEUE_CHANGED(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.REPEAT(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.SEEK_TO_ANOTHER_AUDIO_ITEM(oldPosition)
                )
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val pausedBecauseReachedEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            playerEventHolder.updatePlayWhenReadyChange(
                PlayWhenReadyChangeData(playWhenReady, pausedBecauseReachedEnd)
            )
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                transportReason = TransportReason.AUDIO_FOCUS_LOSS
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            publishSnapshot()
        }

        /**
         * The legacy state derivation, unchanged from the ExoPlayer 2 build. Order matters: each
         * assignment to [playerState] fires an event.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            for (i in 0 until events.size()) {
                when (events[i]) {
                    Player.EVENT_PLAYBACK_STATE_CHANGED -> {
                        val state = when (player.playbackState) {
                            Player.STATE_BUFFERING -> AudioPlayerState.BUFFERING
                            Player.STATE_READY -> AudioPlayerState.READY
                            Player.STATE_IDLE ->
                                if (
                                    playerState == AudioPlayerState.ERROR ||
                                    playerState == AudioPlayerState.STOPPED
                                )
                                    null
                                else
                                    AudioPlayerState.IDLE
                            Player.STATE_ENDED ->
                                if (player.mediaItemCount > 0) AudioPlayerState.ENDED
                                else AudioPlayerState.IDLE
                            else -> null // noop
                        }
                        if (state != null && state != playerState) {
                            playerState = state
                        }
                        if (player.playbackState == Player.STATE_ENDED) {
                            markEndOfQueue(player)
                        }
                    }
                    Player.EVENT_MEDIA_ITEM_TRANSITION -> {
                        playbackError = null
                        if (currentItem != null) {
                            playerState = AudioPlayerState.LOADING
                            if (isPlaying) {
                                playerState = AudioPlayerState.READY
                                playerState = AudioPlayerState.PLAYING
                            }
                        }
                    }
                    Player.EVENT_PLAY_WHEN_READY_CHANGED -> {
                        if (!player.playWhenReady && playerState != AudioPlayerState.STOPPED) {
                            playerState = AudioPlayerState.PAUSED
                        } else if (player.playWhenReady && playerState == AudioPlayerState.PAUSED && player.playbackState == Player.STATE_READY) {
                            playerState = AudioPlayerState.PLAYING
                        }
                    }
                    Player.EVENT_IS_PLAYING_CHANGED -> {
                        if (player.isPlaying) {
                            playerState = AudioPlayerState.PLAYING
                        }
                    }
                }
            }
            publishSnapshot()
        }

        /**
         * The end of the queue is recorded in the snapshot ([Transport.ENDED] with reason
         * [TransportReason.END_OF_QUEUE]) but the engine does **not** pause itself here, which the
         * architecture doc's §4 asks for.
         *
         * It was implemented and then taken out, because on media3 dropping `playWhenReady` while
         * the player is in `STATE_ENDED` moves it back to `STATE_READY` — ExoPlayer 2 stayed ENDED.
         * That is a change to the legacy `AudioPlayerState` JS receives, which step 2 is not allowed
         * to make: `QueuedAudioPlayerTest.RepeatMode_Off_…_thenShouldStopPlayback` fails
         * `expected:<ENDED> but was:<READY>` deterministically with the pause in, and passes with it
         * out. The intent correction belongs with step 4, where the JS side of end-of-queue moves
         * anyway; until then `snapshot.transport == ENDED` already tells a consumer what the button
         * should show.
         */
        private fun markEndOfQueue(player: Player) {
            if (player.mediaItemCount == 0 || player.hasNextMediaItem()) return
            transportReason = TransportReason.END_OF_QUEUE
        }

        override fun onPlayerError(error: PlaybackException) {
            val playbackError = PlaybackError(
                error.errorCodeName
                    .replace("ERROR_CODE_", "")
                    .lowercase(Locale.getDefault())
                    .replace("_", "-"),
                error.message
            )
            playerEventHolder.updatePlaybackError(playbackError)
            this@BaseAudioPlayer.playbackError = playbackError
            playerState = AudioPlayerState.ERROR
            publishSnapshot(TransportReason.ERROR)
        }
    }
}
