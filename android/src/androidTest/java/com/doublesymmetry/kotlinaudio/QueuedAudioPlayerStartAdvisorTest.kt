package com.doublesymmetry.kotlinaudio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.CacheConfig
import com.doublesymmetry.kotlinaudio.models.PlaybackStartAdvisor
import com.doublesymmetry.kotlinaudio.models.PlayerConfig
import com.doublesymmetry.kotlinaudio.models.Suppression
import com.doublesymmetry.kotlinaudio.models.Transport
import com.doublesymmetry.kotlinaudio.models.TransportReason
import com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer
import com.doublesymmetry.kotlinaudio.utils.NoopMediaSessionCallback
import com.doublesymmetry.kotlinaudio.utils.TestSound
import com.doublesymmetry.kotlinaudio.utils.eventually
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The engine's half of "where does playback start": it asks the [PlaybackStartAdvisor] the app
 * registered, passes what it knows, and plays from the answer, holding no rule of its own. With no
 * advisor everything behaves as before.
 */
@RunWith(AndroidJUnit4::class)
class QueuedAudioPlayerStartAdvisorTest : QueuedAudioPlayerTestBase() {
    private class Call(
        val positionMs: Long,
        val pausedForMs: Long?,
        val seekedDuringPause: Boolean,
        val restore: Boolean,
    )

    /** Answers [answerMs] (or the position when null) and remembers every question. */
    private class FakeAdvisor(
        val answerMs: Long? = null,
        val holdMs: Long? = null,
    ) : PlaybackStartAdvisor {
        val calls = mutableListOf<Call>()

        override fun startPositionMs(
            item: AudioItem,
            positionMs: Long,
            pausedForMs: Long?,
            seekedDuringPause: Boolean,
            restore: Boolean,
        ): Long {
            calls += Call(positionMs, pausedForMs, seekedDuringPause, restore)
            return answerMs ?: positionMs
        }

        override fun holdBecomesPauseAfterMs(): Long? = holdMs
    }

    /** Loaded at 3 s, playing, then paused for [pauseMs]. */
    private suspend fun playThenPause(pauseMs: Long = 300) {
        testPlayer.loadQueue(listOf(TestSound.fiveSeconds), 0, 3000, playWhenReady = true)
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() { assertTrue(testPlayer.isPlaying) })
        testPlayer.pause()
        delay(pauseMs)
    }

    // region play after a pause

    @Test
    fun PlayAfterPause_givenNoAdvisor_thenPlaysFromWhereItStopped() =
        runBlocking(Dispatchers.Main) {
            playThenPause()
            val stopped = testPlayer.position

            testPlayer.play()

            assertEquals(stopped, testPlayer.position)
            assertEquals(0.0, testPlayer.state.value.snapSec, 0.0)
        }

    @Test
    fun PlayAfterPause_asksTheAdvisorAndStartsFromItsAnswer() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor(answerMs = 1000)
            testPlayer.startAdvisor = advisor
            playThenPause(pauseMs = 300)
            val stopped = testPlayer.position

            testPlayer.play()

            val call = advisor.calls.single()
            assertEquals(stopped, call.positionMs)
            assertTrue("paused for ${call.pausedForMs} ms", (call.pausedForMs ?: 0) >= 250)
            assertFalse(call.seekedDuringPause)
            assertFalse(call.restore)
            assertEquals(1000L, testPlayer.position)
            assertEquals((stopped - 1000) / 1000.0, testPlayer.state.value.snapSec, 0.001)
            assertEquals(Transport.PLAYING, testPlayer.state.value.transport)
        }

    @Test
    fun PlayAfterPause_givenASeekDuringIt_thenTellsTheAdvisor() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor()
            testPlayer.startAdvisor = advisor
            playThenPause()
            testPlayer.seek(2000, TimeUnit.MILLISECONDS)

            testPlayer.play()

            assertTrue(advisor.calls.single().seekedDuringPause)
            assertEquals(2000L, testPlayer.position)
        }

    @Test
    fun PlayAfterPause_givenSetPlayWhenReadyThenPlay_thenAsksOnce() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor(answerMs = 1000)
            testPlayer.startAdvisor = advisor
            playThenPause()

            testPlayer.playWhenReady = true
            testPlayer.play()

            assertEquals(1, advisor.calls.size)
        }

    @Test
    fun Play_givenNoPauseBefore_thenTellsTheAdvisorThereWasNone() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor()
            testPlayer.startAdvisor = advisor
            testPlayer.loadQueue(listOf(TestSound.fiveSeconds), 0, 3000, playWhenReady = false)

            testPlayer.play()

            assertNull(advisor.calls.single().pausedForMs)
            assertEquals(3000L, testPlayer.position)
        }

    // endregion

    // region restore

    @Test
    fun LoadQueue_givenRestore_thenStartsFromTheAdvisorsAnswer() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor(answerMs = 1000)
            testPlayer.startAdvisor = advisor

            testPlayer.loadQueue(tracks, 1, 3000, playWhenReady = false, restore = true)

            val call = advisor.calls.single()
            assertTrue(call.restore)
            assertEquals(3000L, call.positionMs)
            assertNull(call.pausedForMs)
            assertEquals(1000L, testPlayer.position)
            assertEquals(2.0, testPlayer.state.value.snapSec, 0.001)
        }

    @Test
    fun LoadQueue_givenNoRestore_thenDoesNotAsk() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor(answerMs = 1000)
            testPlayer.startAdvisor = advisor

            testPlayer.loadQueue(tracks, 1, 3000, playWhenReady = false)

            assertTrue(advisor.calls.isEmpty())
            assertEquals(3000L, testPlayer.position)
        }

    @Test
    fun JumpToItem_givenRestore_thenStartsFromTheAdvisorsAnswer() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor(answerMs = 1000)
            testPlayer.loadQueue(tracks, 0, 0, playWhenReady = false)
            testPlayer.startAdvisor = advisor

            testPlayer.jumpToItem(1, 3000, restore = true)

            assertTrue(advisor.calls.single().restore)
            assertEquals(1, testPlayer.currentIndex)
            assertEquals(1000L, testPlayer.position)
        }

    @Test
    fun Seek_givenRestore_thenSeeksToTheAdvisorsAnswer() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor(answerMs = 1000)
            testPlayer.loadQueue(listOf(TestSound.fiveSeconds), 0, 0, playWhenReady = false)
            testPlayer.startAdvisor = advisor

            testPlayer.seek(3000, TimeUnit.MILLISECONDS, restore = true)

            assertTrue(advisor.calls.single().restore)
            assertEquals(1000L, testPlayer.position)
        }

    // endregion

    // region a call holding playback

    /** A player that handles audio focus, as the app's does, so another request holds it. */
    private fun focusPlayer(advisor: FakeAdvisor): QueuedAudioPlayer {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return QueuedAudioPlayer(
            context,
            playerConfig = PlayerConfig(handleAudioFocus = true),
            cacheConfig = CacheConfig(maxCacheSize = (1024 * 50).toLong(), identifier = "${testName.methodName}-focus"),
            mediaSessionCallback = NoopMediaSessionCallback,
        ).also {
            it.volume = 0f
            it.startAdvisor = advisor
        }
    }

    /** What a phone call does to the player: another audio user takes focus for a while. */
    private fun takeTransientFocus(): () -> Unit {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
                ).setOnAudioFocusChangeListener {}
                .build()
        audioManager.requestAudioFocus(request)
        return { audioManager.abandonAudioFocusRequest(request) }
    }

    @Test
    fun Hold_givenShorterThanTheLimit_thenResumesByItself() =
        runBlocking(Dispatchers.Main) {
            val player = focusPlayer(FakeAdvisor(holdMs = 1500))
            try {
                player.loadQueue(listOf(TestSound.fiveSeconds), 0, 0, playWhenReady = true)
                eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() { assertTrue(player.isPlaying) })
                val release = takeTransientFocus()
                eventually(Duration.ofSeconds(5), Dispatchers.Main, fun() {
                    assertEquals(Suppression.TRANSIENT_AUDIO_FOCUS_LOSS, player.state.value.suppression)
                })
                delay(500)
                release()

                eventually(Duration.ofSeconds(5), Dispatchers.Main, fun() { assertTrue(player.isPlaying) })
                assertTrue(player.playWhenReady)
            } finally {
                player.destroy()
            }
        }

    @Test
    fun Hold_givenLongerThanTheLimit_thenBecomesAPauseTimedFromItsStart() =
        runBlocking(Dispatchers.Main) {
            val advisor = FakeAdvisor(holdMs = 1000)
            val player = focusPlayer(advisor)
            try {
                player.loadQueue(listOf(TestSound.fiveSeconds), 0, 0, playWhenReady = true)
                eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() { assertTrue(player.isPlaying) })
                val release = takeTransientFocus()
                eventually(Duration.ofSeconds(5), Dispatchers.Main, fun() {
                    assertFalse(player.playWhenReady)
                })
                assertEquals(Transport.PAUSED, player.state.value.transport)
                assertEquals(TransportReason.AUDIO_FOCUS_LOSS, player.state.value.transportReason)
                release()
                delay(500)
                assertFalse("the call ended and playback resumed by itself", player.playWhenReady)

                player.play()

                val paused = advisor.calls.single().pausedForMs
                assertNotNull(paused)
                assertTrue("paused for $paused ms, counted from the hold", paused!! >= 1400)
            } finally {
                player.destroy()
            }
        }

    // endregion
}
