package com.doublesymmetry.kotlinaudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.Transport
import com.doublesymmetry.kotlinaudio.models.TransportReason
import com.doublesymmetry.kotlinaudio.utils.TestSound
import com.doublesymmetry.kotlinaudio.utils.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The two engine commands step 3 puts on the bridge: the atomic load and the pending stop.
 *
 * Both are about *when* a value is observable, not just what it ends up being, so the assertions
 * here are deliberately made on the instant the command returns rather than after settling:
 *  - `loadQueue` has to report the start position immediately, because the bug it fixes is JS
 *    writing the 0 that the old add-then-seek pair published in between;
 *  - `setStopAt` has to disarm itself *before* a seek reaches the player, because ExoPlayer delivers
 *    a pending message whose position a seek jumps over rather than dropping it.
 */
@RunWith(AndroidJUnit4::class)
class QueuedAudioPlayerLoadQueueTest : QueuedAudioPlayerTestBase() {

    // region loadQueue

    @Test
    fun LoadQueue_givenStartPosition_thenNeverReportsZeroBeforeTheTarget() =
        runBlocking(Dispatchers.Main) {
            testPlayer.loadQueue(
                items = tracks,
                startIndex = 1,
                startPositionMs = 2000,
                playWhenReady = false,
            )

            // Masked: ExoPlayer answers with the requested position the instant `setMediaItems`
            // returns, long before the item is ready.
            assertEquals(1, testPlayer.currentIndex)
            assertEquals(2000L, testPlayer.position)
            assertEquals(2000L, testPlayer.state.value.positionMs)
            assertEquals(3, testPlayer.state.value.queueSize)
            assertEquals(1, testPlayer.state.value.index)

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertEquals(AudioPlayerState.READY, testPlayer.playerState)
                assertTrue("position fell back to the top of the track", testPlayer.position >= 2000)
            })
        }

    @Test
    fun LoadQueue_givenPlayWhenReadyFalse_thenIntentIsPausedAndNothingPlays() =
        runBlocking(Dispatchers.Main) {
            testPlayer.loadQueue(items = tracks, startIndex = 0, startPositionMs = 0, playWhenReady = false)

            assertFalse(testPlayer.playWhenReady)
            assertEquals(Transport.PAUSED, testPlayer.state.value.transport)

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertEquals(AudioPlayerState.READY, testPlayer.playerState)
            })
            assertFalse(testPlayer.isPlaying)
        }

    @Test
    fun LoadQueue_givenPlayWhenReadyTrue_thenTransportIsPlayingImmediately() =
        runBlocking(Dispatchers.Main) {
            testPlayer.loadQueue(
                items = listOf(TestSound.fiveSeconds),
                startIndex = 0,
                startPositionMs = 0,
                playWhenReady = true,
            )

            // The whole point of binding the button to `transport`: it is "playing" from the call,
            // not from whenever the item finishes loading.
            assertTrue(testPlayer.playWhenReady)
            assertEquals(Transport.PLAYING, testPlayer.state.value.transport)

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.isPlaying)
                assertEquals(Transport.PLAYING, testPlayer.state.value.transport)
            })
        }

    @Test
    fun LoadQueue_givenAQueueAlreadyLoaded_thenReplacesItWholesale() = runBlocking(Dispatchers.Main) {
        testPlayer.add(tracks)
        testPlayer.loadQueue(
            items = listOf(TestSound.fiveSeconds2),
            startIndex = 0,
            startPositionMs = 0,
            playWhenReady = false,
        )

        assertEquals(1, testPlayer.items.size)
        assertEquals(TestSound.fiveSeconds2, testPlayer.currentItem)
    }

    // endregion

    // region stopAt

    @Test
    fun StopAt_whenReached_thenPausesWithStopAtReasonAndEmits() = runBlocking(Dispatchers.Main) {
        val reached = mutableListOf<Long>()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            testPlayer.event.stopAtReached.collect { reached.add(it) }
        }

        testPlayer.loadQueue(
            items = listOf(TestSound.fiveSeconds),
            startIndex = 0,
            startPositionMs = 0,
            playWhenReady = true,
        )
        testPlayer.setStopAt(1500)
        assertEquals(1500L, testPlayer.stopAtPosition)

        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
            assertTrue("stopAtReached never fired", reached.isNotEmpty())
        })

        assertFalse("the engine kept playing past the stop", testPlayer.isPlaying)
        assertFalse(testPlayer.playWhenReady)
        assertEquals(Transport.PAUSED, testPlayer.state.value.transport)
        assertEquals(TransportReason.STOP_AT, testPlayer.state.value.transportReason)
        assertTrue("paused before the stop position", testPlayer.position >= 1400)
        assertTrue("overshot the stop position", testPlayer.position < 3000)
        // Disarmed by delivery: nothing pauses a second time.
        assertNull(testPlayer.stopAtPosition)
        assertEquals(0, testPlayer.currentIndex)

        collector.cancel()
    }

    @Test
    fun StopAt_whenCleared_thenPlaybackRunsPastThePosition() = runBlocking(Dispatchers.Main) {
        testPlayer.loadQueue(
            items = listOf(TestSound.fiveSeconds),
            startIndex = 0,
            startPositionMs = 0,
            playWhenReady = false,
        )
        testPlayer.setStopAt(1000)
        assertEquals(1000L, testPlayer.stopAtPosition)

        testPlayer.clearStopAt()
        assertNull(testPlayer.stopAtPosition)

        testPlayer.play()
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
            assertTrue("playback stopped at a cleared stopAt", testPlayer.position > 1600)
        })
        assertTrue(testPlayer.isPlaying)
        assertEquals(Transport.PLAYING, testPlayer.state.value.transport)
    }

    @Test
    fun StopAt_whenSeeked_thenIsDisarmed() = runBlocking(Dispatchers.Main) {
        testPlayer.loadQueue(
            items = listOf(TestSound.fiveSeconds),
            startIndex = 0,
            startPositionMs = 0,
            playWhenReady = false,
        )
        testPlayer.setStopAt(1000)
        assertEquals(1000L, testPlayer.stopAtPosition)

        // A seek *past* the armed position is the case that matters: ExoPlayer would otherwise
        // deliver the message the seek jumped over.
        testPlayer.seek(2000, TimeUnit.MILLISECONDS)
        assertNull(testPlayer.stopAtPosition)

        testPlayer.play()
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
            assertTrue("playback stopped at a seek-cleared stopAt", testPlayer.position > 2600)
        })
        assertTrue(testPlayer.isPlaying)
    }

    @Test
    fun StopAt_whenLoadQueueRuns_thenIsDisarmed() = runBlocking(Dispatchers.Main) {
        testPlayer.loadQueue(
            items = listOf(TestSound.fiveSeconds),
            startIndex = 0,
            startPositionMs = 0,
            playWhenReady = false,
        )
        testPlayer.setStopAt(1000)

        testPlayer.loadQueue(
            items = listOf(TestSound.fiveSeconds2),
            startIndex = 0,
            startPositionMs = 0,
            playWhenReady = false,
        )
        assertNull(testPlayer.stopAtPosition)
    }

    // endregion
}
