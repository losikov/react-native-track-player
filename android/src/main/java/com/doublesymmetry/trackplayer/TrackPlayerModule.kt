package com.doublesymmetry.trackplayer

import android.content.*
import android.content.Context
import android.media.MediaDescription
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.net.Uri
import android.support.v4.media.RatingCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.utils.MediaConstants
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
import com.facebook.react.bridge.*
import com.google.android.exoplayer2.DefaultLoadControl.*
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import javax.annotation.Nonnull

/**
 * TurboModule implementation for react-native-track-player
 * This provides New Architecture support with full functionality from MusicModule
 */
class TrackPlayerModule(
    private val reactContext: ReactApplicationContext
) : NativeRTNTrackPlayerSpec(reactContext), ServiceConnection, MusicServiceEventListener {

    companion object {
        const val NAME = "RTNTrackPlayer"
        const val EVENT_INTENT = "com.doublesymmetry.trackplayer.event"
        
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
                Timber.d("🎵 TrackPlayerModule setting up player with options")
                musicService.setupPlayer(playerOptions)
                
                // Set this TurboModule as the event listener - clean architecture!
                Timber.d("🎵 TrackPlayerModule setting event listener")
                musicService.trackPlayerModule = this@TrackPlayerModule
                
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

            // prevent crash Fatal Exception: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppForegroundTracker.backgrounded) {
                promise.reject(
                    "android_cannot_setup_player_in_background",
                    "On Android the app must be in the foreground when setting up the player."
                )
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

            Intent(context, MusicService::class.java).also { intent ->
                Timber.d("🎵 TrackPlayerModule starting MusicService")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                @Suppress("DEPRECATION")
                val bindResult = context.bindService(
                    intent,
                    this@TrackPlayerModule,
                    1 // Context.BIND_AUTO_CREATE = 1
                )
                Timber.d("🎵 TrackPlayerModule bindService result: $bindResult")
                
                // Wait for service to be bound before resolving
                if (!bindResult) {
                    promise.reject("service_bind_failed", "Failed to bind to MusicService")
                    return@launch
                }
            }
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
        return data.map {
            hashmapToMediaItem(it)
        }.toMutableList()
    }

    private fun hashmapToMediaItem(hashmap: HashMap<String, String>): MediaItem {
        val mediaId = hashmap["mediaId"]
        val title = hashmap["title"]
        val subtitle = hashmap["subtitle"]
        val mediaUri = hashmap["mediaUri"]
        val iconUri = hashmap["iconUri"]
        val playableFlag = if (hashmap["playable"]?.toInt() == 1) MediaItem.FLAG_BROWSABLE else MediaItem.FLAG_PLAYABLE

        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)
        mediaDescriptionBuilder.setTitle(title)
        mediaDescriptionBuilder.setSubtitle(subtitle)
        mediaDescriptionBuilder.setMediaUri(if (mediaUri != null) Uri.parse(mediaUri) else null)
        mediaDescriptionBuilder.setIconUri(if (iconUri != null) Uri.parse(iconUri) else null)
        val extras = Bundle()
        hashmap["groupTitle"]?.let {
            extras.putString(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it)
        }
        hashmap["contentStyle"]?.toInt()?.let {
            extras.putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, it)
        }
        hashmap["childrenPlayableContentStyle"]?.toInt()?.let {
            extras.putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it)
        }
        hashmap["childrenBrowsableContentStyle"]?.toInt()?.let {
            extras.putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it)
        }

        // playbackProgress should contain a string representation of a number between 0 and 1 if present
        hashmap["playbackProgress"]?.toDouble()?.let {
            if (it > 0.98) {
                extras.putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
            } else if (it == 0.0) {
                extras.putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
            } else {
                extras.putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                extras.putDouble(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, it)
            }
        }

        // MEDIA_TYPE: Add media type to extras for Google Assistant recommendations
        // mediaType is required in TypeScript, but we default to AUDIOBOOK for safety
        // Use string key directly as MediaConstants doesn't have METADATA_KEY_MEDIA_TYPE in ExoPlayer 2.19.1
        val mediaTypeString = hashmap["mediaType"] ?: "AUDIO_BOOK"
        val mediaType = mapContentTypeToMediaConstant(mediaTypeString)
        extras.putInt("android.media.metadata.MEDIA_TYPE", mediaType)

        mediaDescriptionBuilder.setExtras(extras)
        return MediaItem(mediaDescriptionBuilder.build(), playableFlag)
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
                musicService.skip(index.toInt())
                if (initialPosition != null && initialPosition >= 0) {
                    musicService.seekTo(initialPosition.toFloat())
                }
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
            if (verifyServiceBoundOrReject(promise)) return@launch
            try {
                val mediaItemsMap = tree.toHashMap()
                musicService.mediaTree = mediaItemsMap.mapValues { 
                    readableArrayToMediaItems(it.value as ArrayList<HashMap<String, String>>) 
                }
                Timber.d("refreshing browseTree")
                mediaItemsMap.keys.forEach {
                    musicService.notifyChildrenChanged(it)
                }
                promise.resolve(musicService.mediaTree.toString())
            } catch (exception: Exception) {
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
                    2 -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                    3 -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
                    4 -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
                    else -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
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
        Timber.d("🎵 TrackPlayerModule.onPlaybackState: $state")
        scope.launch {
            val stateObj = Arguments.createMap().apply {
                putString("state", state)
                // If there's error data in the bundle, add it
                if (data.containsKey("error")) {
                    data.getBundle("error")?.let { errorBundle ->
                        putMap("error", Arguments.fromBundle(errorBundle))
                    }
                }
            }
            emitOnPlaybackState(stateObj)
        }
    }
    
    override fun onPlaybackProgressUpdated(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackProgressUpdated")
        scope.launch {
            emitOnPlaybackProgressUpdated(Arguments.fromBundle(data))
        }
    }
    
    override fun onPlaybackActiveTrackChanged(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackActiveTrackChanged")
        scope.launch {
            emitOnPlaybackActiveTrackChanged(Arguments.fromBundle(data))
        }
    }
    
    override fun onPlaybackQueueEnded(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackQueueEnded")
        scope.launch {
            emitOnPlaybackQueueEnded(Arguments.fromBundle(data))
        }
    }
    
    override fun onPlaybackError(error: String, data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackError: $error")
        scope.launch {
            emitOnPlaybackError(Arguments.fromBundle(data))
        }
    }
    
    override fun onPlaybackPlayWhenReadyChanged(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onPlaybackPlayWhenReadyChanged")
        scope.launch {
            emitOnPlaybackPlayWhenReadyChanged(Arguments.fromBundle(data))
        }
    }
    
    // Remote control events
    override fun onRemotePlay() {
        Timber.d("🎵 TrackPlayerModule.onRemotePlay")
        scope.launch {
            emitOnRemotePlay()
        }
    }
    
    override fun onRemotePause() {
        Timber.d("🎵 TrackPlayerModule.onRemotePause")
        scope.launch {
            emitOnRemotePause()
        }
    }
    
    override fun onRemoteStop() {
        Timber.d("🎵 TrackPlayerModule.onRemoteStop")
        scope.launch {
            emitOnRemoteStop()
        }
    }
    
    override fun onRemoteNext() {
        Timber.d("🎵 TrackPlayerModule.onRemoteNext")
        scope.launch {
            emitOnRemoteNext()
        }
    }
    
    override fun onRemotePrevious() {
        Timber.d("🎵 TrackPlayerModule.onRemotePrevious")
        scope.launch {
            emitOnRemotePrevious()
        }
    }
    
    override fun onRemoteSeek(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteSeek")
        scope.launch {
            emitOnRemoteSeek(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteJumpForward(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteJumpForward")
        scope.launch {
            emitOnRemoteJumpForward(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteJumpBackward(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteJumpBackward")
        scope.launch {
            emitOnRemoteJumpBackward(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteBookmark() {
        Timber.d("🎵 TrackPlayerModule.onRemoteBookmark")
        scope.launch {
            emitOnRemoteBookmark()
        }
    }
    
    override fun onRemotePlayId(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemotePlayId")
        scope.launch {
            emitOnRemotePlayId(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteBrowse(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteBrowse")
        scope.launch {
            emitOnRemoteBrowse(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemotePlayFromSearch(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemotePlayFromSearch")
        scope.launch {
            emitOnRemotePlayFromSearch(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemotePrepareId(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemotePrepareId")
        scope.launch {
            emitOnRemotePrepareId(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemotePrepareFromSearch(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemotePrepareFromSearch")
        scope.launch {
            emitOnRemotePrepareFromSearch(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteSkip(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteSkip")
        scope.launch {
            emitOnRemoteSkip(Arguments.fromBundle(data))
        }
    }
    
    override fun onRemoteDuck(data: Bundle) {
        Timber.d("🎵 TrackPlayerModule.onRemoteDuck")
        scope.launch {
            emitOnRemoteDuck(Arguments.fromBundle(data))
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
        if (!verifyServiceBoundOrReject(promise)) return
        
        try {
            val player = musicService.getPlayer()
            if (player != null) {
                player.setPlaybackStateError(errorCode.toInt(), errorMessage)
                promise.resolve(null)
            } else {
                promise.reject("player_not_initialized", "Player not initialized")
            }
        } catch (e: Exception) {
            promise.reject("error_setting_playback_state_error", e.message ?: "Unknown error", e)
        }
    }
}