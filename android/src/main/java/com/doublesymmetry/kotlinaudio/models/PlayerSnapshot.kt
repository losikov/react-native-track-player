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
)

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
