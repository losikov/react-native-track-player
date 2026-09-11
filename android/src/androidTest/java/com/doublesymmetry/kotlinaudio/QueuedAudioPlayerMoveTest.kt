package com.doublesymmetry.kotlinaudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Converted from JUnit 5's `QueuedAudioPlayerTest.Move` (see
// react-native-track-player/android/src/main/java/com/doublesymmetry/kotlinaudio/NOTICE.md). Split
// out of [QueuedAudioPlayerTest] because this `@Nested` group had its own `@BeforeEach` on top of the
// outer one; see [QueuedAudioPlayerTestBase] for why a separate class reproduces that ordering.
@RunWith(AndroidJUnit4::class)
class QueuedAudioPlayerMoveTest : QueuedAudioPlayerTestBase() {

    @Before
    fun setUpQueue() {
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks)
        }
    }

    @Test
    fun givenAddedThreeItemsAndMovingFirstToSecond_thenFirstItemShouldBeAtSecondIndex() =
        runBlocking(Dispatchers.Main) {
            testPlayer.move(0, 1)
            assertEquals(testPlayer.items[0], tracks[1])
            assertEquals(testPlayer.items[1], tracks[0])
            assertEquals(testPlayer.currentItem, tracks[0])
            assertEquals(testPlayer.currentIndex, 1)
        }

    @Test
    fun givenAddedThreeItemsAndMovingSecondToFirst_thenSecnodItemShouldBeAtFirstIndex() =
        runBlocking(Dispatchers.Main) {
            testPlayer.move(1, 0)
            assertEquals(testPlayer.items[0], tracks[1])
            assertEquals(testPlayer.items[1], tracks[0])
            assertEquals(testPlayer.currentItem, tracks[0])
            assertEquals(testPlayer.currentIndex, 1)
        }

    @Test
    fun givenAddedThreeItemsAndMovingFirstToThird_thenFirstItemShouldBeAtThirdIndex() =
        runBlocking(Dispatchers.Main) {
            testPlayer.move(0, 2)
            assertEquals(testPlayer.items[0], tracks[1])
            assertEquals(testPlayer.items[1], tracks[2])
            assertEquals(testPlayer.items[2], tracks[0])
            assertEquals(testPlayer.currentItem, tracks[0])
            assertEquals(testPlayer.currentIndex, 2)
        }

    @Test
    fun givenAddedThreeItemsAndMovingFirstToIndexEqualToQueueSize_thenItemIsMovedToEndOfQueue() =
        runBlocking(Dispatchers.Main) {
            assertEquals(tracks[0], testPlayer.currentItem)
            testPlayer.move(0, testPlayer.items.size)
            assertEquals(3, testPlayer.items.size)
            assertEquals(tracks[1].title, testPlayer.items[0].title)
            assertEquals(tracks[2].title, testPlayer.items[1].title)
            assertEquals(tracks[0], testPlayer.items[2])
            assertEquals(tracks[0], testPlayer.currentItem)
        }

    @Test
    fun givenAddedThreeItemsAndMovingFirstToIndexGreaterThanQueueSize_thenItemIsMovedToEndOfQueue() =
        runBlocking(Dispatchers.Main) {
            assertEquals(tracks[0], testPlayer.currentItem)
            testPlayer.move(0, Int.MAX_VALUE)
            assertEquals(3, testPlayer.items.size)
            assertEquals(tracks[1].title, testPlayer.items[0].title)
            assertEquals(tracks[2].title, testPlayer.items[1].title)
            assertEquals(tracks[0], testPlayer.items[2])
            assertEquals(tracks[0], testPlayer.currentItem)
        }

    @Test
    fun givenAddedThreeItemsAndMovingFirstToNegativeIndex_thenShouldThrow() =
        runBlocking(Dispatchers.Main) {
            assertEquals(tracks[0], testPlayer.currentItem)
            var caughtError: IllegalArgumentException? = null;
            try {
                testPlayer.move(0, -1)
            } catch (error: IllegalArgumentException) {
                caughtError = error;
            }
            assertNotNull(caughtError)
        }
}
