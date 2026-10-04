package com.doublesymmetry.kotlinaudio.models

import androidx.media3.common.Player

/**
 * [endedItem] is the item [pausedBecauseReachedEnd] paused at the end of, captured when media3 paused
 * there: by the time a collector runs, the queue has already moved on to the next item.
 */
data class PlayWhenReadyChangeData(
    val playWhenReady: Boolean,
    val pausedBecauseReachedEnd: Boolean,
    val endedItem: AudioItem? = null,
)
