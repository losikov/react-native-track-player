package com.doublesymmetry.kotlinaudio.players

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import com.doublesymmetry.kotlinaudio.models.*
import com.doublesymmetry.kotlinaudio.players.components.getAudioItemHolder
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * The queue API. Its public surface is unchanged from the ExoPlayer 2 build — the instrumented
 * suite targets it directly — and only the private representation moved: the queue holds media3
 * `MediaItem`s rather than pre-built `MediaSource`s, because
 * [com.doublesymmetry.kotlinaudio.players.components.MediaFactory] is now the player's own
 * `MediaSource.Factory` and turns each item into the right source on demand.
 */
@UnstableApi
class QueuedAudioPlayer(
    context: Context,
    playerConfig: PlayerConfig = PlayerConfig(),
    bufferConfig: BufferConfig? = null,
    cacheConfig: CacheConfig? = null,
    mediaSessionCallback: AAMediaSessionCallBack
) : BaseAudioPlayer(context, playerConfig, bufferConfig, cacheConfig, mediaSessionCallback) {
    private val queue = LinkedList<Media3Item>()
    override val playerOptions = DefaultQueuedPlayerOptions(this)

    val currentIndex
        get() = exoPlayer.currentMediaItemIndex

    override val currentItem: AudioItem?
        get() = queue.getOrNull(currentIndex)?.getAudioItemHolder()?.audioItem

    val nextIndex: Int?
        get() {
            return if (exoPlayer.nextMediaItemIndex == C.INDEX_UNSET) null
            else exoPlayer.nextMediaItemIndex
        }

    val previousIndex: Int?
        get() {
            return if (exoPlayer.previousMediaItemIndex == C.INDEX_UNSET) null
            else exoPlayer.previousMediaItemIndex
        }

    val items: List<AudioItem>
        get() = queue.map { it.getAudioItemHolder().audioItem }

    val previousItems: List<AudioItem>
        get() {
            return if (queue.isEmpty()) emptyList()
            else queue
                .subList(0, exoPlayer.currentMediaItemIndex)
                .map { it.getAudioItemHolder().audioItem }
        }

    val nextItems: List<AudioItem>
        get() {
            return if (queue.isEmpty()) emptyList()
            else queue
                .subList(exoPlayer.currentMediaItemIndex, queue.lastIndex)
                .map { it.getAudioItemHolder().audioItem }
        }

    val nextItem: AudioItem?
        get() = items.getOrNull(currentIndex + 1)

    val previousItem: AudioItem?
        get() = items.getOrNull(currentIndex - 1)

    override fun load(item: AudioItem, playWhenReady: Boolean) {
        load(item)
        exoPlayer.playWhenReady = playWhenReady
    }

    override fun load(item: AudioItem) {
        clearStopAt()
        if (queue.isEmpty()) {
            add(item)
        } else {
            val mediaItem = mediaItemOf(item)
            queue[currentIndex] = mediaItem
            exoPlayer.addMediaItem(currentIndex + 1, mediaItem)
            exoPlayer.removeMediaItem(currentIndex)
            exoPlayer.seekTo(currentIndex, C.TIME_UNSET)
            exoPlayer.prepare()
        }
    }

    /**
     * Load a whole queue atomically — `setMediaItems(items, startIndex, startPositionMs)`,
     * `prepare()`, then the intent — instead of "add, then seek". Nothing in JS calls it yet.
     */
    override fun loadQueue(
        items: List<AudioItem>,
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        queue.clear()
        queue.addAll(items.map { mediaItemOf(it) })
        super.loadQueue(items, startIndex, startPositionMs, playWhenReady)
    }

    /**
     * Add a single item to the queue. If the AudioPlayer has no item loaded, it will load the `item`.
     * @param item The [AudioItem] to add.
     */
    fun add(item: AudioItem, playWhenReady: Boolean) {
        exoPlayer.playWhenReady = playWhenReady
        add(item)
    }

    /**
     * Add a single item to the queue. If the AudioPlayer has no item loaded, it will load the `item`.
     * @param item The [AudioItem] to add.
     */
    fun add(item: AudioItem) {
        val mediaItem = mediaItemOf(item)
        queue.add(mediaItem)
        exoPlayer.addMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    /**
     * Add multiple items to the queue. If the AudioPlayer has no item loaded, it will load the first item in the list.
     * @param items The [AudioItem]s to add.
     * @param playWhenReady Whether playback starts automatically.
     */
    fun add(items: List<AudioItem>, playWhenReady: Boolean) {
        exoPlayer.playWhenReady = playWhenReady
        add(items)
    }

    /**
     * Add multiple items to the queue. If the AudioPlayer has no item loaded, it will load the first item in the list.
     * @param items The [AudioItem]s to add.
     */
    fun add(items: List<AudioItem>) {
        val mediaItems = items.map { mediaItemOf(it) }
        queue.addAll(mediaItems)
        exoPlayer.addMediaItems(mediaItems)
        exoPlayer.prepare()
    }

    /**
     * Add multiple items to the queue.
     * @param items The [AudioItem]s to add.
     * @param atIndex  Index to insert items at, if no items loaded this will not automatically start playback.
     */
    fun add(items: List<AudioItem>, atIndex: Int) {
        val mediaItems = items.map { mediaItemOf(it) }
        queue.addAll(atIndex, mediaItems)
        exoPlayer.addMediaItems(atIndex, mediaItems)
        exoPlayer.prepare()
    }

    /**
     * Remove an item from the queue.
     * @param index The index of the item to remove.
     */
    fun remove(index: Int) {
        queue.removeAt(index)
        exoPlayer.removeMediaItem(index)
    }

    /**
     * Remove items from the queue.
     * @param indexes The indexes of the items to remove.
     */
    fun remove(indexes: List<Int>) {
        val sorted = indexes.toMutableList()
        // Sort the indexes in descending order so we can safely remove them one by one
        // without having the next index possibly newly pointing to another item than intended:
        sorted.sortDescending()
        sorted.forEach {
            remove(it)
        }
    }

    /**
     * Skip to the next item in the queue, which may depend on the current repeat mode.
     * Does nothing if there is no next item to skip to.
     */
    fun next() {
        clearStopAt()
        exoPlayer.seekToNextMediaItem()
        exoPlayer.prepare()
    }

    /**
     * Skip to the previous item in the queue, which may depend on the current repeat mode.
     * Does nothing if there is no previous item to skip to.
     */
    fun previous() {
        clearStopAt()
        exoPlayer.seekToPreviousMediaItem()
        exoPlayer.prepare()
    }

    /**
     * Move an item in the queue from one position to another.
     * @param fromIndex The index of the item ot move.
     * @param toIndex The index to move the item to. If the index is larger than the size of the queue, the item is moved to the end of the queue instead.
     */
    fun move(fromIndex: Int, toIndex: Int) {
        exoPlayer.moveMediaItem(fromIndex, toIndex)
        val item = queue[fromIndex]
        queue.removeAt(fromIndex)
        queue.add(max(0, min(items.size, if (toIndex > fromIndex) toIndex else toIndex - 1)), item)
    }

    /**
     * Jump to an item in the queue.
     * @param index the index to jump to
     * @param playWhenReady Whether playback starts automatically.
     */
    fun jumpToItem(index: Int, playWhenReady: Boolean) {
        exoPlayer.playWhenReady = playWhenReady
        jumpToItem(index)
    }

    /**
     * Jump to an item in the queue.
     * @param index the index to jump to
     * @param positionMs position within the item in milliseconds, or [C.TIME_UNSET] to start at the beginning
     */
    fun jumpToItem(index: Int, positionMs: Long = C.TIME_UNSET) {
        clearStopAt()
        try {
            exoPlayer.seekTo(index, positionMs)
            exoPlayer.prepare()
        } catch (e: IllegalSeekPositionException) {
            throw Error("This item index $index does not exist. The size of the queue is ${queue.size} items.")
        }
    }

    /**
     * Replaces item at index in queue.
     *
     * On media3 the replacement is pushed into the timeline as well, not only into this list: a
     * metadata-only change satisfies `MediaSource.canUpdateMediaItem`, so ExoPlayer swaps it in
     * without interrupting playback, and every controller — notification, Android Auto, Bluetooth —
     * sees the new metadata because they all read `Player.getMediaMetadata()`.
     */
    fun replaceItem(index: Int, item: AudioItem) {
        val mediaItem = mediaItemOf(item)
        queue[index] = mediaItem
        if (automaticallyUpdateNotificationMetadata && index < exoPlayer.mediaItemCount) {
            exoPlayer.replaceMediaItem(index, mediaItem)
        }
    }

    /**
     * Removes all the upcoming items, if any (the ones returned by [next]).
     */
    fun removeUpcomingItems() {
        if (queue.lastIndex == -1 || currentIndex == -1) return
        val lastIndex = queue.lastIndex + 1
        val fromIndex = currentIndex + 1

        exoPlayer.removeMediaItems(fromIndex, lastIndex)
        queue.subList(fromIndex, lastIndex).clear()
    }

    /**
     * Removes all the previous items, if any (the ones returned by [previous]).
     */
    fun removePreviousItems() {
        exoPlayer.removeMediaItems(0, currentIndex)
        queue.subList(0, currentIndex).clear()
    }

    override fun destroy() {
        queue.clear()
        super.destroy()
    }

    override fun clear() {
        queue.clear()
        super.clear()
    }
}
