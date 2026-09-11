package com.doublesymmetry.trackplayer.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.doublesymmetry.kotlinaudio.models.Capability
import com.doublesymmetry.trackplayer.R
import com.google.common.collect.ImmutableList

/**
 * The notification, rebuilt on media3's provider.
 *
 * `DefaultMediaNotificationProvider` posts the notification, keeps it in step with the player and
 * moves the service in and out of the foreground — all of which the deleted 903-line
 * `NotificationManager` and the 108-line `setupForegrounding()` state machine used to do by hand.
 * The only thing left to say is *which buttons*, because the app configures them from JS
 * (`notificationCapabilities` / `compactCapabilities`) and media3's default set is
 * previous / play-pause / next with no ±10 s jumps.
 *
 * Button order reproduces `PlayerNotificationManager`'s: previous, rewind, play/pause, forward,
 * next, stop. The compact view (the collapsed notification, three slots) takes whichever of those
 * the app listed in `compactCapabilities`, in the same order.
 *
 * Channel id and notification id are the values KotlinAudio used, so an upgrading install keeps the
 * channel the user has already configured rather than silently getting a second one.
 */
@UnstableApi
class RntpNotificationProvider(context: Context) : DefaultMediaNotificationProvider(
    context,
    { NOTIFICATION_ID },
    CHANNEL_ID,
    R.string.playback_channel_name,
) {
    /** Empty until JS calls `updateOptions`; media3's defaults apply until then. */
    var notificationCapabilities: List<Capability> = emptyList()
    var compactCapabilities: List<Capability> = emptyList()

    var playIcon: Int? = null
    var pauseIcon: Int? = null
    var stopIcon: Int? = null
    var nextIcon: Int? = null
    var previousIcon: Int? = null
    var forwardIcon: Int = R.drawable.forward
    var rewindIcon: Int = R.drawable.rewind

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        if (notificationCapabilities.isEmpty()) {
            return super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
        }

        val buttons = mutableListOf<CommandButton>()
        var compactSlot = 0

        fun add(capability: Capability, icon: Int, iconResId: Int?, command: Int, name: String) {
            if (!notificationCapabilities.contains(capability)) return
            val extras = Bundle()
            if (compactCapabilities.contains(capability) && compactSlot < MAX_COMPACT_SLOTS) {
                extras.putInt(COMMAND_KEY_COMPACT_VIEW_INDEX, compactSlot++)
            }
            buttons += CommandButton.Builder(icon)
                .setPlayerCommand(command)
                .setDisplayName(name)
                .apply { iconResId?.let { setCustomIconResId(it) } }
                .setExtras(extras)
                .setEnabled(true)
                .build()
        }

        add(Capability.SKIP_TO_PREVIOUS, CommandButton.ICON_PREVIOUS, previousIcon, Player.COMMAND_SEEK_TO_PREVIOUS, "Previous")
        add(Capability.JUMP_BACKWARD, CommandButton.ICON_SKIP_BACK_10, rewindIcon, Player.COMMAND_SEEK_BACK, "Rewind")
        // Play and Pause are one button; the app lists both capabilities, so only the first of the
        // pair that is present creates it.
        val playPause = if (notificationCapabilities.contains(Capability.PLAY)) Capability.PLAY else Capability.PAUSE
        add(
            playPause,
            if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
            if (showPauseButton) pauseIcon else playIcon,
            Player.COMMAND_PLAY_PAUSE,
            if (showPauseButton) "Pause" else "Play",
        )
        add(Capability.JUMP_FORWARD, CommandButton.ICON_SKIP_FORWARD_10, forwardIcon, Player.COMMAND_SEEK_FORWARD, "Forward")
        add(Capability.SKIP_TO_NEXT, CommandButton.ICON_NEXT, nextIcon, Player.COMMAND_SEEK_TO_NEXT, "Next")
        add(Capability.STOP, CommandButton.ICON_STOP, stopIcon, Player.COMMAND_STOP, "Stop")

        return ImmutableList.copyOf(buttons)
    }

    companion object {
        /** KotlinAudio's values — see `NotificationManager.CHANNEL_ID` in the vendored history. */
        const val CHANNEL_ID = "kotlin_audio_player"
        const val NOTIFICATION_ID = 1

        private const val MAX_COMPACT_SLOTS = 3
    }
}
