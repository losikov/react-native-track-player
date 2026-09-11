package com.doublesymmetry.kotlinaudio.players

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.doublesymmetry.kotlinaudio.models.AAMediaSessionCallBack
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.TransportPolicy
import com.doublesymmetry.kotlinaudio.models.TransportReason
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The player the media session is built over.
 *
 * Two jobs, and it is the single most behaviour-sensitive class in the media3 migration:
 *
 * 1. **Transport policy.** On ExoPlayer 2 the session applied *nothing* natively: a
 *    `ForwardingPlayer` turned play/pause/next/previous/forward/back/stop/seek into
 *    `onPlayerActionTriggeredExternally` events, `MusicService` forwarded them to JS as
 *    `onRemotePlay`/`onRemotePause`/…, and JS issued the real command back through the module.
 *    [TransportPolicy.ROUTE_TO_LISTENERS] reproduces that exactly.
 *
 *    Step 4 runs on [TransportPolicy.APPLY_NATIVELY_AND_NOTIFY]: play, pause, stop, seek and the
 *    ±jumps are applied by the engine here, synchronously, and *then* announced to JS, which does
 *    its bookkeeping (analytics, stored progress) without re-issuing the command. The player is
 *    therefore controllable from the lock screen, Bluetooth, Android Auto and the Assistant whether
 *    or not a React context is alive. Every command goes through the engine's own methods
 *    ([BaseAudioPlayer.play], [BaseAudioPlayer.seek], …) rather than `exoPlayer` directly, because
 *    the engine is what disarms a pending `setStopAt` and what publishes the snapshot with
 *    `reason=remote`.
 *
 *    JS owns the *meaning* of next/previous (chapter and daily-date navigation, the 20-second
 *    restart), and of a seek to a different queue item, so those stay routed forever.
 *
 *    Because next/previous are routed rather than applied, ExoPlayer's own view of "is there a next
 *    item" must not decide whether the buttons exist: [getAvailableCommands] always advertises the
 *    four next/previous commands so the notification, Bluetooth and Android Auto keep showing them.
 *
 * 2. **Play/prepare from media id and from search.** media3 has no `onPlayFromMediaId`. Its legacy
 *    stub turns the legacy call into `MediaSession.Callback.onSetMediaItems` with a *request* item —
 *    a `MediaItem` carrying only a `mediaId` (or a `requestMetadata.searchQuery`) and no URI —
 *    followed, synchronously on the app thread, by `setMediaItem(s)`, `prepare()` and, for "play",
 *    `play()` on the session player. A URI-less item handed to ExoPlayer is an error, and the app
 *    resolves media ids in JS, so those three calls are swallowed here and replayed as exactly one
 *    of `handlePlayFromMediaId` / `handlePlayFromSearch` / `handlePrepareFromMediaId` /
 *    `handlePrepareFromSearch`. Which one is decided at the end of the current main-loop message:
 *    if `play()` arrived, it was a play; otherwise it was a prepare.
 */
@UnstableApi
class InterceptingPlayer(
    private val engine: BaseAudioPlayer,
    private val sessionCallback: AAMediaSessionCallBack,
    private val onAction: (MediaSessionCallback) -> Unit,
) : ForwardingPlayer(engine.exoPlayer) {

    /**
     * Step 2 kept every command routed; step 4 flips the pure-transport ones. The value in use is
     * set by `MusicService.configureSessionPlayer`, which is the one place that builds this object;
     * the field default stays [TransportPolicy.ROUTE_TO_LISTENERS] so a bare instance behaves as the
     * ExoPlayer 2 build did.
     */
    var policy: TransportPolicy = TransportPolicy.ROUTE_TO_LISTENERS

    private val handler = Handler(Looper.getMainLooper())

    /**
     * media3 fixes the ±jump increments at `ExoPlayer.Builder` time, but the app configures them
     * through `updateOptions`, which arrives after the engine exists. Every controller — the
     * notification, a head unit, a Bluetooth remote — reads them off the session player, so this is
     * where they can still be answered correctly.
     */
    var seekForwardIncrementOverrideMs: Long = C.TIME_UNSET
    var seekBackIncrementOverrideMs: Long = C.TIME_UNSET

    override fun getSeekForwardIncrement(): Long =
        if (seekForwardIncrementOverrideMs > 0) seekForwardIncrementOverrideMs
        else super.getSeekForwardIncrement()

    override fun getSeekBackIncrement(): Long =
        if (seekBackIncrementOverrideMs > 0) seekBackIncrementOverrideMs
        else super.getSeekBackIncrement()

    private class PendingRequest(
        val mediaId: String?,
        val searchQuery: String?,
        val extras: Bundle?,
    ) {
        var sawPlay = false
    }

    private var pending: PendingRequest? = null

    // region play / prepare from id and search

    private fun MediaItem.asRequestOrNull(): PendingRequest? {
        // A request item has no playback configuration — media3 builds it from a media id or a
        // search query alone. Anything with a URI is a real item from our own engine.
        if (localConfiguration != null) return null
        val query = requestMetadata.searchQuery
        val id = if (mediaId != MediaItem.DEFAULT_MEDIA_ID) mediaId else null
        if (query == null && id == null) return null
        return PendingRequest(id, query, requestMetadata.extras)
    }

    private val resolveRequest = Runnable { resolvePending() }

    private fun capture(items: List<MediaItem>): Boolean {
        val request = items.firstOrNull()?.asRequestOrNull() ?: return false
        pending = request
        handler.removeCallbacks(resolveRequest)
        handler.postDelayed(resolveRequest, RESOLVE_DELAY_MS)
        return true
    }

    private fun resolvePending() {
        val request = pending ?: return
        pending = null
        val extras = request.extras
        when {
            request.searchQuery != null && request.sawPlay -> {
                Timber.tag(TAG).d("routing play-from-search: '%s'", request.searchQuery)
                sessionCallback.handlePlayFromSearch(request.searchQuery, extras)
            }
            request.searchQuery != null -> {
                Timber.tag(TAG).d("routing prepare-from-search: '%s'", request.searchQuery)
                sessionCallback.handlePrepareFromSearch(request.searchQuery, extras)
            }
            request.sawPlay -> {
                Timber.tag(TAG).d("routing play-from-id: %s", request.mediaId)
                sessionCallback.handlePlayFromMediaId(request.mediaId, extras)
            }
            else -> {
                Timber.tag(TAG).d("routing prepare-from-id: %s", request.mediaId)
                sessionCallback.handlePrepareFromMediaId(request.mediaId, extras)
            }
        }
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        if (capture(listOf(mediaItem))) return
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (capture(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (capture(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (capture(mediaItems)) return
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (capture(mediaItems)) return
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (capture(mediaItems)) return
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun prepare() {
        // While a request is pending the item ExoPlayer holds is the *old* queue; preparing it would
        // restart the previous book under a "play this other thing" command.
        if (pending != null) {
            handler.removeCallbacks(resolveRequest)
            handler.postDelayed(resolveRequest, RESOLVE_DELAY_MS)
            return
        }
        super.prepare()
    }

    // endregion

    // region transport

    override fun play() {
        pending?.let {
            it.sawPlay = true
            // A media3 controller sends setMediaItem, prepare and play as three separate binder
            // calls, so `play` can land after the first resolve would have fired; the timer is
            // restarted so all three are still read as one request. (A legacy controller — Android
            // Auto, Assistant — issues all three inside a single main-loop message, so it never
            // needs this.)
            handler.removeCallbacks(resolveRequest)
            handler.postDelayed(resolveRequest, RESOLVE_DELAY_MS)
            return
        }
        when (policy) {
            TransportPolicy.ROUTE_TO_LISTENERS -> onAction(MediaSessionCallback.PLAY)
            TransportPolicy.APPLY_NATIVELY_AND_NOTIFY -> {
                Timber.tag(TAG).d("applying play natively (position=%d)", engine.position)
                engine.play(TransportReason.REMOTE)
                onAction(MediaSessionCallback.PLAY)
            }
        }
    }

    override fun pause() {
        when (policy) {
            TransportPolicy.ROUTE_TO_LISTENERS -> onAction(MediaSessionCallback.PAUSE)
            TransportPolicy.APPLY_NATIVELY_AND_NOTIFY -> {
                Timber.tag(TAG).d("applying pause natively (position=%d)", engine.position)
                engine.pause(TransportReason.REMOTE)
                onAction(MediaSessionCallback.PAUSE)
            }
        }
    }

    override fun stop() {
        when (policy) {
            TransportPolicy.ROUTE_TO_LISTENERS -> onAction(MediaSessionCallback.STOP)
            TransportPolicy.APPLY_NATIVELY_AND_NOTIFY -> {
                Timber.tag(TAG).d("applying stop natively (position=%d)", engine.position)
                engine.stop(TransportReason.REMOTE)
                onAction(MediaSessionCallback.STOP)
            }
        }
    }

    override fun seekTo(positionMs: Long) = routeSeek(C.INDEX_UNSET, positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = routeSeek(mediaItemIndex, positionMs)

    /**
     * A seek carrying a media-item index is two different commands wearing one name. Same index (or
     * none): a position seek, which is pure transport. A *different* index: "play that row of the
     * queue", which is a queue move — JS owns what the queue means, so it is routed exactly like a
     * tap on an Android Auto queue row, and the position is dropped because JS re-derives it.
     */
    private fun routeSeek(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != engine.exoPlayer.currentMediaItemIndex) {
            Timber.tag(TAG).d("routing seek to another queue item as skipToQueueItem: %d", mediaItemIndex)
            sessionCallback.handleSkipToQueueItem(mediaItemIndex.toLong())
            return
        }
        when (policy) {
            TransportPolicy.ROUTE_TO_LISTENERS -> onAction(MediaSessionCallback.SEEK(positionMs))
            TransportPolicy.APPLY_NATIVELY_AND_NOTIFY -> {
                val before = engine.position
                // Through the engine, never `exoPlayer.seekTo` directly: the engine is what disarms
                // a pending `setStopAt` (ExoPlayer delivers a message a seek jumped over rather than
                // dropping it) and what publishes the snapshot with `reason=remote`.
                engine.seek(positionMs, TimeUnit.MILLISECONDS, TransportReason.REMOTE)
                Timber.tag(TAG).d("applied seek natively: %d -> %d", before, engine.position)
                onAction(MediaSessionCallback.SEEK(positionMs))
            }
        }
    }

    override fun seekForward() {
        when (policy) {
            TransportPolicy.ROUTE_TO_LISTENERS -> onAction(MediaSessionCallback.FORWARD)
            TransportPolicy.APPLY_NATIVELY_AND_NOTIFY -> {
                val before = engine.position
                val increment = seekForwardIncrement
                engine.seekBy(increment, TimeUnit.MILLISECONDS, TransportReason.REMOTE)
                Timber.tag(TAG).d(
                    "applied seekForward natively: %d -> %d (+%d ms)", before, engine.position, increment
                )
                onAction(MediaSessionCallback.FORWARD)
            }
        }
    }

    override fun seekBack() {
        when (policy) {
            TransportPolicy.ROUTE_TO_LISTENERS -> onAction(MediaSessionCallback.REWIND)
            TransportPolicy.APPLY_NATIVELY_AND_NOTIFY -> {
                val before = engine.position
                val increment = seekBackIncrement
                engine.seekBy(-increment, TimeUnit.MILLISECONDS, TransportReason.REMOTE)
                Timber.tag(TAG).d(
                    "applied seekBack natively: %d -> %d (-%d ms)", before, engine.position, increment
                )
                onAction(MediaSessionCallback.REWIND)
            }
        }
    }

    // Next/previous are never applied natively: JS decides what they mean (chapter navigation, the
    // daily-date rule, the 20-second restart). Both the "seekTo" and the "seekToMediaItem" spellings
    // reach us — legacy controllers produce the first, media3 controllers the second.
    override fun seekToNext() = routeNext()

    override fun seekToNextMediaItem() = routeNext()

    override fun seekToPrevious() = routePrevious()

    override fun seekToPreviousMediaItem() = routePrevious()

    private fun routeNext() {
        Timber.tag(TAG).d("routing next to JS (never applied natively)")
        onAction(MediaSessionCallback.NEXT)
    }

    private fun routePrevious() {
        Timber.tag(TAG).d("routing previous to JS (never applied natively)")
        onAction(MediaSessionCallback.PREVIOUS)
    }

    /** "Tap a row in the Android Auto queue" — routed to JS as `onRemoteSkip{index}`, as before. */
    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (pending != null) return
        sessionCallback.handleSkipToQueueItem(mediaItemIndex.toLong())
    }

    override fun seekToDefaultPosition() {
        if (pending != null) return
        super.seekToDefaultPosition()
    }

    // endregion

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*ALWAYS_AVAILABLE).build()

    override fun isCommandAvailable(command: Int): Boolean =
        ALWAYS_AVAILABLE.contains(command) || super.isCommandAvailable(command)

    companion object {
        private const val TAG = "RNTP-Transport"

        /**
         * How long to wait after a request item before deciding play vs prepare.
         *
         * Zero (a plain `post`) is enough for a legacy controller, which drives
         * `setMediaItems` -> `prepare` -> `play` synchronously inside one main-loop message. A
         * media3 controller sends three separate binder calls, and a plain post can land between
         * them and turn a "play" into a "prepare". Small enough to be invisible on a voice command,
         * far smaller than the gap between a genuine PREPARE and the play that may follow it.
         */
        private const val RESOLVE_DELAY_MS = 150L

        private val ALWAYS_AVAILABLE = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
