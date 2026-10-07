package com.doublesymmetry.kotlinaudio.models

/**
 * The engine state model ("PlayerCore") installed in step 2 of the media3 migration.
 *
 * The legacy [AudioPlayerState] mirrors raw ExoPlayer readiness, which is why the play/pause button
 * in JS flickers through LOADING -> READY -> BUFFERING -> PLAYING on every load. [Transport] is the
 * answer to the only question that button asks — "is this thing meant to be playing?" — and is
 * deliberately *not* derived from readiness.
 *
 * Nothing in step 2 consumes [Transport] yet: [AudioPlayerState] is still what
 * `event.stateChange` carries and what the `state` string in `onPlaybackState` is derived from, bit
 * for bit. The snapshot rides alongside as additive keys so step 3 can move JS onto it.
 */
enum class Readiness {
    IDLE,
    LOADING,
    BUFFERING,
    READY,
    ENDED,
}

/** What the play/pause button shows. */
enum class Transport {
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

/** Why [Transport] last changed. */
enum class TransportReason {
    USER,
    REMOTE,
    ERROR,
    END_OF_QUEUE,
    AUDIO_FOCUS_LOSS,
    STOP_AT,
    SYSTEM,
}

/**
 * Playback is intended but the system is holding it back. A phone call is a *suppression*, not a
 * pause: the button keeps showing "pause" and playback resumes by itself when focus returns.
 */
enum class Suppression {
    NONE,
    TRANSIENT_AUDIO_FOCUS_LOSS,
    UNSUITABLE_OUTPUT,
}

data class PlayerSnapshot(
    val transport: Transport = Transport.PAUSED,
    val transportReason: TransportReason = TransportReason.SYSTEM,
    /** Raw intent — `ExoPlayer.playWhenReady`, which is masked and therefore synchronous. */
    val playWhenReady: Boolean = false,
    val readiness: Readiness = Readiness.IDLE,
    val isPlaying: Boolean = false,
    val suppression: Suppression = Suppression.NONE,
    val index: Int? = null,
    val queueSize: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val rate: Float = 1f,
    val volume: Float = 1f,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Sticky until the next load or a play-after-error. */
    val error: PlaybackError? = null,
    /**
     * How far back the [PlaybackStartAdvisor] moved the start of the playback in progress, in seconds;
     * 0 when it did not. Cleared by the next pause and by any other move of the playhead.
     */
    val snapSec: Double = 0.0,
)

/**
 * App code that decides where playback starts, as the engine sees it: about a queue item, in
 * milliseconds. The engine holds no rule of its own: before every start of playback it asks, passes
 * what it knows, and plays from the answer. `MusicService` adapts the app's `StartPositionAdvisor` to
 * this; with none, every start plays from where it is. The iOS twin is `PlayerStartPositionAdvisor`.
 *
 * Asked on Main, on two occasions: a play after a pause, from any source, with `restore` false — it
 * must answer from memory — and a load, skip or seek whose caller said its position was restored from
 * storage, where it may take a few milliseconds to read what it needs.
 */
interface PlaybackStartAdvisor {
    /**
     * @param pausedForMs how long playback has been paused, or null when this start does not follow a
     *   pause (a load, a skip, the first play after either).
     * @param seekedDuringPause a seek, skip, load or stop happened since that pause began.
     * @param restore the command said its position was restored from storage.
     * @return the position to start from; [positionMs] to leave it where it is.
     */
    fun startPositionMs(
        item: AudioItem,
        positionMs: Long,
        pausedForMs: Long?,
        seekedDuringPause: Boolean,
        restore: Boolean,
    ): Long

    /**
     * How long a transient audio-focus loss (a phone call) may hold playback back before the engine
     * turns it into a real pause, timed from when the hold began. Null keeps today's behaviour: the
     * hold lasts as long as the loss, and playback resumes by itself when it ends.
     */
    fun holdBecomesPauseAfterMs(): Long?
}

/**
 * What [com.doublesymmetry.kotlinaudio.players.InterceptingPlayer] does with a transport command
 * that arrives from the media session (notification, Bluetooth, Android Auto, Assistant).
 *
 * [ROUTE_TO_LISTENERS] is exactly what the ExoPlayer 2 build did, and what step 2 used for
 * everything: the command becomes an `onPlayerActionTriggeredExternally` event, `MusicService`
 * forwards it to JS as `onRemotePlay`/`onRemotePause`/..., and JS issues the real command back
 * through the module — so nothing external can control playback while JS is asleep or dead.
 *
 * Step 4 runs the session on [APPLY_NATIVELY_AND_NOTIFY]: play, pause, stop, seek and the ±jumps are
 * applied by the engine synchronously and *then* announced to JS, which records them (analytics,
 * stored progress) without re-issuing them. JS owns the *meaning* of next/previous (chapter and
 * daily-date navigation, the 20-second restart) and of a seek naming another queue item, so those
 * stay routed forever.
 */
enum class TransportPolicy {
    ROUTE_TO_LISTENERS,
    APPLY_NATIVELY_AND_NOTIFY,
}
