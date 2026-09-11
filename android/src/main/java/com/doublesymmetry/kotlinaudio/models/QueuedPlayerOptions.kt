package com.doublesymmetry.kotlinaudio.models

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.doublesymmetry.kotlinaudio.players.BaseAudioPlayer

interface QueuedPlayerOptions : PlayerOptions {
    override var alwaysPauseOnInterruption: Boolean
    var repeatMode: RepeatMode
}

/**
 * Reads and writes the repeat mode through the engine rather than through a captured `ExoPlayer`:
 * the engine can rebuild its ExoPlayer when buffer options arrive late (an Android Auto cold start
 * builds the player before JS has called `setupPlayer`), and a captured reference would then be
 * writing into a released instance.
 */
@UnstableApi
class DefaultQueuedPlayerOptions(
    private val player: BaseAudioPlayer,
    override var alwaysPauseOnInterruption: Boolean = false,
) : QueuedPlayerOptions {
    override var repeatMode: RepeatMode
        get() {
            return when (player.exoPlayer.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            }
        }
        set(value) {
            when (value) {
                RepeatMode.ALL -> player.exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> player.exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                RepeatMode.OFF -> player.exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
}

enum class RepeatMode {
    OFF, ONE, ALL;

    companion object {
        fun fromOrdinal(ordinal: Int): RepeatMode {
            return when (ordinal) {
                0 -> OFF
                1 -> ONE
                2 -> ALL
                else -> error("Wrong ordinal")
            }
        }
    }
}
