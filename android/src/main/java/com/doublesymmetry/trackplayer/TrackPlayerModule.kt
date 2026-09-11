package com.doublesymmetry.trackplayer

import android.content.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.net.Uri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.doublesymmetry.kotlinaudio.models.Capability
import com.doublesymmetry.kotlinaudio.models.RepeatMode
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toMilliseconds
import com.doublesymmetry.trackplayer.extensions.asLibState
import com.doublesymmetry.trackplayer.model.State
import com.doublesymmetry.trackplayer.model.Track
import com.doublesymmetry.trackplayer.module.MusicEvents.Companion.EVENT_INTENT
import com.doublesymmetry.trackplayer.service.MusicService
import com.doublesymmetry.trackplayer.service.MusicServiceEventListener
import com.doublesymmetry.trackplayer.utils.AppForegroundTracker
import com.doublesymmetry.trackplayer.utils.RejectionException
import com.doublesymmetry.trackplayer.utils.UriUtils
import com.facebook.react.bridge.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import javax.annotation.Nonnull

/**
 * TurboModule implementation for react-native-track-player
 * This provides New Architecture support with full functionality from MusicModule
 */
@UnstableApi
class TrackPlayerModule(
    private val reactContext: ReactApplicationContext
) : NativeRTNTrackPlayerSpec(reactContext), ServiceConnection, MusicServiceEventListener {

    companion object {
        const val NAME = "RTNTrackPlayer"
        const val EVENT_INTENT = "com.doublesymmetry.trackplayer.event"
        
        /** `android.media.MediaMetadata.METADATA_KEY_MEDIA_TYPE`, which is not a public constant. */
        private const val LEGACY_METADATA_KEY_MEDIA_TYPE = "android.media.metadata.MEDIA_TYPE"

        // Buffer constants
        private const val DEFAULT_MIN_BUFFER_MS = 15000
        private const val DEFAULT_MAX_BUFFER_MS = 50000
        private const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 2500
        private const val DEFAULT_BACK_BUFFER_DURATION_MS = 0
    }

    private var playerOptions: Bundle? = null
    private var isServiceBound = false
    private var playerSetUpPromise: Promise? = null
    private val scope = MainScope()

    /**
     * Set once the React instance this module belongs to is going away — a Metro reload, a host
     * destroy. Guarded by [emitLock] rather than only volatile: [invalidate] runs on the ReactHost's
     * thread while emits run on Main, and the instance is destroyed the moment [invalidate] returns,
     * so an emit that had already passed a plain flag check would still call into a dead runtime.
     */
    private var invalidated = false
    private val emitLock = Any()

    /**
     * Every JS-bound event goes through here. `MusicService` is a started service that outlives the
     * React instance: after a reload it kept calling the *old* module, whose codegen emitter is a
     * `CxxCallbackImpl` into a runtime that no longer exists — the crash surfaced as
     * `RuntimeException: __next_prime overflow` from `emitOnPlaybackProgressUpdated`.
     */
    private fun emit(block: () -> Unit) {
        scope.launch {
            synchronized(emitLock) {
                if (invalidated || !reactContext.hasActiveReactInstance()) return@launch
                block()
            }
        }
    }

    /**
     * The React instance is being torn down. Detach from the service so it stops calling this
     * module, drop the binding so the connection does not pin the dead context, and cancel the
     * scope so nothing already queued on Main fires. The service itself keeps playing; the next
     * instance's module binds and takes the listener seat in `onServiceConnected`.
     */
    override fun invalidate() {
        synchronized(emitLock) { invalidated = true }
        if (::musicService.isInitialized && musicService.trackPlayerModule === this) {
            musicService.trackPlayerModule = null
        }
        if (isServiceBound) {
            isServiceBound = false
            try {
                context.unbindService(this)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "🎵 TrackPlayerModule.invalidate: service was not bound")
            }
        }
        scope.cancel()
        super.invalidate()
    }
    private lateinit var musicService: MusicService
    private val context = reactContext

    override fun getName(): String = NAME

    override fun initialize() {
        Timber.plant(Timber.DebugTree())
        AppForegroundTracker.start()
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        scope.launch {
            Timber.d("🎵 TrackPlayerModule.onServiceConnected called")
            // If a binder already exists, don't get a new one
            if (!::musicService.isInitialized) {
                val binder: MusicService.MusicBinder = service as MusicService.MusicBinder
                musicService = binder.service
                
                // Set this TurboModule as the event listener FIRST (before setupPlayer)
                // This allows browse tree to be set even if setupPlayer() fails (e.g., app in background)
                Timber.d("🎵 TrackPlayerModule setting event listener")
                musicService.trackPlayerModule = this@TrackPlayerModule
                
                // Try to setup player, but don't fail if it can't (e.g., app in background for Android Auto)
                try {
                    Timber.d("🎵 TrackPlayerModule setting up player with options")
                    musicService.setupPlayer(playerOptions)
                } catch (e: Exception) {
                    Timber.w(e, "🎵 TrackPlayerModule setupPlayer() failed (may be in background for Android Auto)")
                    // Continue anyway - browse tree can still be set
                }
                
                // Send pending browse results if React Native was initialized for Android Auto
                musicService.sendPendingBrowseResults()
                // Process pending search requests that were queued before TrackPlayerModule was ready
                Timber.tag("RNTP-AA").d("TrackPlayerModule bound, processing pending search requests")
                musicService.processPendingSearchRequests()
                
                Timber.d("🎵 TrackPlayerModule resolving setup promise")
                playerSetUpPromise?.resolve(null)
                playerSetUpPromise = null
            }

            isServiceBound = true
            Timber.d("🎵 TrackPlayerModule service bound successfully")
        }
    }
    

    override fun onServiceDisconnected(name: ComponentName) {
        scope.launch {
            isServiceBound = false
        }
    }

    /**
     * Checks whether service is bound, or rejects. Returns whether promise was rejected.
     */
    private fun verifyServiceBoundOrReject(promise: Promise): Boolean {
        if (!isServiceBound) {
            promise.reject(
                "player_not_initialized",
                "The player is not initialized. Call setupPlayer first."
            )
            return true
        }

        return false
    }

    private fun bundleToTrack(bundle: Bundle): Track {
        return Track(context, bundle, musicService.ratingType)
    }

    override fun setupPlayer(options: ReadableMap, promise: Promise) {
        Timber.d("🎵 TurboModule setupPlayer() called")
        
        scope.launch {
            Timber.d("🎵 TrackPlayerModule.setupPlayer called, isServiceBound: $isServiceBound")
            if (isServiceBound) {
                promise.reject(
                    "player_already_initialized",
                    "The player has already been initialized via setupPlayer."
                )
                return@launch
            }

            // Bind service FIRST, even if setupPlayer() will fail due to background check
            // This allows browse tree to be set even when app is in background (e.g., Android Auto)
            val isBackgrounded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppForegroundTracker.backgrounded
            
            // Always bind service early to ensure TrackPlayerModule connects even if setupPlayer() fails
            Intent(context, MusicService::class.java).also { intent ->
                // startService, not startForegroundService. On the ExoPlayer 2 build the service
                // answered a foreground start by posting and immediately cancelling an empty
                // notification, purely to satisfy the 5-second startForeground() deadline
                // (`startAndStopEmptyNotificationToAvoidANR`). media3 posts the notification when
                // playback actually starts and promotes the service itself at that point, so there
                // is nothing to satisfy the deadline with — and nothing that needs to. This branch
                // only runs while the app is in the foreground, where startService is allowed.
                if (!isBackgrounded) {
                    try {
                        context.startService(intent)
                    } catch (e: IllegalStateException) {
                        // The process may have been backgrounded between the check above and this call,
                        // or the app may be under a background-start restriction we can't observe.
                        Timber.w(e, "🎵 Could not start MusicService in the foreground, binding only")
                    }
                }
                @Suppress("DEPRECATION")
                context.bindService(intent, this@TrackPlayerModule, Context.BIND_AUTO_CREATE)
            }

            // prevent crash Fatal Exception: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException
            if (isBackgrounded) {
                promise.reject(
                    "android_cannot_setup_player_in_background",
                    "On Android the app must be in the foreground when setting up the player."
                )
                // Service binding was initiated above - onServiceConnected() will be called
                // and trackPlayerModule will be set, allowing browse tree to work
                return@launch
            }

            // Validate buffer keys.
            val bundledData = Arguments.toBundle(options)
            val minBuffer =
                bundledData?.getDouble(MusicService.MIN_BUFFER_KEY)?.toMilliseconds()?.toInt()
                    ?: DEFAULT_MIN_BUFFER_MS
            val maxBuffer =
                bundledData?.getDouble(MusicService.MAX_BUFFER_KEY)?.toMilliseconds()?.toInt()
                    ?: DEFAULT_MAX_BUFFER_MS
            val playBuffer =
                bundledData?.getDouble(MusicService.PLAY_BUFFER_KEY)?.toMilliseconds()?.toInt()
                    ?: DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val backBuffer =
                bundledData?.getDouble(MusicService.BACK_BUFFER_KEY)?.toMilliseconds()?.toInt()
                    ?: DEFAULT_BACK_BUFFER_DURATION_MS

            if (playBuffer < 0) {
                promise.reject(
                    "play_buffer_error",
                    "The value for playBuffer should be greater than or equal to zero."
                )
                return@launch
            }

            if (backBuffer < 0) {
                promise.reject(
                    "back_buffer_error",
                    "The value for backBuffer should be greater than or equal to zero."
                )
                return@launch
            }

            if (minBuffer < playBuffer) {
                promise.reject(
                    "min_buffer_error",
                    "The value for minBuffer should be greater than or equal to playBuffer."
                )
                return@launch
            }

            if (maxBuffer < minBuffer) {
                promise.reject(
                    "min_buffer_error",
                    "The value for maxBuffer should be greater than or equal to minBuffer."
                )
                return@launch
            }

            playerSetUpPromise = promise
            playerOptions = bundledData

            LocalBroadcastManager.getInstance(context).registerReceiver(
                com.doublesymmetry.trackplayer.module.MusicEvents(context),
                IntentFilter(EVENT_INTENT)
            )

            // Service binding was already done above (before foreground check)
            // If we're here, app is in foreground, so we can continue with setupPlayer()
            // The service binding initiated above will complete via onServiceConnected()
        }
    }

    private fun readableArrayToTrackList(data: ReadableArray?): MutableList<Track> {
        val bundleList = Arguments.toList(data)
        if (bundleList !is ArrayList) {
            throw RejectionException("invalid_parameter", "Was not given an array of tracks")
        }
        return bundleList.map {
            if (it is Bundle) {
                bundleToTrack(it)
            } else {
                throw RejectionException(
                    "invalid_track_object",
                    "Track was not a dictionary type"
                )
            }
        }.toMutableList()
    }

    private fun readableArrayToMediaItems(data: ArrayList<HashMap<String, String>>): MutableList<MediaItem> {
        return data.map { hashmap ->
            hashmapToMediaItem(hashmap)
        }.filterNotNull().toMutableList()
    }

    /**
     * A browse-tree node, as a media3 [MediaItem].
     *
     * Every extras key is the one the ExoPlayer 2 build used — `androidx.media.utils.MediaConstants`
     * and `androidx.media3.session.MediaConstants` carry the same strings — so Android Auto reads
     * the same content styles, grouping subheadings and playback-progress hints it always did. Only
     * the container moved: `MediaDescriptionCompat.extras` became `MediaMetadata.extras`, and the
     * browsable/playable flag became `MediaMetadata.isBrowsable` / `isPlayable`.
     */
    private fun hashmapToMediaItem(hashmap: HashMap<String, String>): MediaItem {
        val mediaId = hashmap["mediaId"] ?: ""
        val title = hashmap["title"]
        val subtitle = hashmap["subtitle"]
        val mediaUri = hashmap["mediaUri"]
        val iconUri = hashmap["iconUri"]
        // Unchanged, including the inversion: "1" means browsable (MediaItemPlayable.MediaBrowsable).
        val isBrowsable = hashmap["playable"]?.toIntOrNull() == 1

        val extras = Bundle()
        hashmap["groupTitle"]?.let {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it)
        }
        hashmap["contentStyle"]?.toIntOrNull()?.let {
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, it)
        }
        hashmap["childrenPlayableContentStyle"]?.toIntOrNull()?.let {
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it)
        }
        hashmap["childrenBrowsableContentStyle"]?.toIntOrNull()?.let {
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it)
        }

        // playbackProgress should contain a string representation of a number between 0 and 1 if present
        hashmap["playbackProgress"]?.toDoubleOrNull()?.let {
            if (it > 0.98) {
                extras.putInt(
                    MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                )
            } else if (it == 0.0) {
                extras.putInt(
                    MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
                )
            } else {
                extras.putInt(
                    MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                )
                extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, it)
            }
        }

        // MEDIA_TYPE for Google Assistant recommendations. The raw legacy int is kept in the extras
        // under the key legacy browsers read, and media3's own media type is set alongside it.
        val mediaTypeString = hashmap["mediaType"] ?: "AUDIO_BOOK"
        extras.putInt(LEGACY_METADATA_KEY_MEDIA_TYPE, mapContentTypeToMediaConstant(mediaTypeString))

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(isBrowsable)
            .setIsPlayable(!isBrowsable)
            .setMediaType(mapContentTypeToMedia3Type(mediaTypeString))
            .setExtras(extras)

        // Convert URIs for Android Auto compatibility: it reads artwork across a process boundary,
        // so file:// in our private storage is unreadable and http(s):// may not be fetched at all.
        iconUri?.let { metadata.setArtworkUri(browsableUri(it)) }

        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.build())
        if (mediaUri != null) {
            builder.setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(mediaUri)).build()
            )
        }
        return builder.build()
    }

    private fun browsableUri(raw: String): Uri {
        val uri = Uri.parse(raw)
        return when (uri.scheme) {
            "file" -> {
                val converted = UriUtils.convertFileUriToContentUri(reactContext, raw)
                if (converted != null) Uri.parse(converted) else {
                    Timber.tag("RNTP-AA").w("Failed to convert file:// URI, using original: $raw")
                    uri
                }
            }
            "http", "https" -> {
                val cached = UriUtils.convertHttpUriToContentUri(reactContext, raw)
                if (cached != null) Uri.parse(cached) else {
                    Timber.tag("RNTP-AA").w("HTTPS URL not cached locally - Android Auto may not display this image: $raw")
                    Timber.tag("RNTP-AA").w("Consider downloading and caching images before setting iconUri for grid items")
                    uri
                }
            }
            else -> uri
        }
    }

    /** media3's own media type, alongside the legacy int above. */
    private fun mapContentTypeToMedia3Type(contentType: String): Int = when (contentType.uppercase()) {
        "MUSIC" -> MediaMetadata.MEDIA_TYPE_MUSIC
        "ALBUM" -> MediaMetadata.MEDIA_TYPE_ALBUM
        "ARTIST" -> MediaMetadata.MEDIA_TYPE_ARTIST
        "PLAYLIST" -> MediaMetadata.MEDIA_TYPE_PLAYLIST
        "PODCAST_EPISODE" -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
        "RADIO_STATION" -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
        else -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
    }

    /**
     * Maps content type string from React Native to android.media.MediaMetadata.MEDIA_TYPE integer constants.
     * Defaults to MEDIA_TYPE_AUDIOBOOK (11) if type is not recognized.
     * 
     * Media type constants from android.media.MediaMetadata (API 21+):
     * - MEDIA_TYPE_MUSIC = 1
     * - MEDIA_TYPE_ALBUM = 2
     * - MEDIA_TYPE_ARTIST = 3
     * - MEDIA_TYPE_PLAYLIST = 4
     * - MEDIA_TYPE_TV_SHOW_EPISODE = 5
     * - MEDIA_TYPE_PODCAST_EPISODE = 6
     * - MEDIA_TYPE_VIDEO = 7
     * - MEDIA_TYPE_NEWS = 8
     * - MEDIA_TYPE_AUDIOBOOK = 11
     * - MEDIA_TYPE_RADIO_STATION = 12
     */
    private fun mapContentTypeToMediaConstant(contentType: String): Int {
        return when (contentType.uppercase()) {
            "MUSIC" -> 1 // MediaMetadata.MEDIA_TYPE_MUSIC
            "ALBUM" -> 2 // MediaMetadata.MEDIA_TYPE_ALBUM
            "ARTIST" -> 3 // MediaMetadata.MEDIA_TYPE_ARTIST
            "PLAYLIST" -> 4 // MediaMetadata.MEDIA_TYPE_PLAYLIST
            "TV_SHOW_EPISODE" -> 5 // MediaMetadata.MEDIA_TYPE_TV_SHOW_EPISODE
            "PODCAST_EPISODE" -> 6 // MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
            "VIDEO" -> 7 // MediaMetadata.MEDIA_TYPE_VIDEO
            "NEWS" -> 8 // MediaMetadata.MEDIA_TYPE_NEWS
            "AUDIO_BOOK", "AUDIOBOOK" -> 11 // MediaMetadata.MEDIA_TYPE_AUDIOBOOK
            "RADIO_STATION" -> 12 // MediaMetadata.MEDIA_TYPE_RADIO_STATION
            else -> {
                // Default to AUDIOBOOK for unknown types (we're primarily an audiobook app)
                11 // MediaMetadata.MEDIA_TYPE_AUDIOBOOK
            }
        }
    }

    override fun add(tracks: ReadableArray, insertBeforeIndex: Double, promise: Promise) {
        Timber.d("🎵 TurboModule add() called with ${tracks.size()} tracks, insertBeforeIndex: $insertBeforeIndex")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch

            try {
                val trackList = readableArrayToTrackList(tracks)
                val insertIndex = insertBeforeIndex.toInt()
                if (insertIndex < -1 || insertIndex > musicService.tracks.size) {
                    promise.reject("index_out_of_bounds", "The track index is out of bounds")
                    return@launch
                }
                val index = if (insertIndex == -1) musicService.tracks.size else insertIndex
                musicService.add(trackList, index)
                promise.resolve(index)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }


    override fun remove(indexes: ReadableArray, promise: Promise) {
        Timber.d("🎵 TurboModule remove() called with ${indexes.size()} indexes")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val indexList = mutableListOf<Int>()
                for (i in 0 until indexes.size()) {
                    indexList.add(indexes.getInt(i))
                }
                musicService.remove(indexList)
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun updateMetadataForTrack(id: String, metadata: ReadableMap, promise: Promise) {
        Timber.d("🎵 TurboModule updateMetadataForTrack() called with id: $id")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch

            val index = id.toIntOrNull()
            if (index == null) {
                promise.reject("invalid_parameter", "The track id must be a valid integer")
                return@launch
            }

            if (index < 0 || index >= musicService.tracks.size) {
                promise.reject("index_out_of_bounds", "The index is out of bounds")
            } else {
                val track = musicService.tracks[index]
                track.setMetadata(context, Arguments.toBundle(metadata), musicService.ratingType)
                musicService.updateMetadataForTrack(index, track)
                promise.resolve(null)
            }
        }
    }

    override fun play(promise: Promise) {
        Timber.d("🎵 TurboModule play() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.play()
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun pause(promise: Promise) {
        Timber.d("🎵 TurboModule pause() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.pause()
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun stop(promise: Promise) {
        Timber.d("🎵 TurboModule stop() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.stop()
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    // Return basic constants
    override fun getConstants(): MutableMap<String, Any> {
        return mutableMapOf(
            "CAPABILITY_PLAY" to "play",
            "CAPABILITY_PAUSE" to "pause",
            "CAPABILITY_STOP" to "stop",
            "CAPABILITY_NEXT" to "next",
            "CAPABILITY_PREVIOUS" to "previous",
            "CAPABILITY_SEEK_TO" to "seekTo",
            "CAPABILITY_SKIP" to "skip",
            "CAPABILITY_JUMP_FORWARD" to "jumpForward",
            "CAPABILITY_JUMP_BACKWARD" to "jumpBackward",
            "CAPABILITY_SET_RATING" to "setRating",
            "CAPABILITY_LIKE" to "like",
            "CAPABILITY_DISLIKE" to "dislike",
            "CAPABILITY_BOOKMARK" to "bookmark",

            "STATE_NONE" to "none",
            "STATE_PLAYING" to "playing",
            "STATE_PAUSED" to "paused",
            "STATE_STOPPED" to "stopped",
            "STATE_BUFFERING" to "buffering",
            "STATE_READY" to "ready",
            "STATE_CONNECTING" to "connecting",
            "STATE_LOADING" to "loading",
            "STATE_ERROR" to "error",

            "REPEAT_OFF" to 0,
            "REPEAT_TRACK" to 1,
            "REPEAT_QUEUE" to 2,

            "SHUFFLE_OFF" to 0,
            "SHUFFLE_TRACKS" to 1,
            "SHUFFLE_QUEUE" to 2,

            "ANDROID_AUTO_CONTENT_STYLE_LIST" to 0,
            "ANDROID_AUTO_CONTENT_STYLE_GRID" to 1,

            "AppKilledPlaybackBehavior" to mapOf(
                "ContinuePlayback" to "continue-playback",
                "PausePlayback" to "pause-playback",
                "StopPlaybackAndRemoveNotification" to "stop-playback-and-remove-notification"
            ),
            "IOSCategory" to mapOf(
                "Playback" to "playback",
                "Record" to "record",
                "PlayAndRecord" to "playAndRecord",
                "MultiRoute" to "multiRoute"
            ),
            "IOSCategoryMode" to mapOf(
                "Default" to "default",
                "GameChat" to "gameChat",
                "Measurement" to "measurement",
                "MoviePlayback" to "moviePlayback",
                "SpokenAudio" to "spokenAudio",
                "VideoChat" to "videoChat",
                "VideoRecording" to "videoRecording",
                "VoiceChat" to "voiceChat",
                "VoicePrompt" to "voicePrompt"
            ),
            "IOSCategoryOptions" to mapOf(
                "MixWithOthers" to "mixWithOthers",
                "DuckOthers" to "duckOthers",
                "InterruptSpokenAudioAndMixWithOthers" to "interruptSpokenAudioAndMixWithOthers",
                "AllowBluetooth" to "allowBluetooth",
                "AllowBluetoothA2DP" to "allowBluetoothA2DP",
                "AllowAirPlay" to "allowAirPlay",
                "DefaultToSpeaker" to "defaultToSpeaker"
            ),
            "AndroidAudioContentType" to mapOf(
                "Speech" to "speech",
                "Music" to "music",
                "Movie" to "movie",
                "Sonification" to "sonification"
            ),
            "AndroidAutoContentStyle" to mapOf(
                "List" to 0,
                "Grid" to 1
            ),
            "PitchAlgorithm" to mapOf(
                "Linear" to "linear",
                "Music" to "music",
                "Voice" to "voice"
            ),
            "TrackType" to mapOf(
                "Default" to "default",
                "Dash" to "dash",
                "HLS" to "hls",
                "SmoothStreaming" to "smoothstreaming"
            )
        )
    }

    override fun reset(promise: Promise) {
        Timber.d("🎵 TurboModule reset() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.stop()
                delay(300) // Allow playback to stop
                musicService.clear()
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun seekTo(position: Double, promise: Promise) {
        Timber.d("🎵 TurboModule seekTo() called with position: $position")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.seekTo(position.toFloat())
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    /**
     * Replace the queue, the index and the start position in one call — the bridge half of
     * [com.doublesymmetry.kotlinaudio.players.BaseAudioPlayer.loadQueue].
     *
     * This is what JS's `loadTracksAndSeekToCurrent` calls instead of `reset()` + `add()` +
     * `skip(index, position)`: the start position is part of the load, so no progress or
     * track-changed event can report 0 before the target.
     */
    override fun loadQueue(
        tracks: ReadableArray,
        startIndex: Double,
        startPositionSec: Double,
        playWhenReady: Boolean,
        promise: Promise
    ) {
        Timber.d("🎵 TurboModule loadQueue() called with ${tracks.size()} tracks, startIndex: $startIndex, startPositionSec: $startPositionSec, playWhenReady: $playWhenReady")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val trackList = readableArrayToTrackList(tracks)
                if (trackList.isEmpty()) {
                    promise.reject("invalid_parameter", "The track list is empty")
                    return@launch
                }
                val index = startIndex.toInt()
                if (index < 0 || index >= trackList.size) {
                    promise.reject("index_out_of_bounds", "The track index is out of bounds")
                    return@launch
                }
                musicService.loadQueue(trackList, index, startPositionSec, playWhenReady)
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun setStopAt(positionSec: Double, promise: Promise) {
        Timber.d("🎵 TurboModule setStopAt() called with position: $positionSec")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.setStopAt(positionSec)
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun clearStopAt(promise: Promise) {
        Timber.d("🎵 TurboModule clearStopAt() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.clearStopAt()
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun setVolume(volume: Double, promise: Promise) {
        Timber.d("🎵 TurboModule setVolume() called with volume: $volume")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.setVolume(volume.toFloat())
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getVolume(promise: Promise) {
        Timber.d("🎵 TurboModule getVolume() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.getVolume().toDouble())
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun setRate(rate: Double, promise: Promise) {
        Timber.d("🎵 TurboModule setRate() called with rate: $rate")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.setRate(rate.toFloat())
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getRate(promise: Promise) {
        Timber.d("🎵 TurboModule getRate() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.getRate().toDouble())
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getPosition(promise: Promise) {
        Timber.d("🎵 TurboModule getPosition() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.getPositionInSeconds())
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getBufferedPosition(promise: Promise) {
        Timber.d("🎵 TurboModule getBufferedPosition() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.getBufferedPositionInSeconds())
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getDuration(promise: Promise) {
        Timber.d("🎵 TurboModule getDuration() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.getDurationInSeconds())
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getState(promise: Promise) {
        Timber.d("🎵 TurboModule getState() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.state.asLibState.state)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getCurrentTrack(promise: Promise) {
        Timber.d("🎵 TurboModule getCurrentTrack() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val index = if (musicService.tracks.isEmpty()) null else musicService.getCurrentTrackIndex()
                promise.resolve(index)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getActiveTrack(promise: Promise) {
        Timber.d("🎵 TurboModule getActiveTrack() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                if (musicService.tracks.isEmpty()) {
                    promise.resolve(null)
                } else {
                    val originalItem = musicService.tracks[musicService.getCurrentTrackIndex()].originalItem
                    promise.resolve(if (originalItem != null) Arguments.fromBundle(originalItem) else null)
                }
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getTrack(index: Double, promise: Promise) {
        Timber.d("🎵 TurboModule getTrack() called with index: $index")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val track = musicService.tracks.getOrNull(index.toInt())
                promise.resolve(track?.originalItem)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getQueue(promise: Promise) {
        Timber.d("🎵 TurboModule getQueue() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val tracks = musicService.tracks.mapNotNull { it.originalItem }
                promise.resolve(Arguments.fromList(tracks))
            } catch (exception: Exception) {
                Timber.e(exception, "🎵 TurboModule getQueue() error")
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getCurrentIndex(promise: Promise) {
        Timber.d("🎵 TurboModule getCurrentIndex() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(
                    if (musicService.tracks.isEmpty()) null else musicService.getCurrentTrackIndex()
                )
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun skipToNext(promise: Promise) {
        Timber.d("🎵 TurboModule skipToNext() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.skipToNext()
                promise.resolve(null)
            } catch (e: Exception) {
                Timber.e(e, "skipToNext error")
                promise.reject("skip_to_next_error", e.message, e)
            }
        }
    }

    override fun skipToPrevious(promise: Promise) {
        Timber.d("🎵 TurboModule skipToPrevious() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.skipToPrevious()
                promise.resolve(null)
            } catch (e: Exception) {
                Timber.e(e, "skipToPrevious error")
                promise.reject("skip_to_previous_error", e.message, e)
            }
        }
    }

    override fun skip(index: Double, initialPosition: Double?, promise: Promise) {
        Timber.d("🎵 TurboModule skip() called with index: $index, initialPosition: $initialPosition")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val initialPositionSeconds =
                    if (initialPosition != null && initialPosition >= 0) initialPosition.toFloat() else null
                musicService.skip(index.toInt(), initialPositionSeconds)
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun skipToTrack(index: Double, promise: Promise) {
        Timber.d("🎵 TurboModule skipToTrack() called with index: $index")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.skip(index.toInt())
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun removeUpcomingTracks(promise: Promise) {
        Timber.d("🎵 TurboModule removeUpcomingTracks() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.removeUpcomingTracks()
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun updateNowPlayingMetadata(metadata: ReadableMap, promise: Promise) {
        Timber.d("🎵 TurboModule updateNowPlayingMetadata() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch

            if (musicService.tracks.isEmpty()) {
                promise.reject("no_current_item", "There is no current item in the player")
                return@launch
            }

            try {
                Arguments.toBundle(metadata)?.let {
                    val track = bundleToTrack(it)
                    musicService.updateNowPlayingMetadata(track)
                }
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun clearNowPlayingMetadata(promise: Promise) {
        Timber.d("🎵 TurboModule clearNowPlayingMetadata() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch

            if (musicService.tracks.isEmpty()) {
                promise.reject("no_current_item", "There is no current item in the player")
                return@launch
            }

            try {
                musicService.clearNotificationMetadata()
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun setRepeatMode(mode: Double, promise: Promise) {
        Timber.d("🎵 TurboModule setRepeatMode() called with mode: $mode")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.setRepeatMode(RepeatMode.fromOrdinal(mode.toInt()))
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getRepeatMode(promise: Promise) {
        Timber.d("🎵 TurboModule getRepeatMode() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.getRepeatMode().ordinal)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getPlaybackState(promise: Promise) {
        Timber.d("🎵 TurboModule getPlaybackState() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(Arguments.fromBundle(musicService.getPlayerStateBundle(musicService.state)))
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getPlaybackRate(promise: Promise) {
        Timber.d("🎵 TurboModule getPlaybackRate() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.getRate().toDouble())
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun setPlaybackRate(rate: Double, promise: Promise) {
        Timber.d("🎵 TurboModule setPlaybackRate() called with rate: $rate")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.setRate(rate.toFloat())
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getPlayWhenReady(promise: Promise) {
        Timber.d("🎵 TurboModule getPlayWhenReady() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(musicService.playWhenReady)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean, promise: Promise) {
        Timber.d("🎵 TurboModule setPlayWhenReady() called with playWhenReady: $playWhenReady")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.playWhenReady = playWhenReady
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun setBrowseTree(tree: ReadableMap, promise: Promise) {
        Timber.d("🎵 TurboModule setBrowseTree() called")
        scope.launch {
            // Allow setBrowseTree() even if service isn't bound yet (for Android Auto background initialization)
            // We can still set the browse tree on MusicService directly
            if (!isServiceBound) {
                Timber.w("setBrowseTree() called but service not bound yet - using MusicService.getInstance()")
                
                // Try to get MusicService instance directly
                val musicServiceInstance = com.doublesymmetry.trackplayer.service.MusicService.getInstance()
                if (musicServiceInstance == null) {
                    Timber.e("setBrowseTree() - MusicService instance is null, rejecting")
                    promise.reject(
                        "service_not_available",
                        "MusicService is not available. Please wait for service to initialize."
                    )
                    return@launch
                }
                
                // Set browse tree directly on MusicService instance
                try {
                    val mediaItemsMap = tree.toHashMap()
                    musicServiceInstance.mediaTree = mediaItemsMap.mapValues { entry ->
                        val rawItems = entry.value as ArrayList<HashMap<String, String>>
                        readableArrayToMediaItems(rawItems)
                    }
                    Timber.d("Browse tree set directly on MusicService (service not bound)")
                    mediaItemsMap.keys.forEach {
                        musicServiceInstance.notifyChildrenChanged(it)
                    }
                    
                    // Send pending browse results
                    musicServiceInstance.sendPendingBrowseResults()
                    
                    promise.resolve(musicServiceInstance.mediaTree.toString())
                    return@launch
                } catch (exception: Exception) {
                    Timber.e(exception, "setBrowseTree error")
                    promise.reject("runtime_exception", exception.message, exception)
                    return@launch
                }
            }
            
            try {
                val mediaItemsMap = tree.toHashMap()
                
                musicService.mediaTree = mediaItemsMap.mapValues { entry ->
                    val rawItems = entry.value as ArrayList<HashMap<String, String>>
                    readableArrayToMediaItems(rawItems)
                }
                Timber.d("refreshing browseTree")
                mediaItemsMap.keys.forEach {
                    musicService.notifyChildrenChanged(it)
                }
                
                // Send pending browse results now that browse tree is populated
                musicService.sendPendingBrowseResults()
                
                promise.resolve(musicService.mediaTree.toString())
            } catch (exception: Exception) {
                Timber.tag("RNTP-AA").e(exception, "setBrowseTree error")
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun updateOptions(options: ReadableMap, promise: Promise) {
        Timber.d("🎵 TurboModule updateOptions() called")
        scope.launch {
            Timber.d("🎵 TurboModule updateOptions: inside coroutine")
            if (verifyServiceBoundOrReject(promise)) return@launch
            Timber.d("🎵 TurboModule updateOptions: service bound verification passed")
            try {
                Timber.d("🎵 TurboModule updateOptions: converting options to Bundle")
                val bundle = Arguments.toBundle(options) ?: Bundle()
                Timber.d("🎵 TurboModule updateOptions: calling musicService.updateOptions")
                musicService.updateOptions(bundle)
                Timber.d("🎵 TurboModule updateOptions: musicService.updateOptions completed")
                promise.resolve(null)
                Timber.d("🎵 TurboModule updateOptions: promise resolved")
            } catch (exception: Exception) {
                Timber.e(exception, "🎵 TurboModule updateOptions: exception occurred")
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    // Additional methods needed by AudioPlayer.ts
    override fun load(track: ReadableMap, promise: Promise) {
        Timber.d("🎵 TurboModule load() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.load(bundleToTrack(Arguments.toBundle(track) ?: Bundle()))
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun seekBy(offset: Double, promise: Promise) {
        Timber.d("🎵 TurboModule seekBy() called with offset: $offset")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.seekBy(offset.toFloat())
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getProgress(promise: Promise) {
        Timber.d("🎵 TurboModule getProgress() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val progress = Arguments.createMap().apply {
                    putDouble("duration", musicService.getDurationInSeconds())
                    putDouble("position", musicService.getPositionInSeconds())
                    putDouble("buffered", musicService.getBufferedPositionInSeconds())
                }
                promise.resolve(progress)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun getActiveTrackIndex(promise: Promise) {
        Timber.d("🎵 TurboModule getActiveTrackIndex() called")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                promise.resolve(
                    if (musicService.tracks.isEmpty()) null else musicService.getCurrentTrackIndex()
                )
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }


    override fun setBrowseTreeStyle(contentStyleGrid: Double, contentStyleList: Double, promise: Promise) {
        Timber.d("🎵 TurboModule setBrowseTreeStyle() called with grid: $contentStyleGrid, list: $contentStyleList")
        scope.launch {
            fun getStyle(check: Int): Int {
                return when (check) {
                    2 -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                    3 -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
                    4 -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
                    else -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                }
            }
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.mediaTreeStyle = listOf(
                    getStyle(contentStyleGrid.toInt()),
                    getStyle(contentStyleList.toInt())
                )
                promise.resolve(null)
            } catch (exception: Exception) {
                promise.reject("runtime_exception", exception.message, exception)
            }
        }
    }

    override fun destroy(promise: Promise) {
        Timber.d("🎵 TurboModule destroy() called")
        promise.resolve(null)
    }

    // ===== MusicServiceEventListener Implementation =====
    // These methods are called by MusicService when events occur
    // We then call the Codegen-generated emit methods to send events to JavaScript
    
    override fun onPlaybackState(state: String, data: Bundle) {
        // The whole payload, not just the legacy string: `transport` is what the JS button binds to
        // as of step 3, and "did the button ever leave pause during this load" is a question you
        // answer from this line.
        Timber.d(
            "🎵 TrackPlayerModule.onPlaybackState: state=%s transport=%s readiness=%s reason=%s playWhenReady=%s isPlaying=%s suppression=%s",
            state,
            data.getString("transport"),
            data.getString("readiness"),
            data.getString("reason"),
            data.getBoolean("playWhenReady"),
            data.getBoolean("isPlaying"),
            data.getString("suppression"),
        )
        emit {
            val stateObj = Arguments.createMap().apply {
                putString("state", state)
                // If there's error data in the bundle, add it
                if (data.containsKey("error")) {
                    data.getBundle("error")?.let { errorBundle ->
                        putMap("error", Arguments.fromBundle(errorBundle))
                    }
                }
                // Additive PlayerCore fields — the legacy `state` string above is unchanged, these
                // ride alongside it (see MusicService.getPlayerStateBundle).
                if (data.containsKey("playWhenReady")) putBoolean("playWhenReady", data.getBoolean("playWhenReady"))
                if (data.containsKey("isPlaying")) putBoolean("isPlaying", data.getBoolean("isPlaying"))
                data.getString("transport")?.let { putString("transport", it) }
                data.getString("readiness")?.let { putString("readiness", it) }
                data.getString("reason")?.let { putString("reason", it) }
                data.getString("suppression")?.let { putString("suppression", it) }
            }
            emitOnPlaybackState(stateObj)
        }
    }
    
    override fun onPlaybackProgressUpdated(data: Bundle) {
        Timber.d(
            "🎵 TrackPlayerModule.onPlaybackProgressUpdated: position=%s duration=%s",
            data.getDouble("position"),
            data.getDouble("duration"),
        )
        emit {
            emitOnPlaybackProgressUpdated(Arguments.fromBundle(data))
        }
    }
    
    override fun onPlaybackActiveTrackChanged(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackActiveTrackChanged")
        emit {
            emitOnPlaybackActiveTrackChanged(Arguments.fromBundle(data))
        }
    }
    
    override fun onPlaybackQueueEnded(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackQueueEnded")
        emit {
            emitOnPlaybackQueueEnded(Arguments.fromBundle(data))
        }
    }
    
    override fun onPlaybackError(error: String, data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackError: $error")
        emit {
            emitOnPlaybackError(Arguments.fromBundle(data))
        }
    }
    
    override fun onPlaybackPlayWhenReadyChanged(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackPlayWhenReadyChanged")
        emit {
            emitOnPlaybackPlayWhenReadyChanged(Arguments.fromBundle(data))
        }
    }

    override fun onPlaybackStopAtReached(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackStopAtReached")
        emit {
            emitOnPlaybackStopAtReached(Arguments.fromBundle(data))
        }
    }
    
    // Remote control events
    override fun onRemotePlay() {
        Timber.d("🎵 TrackPlayerModule.onRemotePlay")
        emit {
            emitOnRemotePlay()
        }
    }
    
    override fun onRemotePause() {
        Timber.d("🎵 TrackPlayerModule.onRemotePause")
        emit {
            emitOnRemotePause()
        }
    }
    
    override fun onRemoteStop() {
        Timber.d("🎵 TrackPlayerModule.onRemoteStop")
        emit {
            emitOnRemoteStop()
        }
    }
    
    override fun onRemoteNext() {
        Timber.d("🎵 TrackPlayerModule.onRemoteNext")
        emit {
            emitOnRemoteNext()
        }
    }
    
    override fun onRemotePrevious() {
        Timber.d("🎵 TrackPlayerModule.onRemotePrevious")
        emit {
            emitOnRemotePrevious()
        }
    }
    
    override fun onRemoteSeek(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteSeek")
        emit {
            emitOnRemoteSeek(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteJumpForward(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteJumpForward")
        emit {
            emitOnRemoteJumpForward(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteJumpBackward(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteJumpBackward")
        emit {
            emitOnRemoteJumpBackward(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteBookmark() {
        Timber.d("🎵 TrackPlayerModule.onRemoteBookmark")
        emit {
            emitOnRemoteBookmark()
        }
    }
    
    override fun onRemotePlayId(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemotePlayId")
        emit {
            emitOnRemotePlayId(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteBrowse(data: Bundle) {
        emit {
            emitOnRemoteBrowse(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemotePlayFromSearch(data: Bundle) {
        val query = data.getString("query") ?: ""
        Timber.tag("GVA-RNTP").d("TrackPlayerModule.onRemotePlayFromSearch called with query: '$query'")
        emit {
            try {
                emitOnRemotePlayFromSearch(Arguments.fromBundle(data))
                Timber.tag("GVA-RNTP").d("TrackPlayerModule.onRemotePlayFromSearch event emitted successfully")
            } catch (e: Exception) {
                Timber.tag("GVA-RNTP").e(e, "Error emitting onRemotePlayFromSearch")
            }
        }
    }
    
    override fun onRemotePrepareId(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemotePrepareId")
        emit {
            emitOnRemotePrepareId(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemotePrepareFromSearch(data: Bundle) {
        val query = data.getString("query") ?: ""
        Timber.tag("GVA-RNTP").d("TrackPlayerModule.onRemotePrepareFromSearch called with query: '$query'")
        emit {
            try {
                emitOnRemotePrepareFromSearch(Arguments.fromBundle(data))
                Timber.tag("GVA-RNTP").d("TrackPlayerModule.onRemotePrepareFromSearch event emitted successfully")
            } catch (e: Exception) {
                Timber.tag("GVA-RNTP").e(e, "Error emitting onRemotePrepareFromSearch")
            }
        }
    }
    
    override fun onRemoteSkip(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteSkip")
        emit {
            emitOnRemoteSkip(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteDuck(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteDuck")
        emit {
            emitOnRemoteDuck(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteSearch(data: Bundle) {
        Timber.tag("TrackPlayerModule").d("onRemoteSearch")
        emit {
            emitOnRemoteSearch(Arguments.fromBundle(data))
        }
    }
    
    // ===== Error Reporting =====
    
    /**
     * Set PlaybackState error for Google Assistant recognition.
     * Called from React Native when content is not found or actions fail.
     * 
     * @param errorCode PlaybackStateCompat error code (e.g., ERROR_CODE_NOT_SUPPORTED, ERROR_CODE_APP_ERROR)
     * @param errorMessage User-readable error message
     */
    override fun setPlaybackStateError(errorCode: Double, errorMessage: String, promise: Promise) {
        // The guard was inverted (`if (!verifyServiceBoundOrReject(promise)) return`), and
        // `verifyServiceBoundOrReject` returns true when it *rejected*: with the service bound —
        // the only case in which this can do anything — the method returned immediately, without
        // setting the error and without settling the promise, so the `await` in JS never came back.
        // Found while checking §4's "setPlaybackStateError producing a legacy STATE_ERROR with a
        // code"; it had never produced one.
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                musicService.setPlaybackStateError(errorCode.toInt(), errorMessage)
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("error_setting_playback_state_error", e.message ?: "Unknown error", e)
            }
        }
    }
    
    /**
     * Send search results back to MusicService to complete a search request
     * Called from React Native after handleSearch returns results
     * @param results Array of track objects with metadata: { mediaId, title, artist?, album?, artwork? }
     */
    override fun sendSearchResults(searchId: String, results: ReadableArray, promise: Promise) {
        Timber.tag("RNTP-AA").d("sendSearchResults called with searchId: $searchId, count: ${results.size()}")
        scope.launch {
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val trackResults = mutableListOf<Map<String, String?>>()
                for (i in 0 until results.size()) {
                    val resultMap = results.getMap(i)
                    if (resultMap != null) {
                        val duration = resultMap.getDouble("duration")
                        val trackData = mapOf(
                            "mediaId" to resultMap.getString("mediaId"),
                            "title" to resultMap.getString("title"),
                            "artist" to resultMap.getString("artist"),
                            "album" to resultMap.getString("album"),
                            "artwork" to resultMap.getString("artwork"),
                            "url" to resultMap.getString("url"),
                            "duration" to if (!duration.isNaN()) duration.toString() else null
                        )
                        trackResults.add(trackData)
                    }
                }
                musicService.sendSearchResults(searchId, trackResults)
                promise.resolve(null)
            } catch (e: Exception) {
                Timber.tag("RNTP-AA").e(e, "Error in sendSearchResults")
                promise.reject("error_sending_search_results", e.message ?: "Unknown error", e)
            }
        }
    }
}