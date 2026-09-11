package com.doublesymmetry.kotlinaudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.CacheConfig
import com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer
import com.doublesymmetry.kotlinaudio.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.time.Duration

// Converted from JUnit 5 (see react-native-track-player/android/src/main/java/com/doublesymmetry/kotlinaudio/NOTICE.md).
// `@Nested inner class Foo { @Test fun bar() }` groups are flattened to `Foo_bar()` at top level;
// none of these nested groups had their own `@BeforeEach`, so a single `@Before` still covers all of
// them, matching the original JUnit 5 setup/teardown timing.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AudioPlayerTest {
    private lateinit var testPlayer: QueuedAudioPlayer
    private lateinit var states: MutableList<String>
    private lateinit var statesWithoutBuffering: MutableList<String>

    @get:Rule
    val testName = TestName()

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Construction (not just the volume write below) has to happen on a thread with a
        // prepared Looper: BaseAudioPlayer's `init` builds a MediaSessionCompat, whose
        // constructor creates a Handler bound to Looper.myLooper() of the calling thread.
        // Under JUnit 5 + the mannodermaus android-test-runner this apparently ran on a thread
        // that already had one; androidx.test's plain AndroidJUnitRunner does not prepare a
        // Looper on its instrumentation thread, so construction crashes with "Can't create
        // handler inside thread ... that has not called Looper.prepare()" unless it is moved to
        // Dispatchers.Main here.
        runBlocking(Dispatchers.Main) {
            testPlayer = QueuedAudioPlayer(
                appContext,
                cacheConfig = CacheConfig(maxCacheSize = (1024 * 50).toLong(), identifier = testName.methodName),
                mediaSessionCallback = NoopMediaSessionCallback
            )
            testPlayer.volume = 0f
        }
        states = mutableListOf()
        statesWithoutBuffering = mutableListOf()
        testPlayer.event.stateChange.map {
            if (
                // Skipping buffering and ready since it depends on circumstances when and how often
                // rebuffering.
                it != AudioPlayerState.BUFFERING &&
                it != AudioPlayerState.READY &&
                // Also make sure we aren't adding duplicate states (due to skipping those above)
                (states.size == 0 || states.last() != it.toString())
            ) {
                states.add(it.toString())
            }

            if (
                // Skipping buffering:
                it != AudioPlayerState.BUFFERING
            ) {
                statesWithoutBuffering.add(it.toString())
            }
        }.stateIn(
            CoroutineScope(Dispatchers.Default),
            SharingStarted.Eagerly,
            emptyList<AudioPlayerState>()
        )
        testPlayer.event.stateChange.waitUntil { it == AudioPlayerState.IDLE }
    }

    // State

    @Test
    fun State_givenNewPlayer_thenShouldBeIdle() = runBlocking(Dispatchers.Main) {
        assertEquals(AudioPlayerState.IDLE, testPlayer.playerState)
    }

    @Test
    fun State_givenLoadSource_thenShouldBeReady() = runBlocking(Dispatchers.Main) {
        testPlayer.load(TestSound.default)
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertEquals(mutableListOf<String>("IDLE", "LOADING", "READY"), statesWithoutBuffering);
        })
    }

    @Test
    fun State_givenLoadSourceAndPlayWhenReady_thenShouldBePlaying() = runBlocking(Dispatchers.Main) {
        testPlayer.load(TestSound.fiveSeconds, true)
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertEquals(mutableListOf<String>("IDLE", "LOADING", "PLAYING"), states);
            assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
        })
    }

    @Test
    fun State_givenLoadSourceAndPlayWhenReadyAfterLoadSource_thenShouldBePlaying() = runBlocking(Dispatchers.Main) {
        testPlayer.load(TestSound.fiveSeconds, true)
        launchWithTimeoutSync(this) {
            testPlayer.event.stateChange
                .waitUntil { it == AudioPlayerState.PLAYING }
                .collect {
                    testPlayer.load(TestSound.fiveSeconds2, true)
                }
        }
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertEquals(mutableListOf<String>("IDLE", "LOADING", "PLAYING", "LOADING", "PLAYING"), states);
        })
    }

    @Test
    fun State_givenPlaySource_thenShouldBePlaying() = runBlocking(Dispatchers.Main) {
        testPlayer.play()
        testPlayer.load(TestSound.default)
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertEquals(mutableListOf<String>("IDLE", "LOADING", "PLAYING"), states.subList(0, 3));
        })
    }

    @Test
    fun State_givenPlaySource_thenShouldBePlayingAndFinallyEnded() = runBlocking(Dispatchers.Main) {
        testPlayer.play()
        testPlayer.load(TestSound.default)
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertEquals(mutableListOf<String>("IDLE", "LOADING", "PLAYING", "ENDED"), states.subList(0, 4));
        })
    }

    @Test
    fun State_givenPausingSource_thenShouldBePaused() = runBlocking(Dispatchers.Main) {
        testPlayer.load(TestSound.fiveSeconds, playWhenReady = true)

        launchWithTimeoutSync(this) {
            testPlayer.event.stateChange
                .waitUntil { it == AudioPlayerState.PLAYING }
                .collect { testPlayer.pause() }
        }

        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertEquals(mutableListOf<String>("IDLE", "LOADING", "PLAYING", "PAUSED"), states);
            assertEquals(AudioPlayerState.PAUSED, testPlayer.playerState)
        })
    }

    @Test
    fun State_givenStoppingSource_thenShouldBeIdle() = runBlocking(Dispatchers.Main) {
        testPlayer.load(TestSound.long, playWhenReady = true)

        var hasBeenPlaying = false

        launchWithTimeoutSync(this) {
            testPlayer.event.stateChange
                .waitUntil { it == AudioPlayerState.PLAYING }
                .collect {
                    hasBeenPlaying = true
                    testPlayer.stop()
                }
        }
        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertEquals(mutableListOf<String>("IDLE", "LOADING", "PLAYING", "STOPPED"), states);
            assertEquals(true, hasBeenPlaying)
            assertEquals(AudioPlayerState.STOPPED, testPlayer.playerState)
        })
    }

    // Position

    @Test
    fun Position_thenShouldBe0() = runBlocking(Dispatchers.Main) {
        assertEquals(0, testPlayer.position)
    }

    @Test
    fun Position_givenPlayingSource_thenShouldBeGreaterThan0() = runBlocking(Dispatchers.Main) {
        // TODO: Fix bug with load when you use it to add first item
//            testPlayer.load(TestSound.default, playWhenReady = false)
        testPlayer.add(TestSound.long, playWhenReady = true)

        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertTrue(testPlayer.position > 0)
        })
    }

    // Rate

    @Test
    fun Rate_givenNewPlayer_thenShouldBe1() = runBlocking(Dispatchers.Main) {
        assertEquals(1.0f, testPlayer.playbackSpeed)
    }

    @Test
    fun Rate_givenPlayingSource_thenShouldBe1() = runBlocking(Dispatchers.Main) {
        testPlayer.load(TestSound.fiveSeconds, playWhenReady = true)

        var hasMetSpeedExpectation = false
        launchWithTimeoutSync(this) {
            testPlayer.event.stateChange
                .waitUntil { it == AudioPlayerState.PLAYING }
                .collect { hasMetSpeedExpectation = testPlayer.playbackSpeed == 1.0f }
        }

        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertTrue(hasMetSpeedExpectation)
        })
    }

    // CurrentItem

    @Test
    fun CurrentItem_givenNewPlayer_thenShouldBeNull() = runBlocking(Dispatchers.Main) {
        assertNull(testPlayer.currentItem)
    }

    @Test
    fun CurrentItem_givenLoadingSource_thenShouldNotBeNull() = runBlocking(Dispatchers.Main) {
        // TODO: Fix bug with load when you use it to add first item
//            testPlayer.load(TestSound.default, playWhenReady = false)
        testPlayer.add(TestSound.long, playWhenReady = true)

        eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
            assertNotNull(testPlayer.currentItem)
        })
    }
}
