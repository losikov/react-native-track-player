package com.doublesymmetry.kotlinaudio

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doublesymmetry.kotlinaudio.models.AAMediaSessionCallBack
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.Transport
import com.doublesymmetry.kotlinaudio.models.TransportPolicy
import com.doublesymmetry.kotlinaudio.models.TransportReason
import com.doublesymmetry.kotlinaudio.players.InterceptingPlayer
import com.doublesymmetry.kotlinaudio.utils.TestSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 4: the session player applies pure transport itself and tells JS afterwards.
 *
 * What is actually at risk here is not "does play() play" but the two things that make a *native*
 * command different from a JS one:
 *  - it must go through the engine, so a pending `setStopAt` is disarmed and the snapshot carries
 *    `reason=remote` — applying it on `exoPlayer` directly would leave both wrong;
 *  - it must still tell JS, exactly once, so the app's analytics and stored progress see it.
 *
 * And the commands JS owns the *meaning* of — next, previous, a seek naming another queue item —
 * must reach JS without the engine having moved.
 */
@RunWith(AndroidJUnit4::class)
class InterceptingPlayerTransportTest : QueuedAudioPlayerTestBase() {

    private val actions = mutableListOf<MediaSessionCallback>()
    private val skipped = mutableListOf<Long>()

    private val recordingSessionCallback = object : AAMediaSessionCallBack {
        override fun handlePlayFromMediaId(mediaId: String?, extras: Bundle?) = Unit
        override fun handlePlayFromSearch(query: String?, extras: Bundle?) = Unit
        override fun handlePrepareFromMediaId(mediaId: String?, extras: Bundle?) = Unit
        override fun handlePrepareFromSearch(query: String?, extras: Bundle?) = Unit
        override fun handleSkipToQueueItem(id: Long) {
            skipped.add(id)
        }
    }

    /** The session player as `MusicService.configureSessionPlayer` builds it. */
    private fun sessionPlayer(): InterceptingPlayer {
        val player = InterceptingPlayer(testPlayer, recordingSessionCallback) { actions.add(it) }
        player.policy = TransportPolicy.APPLY_NATIVELY_AND_NOTIFY
        player.seekForwardIncrementOverrideMs = 10_000
        player.seekBackIncrementOverrideMs = 10_000
        return player
    }

    private fun loadPaused(startPositionMs: Long = 0) {
        testPlayer.loadQueue(
            items = tracks,
            startIndex = 1,
            startPositionMs = startPositionMs,
            playWhenReady = false,
        )
    }

    @Test
    fun Seek_whenAppliedNatively_thenDisarmsAnArmedStopAt() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()
        testPlayer.setStopAt(1000)
        assertEquals(1000L, testPlayer.stopAtPosition)

        // Past the armed position: the case that matters, because ExoPlayer delivers a pending
        // message that a seek jumped over rather than dropping it.
        session.seekTo(2000)

        assertNull("a native seek left the stopAt armed", testPlayer.stopAtPosition)
        assertEquals(2000L, testPlayer.position)
        assertEquals(1, actions.size)
        assertEquals(2000L, (actions.single() as MediaSessionCallback.SEEK).positionMs)
    }

    @Test
    fun Seek_whenAppliedNatively_thenSnapshotSaysRemote() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()

        session.seekTo(2500)

        assertEquals(2500L, testPlayer.state.value.positionMs)
        assertEquals(TransportReason.REMOTE, testPlayer.state.value.transportReason)
    }

    @Test
    fun SeekForward_whenAppliedNatively_thenMovesExactlyOneIncrement() = runBlocking(Dispatchers.Main) {
        loadPaused(startPositionMs = 1000)
        val session = sessionPlayer()
        val before = testPlayer.position

        session.seekForward()

        assertEquals(before + 10_000, testPlayer.position)
        assertEquals(MediaSessionCallback.FORWARD, actions.single())
    }

    @Test
    fun SeekBack_whenAppliedNatively_thenMovesExactlyOneIncrementAndDisarmsStopAt() =
        runBlocking(Dispatchers.Main) {
            loadPaused(startPositionMs = 12_000)
            val session = sessionPlayer()
            testPlayer.setStopAt(13_000)
            val before = testPlayer.position

            session.seekBack()

            assertEquals(before - 10_000, testPlayer.position)
            assertNull(testPlayer.stopAtPosition)
            assertEquals(MediaSessionCallback.REWIND, actions.single())
        }

    @Test
    fun Play_whenAppliedNatively_thenIntentIsSetAndJsIsTold() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()

        session.play()

        // Masked: the intent and the snapshot are right before the item is even ready, which is what
        // makes the lock-screen button agree with the app's button instantly.
        assertTrue(testPlayer.playWhenReady)
        assertEquals(Transport.PLAYING, testPlayer.state.value.transport)
        assertEquals(TransportReason.REMOTE, testPlayer.state.value.transportReason)
        assertEquals(MediaSessionCallback.PLAY, actions.single())
    }

    @Test
    fun Pause_whenAppliedNatively_thenIntentIsClearedAndJsIsTold() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()
        session.play()
        actions.clear()

        session.pause()

        assertFalse(testPlayer.playWhenReady)
        assertEquals(Transport.PAUSED, testPlayer.state.value.transport)
        assertEquals(TransportReason.REMOTE, testPlayer.state.value.transportReason)
        assertEquals(MediaSessionCallback.PAUSE, actions.single())
    }

    @Test
    fun Stop_whenAppliedNatively_thenDisarmsStopAtAndJsIsTold() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()
        testPlayer.setStopAt(1000)

        session.stop()

        assertFalse(testPlayer.playWhenReady)
        assertNull(testPlayer.stopAtPosition)
        assertEquals(MediaSessionCallback.STOP, actions.single())
    }

    @Test
    fun NextAndPrevious_areNeverAppliedNatively() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()

        session.seekToNext()
        session.seekToNextMediaItem()
        session.seekToPrevious()
        session.seekToPreviousMediaItem()

        // JS owns what they mean, so the queue must not have moved.
        assertEquals(1, testPlayer.currentIndex)
        assertEquals(
            listOf(
                MediaSessionCallback.NEXT,
                MediaSessionCallback.NEXT,
                MediaSessionCallback.PREVIOUS,
                MediaSessionCallback.PREVIOUS,
            ),
            actions.toList()
        )
    }

    @Test
    fun SeekNamingAnotherQueueItem_isRoutedAsSkipToQueueItem() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()

        session.seekTo(2, 0)

        // A queue move, not a position seek: JS is asked to do it and the engine has not moved.
        assertEquals(listOf(2L), skipped.toList())
        assertTrue("a queue move was applied natively", actions.isEmpty())
        assertEquals(1, testPlayer.currentIndex)
    }

    @Test
    fun SeekNamingTheCurrentQueueItem_isAPositionSeek() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()

        session.seekTo(1, 3000)

        assertTrue("a same-item seek was routed as a queue move", skipped.isEmpty())
        assertEquals(3000L, testPlayer.position)
        assertEquals(3000L, (actions.single() as MediaSessionCallback.SEEK).positionMs)
    }

    @Test
    fun RouteToListeners_appliesNothing() = runBlocking(Dispatchers.Main) {
        loadPaused()
        val session = sessionPlayer()
        session.policy = TransportPolicy.ROUTE_TO_LISTENERS

        session.play()
        session.seekTo(4000)

        assertFalse("ROUTE_TO_LISTENERS applied the command", testPlayer.playWhenReady)
        assertEquals(0L, testPlayer.position)
        assertEquals(2, actions.size)
    }
}
