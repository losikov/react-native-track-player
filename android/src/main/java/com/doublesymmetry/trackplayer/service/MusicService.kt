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
import android.support.v4.media.RatingCompat
import android.util.Log
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

    @ExperimentalCoroutinesApi
    override fun onCreate() {
        Timber.tag("GVA-RNTP").d("RNTP musicservice created.")
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
        Timber.tag("RNTP-AA").d("$clientPackageName (uid=$clientUid) attempted to get Browsable root.")

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
        Timber.tag("GVA-RNTP").d("RNTP received loadChildren req: %s", parentMediaId)
        
        trackPlayerModule?.onRemoteBrowse(Bundle().apply {
            putString("mediaId", parentMediaId)
        })
        result.sendResult(mediaTree[parentMediaId])
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
        getTaskConfig(intent)?.let { startTask(it) }
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
            print("Player was initialized. Prevent re-initializing again")
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
                Timber.tag("GVA-RNTP").d("RNTP received req to play from mediaID: %s", mediaId)
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
                Timber.tag("GVA-RNTP").d("RNTP received req to play from query: %s, extras: %s", query, extras)
                
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
                trackPlayerModule?.onRemotePlayFromSearch(searchBundle)
            }
            
            override fun handlePrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                Timber.tag("GVA-RNTP").d("RNTP received req to prepare from mediaID: %s", mediaId)
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
                Timber.tag("GVA-RNTP").d("RNTP received req to prepare from query: %s, extras: %s", query, extras)
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
                trackPlayerModule?.onRemotePrepareFromSearch(searchBundle)
            }

            override fun handleSkipToQueueItem(id: Long) {
                Timber.tag("GVA-RNTP").d("RNTP received req to play from queue index: %d", id)
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
    fun skip(index: Int) {
        player.jumpToItem(index)
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
