package com.doublesymmetry.kotlinaudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.utils.eventually
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

// Converted from JUnit 5's `QueuedAudioPlayerTest.Remove` (see
// react-native-track-player/android/src/main/java/com/doublesymmetry/kotlinaudio/NOTICE.md). Split
// out of [QueuedAudioPlayerTest] because this `@Nested` group had its own `@BeforeEach` on top of the
// outer one; see [QueuedAudioPlayerTestBase] for why a separate class reproduces that ordering.
@RunWith(AndroidJUnit4::class)
class QueuedAudioPlayerRemoveTest : QueuedAudioPlayerTestBase() {

    @Before
    fun setUpQueue() {
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks)
        }
    }

    @Test
    fun givenAddedThreeItemsAndDeletingFirst_thenFirstItemShouldBeRemoved() =
        runBlocking(Dispatchers.Main) {
            testPlayer.remove(0)
            assertEquals(testPlayer.items.size, 2)
            assertEquals(testPlayer.items[0], tracks[1])
        }

    @Test
    fun givenAddedThreeItemsAndDeletingLast_thenFirstItemShouldBeRemoved() =
        runBlocking(Dispatchers.Main) {
            testPlayer.remove(2)
            assertEquals(2, testPlayer.items.size)
            assertEquals(testPlayer.items[0], tracks[0])
            assertEquals(testPlayer.items[1], tracks[1])
        }

    @Test
    fun givenAddedThreeItemsAndDeletingFirst_thenFirstItemShouldBeRemovedAndNextTrackBecomesCurrent() =
        runBlocking(Dispatchers.Main) {
            testPlayer.remove(0)
            assertEquals(2, testPlayer.items.size)
            assertEquals(0, testPlayer.currentIndex)
            assertEquals(testPlayer.currentItem, tracks[1])
        }

    @Test
    fun givenAddedThreeItemsAndJumpingToSecondAndDeletingSecond_thenSecondItemShouldBeRemovedAndNextTrackBecomesCurrent() =
        runBlocking(Dispatchers.Main) {
            testPlayer.jumpToItem(1)
            testPlayer.remove(1)
            assertEquals(2, testPlayer.items.size)
            assertEquals(1, testPlayer.currentIndex)
            assertEquals(tracks[2], testPlayer.currentItem)
        }

    @Test
    fun givenAddedThreeItemsAndJumpingToLastAndDeletingIt_thenLastTrackShouldBeRemovedAndFirstTrackBecomesCurrent() =
        runBlocking(Dispatchers.Main) {
            testPlayer.jumpToItem(2)
            testPlayer.remove(2)
            assertEquals(testPlayer.items.size, 2)
            assertEquals(0, testPlayer.currentIndex)
            assertEquals(tracks[0], testPlayer.currentItem)
        }

    @Test
    fun givenAddedThreeItemsAndRemovingWithIndexesInNonDescendingOrder_thenShouldDeleteCorrectItems() =
        runBlocking(Dispatchers.Main) {
            testPlayer.remove(listOf(0, 1))
            assertEquals(testPlayer.items.size, 1)
            assertEquals(tracks[2], testPlayer.items[0])
        }

    @Test
    fun givenAddedThreeItemsAndDeletingAll_thenShouldDeleteCorrectItems() =
        runBlocking(Dispatchers.Main) {
            testPlayer.remove(listOf(0, 1, 2))
            assertEquals(0, testPlayer.items.size)
            assertEquals(0, testPlayer.currentIndex)
            assertEquals(null, testPlayer.currentItem)
        }

    @Test
    fun givenAddedThreeItemsAndDeletingAll_thenPlayerStateShouldBecomeIDLE() =
        runBlocking(Dispatchers.Main) {
            testPlayer.remove(listOf(0, 1, 2))
            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
                assertEquals(AudioPlayerState.IDLE, testPlayer.playerState)
            });
        }
}
