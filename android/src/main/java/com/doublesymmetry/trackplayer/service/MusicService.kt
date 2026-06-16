package com.doublesymmetry.trackplayer.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.RatingCompat
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.PRIORITY_LOW
import androidx.media.utils.MediaConstants
import com.doublesymmetry.kotlinaudio.models.*
import com.doublesymmetry.kotlinaudio.models.NotificationButton.*
import com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer
import com.doublesymmetry.trackplayer.HeadlessJsMediaService
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toMilliseconds
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toSeconds
import com.doublesymmetry.trackplayer.extensions.asLibState
import com.doublesymmetry.trackplayer.extensions.find
import com.doublesymmetry.trackplayer.model.MetadataAdapter
import com.doublesymmetry.trackplayer.model.PlaybackMetadata
import com.doublesymmetry.trackplayer.model.Track
import com.doublesymmetry.trackplayer.model.TrackAudioItem
import com.doublesymmetry.trackplayer.module.MusicEvents
import com.doublesymmetry.trackplayer.module.MusicEvents.Companion.METADATA_PAYLOAD_KEY
import com.doublesymmetry.trackplayer.utils.BundleUtils
import com.doublesymmetry.trackplayer.utils.BundleUtils.setRating
import com.doublesymmetry.trackplayer.utils.UriUtils
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import com.doublesymmetry.trackplayer.R as TrackPlayerR
import com.google.android.exoplayer2.ui.R as ExoPlayerR

/**
 * Interface for listening to MusicService events
 * This allows TurboModule to receive events without using old DeviceEventEmitter
 * Matches the EventEmitters defined in js/NativeRTNTrackPlayer.ts
 */
interface MusicServiceEventListener {
    // Playback events
    fun onPlaybackState(state: String, data: Bundle)
    fun onPlaybackProgressUpdated(data: Bundle)
    fun onPlaybackActiveTrackChanged(data: Bundle)
    fun onPlaybackQueueEnded(data: Bundle)
    fun onPlaybackError(error: String, data: Bundle)
    fun onPlaybackPlayWhenReadyChanged(data: Bundle)
    
    // Remote control events
    fun onRemotePlay()
    fun onRemotePause()
    fun onRemoteStop()
    fun onRemoteNext()
    fun onRemotePrevious()
    fun onRemoteSeek(data: Bundle)
    fun onRemoteJumpForward(data: Bundle)
    fun onRemoteJumpBackward(data: Bundle)
    fun onRemoteBookmark()
    fun onRemotePlayId(data: Bundle)
    fun onRemoteBrowse(data: Bundle)
    fun onRemotePlayFromSearch(data: Bundle)
    fun onRemoteSkip(data: Bundle)
    // PREPARE actions for reduced latency
    fun onRemotePrepareId(data: Bundle)
    fun onRemotePrepareFromSearch(data: Bundle)
    
    // Search events
    fun onRemoteSearch(data: Bundle)
    
    // Audio interruption events
    fun onRemoteDuck(data: Bundle)
}

@MainThread
class MusicService : HeadlessJsMediaService(), AudioManager.OnAudioFocusChangeListener {
    private lateinit var player: QueuedAudioPlayer
    
    /**
     * Get the player instance (for error reporting from external components)
     */
    fun getPlayer(): QueuedAudioPlayer? {
        return if (::player.isInitialized) player else null
    }
    private val binder = MusicBinder()
    private val scope = MainScope()
    private var progressUpdateJob: Job? = null
    var mediaTree: Map<String, List<MediaItem>> = HashMap()
    var mediaTreeStyle: List<Int> = listOf(
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    
    // Direct reference to TrackPlayerModule for New Architecture events
    var trackPlayerModule: MusicServiceEventListener? = null
    
    // Audio focus handling
    private var audioManager: AudioManager? = null
    private var interruptionStartTime: Long? = null
    
    // Search result callbacks - map search ID to Result callback
    private val pendingSearchResults = mutableMapOf<String, Result<List<MediaItem>>>()
    private var searchIdCounter = 0
    
    // Pending search requests - queue search requests when TrackPlayerModule isn't ready yet
    private data class PendingSearchRequest(
        val query: String,
        val extras: Bundle?,
        val result: Result<List<MediaItem>>
    )
    private val pendingSearchRequests = mutableListOf<PendingSearchRequest>()
    
    // Browse result callbacks - map mediaId to Result callback (for lazy initialization)
    private val pendingBrowseResults = mutableMapOf<String, Result<List<MediaItem>>>()
    private var isInitializingReactNative = false

    @ExperimentalCoroutinesApi
    override fun onCreate() {
        Timber.tag("GVA-RNTP").d("RNTP musicservice created.")
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        MusicService.setInstance(this)
    }

    /**
     * Use [appKilledPlaybackBehavior] instead.
     */
    @Deprecated("This will be removed soon")
    var stoppingAppPausesPlayback = true
        private set

    @SuppressLint("VisibleForTests")
    override fun onGetRoot(
            clientPackageName: String,
            clientUid: Int,
            rootHints: Bundle?
    ): BrowserRoot {
        // Detect Android Auto connection and ensure audio routing when playback is active
        if (clientPackageName == "com.google.android.projection.gearhead") {
            scope.launch {
                kotlinx.coroutines.delay(500)
                if (::player.isInitialized && player.isPlaying) {
                    // Force audio session re-initialization to route audio to Android Auto
                    // This is necessary because ExoPlayer 2.x doesn't automatically re-route
                    // audio when Android Auto connects while playback is active
                    player.ensureAudioSessionInitialized()
                }
            }
        }
        
        // Log root hints for debugging
        if (rootHints != null) {
            val artSizeHint = rootHints.getInt("android.media.browse.EXTRA_MEDIA_ART_SIZE_HINT_PIXELS", -1)
            if (artSizeHint > 0) {
                Timber.tag("RNTP-AA").d("Android Auto requests images at size: ${artSizeHint}x${artSizeHint} pixels")
            }
        }

        // CRITICAL: Always return a valid BrowserRoot for ALL clients FIRST (return quickly)
        // Returning null would make the service undiscoverable by Google Assistant
        // The MediaSession is created inside the service (via QueuedAudioPlayer) and
        // is independent of the activity lifecycle, so we can always return a valid root.
        val extras = Bundle()
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            mediaTreeStyle[0]
        )
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            mediaTreeStyle[1]
        )
        // Declare search support for browsable search results
        extras.putBoolean(
            MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED,
            true
        )
        
        // Check if Google Assistant is requesting suggested items
        // EXTRA_SUGGESTED constant: "android.service.media.extra.SUGGESTED"
        val isRequestingSuggested = rootHints?.getBoolean(
            "android.service.media.extra.SUGGESTED",
            false
        ) ?: false
        
        // Return different root ID for suggested items vs normal browsing
        val rootId = if (isRequestingSuggested) {
            "/suggested"  // Root for Google Assistant recommendations
        } else {
            "/"  // Default root for normal browsing
        }
        
        val browserRoot = BrowserRoot(rootId, extras)

        // Initialize React Native if not already initialized and browse tree is empty
        // This ensures Android Auto can discover content even when app hasn't been launched
        if (trackPlayerModule == null && mediaTree.isEmpty() && !isInitializingReactNative) {
            isInitializingReactNative = true
            initializeReactNativeForAndroidAuto()
        }

        // MediaBrowserService should NEVER launch an Activity (per Google Assistant guidelines)
        // The MediaSession is created inside the service (via QueuedAudioPlayer) and is independent
        // of the activity lifecycle. Assistant will launch Activity based on PendingIntent from
        // setSessionActivity() or notification's PendingIntent if needed.
        // 
        // NOTE: Removed the Activity launch workaround that violated the "never launch Activity" rule.
        // If React Native needs to be initialized, it should be done through proper service initialization
        // or when the Activity is launched by the system/Assistant, not from MediaBrowserService.
        //
        // DRIVING MODE: The MediaSession playback state is managed by QueuedAudioPlayer based on actual
        // playback state. When nothing is playing, the state is automatically STATE_NONE, which prevents
        // the app from being brought to foreground during driving mode when onGetRoot() is called for
        // recommendations. The MediaSessionConnector automatically updates playback state based on ExoPlayer
        // state, so no explicit state management is needed here.

        return browserRoot
    }

    override fun onLoadChildren(
            parentMediaId: String,
            result: Result<List<MediaItem>>
    ) {
        // Check if React Native is initialized and browse tree has the key
        // Note: An empty list is valid content (means "no items"), we only detach if the key doesn't exist
        val keyExists = mediaTree.containsKey(parentMediaId)
        
        // Only detach if React Native isn't initialized OR the key doesn't exist in the tree yet
        // If the key exists (even with empty list), we should return it immediately
        if (trackPlayerModule == null || !keyExists) {
            result.detach()
            pendingBrowseResults[parentMediaId] = result
            
            // Initialize React Native if not already initializing
            if (!isInitializingReactNative && trackPlayerModule == null) {
                isInitializingReactNative = true
                initializeReactNativeForAndroidAuto()
            }
            
            return
        }
        
        // React Native is initialized and we have content - proceed normally
        trackPlayerModule?.onRemoteBrowse(Bundle().apply {
            putString("mediaId", parentMediaId)
        })
        
        val contentToReturn = mediaTree[parentMediaId] ?: emptyList()
        result.sendResult(contentToReturn)
    }
    
    /**
     * Mark that React Native was initialized by MusicService so MainApplication can skip duplicate initialization.
     * Uses reflection to find MainApplication class dynamically (works for any app package name).
     */
    private fun markReactNativeInitializedByMusicService() {
        try {
            val applicationClass = application.javaClass
            val packageName = applicationClass.`package`?.name
            if (packageName != null) {
                // Try common MainApplication class name patterns
                val mainAppClassNames = listOf(
                    "$packageName.MainApplication",
                    "${packageName}.app.MainApplication"
                )
                for (className in mainAppClassNames) {
                    try {
                        val mainAppClass = Class.forName(className)
                        val markMethod = mainAppClass.getMethod("markReactNativeInitializedByMusicService")
                        markMethod.invoke(null)
                        Timber.tag("RNTP-AA").d("Marked React Native as initialized via $className")
                        return
                    } catch (e: ClassNotFoundException) {
                        // Try next pattern
                        continue
                    }
                }
            }
        } catch (e: Exception) {
            // MainApplication not found or method doesn't exist - that's OK, will check context directly
            Timber.tag("RNTP-AA").d("Could not mark React Native as initialized: ${e.message}")
        }
    }
    
    /**
     * Initialize React Native context for Android Auto.
     * This uses HeadlessJsMediaService's mechanism to create React context in background.
     */
    private fun initializeReactNativeForAndroidAuto() {
        Timber.tag("RNTP-AA").d("initializeReactNativeForAndroidAuto called")
        try {
            val reactInstanceManager = (application as? com.facebook.react.ReactApplication)
                ?.reactNativeHost?.reactInstanceManager
            
            if (reactInstanceManager == null) {
                Timber.tag("RNTP-AA").e("ReactInstanceManager is null!")
                isInitializingReactNative = false
                return
            }
            
            val reactContext = reactInstanceManager.currentReactContext
            
            if (reactContext != null) {
                Timber.tag("RNTP-AA").d("React Native context already exists, marking as initialized")
                isInitializingReactNative = false
                // React Native is already initialized, but trackPlayerModule might not be set yet
                // It will be set when TrackPlayerModule binds, which will trigger sendPendingBrowseResults()
                markReactNativeInitializedByMusicService()
                return
            }
            
            Timber.tag("RNTP-AA").d("React Native context not found, creating in background")
            // React Native not initialized - create context in background
            reactInstanceManager.addReactInstanceEventListener(
                object : com.facebook.react.ReactInstanceManager.ReactInstanceEventListener {
                    override fun onReactContextInitialized(reactContext: com.facebook.react.bridge.ReactContext) {
                        reactInstanceManager.removeReactInstanceEventListener(this)
                        isInitializingReactNative = false
                        Timber.tag("RNTP-AA").d("React Native context initialized, marking and scheduling pending requests")
                        markReactNativeInitializedByMusicService()
                        // TrackPlayerModule will connect and set trackPlayerModule, which will trigger sendPendingBrowseResults()
                        // Give it a moment for TrackPlayerModule to bind
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (trackPlayerModule != null) {
                                Timber.tag("RNTP-AA").d("TrackPlayerModule available after RN init, processing pending requests")
                                sendPendingBrowseResults()
                                processPendingSearchRequests()
                            } else {
                                Timber.tag("RNTP-AA").w("TrackPlayerModule still null after RN init delay")
                            }
                        }, 1000) // Wait 1 second for TrackPlayerModule to bind
                    }
                }
            )
            
            // Also listen for initialization failures
            try {
                reactInstanceManager.createReactContextInBackground()
                Timber.tag("RNTP-AA").d("Started creating React Native context in background")
            } catch (e: Exception) {
                Timber.tag("RNTP-AA").e(e, "Failed to create React context")
                isInitializingReactNative = false
            }
        } catch (e: Exception) {
            Timber.tag("RNTP-AA").e(e, "Error initializing React Native for Android Auto")
            isInitializingReactNative = false
        }
    }
    
    /**
     * Send pending browse results when browse tree is populated.
     * Called from setBrowseTree() or when trackPlayerModule is set.
     */
    fun sendPendingBrowseResults() {
        if (pendingBrowseResults.isEmpty()) {
            Timber.tag("RNTP-AA").d("No pending browse results to send")
            return
        }
        
        Timber.tag("RNTP-AA").d("Sending ${pendingBrowseResults.size} pending browse results")
        val resultsToSend = pendingBrowseResults.toMap()
        pendingBrowseResults.clear()
        
        resultsToSend.forEach { (parentMediaId, result) ->
            val content = mediaTree[parentMediaId] ?: emptyList()
            
            // Notify React Native about the browse request
            trackPlayerModule?.onRemoteBrowse(Bundle().apply {
                putString("mediaId", parentMediaId)
            })
            
            result.sendResult(content)
        }
    }
    
    /**
     * Process pending search requests that were queued before TrackPlayerModule was ready
     */
    fun processPendingSearchRequests() {
        if (pendingSearchRequests.isEmpty()) {
            Timber.tag("RNTP-AA").d("No pending search requests to process")
            return
        }
        
        if (trackPlayerModule == null) {
            Timber.tag("RNTP-AA").w("Cannot process pending search requests: TrackPlayerModule is null")
            return
        }
        
        val requestsToProcess = pendingSearchRequests.toList()
        pendingSearchRequests.clear()
        
        Timber.tag("RNTP-AA").d("Processing ${requestsToProcess.size} pending search requests")
        requestsToProcess.forEach { request ->
            Timber.tag("RNTP-AA").d("Processing queued search: query='${request.query}'")
            processSearchRequest(request.query, request.extras, request.result)
        }
    }

    override fun onSearch(
            query: String,
            extras: Bundle?,
            result: Result<List<MediaItem>>
    ) {
        Timber.tag("RNTP-AA").d("RNTP received search req: query='$query', extras=$extras")
        
        // Detach from result to unblock the caller (search can be expensive)
        result.detach()
        
        // Check if TrackPlayerModule is ready
        val module = trackPlayerModule
        if (module == null) {
            Timber.tag("RNTP-AA").w("TrackPlayerModule is null, queueing search request: query='$query'")
            // Queue the search request to be processed when TrackPlayerModule binds
            pendingSearchRequests.add(PendingSearchRequest(query, extras, result))
            
            // Initialize React Native if not already initializing
            if (!isInitializingReactNative) {
                isInitializingReactNative = true
                initializeReactNativeForAndroidAuto()
            }
            return
        }
        
        // TrackPlayerModule is ready - process search immediately
        processSearchRequest(query, extras, result)
    }
    
    /**
     * Process a search request by calling React Native
     */
    private fun processSearchRequest(
            query: String,
            extras: Bundle?,
            result: Result<List<MediaItem>>
    ) {
        // Generate unique search ID
        val searchId = "search_${++searchIdCounter}_${System.currentTimeMillis()}"
        
        // Store result callback for later
        pendingSearchResults[searchId] = result
        
        // Extract search parameters from extras
        val artistName = extras?.getString("android.intent.extra.artist")
            ?: extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
        val albumName = extras?.getString("android.intent.extra.album")
            ?: extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)
        
        // Call React Native to perform search
        val searchBundle = Bundle().apply {
            putString("searchId", searchId)
            putString("query", query)
            artistName?.let { putString("artistName", it) }
            albumName?.let { putString("albumName", it) }
        }
        
        Timber.tag("RNTP-AA").d("Calling React Native for search: searchId=$searchId, query='$query', artistName=$artistName, albumName=$albumName")
        val module = trackPlayerModule
        if (module == null) {
            Timber.tag("RNTP-AA").e("TrackPlayerModule is null, cannot process search request")
            result.sendResult(emptyList())
            pendingSearchResults.remove(searchId)
            return
        }
        
        module.onRemoteSearch(searchBundle)
        // Note: Results will be returned via sendSearchResults() method
    }
    
    /**
     * Called from React Native (via TrackPlayerModule) to send search results back
     * This completes the search request initiated by onSearch()
     * @param trackResults List of maps containing track metadata: mediaId, title, artist, album, artwork
     */
    fun sendSearchResults(searchId: String, trackResults: List<Map<String, String?>>) {
        Timber.tag("RNTP-AA").d("Received search results: searchId=$searchId, count=${trackResults.size}")
        
        val result = pendingSearchResults.remove(searchId)
        if (result == null) {
            Timber.tag("RNTP-AA").w("No pending search result found for searchId: $searchId")
            return
        }
        
        // Convert track metadata to MediaItem objects on background thread
        scope.launch(Dispatchers.IO) {
            try {
                val mediaItems = trackResults.mapNotNull { trackData ->
                    createMediaItemFromTrackData(trackData)
                }
                
                Timber.tag("RNTP-AA").d("Converted ${mediaItems.size} tracks to MediaItems for searchId: $searchId")
                
                // Send results on main thread
                withContext(Dispatchers.Main) {
                    result.sendResult(mediaItems)
                }
            } catch (e: Exception) {
                Timber.tag("RNTP-AA").e(e, "Error converting search results for searchId: $searchId")
                // This invokes onError() on the search callback
                withContext(Dispatchers.Main) {
                    result.sendResult(null)
                }
            }
        }
    }
    
    /**
     * Creates a MediaItem from track metadata provided by React Native
     * @param trackData Map containing: mediaId, title, artist?, album?, artwork?, url?, duration?
     * Note: url and duration are received but not used in MediaDescriptionCompat (they're playback properties)
     */
    private fun createMediaItemFromTrackData(trackData: Map<String, String?>): MediaItem? {
        try {
            val mediaId = trackData["mediaId"] ?: return null
            val title = trackData["title"] ?: mediaId
            val artist = trackData["artist"]
            val album = trackData["album"]
            val artwork = trackData["artwork"]
            
            val descriptionBuilder = MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
            
            artist?.let { descriptionBuilder.setSubtitle(it) }
            album?.let { descriptionBuilder.setDescription(it) }
            artwork?.let { 
                try {
                    val uri = android.net.Uri.parse(it)
                    val finalArtworkUri = when {
                        uri.scheme == "file" -> {
                            // Convert file:// to content://
                            val convertedArtworkUri = UriUtils.convertFileUriToContentUri(this, it)
                            if (convertedArtworkUri != null) {
                                android.net.Uri.parse(convertedArtworkUri)
                            } else {
                                Timber.tag("RNTP-AA").w("createMediaItemFromTrackData: Failed to convert file:// URI, using original: $it")
                                uri
                            }
                        }
                        uri.scheme == "http" || uri.scheme == "https" -> {
                            // For HTTPS URLs, try to find cached version and convert to content://
                            // Android Auto requires content:// URIs for artwork, not HTTP/HTTPS
                            val cachedUri = UriUtils.convertHttpUriToContentUri(this, it)
                            if (cachedUri != null) {
                                android.net.Uri.parse(cachedUri)
                            } else {
                                Timber.tag("RNTP-AA").w("createMediaItemFromTrackData: HTTPS URL not cached locally - Android Auto may not display this artwork: $it")
                                Timber.tag("RNTP-AA").w("Consider downloading and caching images before setting artwork for tracks")
                                uri // Fallback to HTTPS URL (may not work in Android Auto)
                            }
                        }
                        else -> uri
                    }
                    descriptionBuilder.setIconUri(finalArtworkUri)
                } catch (e: Exception) {
                    Timber.tag("RNTP-AA").w(e, "Invalid artwork URI: $it")
                }
            }
            
            val description = descriptionBuilder.build()
            return MediaItem(description, MediaItem.FLAG_PLAYABLE)
        } catch (e: Exception) {
            Timber.tag("RNTP-AA").e(e, "Error creating MediaItem from track data: $trackData")
            return null
        }
    }
    
    /**
     * Creates a MediaItem from a media ID (format: "track/{bookId}/{contentId}")
     * Returns null if the media ID cannot be parsed or metadata cannot be retrieved
     * 
     * NOTE: This method is deprecated - use createMediaItemFromTrackData instead
     * which receives full metadata from React Native.
     */
    @Deprecated("Use createMediaItemFromTrackData instead")
    private fun createMediaItemFromMediaId(mediaId: String): MediaItem? {
        try {
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(mediaId) // Temporary: use mediaId as title
                .build()
            
            return MediaItem(description, MediaItem.FLAG_PLAYABLE)
        } catch (e: Exception) {
            Timber.tag("RNTP-AA").e(e, "Error creating MediaItem from media ID: $mediaId")
            return null
        }
    }

    enum class AppKilledPlaybackBehavior(val string: String) {
        CONTINUE_PLAYBACK("continue-playback"), PAUSE_PLAYBACK("pause-playback"), STOP_PLAYBACK_AND_REMOVE_NOTIFICATION("stop-playback-and-remove-notification")
    }

    private var appKilledPlaybackBehavior = AppKilledPlaybackBehavior.CONTINUE_PLAYBACK
    private var stopForegroundGracePeriod: Int = DEFAULT_STOP_FOREGROUND_GRACE_PERIOD

    val tracks: List<Track>
        get() = player.items.map { (it as TrackAudioItem).track }

    val currentTrack
        get() = (player.currentItem as TrackAudioItem).track

    val state
        get() = player.playerState

    var ratingType: Int
        get() = player.ratingType
        set(value) {
            player.ratingType = value
        }

    val playbackError
        get() = player.playbackError

    val event
        get() = player.event

    var playWhenReady: Boolean
        get() = player.playWhenReady
        set(value) {
            player.playWhenReady = value
        }

    private var latestOptions: Bundle? = null
    private var capabilities: List<Capability> = emptyList()
    private var notificationCapabilities: List<Capability> = emptyList()
    private var compactCapabilities: List<Capability> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TurboModule: Only start headless task if config is provided (null for New Architecture)
        val taskConfig = getTaskConfig(intent)
        if (taskConfig != null) {
            startTask(taskConfig)
        }
        startAndStopEmptyNotificationToAvoidANR()
        return START_STICKY
    }

    /**
     * Workaround for the "Context.startForegroundService() did not then call Service.startForeground()"
     * within 5s" ANR and crash by creating an empty notification and stopping it right after. For more
     * information see https://github.com/doublesymmetry/react-native-track-player/issues/1666
     */
    private fun startAndStopEmptyNotificationToAvoidANR() {
        val notificationManager = this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(getString(TrackPlayerR.string.rntp_temporary_channel_id), getString(TrackPlayerR.string.rntp_temporary_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notificationBuilder = NotificationCompat.Builder(this, getString(TrackPlayerR.string.rntp_temporary_channel_id))
            .setPriority(PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSmallIcon(ExoPlayerR.drawable.exo_notification_small_icon)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationBuilder.foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
        }
        val notification = notificationBuilder.build()
        try {
            startForeground(EMPTY_NOTIFICATION_ID, notification)
            @Suppress("DEPRECATION")
            stopForeground(true)
        } catch (error: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                error is android.app.ForegroundServiceStartNotAllowedException
            ) {
                Timber.e(
                    "ForegroundServiceStartNotAllowedException: Cannot start foreground service in startAndStopEmptyNotificationToAvoidANR. This is non-fatal.",
                    error
                )
                // Non-fatal: The service will still work, just without the ANR workaround
            } else {
                throw error
            }
        }
    }

    @MainThread
    fun setupPlayer(playerOptions: Bundle?) {
        if (this::player.isInitialized) {
            Timber.d("Player was initialized. Prevent re-initializing again")
            return
        }

        val bufferConfig = BufferConfig(
            playerOptions?.getDouble(MIN_BUFFER_KEY)?.toMilliseconds()?.toInt(),
            playerOptions?.getDouble(MAX_BUFFER_KEY)?.toMilliseconds()?.toInt(),
            playerOptions?.getDouble(PLAY_BUFFER_KEY)?.toMilliseconds()?.toInt(),
            playerOptions?.getDouble(BACK_BUFFER_KEY)?.toMilliseconds()?.toInt(),
        )

        val cacheConfig = CacheConfig(playerOptions?.getDouble(MAX_CACHE_SIZE_KEY)?.toLong())
        val playerConfig = PlayerConfig(
            interceptPlayerActionsTriggeredExternally = true,
            handleAudioBecomingNoisy = playerOptions?.getBoolean(AUTO_HANDLE_ROUTE_CHANGES) ?: true,
            handleAudioFocus = playerOptions?.getBoolean(AUTO_HANDLE_INTERRUPTIONS) ?: false,
            audioContentType = when(playerOptions?.getString(ANDROID_AUDIO_CONTENT_TYPE)) {
                "music" -> AudioContentType.MUSIC
                "speech" -> AudioContentType.SPEECH
                "sonification" -> AudioContentType.SONIFICATION
                "movie" -> AudioContentType.MOVIE
                "unknown" -> AudioContentType.UNKNOWN
                else -> AudioContentType.MUSIC
            }
        )

        val automaticallyUpdateNotificationMetadata = playerOptions?.getBoolean(AUTO_UPDATE_METADATA, true) ?: true
        val mediaSessionCallback = object: AAMediaSessionCallBack {
            override fun handlePlayFromMediaId(mediaId: String?, extras: Bundle?) {
                Timber.tag("GVA-RNTP").d("RNTP received req to play from mediaID: $mediaId")
                if (mediaId.isNullOrEmpty()) {
                    // Invalid media ID - set error state
                    player?.setPlaybackStateError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        "Invalid media ID provided"
                    )
                    return
                }
                trackPlayerModule?.onRemotePlayId((extras ?: Bundle()).apply {
                    putString("id", mediaId)
                })
            }

            override fun handlePlayFromSearch(query: String?, extras: Bundle?) {
                Timber.tag("GVA-RNTP").d("RNTP received req to play from query: $query, extras: $extras")
                
                // Extract search parameters to check if query is valid
                val searchQuery = query ?: ""
                val artistName = extras?.getString("android.intent.extra.artist")
                    ?: extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                val albumName = extras?.getString("android.intent.extra.album")
                    ?: extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                val title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)
                
                // Check if we have any searchable parameters
                val hasSearchParams = searchQuery.isNotEmpty() || 
                    artistName != null || albumName != null || title != null
                
                if (!hasSearchParams) {
                    // Empty query with no metadata - this will be handled by React Native
                    // but we can set a warning (not an error, as onPlay() might handle it)
                    Timber.tag("GVA-RNTP").w("Empty search query with no metadata provided")
                }
                
                val searchBundle = Bundle().apply {
                    putString("query", searchQuery)
                    // Pass extras to React Native so SearchService can use artistName/albumName
                    if (extras != null) {
                        putBundle("extras", extras)
                        // Also extract common extras as top-level keys for easier access
                        artistName?.let {
                            putString("artist", it)
                        }
                        albumName?.let {
                            putString("album", it)
                        }
                    }
                }
                
                // Check if TrackPlayerModule is ready
                val module = trackPlayerModule
                if (module == null) {
                    Timber.tag("GVA-RNTP").w("TrackPlayerModule is null, cannot handle play from search. Initializing React Native...")
                    // Initialize React Native if not already initializing
                    if (!isInitializingReactNative) {
                        isInitializingReactNative = true
                        initializeReactNativeForAndroidAuto()
                    }
                    // Wait for TrackPlayerModule to bind, then retry
                    // Use a delayed handler to retry after React Native initializes
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (trackPlayerModule != null) {
                            Timber.tag("GVA-RNTP").d("TrackPlayerModule now available, retrying play from search")
                            trackPlayerModule?.onRemotePlayFromSearch(searchBundle)
                        } else {
                            Timber.tag("GVA-RNTP").e("TrackPlayerModule still null after initialization delay, play from search may fail")
                            // Try anyway - React Native might handle it gracefully
                            trackPlayerModule?.onRemotePlayFromSearch(searchBundle)
                        }
                    }, 2000) // Wait 2 seconds for TrackPlayerModule to bind
                    return
                }
                
                Timber.tag("GVA-RNTP").d("Calling TrackPlayerModule.onRemotePlayFromSearch with query: '$searchQuery'")
                module.onRemotePlayFromSearch(searchBundle)
            }
            
            override fun handlePrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                Timber.tag("GVA-RNTP").d("RNTP received req to prepare from mediaID: $mediaId")
                if (mediaId.isNullOrEmpty()) {
                    // Invalid media ID - set error state
                    player?.setPlaybackStateError(
                        android.support.v4.media.session.PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                        "Invalid media ID provided"
                    )
                    return
                }
                trackPlayerModule?.onRemotePrepareId((extras ?: Bundle()).apply {
                    putString("id", mediaId)
                    putBoolean("playWhenReady", false) // PREPARE always means prepare without playing
                })
            }

            override fun handlePrepareFromSearch(query: String?, extras: Bundle?) {
                Timber.tag("GVA-RNTP").d("RNTP received req to prepare from query: $query, extras: $extras")
                val searchBundle = Bundle().apply {
                    putString("query", query ?: "")
                    putBoolean("playWhenReady", false) // PREPARE always means prepare without playing
                    // Pass extras to React Native so SearchService can use artistName/albumName
                    if (extras != null) {
                        putBundle("extras", extras)
                        // Also extract common extras as top-level keys for easier access
                        extras.getString("android.intent.extra.artist")?.let {
                            putString("artist", it)
                        }
                        extras.getString("android.intent.extra.album")?.let {
                            putString("album", it)
                        }
                        extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)?.let {
                            putString("artist", it)
                        }
                        extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)?.let {
                            putString("album", it)
                        }
                    }
                }
                
                // Check if TrackPlayerModule is ready
                val module = trackPlayerModule
                if (module == null) {
                    Timber.tag("GVA-RNTP").w("TrackPlayerModule is null, cannot handle prepare from search. Initializing React Native...")
                    // Initialize React Native if not already initializing
                    if (!isInitializingReactNative) {
                        isInitializingReactNative = true
                        initializeReactNativeForAndroidAuto()
                    }
                    // Wait for TrackPlayerModule to bind, then retry
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (trackPlayerModule != null) {
                            Timber.tag("GVA-RNTP").d("TrackPlayerModule now available, retrying prepare from search")
                            trackPlayerModule?.onRemotePrepareFromSearch(searchBundle)
                        } else {
                            Timber.tag("GVA-RNTP").e("TrackPlayerModule still null after initialization delay, prepare from search may fail")
                            trackPlayerModule?.onRemotePrepareFromSearch(searchBundle)
                        }
                    }, 2000) // Wait 2 seconds for TrackPlayerModule to bind
                    return
                }
                
                Timber.tag("GVA-RNTP").d("Calling TrackPlayerModule.onRemotePrepareFromSearch with query: '${query ?: ""}'")
                module.onRemotePrepareFromSearch(searchBundle)
            }

            override fun handleSkipToQueueItem(id: Long) {
                Timber.tag("GVA-RNTP").d("RNTP received req to play from queue index: $id")
                val skipBundle = Bundle().apply {
                    putInt("index", id.toInt())
                }
                trackPlayerModule?.onRemoteSkip(skipBundle)
            }
        }
        player = QueuedAudioPlayer(this@MusicService, playerConfig, bufferConfig, cacheConfig, mediaSessionCallback)
        player.automaticallyUpdateNotificationMetadata = automaticallyUpdateNotificationMetadata
        sessionToken = player.getMediaSessionToken()
        
        // Set session activity PendingIntent so Google Assistant can launch Activity when needed
        // This allows Assistant to launch the Activity based on PendingIntent, rather than service launching it
        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Intent.ACTION_VIEW
            data = Uri.parse("trackplayer://session-activity")
        }
        sessionActivityIntent?.let {
            val sessionActivityPendingIntent = PendingIntent.getActivity(
                this,
                0,
                it,
                getPendingIntentFlags()
            )
            player.setSessionActivity(sessionActivityPendingIntent)
            Timber.d("🎵 MusicService.setupPlayer: setSessionActivity() called with PendingIntent for MainActivity")
        }
        
        observeEvents()
        setupForegrounding()
    }

    @MainThread
    fun updateOptions(options: Bundle) {
        Timber.d("🎵 MusicService.updateOptions: START")
        latestOptions = options
        Timber.d("🎵 MusicService.updateOptions: getting androidOptions bundle")
        val androidOptions = options.getBundle(ANDROID_OPTIONS_KEY)

        Timber.d("🎵 MusicService.updateOptions: setting appKilledPlaybackBehavior")
        appKilledPlaybackBehavior = AppKilledPlaybackBehavior::string.find(androidOptions?.getString(APP_KILLED_PLAYBACK_BEHAVIOR_KEY)) ?: AppKilledPlaybackBehavior.CONTINUE_PLAYBACK

        Timber.d("🎵 MusicService.updateOptions: setting stopForegroundGracePeriod")
        BundleUtils.getIntOrNull(androidOptions, STOP_FOREGROUND_GRACE_PERIOD_KEY)?.let { stopForegroundGracePeriod = it }

        Timber.d("🎵 MusicService.updateOptions: handling deprecated flag")
        // TODO: This handles a deprecated flag. Should be removed soon.
        options.getBoolean(STOPPING_APP_PAUSES_PLAYBACK_KEY).let {
            stoppingAppPausesPlayback = options.getBoolean(STOPPING_APP_PAUSES_PLAYBACK_KEY)
            if (stoppingAppPausesPlayback) {
                appKilledPlaybackBehavior = AppKilledPlaybackBehavior.PAUSE_PLAYBACK
            }
        }

        Timber.d("🎵 MusicService.updateOptions: setting ratingType")
        ratingType = BundleUtils.getInt(options, "ratingType", RatingCompat.RATING_NONE)

        Timber.d("🎵 MusicService.updateOptions: setting alwaysPauseOnInterruption")
        player.playerOptions.alwaysPauseOnInterruption = androidOptions?.getBoolean(PAUSE_ON_INTERRUPTION_KEY) ?: false

        Timber.d("🎵 MusicService.updateOptions: setting capabilities")
        capabilities = parseCapabilities(options.getStringArrayList("capabilities"))
        notificationCapabilities = parseCapabilities(options.getStringArrayList("notificationCapabilities"))
        compactCapabilities = parseCapabilities(options.getStringArrayList("compactCapabilities"))

        Timber.d("🎵 MusicService.updateOptions: checking notificationCapabilities")
        if (notificationCapabilities.isEmpty()) notificationCapabilities = capabilities

        Timber.d("🎵 MusicService.updateOptions: creating buttonsList")
        val buttonsList = notificationCapabilities.mapNotNull {
            when (it) {
                Capability.PLAY, Capability.PAUSE -> {
                    val playIcon = BundleUtils.getIconOrNull(this, options, "playIcon")
                    val pauseIcon = BundleUtils.getIconOrNull(this, options, "pauseIcon")
                    PLAY_PAUSE(playIcon = playIcon, pauseIcon = pauseIcon)
                }
                Capability.STOP -> {
                    val stopIcon = BundleUtils.getIconOrNull(this, options, "stopIcon")
                    STOP(icon = stopIcon)
                }
                Capability.SKIP_TO_NEXT -> {
                    val nextIcon = BundleUtils.getIconOrNull(this, options, "nextIcon")
                    NEXT(icon = nextIcon, isCompact = isCompact(it))
                }
                Capability.SKIP_TO_PREVIOUS -> {
                    val previousIcon = BundleUtils.getIconOrNull(this, options, "previousIcon")
                    PREVIOUS(icon = previousIcon, isCompact = isCompact(it))
                }
                Capability.JUMP_FORWARD -> {
                    val forwardIcon = BundleUtils.getIcon(this, options, "forwardIcon", TrackPlayerR.drawable.forward)
                    FORWARD(icon = forwardIcon, isCompact = isCompact(it))
                }
                Capability.JUMP_BACKWARD -> {
                    val backwardIcon = BundleUtils.getIcon(this, options, "rewindIcon", TrackPlayerR.drawable.rewind)
                    BACKWARD(icon = backwardIcon, isCompact = isCompact(it))
                }
                Capability.SEEK_TO -> {
                    SEEK_TO
                }
                else -> { null }
            }
        }

        Timber.d("🎵 MusicService.updateOptions: creating openAppIntent")
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Add the Uri data so apps can identify that it was a notification click
            data = Uri.parse("trackplayer://notification.click")
            action = Intent.ACTION_VIEW
        }

        Timber.d("🎵 MusicService.updateOptions: creating notificationConfig")
        val accentColor = BundleUtils.getIntOrNull(options, "color")
        val smallIcon = BundleUtils.getIconOrNull(this, options, "icon")
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, getPendingIntentFlags())
        val notificationConfig = NotificationConfig(buttonsList, accentColor, smallIcon, pendingIntent)

        Timber.d("🎵 MusicService.updateOptions: creating notification")
        player.notificationManager.createNotification(notificationConfig)

        Timber.d("🎵 MusicService.updateOptions: setting up progress update events")
        // setup progress update events if configured
        progressUpdateJob?.cancel()
        val updateInterval = BundleUtils.getDoubleOrNull(options, PROGRESS_UPDATE_EVENT_INTERVAL_KEY)
        if (updateInterval != null && updateInterval > 0) {
            progressUpdateJob = scope.launch {
                progressUpdateEventFlow(updateInterval).collect { 
                    Timber.d("🎵 MusicService calling trackPlayerModule.onPlaybackProgressUpdated")
                    trackPlayerModule?.onPlaybackProgressUpdated(it) 
                }
            }
        }
        Timber.d("🎵 MusicService.updateOptions: COMPLETED")
    }

    @MainThread
    private fun progressUpdateEventFlow(interval: Double) = flow {
        while (true) {
            if (player.isPlaying) {
                val bundle = progressUpdateEvent()
                emit(bundle)
            }

            delay((interval * 1000).toLong())
        }
    }

    @MainThread
    private suspend fun progressUpdateEvent(): Bundle {
        return withContext(Dispatchers.Main) {
            Bundle().apply {
                putDouble(POSITION_KEY, player.position.toSeconds())
                putDouble(DURATION_KEY, player.duration.toSeconds())
                putDouble(BUFFERED_POSITION_KEY, player.bufferedPosition.toSeconds())
                putInt(TRACK_KEY, player.currentIndex)
            }
        }
    }

    private fun getPendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
    }

    private fun isCompact(capability: Capability): Boolean {
        return compactCapabilities.contains(capability)
    }

    @MainThread
    fun add(track: Track) {
        add(listOf(track))
    }

    @MainThread
    fun add(tracks: List<Track>) {
        val items = tracks.map { it.toAudioItem() }
        player.add(items)
    }

    @MainThread
    fun add(tracks: List<Track>, atIndex: Int) {
        val items = tracks.map { it.toAudioItem() }
        player.add(items, atIndex)
    }

    @MainThread
    fun load(track: Track) {
        player.load(track.toAudioItem())
    }

    @MainThread
    fun move(fromIndex: Int, toIndex: Int) {
        player.move(fromIndex, toIndex);
    }

    @MainThread
    fun remove(index: Int) {
        remove(listOf(index))
    }

    @MainThread
    fun remove(indexes: List<Int>) {
        player.remove(indexes)
    }

    @MainThread
    fun clear() {
        player.clear()
    }

    @MainThread
    fun play() {
        player.play()
    }

    @MainThread
    fun pause() {
        player.pause()
    }

    @MainThread
    fun stop() {
        player.stop()
    }

    @MainThread
    fun removeUpcomingTracks() {
        player.removeUpcomingItems()
    }

    @MainThread
    fun removePreviousTracks() {
        player.removePreviousItems()
    }

    @MainThread
    fun skip(index: Int, initialPositionSeconds: Float? = null) {
        player.jumpToItem(index)
        // jumpToItem already seeks to the start of the target item (TIME_UNSET).
        // Only seek when resuming mid-track; a seekTo(0) after jump caused stale position on Android.
        if (initialPositionSeconds != null && initialPositionSeconds > 0f) {
            player.seek((initialPositionSeconds * 1000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    @MainThread
    fun skipToNext() {
        player.next()
    }

    @MainThread
    fun skipToPrevious() {
        player.previous()
    }

    @MainThread
    fun seekTo(seconds: Float) {
        player.seek((seconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

    @MainThread
    fun seekBy(offset: Float) {
        player.seekBy((offset.toLong()), TimeUnit.SECONDS)
    }

    @MainThread
    fun retry() {
        player.prepare()
    }

    @MainThread
    fun getCurrentTrackIndex(): Int = player.currentIndex

    @MainThread
    fun getRate(): Float = player.playbackSpeed

    @MainThread
    fun setRate(value: Float) {
        player.playbackSpeed = value
    }

    @MainThread
    fun getRepeatMode(): RepeatMode = player.playerOptions.repeatMode

    @MainThread
    fun setRepeatMode(value: RepeatMode) {
        player.playerOptions.repeatMode = value
    }

    @MainThread
    fun getVolume(): Float = player.volume

    @MainThread
    fun setVolume(value: Float) {
        player.volume = value
    }

    @MainThread
    fun getDurationInSeconds(): Double = player.duration.toSeconds()

    @MainThread
    fun getPositionInSeconds(): Double = player.position.toSeconds()

    @MainThread
    fun getBufferedPositionInSeconds(): Double = player.bufferedPosition.toSeconds()

    @MainThread
    fun getPlayerStateBundle(state: AudioPlayerState): Bundle {
        val bundle = Bundle()
        bundle.putString(STATE_KEY, state.asLibState.state)
        if (state == AudioPlayerState.ERROR) {
            bundle.putBundle(ERROR_KEY, getPlaybackErrorBundle())
        }
        return bundle
    }

    @MainThread
    fun updateMetadataForTrack(index: Int, track: Track) {
        player.replaceItem(index, track.toAudioItem())
    }

    @MainThread
    fun updateNowPlayingMetadata(track: Track) {
        player.notificationManager.overrideMetadata(track.toAudioItem())
    }

    @MainThread
    fun clearNotificationMetadata() {
        player.notificationManager.hideNotification()
    }

    private fun emitPlaybackTrackChangedEvents(
        index: Int?,
        previousIndex: Int?,
        oldPosition: Double
    ) {
        // Only emit the modern playback-active-track-changed event
        // The legacy playback-track-changed event is NOT in the TurboModule spec
        val bundle = Bundle()
        bundle.putDouble("lastPosition", oldPosition)
        if (tracks.isNotEmpty()) {
            bundle.putInt("index", player.currentIndex)
            bundle.putBundle("track", tracks[player.currentIndex].originalItem)
            if (previousIndex != null) {
                bundle.putInt("lastIndex", previousIndex)
                bundle.putBundle("lastTrack", tracks[previousIndex].originalItem)
            }
        }
        Timber.d("🎵 MusicService calling trackPlayerModule.onPlaybackActiveTrackChanged")
        trackPlayerModule?.onPlaybackActiveTrackChanged(bundle)
    }

    private fun emitQueueEndedEvent() {
        val bundle = Bundle()
        bundle.putInt(TRACK_KEY, player.currentIndex)
        bundle.putDouble(POSITION_KEY, player.position.toSeconds())
        Timber.d("🎵 MusicService calling trackPlayerModule.onPlaybackQueueEnded")
        trackPlayerModule?.onPlaybackQueueEnded(bundle)
    }

    @Suppress("DEPRECATION")
    fun isForegroundService(): Boolean {
        val manager = baseContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (MusicService::class.java.name == service.service.className) {
                return service.foreground
            }
        }
        Timber.e("isForegroundService found no matching service")
        return false
    }

    @MainThread
    private fun setupForegrounding() {
        // Implementation based on https://github.com/Automattic/pocket-casts-android/blob/ee8da0c095560ef64a82d3a31464491b8d713104/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackService.kt#L218
        var notificationId: Int? = null
        var notification: Notification? = null
        var stopForegroundWhenNotOngoing = false
        var removeNotificationWhenNotOngoing = false

        fun startForegroundIfNecessary() {
            if (isForegroundService()) {
                Timber.d("skipping foregrounding as the service is already foregrounded")
                return
            }
            if (notification == null) {
                Timber.d("can't startForeground as the notification is null")
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        notificationId!!,
                        notification!!,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(notificationId!!, notification)
                }
                Timber.d("notification has been foregrounded")
            } catch (error: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    error is ForegroundServiceStartNotAllowedException
                ) {
                    Timber.e(
                        "ForegroundServiceStartNotAllowedException: App tried to start a foreground Service when it was not allowed to do so.",
                        error
                    )
                    trackPlayerModule?.onPlaybackError(error.message ?: "unknown", Bundle().apply {
                        putString("message", error.message)
                        putString("code", "android-foreground-service-start-not-allowed")
                    })
                }
            }
        }

        scope.launch {
            val BACKGROUNDABLE_STATES = listOf(
                AudioPlayerState.IDLE,
                AudioPlayerState.ENDED,
                AudioPlayerState.STOPPED,
                AudioPlayerState.ERROR,
                AudioPlayerState.PAUSED
            )
            val REMOVABLE_STATES = listOf(
                AudioPlayerState.IDLE,
                AudioPlayerState.STOPPED,
                AudioPlayerState.ERROR
            )
            val LOADING_STATES = listOf(
                AudioPlayerState.LOADING,
                AudioPlayerState.READY,
                AudioPlayerState.BUFFERING
            )
            var stateCount = 0
            event.stateChange.collect {
                stateCount++
                if (it in LOADING_STATES) return@collect;
                // Skip initial idle state, since we are only interested when
                // state becomes idle after not being idle
                stopForegroundWhenNotOngoing = stateCount > 1 && it in BACKGROUNDABLE_STATES
                removeNotificationWhenNotOngoing = stopForegroundWhenNotOngoing && it in REMOVABLE_STATES
            }
        }

        fun shouldStopForeground(): Boolean {
            return stopForegroundWhenNotOngoing && (removeNotificationWhenNotOngoing || isForegroundService())
        }

        scope.launch {
            event.notificationStateChange.collect {
                when (it) {
                    is NotificationState.POSTED -> {
                        Timber.d("notification posted with id=%s, ongoing=%s", it.notificationId, it.ongoing)
                        notificationId = it.notificationId;
                        notification = it.notification;
                        if (it.ongoing) {
                            if (player.playWhenReady) {
                                startForegroundIfNecessary()
                            }
                        } else if (shouldStopForeground()) {
                            // Allow the application a grace period to complete any actions
                            // that may necessitate keeping the service in a foreground state.
                            // For instance, queuing new media (e.g., related music) after the
                            // user's queue is complete. This prevents the service from potentially
                            // being immediately destroyed once the player finishes playing media.
                            scope.launch {
                                delay(stopForegroundGracePeriod.toLong() * 1000)
                                if (shouldStopForeground()) {
                                    @Suppress("DEPRECATION")
                                    stopForeground(removeNotificationWhenNotOngoing)
                                    Timber.d("Notification has been stopped")
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    @MainThread
    private fun observeEvents() {
        scope.launch {
            event.stateChange.collect {
                Timber.d("🎵 Android TrackPlayer state change: ${it} -> ${it.asLibState.state}")
                val stateBundle = getPlayerStateBundle(it)
                val state = stateBundle.getString("state") ?: ""
                Timber.d("🎵 MusicService calling trackPlayerModule.onPlaybackState: $state")
                trackPlayerModule?.onPlaybackState(state, stateBundle)

                if (it == AudioPlayerState.ENDED && player.nextItem == null) {
                    emitQueueEndedEvent()
                }
            }
        }

        scope.launch {
            event.audioItemTransition.collect {
                if (it !is AudioItemTransitionReason.REPEAT) {
                    emitPlaybackTrackChangedEvents(
                        player.currentIndex,
                        player.previousIndex,
                        (it?.oldPosition ?: 0).toSeconds()
                    )
                }
            }
        }

        scope.launch {
            event.onAudioFocusChanged.collect {
                // BUTTON_DUCK is not in TurboModule spec - skipping
            }
        }

        scope.launch {
            event.onPlayerActionTriggeredExternally.collect {
                when (it) {
                    is MediaSessionCallback.RATING -> {
                        // BUTTON_SET_RATING is not in TurboModule spec - skipping
                    }
                    is MediaSessionCallback.SEEK -> {
                        trackPlayerModule?.onRemoteSeek(Bundle().apply {
                            putDouble("position", it.positionMs.toSeconds())
                        })
                    }
                    MediaSessionCallback.PLAY -> trackPlayerModule?.onRemotePlay()
                    MediaSessionCallback.PAUSE -> trackPlayerModule?.onRemotePause()
                    MediaSessionCallback.NEXT -> trackPlayerModule?.onRemoteNext()
                    MediaSessionCallback.PREVIOUS -> trackPlayerModule?.onRemotePrevious()
                    MediaSessionCallback.STOP -> trackPlayerModule?.onRemoteStop()
                    MediaSessionCallback.FORWARD -> {
                        trackPlayerModule?.onRemoteJumpForward(Bundle().apply {
                            val interval = latestOptions?.getDouble(FORWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
                            putInt("interval", interval.toInt())
                        })
                    }
                    MediaSessionCallback.REWIND -> {
                        trackPlayerModule?.onRemoteJumpBackward(Bundle().apply {
                            val interval = latestOptions?.getDouble(BACKWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
                            putInt("interval", interval.toInt())
                        })
                    }
                }
            }
        }

        scope.launch {
            event.onTimedMetadata.collect {
                // METADATA_TIMED_RECEIVED and PLAYBACK_METADATA are not in TurboModule spec - skipping
            }
        }

        scope.launch {
            event.onCommonMetadata.collect {
                // METADATA_COMMON_RECEIVED is not in TurboModule spec - skipping
            }
        }

        scope.launch {
            event.playWhenReadyChange.collect {
                val bundle = Bundle().apply {
                    putBoolean("playWhenReady", it.playWhenReady)
                }
                Timber.d("🎵 MusicService calling trackPlayerModule.onPlaybackPlayWhenReadyChanged")
                trackPlayerModule?.onPlaybackPlayWhenReadyChanged(bundle)
            }
        }

        scope.launch {
            event.playbackError.collect {
                val errorBundle = getPlaybackErrorBundle()
                val errorMessage = errorBundle.getString("message") ?: ""
                Timber.d("🎵 MusicService calling trackPlayerModule.onPlaybackError: $errorMessage")
                trackPlayerModule?.onPlaybackError(errorMessage, errorBundle)
            }
        }
    }

    private fun getPlaybackErrorBundle(): Bundle {
        val bundle = Bundle()
        val error = playbackError
        if (error?.message != null) {
            bundle.putString("message", error.message)
        }
        if (error?.code != null) {
            bundle.putString("code", "android-" + error.code)
        }
        return bundle
    }


    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        // TurboModule: Don't use headless tasks with New Architecture
        // Remote control events are handled via direct TurboModule callbacks
        return null
    }

    @MainThread
    override fun onBind(intent: Intent?): IBinder? {
        val intentAction = intent?.action
        return if (intentAction != null) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    @MainThread
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        if (!::player.isInitialized) return

        when (appKilledPlaybackBehavior) {
            AppKilledPlaybackBehavior.PAUSE_PLAYBACK -> player.pause()
            AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION -> {
                player.clear()
                player.stop()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }

                stopSelf()
                exitProcess(0)
            }
            else -> {}
        }
    }

    @MainThread
    override fun onHeadlessJsTaskFinish(taskId: Int) {
        // This is empty so ReactNative doesn't kill this service
    }

    @MainThread
    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) {
            player.destroy()
        }

        progressUpdateJob?.cancel()
        
        // Clean up any pending search results to prevent memory leaks
        pendingSearchResults.values.forEach { result ->
            try {
                result.sendResult(emptyList())
            } catch (e: Exception) {
                Timber.tag("RNTP-AA").w(e, "Error cleaning up search result")
            }
        }
        pendingSearchResults.clear()
        
        MusicService.setInstance(null)
    }

    @MainThread
    inner class MusicBinder : Binder() {
        val service = this@MusicService
    }

    companion object {
        const val EMPTY_NOTIFICATION_ID = 1
        const val STATE_KEY = "state"
        const val ERROR_KEY  = "error"
        const val EVENT_KEY = "event"
        const val DATA_KEY = "data"
        const val TRACK_KEY = "track"
        const val NEXT_TRACK_KEY = "nextTrack"
        const val POSITION_KEY = "position"
        const val DURATION_KEY = "duration"
        const val BUFFERED_POSITION_KEY = "buffered"

        const val TASK_KEY = "TrackPlayer"

        const val MIN_BUFFER_KEY = "minBuffer"
        const val MAX_BUFFER_KEY = "maxBuffer"
        const val PLAY_BUFFER_KEY = "playBuffer"
        const val BACK_BUFFER_KEY = "backBuffer"

        const val FORWARD_JUMP_INTERVAL_KEY = "forwardJumpInterval"
        const val BACKWARD_JUMP_INTERVAL_KEY = "backwardJumpInterval"
        const val PROGRESS_UPDATE_EVENT_INTERVAL_KEY = "progressUpdateEventInterval"

        const val MAX_CACHE_SIZE_KEY = "maxCacheSize"

        const val ANDROID_OPTIONS_KEY = "android"

        const val STOPPING_APP_PAUSES_PLAYBACK_KEY = "stoppingAppPausesPlayback"
        const val APP_KILLED_PLAYBACK_BEHAVIOR_KEY = "appKilledPlaybackBehavior"
        const val STOP_FOREGROUND_GRACE_PERIOD_KEY = "stopForegroundGracePeriod"
        const val PAUSE_ON_INTERRUPTION_KEY = "alwaysPauseOnInterruption"
        const val AUTO_UPDATE_METADATA = "autoUpdateMetadata"
        const val AUTO_HANDLE_INTERRUPTIONS = "autoHandleInterruptions"
        const val AUTO_HANDLE_ROUTE_CHANGES = "autoHandleRouteChanges"
        const val ANDROID_AUDIO_CONTENT_TYPE = "androidAudioContentType"
        const val ANDROID_AUDIO_FOCUS_GAIN_TYPE = "androidAudioFocusGainType"
        const val IS_FOCUS_LOSS_PERMANENT_KEY = "permanent"
        const val IS_PAUSED_KEY = "paused"

        const val DEFAULT_JUMP_INTERVAL = 15.0
        const val DEFAULT_STOP_FOREGROUND_GRACE_PERIOD = 5
        
        // Static reference to MusicService instance for external access
        @Volatile
        private var instance: MusicService? = null
        
        fun getInstance(): MusicService? = instance
        
        fun setInstance(service: MusicService?) {
            instance = service
        }
    }

    private fun parseCapabilities(capabilityStrings: ArrayList<String>?): List<Capability> {
        return capabilityStrings?.mapNotNull { capString ->
            when (capString) {
                "play" -> Capability.PLAY
                "playFromId" -> Capability.PLAY_FROM_ID
                "playFromSearch" -> Capability.PLAY_FROM_SEARCH
                "pause" -> Capability.PAUSE
                "stop" -> Capability.STOP
                "seekTo" -> Capability.SEEK_TO
                "skip" -> Capability.SKIP
                "next" -> Capability.SKIP_TO_NEXT
                "previous" -> Capability.SKIP_TO_PREVIOUS
                "jumpForward" -> Capability.JUMP_FORWARD
                "jumpBackward" -> Capability.JUMP_BACKWARD
                "setRating" -> Capability.SET_RATING
                "like" -> Capability.LIKE
                "dislike" -> Capability.DISLIKE
                "bookmark" -> Capability.BOOKMARK
                else -> null
            }
        } ?: emptyList()
    }
    
    // MARK: - Audio Focus Handling
    
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Any loss of audio focus - emit simple begin event
                if (interruptionStartTime == null) {
                    interruptionStartTime = System.currentTimeMillis()
                    trackPlayerModule?.onRemoteDuck(Bundle().apply {
                        putString("reason", "began")
                    })
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Gained audio focus - emit simple end event
                if (interruptionStartTime != null) {
                    trackPlayerModule?.onRemoteDuck(Bundle().apply {
                        putString("reason", "ended")
                    })
                    interruptionStartTime = null
                }
            }
        }
    }
    
    fun requestAudioFocus(): Boolean {
        return audioManager?.requestAudioFocus(
            this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    
    fun abandonAudioFocus() {
        audioManager?.abandonAudioFocus(this)
    }
}
