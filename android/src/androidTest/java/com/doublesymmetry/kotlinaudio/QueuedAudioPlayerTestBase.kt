package com.doublesymmetry.kotlinaudio

import androidx.test.platform.app.InstrumentationRegistry
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.CacheConfig
import com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer
import com.doublesymmetry.kotlinaudio.utils.NoopMediaSessionCallback
import com.doublesymmetry.kotlinaudio.utils.TestSound
import com.doublesymmetry.kotlinaudio.utils.waitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName

/**
 * Converted from JUnit 5 (see
 * react-native-track-player/android/src/main/java/com/doublesymmetry/kotlinaudio/NOTICE.md).
 *
 * The original `QueuedAudioPlayerTest` was one JUnit 5 class with an outer `@BeforeEach`/`@AfterEach`
 * and fourteen `@Nested` groups. Two of those groups (`Remove`, `Move`) declared their own
 * `@BeforeEach` on top of the outer one. JUnit 4 has no nested-class equivalent, so:
 *  - groups without their own setup are flattened into [QueuedAudioPlayerTest] as
 *    `GroupName_testName()` methods;
 *  - `Remove` and `Move`, which need extra setup, became their own top-level classes
 *    ([QueuedAudioPlayerRemoveTest], [QueuedAudioPlayerMoveTest]) extending this base.
 *
 * JUnit 4 runs superclass `@Before` methods before subclass `@Before` methods (and subclass `@After`
 * before superclass `@After`), which reproduces the outer-then-inner ordering the nested classes
 * relied on.
 */
abstract class QueuedAudioPlayerTestBase {
    protected lateinit var testPlayer: QueuedAudioPlayer
    protected lateinit var states: MutableList<String>

    protected val tracks = listOf(
        TestSound.short,
        TestSound.fiveSeconds,
        TestSound.fiveSeconds2
    )

    @get:Rule
    val testName = TestName()

    @After
    fun afterEach() {
        runBlocking(Dispatchers.Main) {
            testPlayer.clear()
        }
    }

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
                cacheConfig = CacheConfig(
                    maxCacheSize = (1024 * 50).toLong(),
                    identifier = testName.methodName
                ),
                mediaSessionCallback = NoopMediaSessionCallback
            )
            testPlayer.volume = 0f
        }
        states = mutableListOf()
        testPlayer.event.stateChange.map {
            if (
                // Skipping buffering and ready since it depends on circumstances when and how often
                // buffering occurs.
                it != AudioPlayerState.BUFFERING &&
                it != AudioPlayerState.READY &&
                // Also make sure we aren't adding duplicate states (due to skipping those above)
                (states.size == 0 || states.last() != it.toString())
            ) {
                states.add(it.toString())
            }
        }.stateIn(
            CoroutineScope(Dispatchers.Default),
            SharingStarted.Eagerly,
            emptyList<AudioPlayerState>()
        )
        testPlayer.event.stateChange.waitUntil { it == AudioPlayerState.IDLE }
    }
}
