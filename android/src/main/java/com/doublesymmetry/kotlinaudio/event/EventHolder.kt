package com.doublesymmetry.kotlinaudio.event

/**
 * The engine's event surface.
 *
 * `notificationStateChange` is gone: media3's `MediaSessionService` posts, updates and removes the
 * notification and moves the service in and out of the foreground itself, so there is no longer a
 * notification lifecycle for anyone outside the engine to observe.
 */
class EventHolder internal constructor(private val playerEventHolder: PlayerEventHolder) {
    val audioItemTransition
        get() = playerEventHolder.audioItemTransition

    val onAudioFocusChanged
        get() = playerEventHolder.onAudioFocusChanged

    val onCommonMetadata
        get() = playerEventHolder.onCommonMetadata

    val onTimedMetadata
        get() = playerEventHolder.onTimedMetadata

    val onPlayerActionTriggeredExternally
        get() = playerEventHolder.onPlayerActionTriggeredExternally

    val playbackEnd
        get() = playerEventHolder.playbackEnd

    val playWhenReadyChange
        get() = playerEventHolder.playWhenReadyChange

    val positionChanged
        get() = playerEventHolder.positionChanged

    /**
     * Fires once whenever the position jumped for a reason no interval tick will report — a seek
     * (including a seek while paused) and a [com.doublesymmetry.kotlinaudio.players.BaseAudioPlayer.loadQueue].
     * `MusicService` turns it into one `onPlaybackProgressUpdated`.
     */
    val progressDiscontinuity
        get() = playerEventHolder.progressDiscontinuity

    /**
     * Fires once when playback reaches the position armed by
     * [com.doublesymmetry.kotlinaudio.players.BaseAudioPlayer.setStopAt]. The engine has already
     * paused; `MusicService` turns it into one `onPlaybackStopAtReached`.
     */
    val stopAtReached
        get() = playerEventHolder.stopAtReached

    val stateChange
        get() = playerEventHolder.stateChange

    val playbackError
        get() = playerEventHolder.playbackError
}
