package com.doublesymmetry.kotlinaudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.RepeatMode.*
import com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer
import com.doublesymmetry.kotlinaudio.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.TimeUnit

// Converted from JUnit 5 (see react-native-track-player/android/src/main/java/com/doublesymmetry/kotlinaudio/NOTICE.md).
// `@Nested inner class Foo { @Test fun bar() }` groups are flattened to `Foo_bar()` at top level
// (`RepeatMode.Off.foo()` -> `RepeatMode_Off_foo()`, etc). None of the groups below had their own
// `@BeforeEach`, so the base class's single `@Before`/`@After` still covers all of them. The two
// groups that *did* have their own setup (`Remove`, `Move`) are [QueuedAudioPlayerRemoveTest] and
// [QueuedAudioPlayerMoveTest] instead.
@RunWith(AndroidJUnit4::class)
class QueuedAudioPlayerTest : QueuedAudioPlayerTestBase() {

    // CurrentItem

    @Test
    fun CurrentItem_thenReturnNull() = runBlocking(Dispatchers.Main) {
        assertNull(testPlayer.currentItem)
    }

    @Test
    fun CurrentItem_givenAddedFirstTrack_thenEqualsFirstTrack() = runBlocking(Dispatchers.Main) {
        testPlayer.add(tracks[0])

        assertEquals(tracks[0], testPlayer.currentItem)
    }

    @Test
    fun CurrentItem_givenAddedOneItemAndLoadingAnother_thenShouldHaveReplacedCurrentItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.load(tracks[1])

            assertEquals(tracks[1], testPlayer.currentItem)
        }

    @Test
    fun CurrentItem_givenAddedTwoItemAndMovingFirstAboveSecond_thenShouldHaveMovedItem() =
        runBlocking(Dispatchers.Main) {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val audioPlayer = QueuedAudioPlayer(appContext, mediaSessionCallback = NoopMediaSessionCallback)

            audioPlayer.add(tracks[0])
            audioPlayer.add(tracks[1])
            assertEquals(tracks[0], audioPlayer.items[0])
            assertEquals(tracks[0], audioPlayer.currentItem)
            audioPlayer.move(0, 1)
            assertEquals(audioPlayer.currentItem, audioPlayer.items[1])
            assertNotEquals(audioPlayer.currentItem, audioPlayer.items[0])
        }


    @Test
    fun CurrentItem_givenAddedMultipleItems_thenCurrentItemIsFirstAddedItem() = runBlocking(Dispatchers.Main) {
        testPlayer.add(tracks[0])
        testPlayer.add(tracks[1])

        assertEquals(tracks[0], testPlayer.currentItem)
    }

    // NextItems

    @Test
    fun NextItems_thenBeEmpty() = runBlocking(Dispatchers.Main) {
        assertTrue(testPlayer.nextItems.isEmpty())
    }

    @Test
    fun NextItems_givenAddedTwoItems_thenShouldContainOneItem() = runBlocking(Dispatchers.Main) {
        testPlayer.add(tracks[0])
        testPlayer.add(tracks[1])

        assertEquals(1, testPlayer.nextItems.size)
    }

    @Test
    fun NextItems_givenAddedTwoItemsAndCallingNext_thenShouldContainZeroItems() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            testPlayer.next()

            assertEquals(0, testPlayer.nextItems.size)
        }

    @Test
    fun NextItems_givenAddedTwoItemsAndCallingNextAndPrevious_thenShouldContainOneItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            testPlayer.next()
            testPlayer.previous()

            assertEquals(1, testPlayer.nextItems.size)
        }

    @Test
    fun NextItems_givenAddedTwoItemsAndRemovingLastItem_thenShouldBeEmpty() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            testPlayer.remove(1)

            assertTrue(testPlayer.nextItems.isEmpty())
        }

    @Test
    fun NextItems_givenAddedTwoItemsAndJumpingToLast_thenShouldBeEmpty() = runBlocking(Dispatchers.Main) {
        testPlayer.add(tracks[0])
        testPlayer.add(tracks[1])
        testPlayer.jumpToItem(1)

        assertTrue(testPlayer.nextItems.isEmpty())
    }

    @Test
    fun NextItems_givenAddedTwoItemsAndRemovingUpcomingItems_thenShouldBeEmpty() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            assertEquals(testPlayer.currentItem?.audioUrl, tracks[0].audioUrl)
            testPlayer.removeUpcomingItems()
            assertEquals(testPlayer.nextItems.size, 0)
            assertEquals(testPlayer.items.size, 1)
            assertEquals(testPlayer.currentItem?.title, tracks[0].title)
        }

    @Test
    fun NextItems_givenAddedTwoItemsAndJumpingToSecondRemovingUpcomingItems_thenShouldBeEmpty() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            testPlayer.jumpToItem(1)
            testPlayer.removeUpcomingItems()
            assertEquals(testPlayer.nextItems.size, 0)
            assertEquals(testPlayer.items.size, 2)
            assertEquals(testPlayer.currentItem?.title, tracks[1].title)
        }

    @Test
    fun NextItems_givenAddedThreeItemsRemovingUpcomingItems_thenShouldBeEmpty() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            testPlayer.add(tracks[2])
            testPlayer.removeUpcomingItems()
            assertEquals(testPlayer.nextItems.size, 0)
            assertEquals(testPlayer.items.size, 1)
            assertEquals(testPlayer.currentItem?.title, tracks[0].title)
        }

    @Test
    fun NextItems_givenAddedThreeItemsAndSkippingToSecondAndRemovingUpcomingItems_thenShouldBeEmpty() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            testPlayer.add(tracks[2])
            testPlayer.jumpToItem(1)
            testPlayer.removeUpcomingItems()
            assertEquals(testPlayer.nextItems.size, 0)
            assertEquals(testPlayer.items.size, 2)
            assertEquals(testPlayer.currentItem?.title, tracks[1].title)
        }

    @Test
    fun NextItems_giveNoItems_thenShouldBeEmpty() = runBlocking(Dispatchers.Main) {
        testPlayer.add(emptyList())
        testPlayer.removeUpcomingItems()
        assertEquals(testPlayer.nextItems.size, 0)
    }

    @Test
    fun NextItems_givenAddedTwoItemsAndStopping_thenShouldStillBeOne() = runBlocking(Dispatchers.Main) {
        testPlayer.add(tracks[0])
        testPlayer.add(tracks[1])
        testPlayer.stop()

        assertEquals(1, testPlayer.nextItems.size)
    }

    // PreviousItems

    @Test
    fun PreviousItems_thenShouldBeEmpty() = runBlocking(Dispatchers.Main) {
        assertTrue(testPlayer.previousItems.isEmpty())
    }

    @Test
    fun PreviousItems_givenAddedTwoItems_thenShouldBeEmpty() = runBlocking(Dispatchers.Main) {
        testPlayer.add(tracks[0])
        testPlayer.add(tracks[1])

        assertEquals(0, testPlayer.previousItems.size)
    }

    @Test
    fun PreviousItems_givenAddedTwoItemsAndCallingNext_thenShouldHaveOneItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            testPlayer.next()

            assertEquals(1, testPlayer.previousItems.size)
        }

    @Test
    fun PreviousItems_givenAddedTwoItemsSkippingToSecondItemAndSeekingToThreeSecondsAndCallingPrevious_thenShouldHaveOneItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[1])
            testPlayer.add(tracks[2])
            testPlayer.jumpToItem(1)
            testPlayer.seek(3000, TimeUnit.MILLISECONDS)
            testPlayer.play()
            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
                assertTrue(testPlayer.position > 3000)
                assertEquals(1, testPlayer.previousItems.size)
            })
            testPlayer.previous()
            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
                assertEquals(testPlayer.currentIndex, 0)
                assertEquals(0, testPlayer.previousItems.size)
            })
        }

    @Test
    fun PreviousItems_givenAddedTwoItemsAndRemovedPreviousItems_thenShouldBeEmpty() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            testPlayer.next()
            testPlayer.removePreviousItems()

            assertTrue(testPlayer.previousItems.isEmpty())
        }

    @Test
    fun PreviousItems_givenAddedTwoItemsAndStoppingAfterJumpingToSecondItem_thenShouldStillBeOne() = runBlocking(Dispatchers.Main) {
        testPlayer.add(tracks[0])
        testPlayer.add(tracks[1])
        assertEquals(0, testPlayer.previousItems.size)

        testPlayer.jumpToItem(1)
        assertEquals(1, testPlayer.previousItems.size)

        testPlayer.stop()

        assertEquals(1, testPlayer.previousItems.size)
    }

    // OnNext

    @Test
    fun OnNext_givenPlayerIsPlayingAndCallingNext_thenShouldGoToNextAndPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.short, TestSound.long))
            testPlayer.next()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
                assertEquals(1, testPlayer.previousItems.size)
                assertEquals(0, testPlayer.nextItems.size)
                assertEquals(TestSound.long, testPlayer.currentItem)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    @Test
    fun OnNext_givenPlayerIsPausedAndCallingNext_thenShouldGoToNextAndNotPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(listOf(TestSound.short, TestSound.long))
            testPlayer.next()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
                assertEquals(1, testPlayer.previousItems.size)
                assertEquals(0, testPlayer.nextItems.size)
                assertEquals(TestSound.long, testPlayer.currentItem)
                assertEquals(AudioPlayerState.READY, testPlayer.playerState)
            })
        }

    // OnPrevious

    @Test
    fun OnPrevious_givenPlayerIsPlayingAndCallingPrevious_thenShouldGoToPreviousAndPlay() =
        runBlocking(Dispatchers.Main) {
            var first = TestSound.fiveSeconds
            var second = TestSound.short
            testPlayer.play()
            testPlayer.add(listOf(first, second))
            assertEquals(first, testPlayer.currentItem)
            testPlayer.next()
            assertEquals(second, testPlayer.currentItem)
            testPlayer.previous()
            assertEquals(first, testPlayer.currentItem)
            testPlayer.seekAndWaitForNextTrackTransition(4.5.toLong(), TimeUnit.SECONDS)
            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertEquals(1, testPlayer.previousItems.size)
                assertEquals(0, testPlayer.nextItems.size)
                assertEquals(second, testPlayer.currentItem)
                assertEquals(AudioPlayerState.ENDED, testPlayer.playerState)
            })
        }

    @Test
    fun OnPrevious_givenPlayerIsPausedAndCallingPrevious_thenShouldGoToPreviousAndNotPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.pause()
            testPlayer.add(listOf(TestSound.short, TestSound.long))
            testPlayer.next()
            assertEquals(TestSound.long, testPlayer.currentItem)
            testPlayer.previous()
            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun () {
                assertEquals(0, testPlayer.previousItems.size)
                assertEquals(1, testPlayer.nextItems.size)
                assertEquals(testPlayer.currentItem, TestSound.short)
                assertEquals(AudioPlayerState.READY, testPlayer.playerState)
            })
        }

    // RepeatMode.Off

    @Test
    fun RepeatMode_Off_givenAddedTwoItemsAndAllowingPlaybackToEnd_whenRepeatModeOff_thenShouldMoveToNextItemAndPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.fiveSeconds2, TestSound.fiveSeconds))
            testPlayer.playerOptions.repeatMode = OFF
            testPlayer.seekAndWaitForNextTrackTransition(4.5.toLong(), TimeUnit.SECONDS)
            testPlayer.play()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.fiveSeconds, testPlayer.currentItem)
                assertEquals(mutableListOf<String>("IDLE", "LOADING", "PLAYING", "LOADING", "PLAYING"), states);
            })
        }

    // TODO: Fix known bug from: https://github.com/doublesymmetry/react-native-track-player/pull/1501
    @Test
    fun RepeatMode_Off_givenAddedTwoItemsAndAllowingPlaybackToEndTwice_whenRepeatModeOff_thenShouldStopPlayback() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.short, TestSound.short))
            testPlayer.playerOptions.repeatMode = OFF
            testPlayer.seekAndWaitForNextTrackTransition(0.0682.toLong(), TimeUnit.SECONDS)
            testPlayer.seekAndWaitForNextTrackTransition(0.0682.toLong(), TimeUnit.SECONDS)

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.short, testPlayer.currentItem)
                assertEquals(AudioPlayerState.ENDED, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_Off_givenAddedTwoItemsAndCallingNext_whenRepeatModeOff_thenShouldMoveToNextItemAndPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.short, TestSound.long))
            testPlayer.playerOptions.repeatMode = OFF
            testPlayer.nextAndWaitForNextTrackTransition()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.long, testPlayer.currentItem)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_Off_givenAddedTwoItemsAndCallingNextTwice_thenShouldDoNothingOnSecondNext() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.short, TestSound.fiveSeconds))
            testPlayer.playerOptions.repeatMode = OFF
            testPlayer.nextAndWaitForNextTrackTransition()
            testPlayer.next()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.fiveSeconds, testPlayer.currentItem)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_Off_givenAddedOneItemAndAllowingPlaybackToEnd_thenShouldStopPlayback() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(TestSound.short)
            testPlayer.playerOptions.repeatMode = OFF
            testPlayer.seekAndWaitForNextTrackTransition(0.0682.toLong(), TimeUnit.SECONDS)

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.short, testPlayer.currentItem)
                assertEquals(AudioPlayerState.ENDED, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_Off_givenAddedOneItemAndCallingNext_thenShouldDoNothing() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(TestSound.fiveSeconds, true)
            testPlayer.playerOptions.repeatMode = OFF
            testPlayer.nextAndWaitForNextTrackTransition()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.fiveSeconds, testPlayer.currentItem)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    // RepeatMode.One

    @Test
    fun RepeatMode_One_givenAddedTwoItemsAndAllowingPlaybackToEnd_whenRepeatModeOne_thenShouldRestartCurrentItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.fiveSeconds, TestSound.fiveSeconds2))
            testPlayer.playerOptions.repeatMode = ONE
            testPlayer.seekAndWaitForNextTrackTransition(
                4.toLong(),
                TimeUnit.SECONDS
            )
            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertEquals(1, testPlayer.nextItems.size)
                assertEquals(0, testPlayer.currentIndex)
                assertEquals(
                    mutableListOf<String>(
                        "IDLE",
                        "LOADING",
                        "PLAYING",
                        "LOADING",
                        "PLAYING"
                    ), states
                );
            })
        }

    @Test
    fun RepeatMode_One_givenAddedTwoItemsAndCallingNext_whenRepeatModeOne_thenShouldMoveToNextItemAndPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.short, TestSound.long))
            testPlayer.playerOptions.repeatMode = ONE
            testPlayer.nextAndWaitForNextTrackTransition()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.long, testPlayer.currentItem)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_One_givenAddedOneItemAndAllowingPlaybackToEnd_whenRepeatModeOne_thenShouldRestartCurrentItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(TestSound.fiveSeconds)
            testPlayer.playerOptions.repeatMode = ONE
            testPlayer.seekAndWaitForNextTrackTransition(
                4.5.toLong(),
                TimeUnit.SECONDS
            )

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertEquals(
                    mutableListOf<String>(
                        "IDLE",
                        "LOADING",
                        "PLAYING",
                        "LOADING",
                        "PLAYING"
                    ), states
                );
                assertEquals(0, testPlayer.nextItems.size)
                assertEquals(0, testPlayer.currentIndex)
            })
        }

    @Test
    fun RepeatMode_One_givenAddedTwoItemsAllowingToPlayTillEnd_whenRepeatModeOne_thenShouldRestartFirstItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(TestSound.fiveSeconds)
            testPlayer.add(TestSound.fiveSeconds2)
            assertEquals(0, testPlayer.currentIndex)
            testPlayer.playerOptions.repeatMode = ONE

            launchWithTimeoutSync(this) {
                testPlayer.event.stateChange
                    .waitUntil { it == AudioPlayerState.PLAYING }
                    .collect {
                        testPlayer.seek(4500, TimeUnit.MILLISECONDS)
                    }
            }

            launchWithTimeoutSync(this) {
                testPlayer.event.stateChange
                    .waitUntil { it == AudioPlayerState.PLAYING }
                    .collect { testPlayer.stop() }
            }

            eventually(Duration.ofSeconds(10), Dispatchers.Main, fun() {
                var expectedStates = mutableListOf<String>("IDLE", "LOADING", "PLAYING", "STOPPED")
                assertEquals(expectedStates, states);
            })
            assertEquals(0, testPlayer.currentIndex)
        }

    @Test
    fun RepeatMode_One_givenAddedTwoItemsAndCallingNext_whenRepeatModeOne_thenShouldBeAtSecondItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(TestSound.fiveSeconds)
            testPlayer.add(TestSound.fiveSeconds2)
            assertEquals(0, testPlayer.currentIndex)
            testPlayer.playerOptions.repeatMode = ONE
            testPlayer.nextAndWaitForNextTrackTransition()
            assertEquals(1, testPlayer.currentIndex)
        }

    // RepeatMode.All

    @Test
    fun RepeatMode_All_givenAddedTwoItemsAndAllowingPlaybackToEnd_whenRepeatModeAll_thenShouldMoveToNextItemAndPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.fiveSeconds, TestSound.long))
            testPlayer.playerOptions.repeatMode = ALL
            testPlayer.seekAndWaitForNextTrackTransition(
                4.5.toLong(),
                TimeUnit.SECONDS
            )

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.long, testPlayer.currentItem)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_All_givenAddedTwoItemsAndAllowingPlaybackToEndTwice_whenRepeatModeAll_thenShouldMoveToFirstTrackAndPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.long, TestSound.fiveSeconds))
            testPlayer.playerOptions.repeatMode = ALL
            testPlayer.nextAndWaitForNextTrackTransition()
            testPlayer.seekAndWaitForNextTrackTransition(
                4.5.toLong(),
                TimeUnit.SECONDS
            )

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
                assertEquals(1, testPlayer.nextItems.size)
                assertEquals(TestSound.long, testPlayer.currentItem)
            })
        }

    @Test
    fun RepeatMode_All_givenAddedTwoItemsAndCallingNext_whenRepeatModeAll_thenShouldMoveToNextItemAndPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.fiveSeconds, TestSound.fiveSeconds2))
            testPlayer.playerOptions.repeatMode = ALL
            testPlayer.nextAndWaitForNextTrackTransition()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.nextItems.isEmpty())
                assertEquals(TestSound.fiveSeconds2, testPlayer.currentItem)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_All_givenAddedTwoItemsAndCallingNextTwice_whenRepeatModeAll_thenShouldMoveToFirstTrackAndPlay() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(listOf(TestSound.fiveSeconds, TestSound.short))
            testPlayer.playerOptions.repeatMode = ALL
            testPlayer.nextAndWaitForNextTrackTransition()
            testPlayer.nextAndWaitForNextTrackTransition()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertEquals(1, testPlayer.nextItems.size)
                assertEquals(TestSound.fiveSeconds, testPlayer.currentItem)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_All_givenAddedOneItemAndAllowingPlaybackToEnd_whenRepeatModeAll_thenShouldRestartCurrentItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.playerOptions.repeatMode = ALL
            testPlayer.add(TestSound.fiveSeconds)
            testPlayer.seekAndWaitForNextTrackTransition(
                4.5.toLong(),
                TimeUnit.SECONDS
            )

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.position < 4000 && testPlayer.position > 0)
                assertEquals(0, testPlayer.nextItems.size)
                assertEquals(0, testPlayer.currentIndex)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    @Test
    fun RepeatMode_All_givenAddedOneItemAndCallingNext_whenRepeatModeAll_thenShouldRestartCurrentItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.add(TestSound.fiveSeconds)
            testPlayer.playerOptions.repeatMode = ALL
            testPlayer.nextAndWaitForNextTrackTransition()

            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertTrue(testPlayer.position > 0)
                assertEquals(0, testPlayer.nextItems.size)
                assertEquals(0, testPlayer.currentIndex)
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }

    // Add

    @Test
    fun Add_givenNoAddedItems_currentIndexIsZero() =
        runBlocking(Dispatchers.Main) {
            assertEquals(testPlayer.currentIndex, 0)
        }

    @Test
    fun Add_givenAddedEmptyListOfItems_thenShouldBeEmpty() = runBlocking(Dispatchers.Main) {
        testPlayer.add(emptyList())
        assertEquals(0, testPlayer.items.size)
        assertEquals(0, testPlayer.currentIndex)
        assertEquals(null, testPlayer.currentItem)
    }

    @Test
    fun Add_givenNoAddedItems_currentItemIsNull() =
        runBlocking(Dispatchers.Main) {
            assertNull(testPlayer.currentItem)
        }

    @Test
    fun Add_givenAddedFirstItem_thenTheFirstAddedItemIsCurrent() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
                assertEquals(testPlayer.currentItem, tracks[0])
            assertEquals(testPlayer.currentIndex, 0)
        }

    @Test
    fun Add_givenAddedSecondItem_thenTheFirstAddedItemIsCurrentAndTheSecondIsSecond() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(tracks[1])
            assertEquals(testPlayer.currentItem, tracks[0])
            assertEquals(testPlayer.currentIndex, 0)
            assertEquals(testPlayer.items[1], tracks[1])

        }

    // Add.AtIndex

    @Test
    fun Add_AtIndex_givenAddedFirstItemAndAddedSecondAtIndexZero_thenSecondTrackIsFirstItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(listOf(tracks[1]), 0)
            assertEquals(tracks[0], testPlayer.currentItem)
            assertEquals(1, testPlayer.currentIndex)
            assertEquals(tracks[1], testPlayer.items[0])
        }

    @Test
    fun Add_AtIndex_givenAddedFirstItemAndAddedTwoTracksAtIndexZero_thenSecondTrackIsFirstItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(tracks[0])
            testPlayer.add(listOf(tracks[1], tracks[2]), 0)
            assertEquals(tracks[0], testPlayer.currentItem)
            assertEquals(2, testPlayer.currentIndex)
            assertEquals(tracks[1], testPlayer.items[0])
            assertEquals(tracks[2], testPlayer.items[1])
        }

    // Load

    @Test
    fun Load_givenLoadedItemWithPlayWhenReady_thenThePlayerShouldStartPlaying() =
        runBlocking(Dispatchers.Main) {
            testPlayer.play()
            testPlayer.load(TestSound.fiveSeconds)
            eventually(Duration.ofSeconds(30), Dispatchers.Main, fun() {
                assertEquals(AudioPlayerState.PLAYING, testPlayer.playerState)
            })
        }


    @Test
    fun Load_givenLoadedItemWithoutAdding_thenItShouldAddAsFirst() = runBlocking(Dispatchers.Main) {
        testPlayer.load(TestSound.long)
        assertEquals(TestSound.long.audioUrl, testPlayer.currentItem?.audioUrl)
        assertEquals(testPlayer.currentIndex, 0)
        assertEquals(testPlayer.items.size, 1)
    }

    @Test
    fun Load_givenAddedOneItemAndLoadingAnother_thenShouldHaveReplacedItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(TestSound.short)
            testPlayer.load(TestSound.long)

            assertEquals(TestSound.long.audioUrl, testPlayer.currentItem?.audioUrl)
        }

    @Test
    fun Load_givenAddedTwoItemsAndJumpingToTheSecondLoadingAnother_thenShouldHaveReplacedSecondItem() =
        runBlocking(Dispatchers.Main) {
            testPlayer.add(TestSound.short)
            testPlayer.add(TestSound.fiveSeconds)
            testPlayer.jumpToItem(1)
            testPlayer.load(TestSound.long)

            assertEquals(testPlayer.currentItem?.audioUrl, TestSound.long.audioUrl)
            assertEquals(testPlayer.items[1].audioUrl, TestSound.long.audioUrl)
            assertEquals(testPlayer.currentIndex, 1)
        }
}
