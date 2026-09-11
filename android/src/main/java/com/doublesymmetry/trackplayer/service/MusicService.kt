package com.doublesymmetry.trackplayer.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.support.v4.media.RatingCompat
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import com.doublesymmetry.kotlinaudio.models.*
import com.doublesymmetry.kotlinaudio.players.InterceptingPlayer
import com.doublesymmetry.kotlinaudio.players.QueuedAudioPlayer
import com.doublesymmetry.trackplayer.HeadlessJsMediaService
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toMilliseconds
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toSeconds
import com.doublesymmetry.trackplayer.extensions.asLibState
import com.doublesymmetry.trackplayer.extensions.find
import com.doublesymmetry.trackplayer.model.Track
import com.doublesymmetry.trackplayer.model.TrackAudioItem
import com.doublesymmetry.trackplayer.utils.BundleUtils
import com.doublesymmetry.trackplayer.utils.UriUtils
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

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
    fun onPlaybackStopAtReached(data: Bundle)

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

/**
 * The media3 `MediaLibraryService`.
 *
 * Compared with the `MediaBrowserServiceCompat` it replaces, three whole subsystems are gone:
 * `setupForegrounding()`'s 108-line state machine plus `isForegroundService()` and
 * `startAndStopEmptyNotificationToAvoidANR()` (media3 posts the notification and moves the service
 * in and out of the foreground), the 903-line `NotificationManager` (see [RntpNotificationProvider])
 * and the hand-rolled audio focus (ExoPlayer's own manager). What is *not* gone is any behaviour the
 * app or a car can see: the browse tree and its content styles, voice search, PLAY and PREPARE from
 * id and from search, the error state Assistant reads, the artwork content provider, and the fact
 * that every transport command is routed to JS rather than applied natively.
 *
 * One structural difference is worth knowing about. media3 has to hand a session — and therefore a
 * player — to the first controller that connects, and on an Android Auto cold start that happens
 * before JS exists to call `setupPlayer`. So the engine is created on demand in [ensureSession] with
 * whatever options are known, and [setupPlayer] applies the real ones when they arrive; the buffer
 * sizes are the only thing that needs the player rebuilt, and that is safe exactly while the queue
 * is still empty.
 */
@UnstableApi
@MainThread
class MusicService : HeadlessJsMediaService() {
    private var engine: QueuedAudioPlayer? = null
    private var sessionPlayer: InterceptingPlayer? = null
    private var librarySession: MediaLibraryService.MediaLibrarySession? = null
    private val notificationProvider by lazy { RntpNotificationProvider(this) }

    private val binder = MusicBinder()
    private val scope = MainScope()
    private var progressUpdateJob: Job? = null
    private var eventJobs = mutableListOf<Job>()

    var mediaTree: Map<String, List<MediaItem>> = HashMap()
    var mediaTreeStyle: List<Int> = listOf(
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    )

    // Direct reference to TrackPlayerModule for New Architecture events
    var trackPlayerModule: MusicServiceEventListener? = null

    var ratingType: Int = RatingCompat.RATING_NONE

    // Search result callbacks — a search id is answered by JS through sendSearchResults()
    private class PendingSearch(
        val query: String,
        val controller: MediaSession.ControllerInfo,
        val params: MediaLibraryService.LibraryParams?,
    )

    private val pendingSearchResults = mutableMapOf<String, PendingSearch>()
    private val searchResults = mutableMapOf<String, List<MediaItem>>()
    private var searchIdCounter = 0

    /** Queued when a browser searched before the React runtime existed. */
    private data class PendingSearchRequest(
        val query: String,
        val extras: Bundle?,
        val controller: MediaSession.ControllerInfo,
        val params: MediaLibraryService.LibraryParams?,
    )

    private val pendingSearchRequests = mutableListOf<PendingSearchRequest>()

    /** `onGetChildren` for a node the tree does not have yet: resolved by `setBrowseTree`. */
    private val pendingBrowseResults =
        mutableMapOf<String, SettableFuture<LibraryResult<ImmutableList<MediaItem>>>>()
    private var isInitializingReactNative = false

    override fun onCreate() {
        Timber.tag("GVA-RNTP").d("RNTP musicservice created.")
        super.onCreate()
        setInstance(this)
        setForegroundServiceTimeoutMs(stopForegroundGracePeriod * 1000L)
        // The ExoPlayer 2 build removed the notification outright in IDLE, STOPPED and ERROR
        // (`REMOVABLE_STATES` in the deleted `setupForegrounding`). Stated explicitly rather than
        // left to whatever media3's default happens to be in a given release.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                // The same JS error code the hand-written foregrounding used to report.
                Timber.e("ForegroundServiceStartNotAllowedException: App tried to start a foreground Service when it was not allowed to do so.")
                trackPlayerModule?.onPlaybackError("foreground service start not allowed", Bundle().apply {
                    putString("message", "App tried to start a foreground Service when it was not allowed to do so.")
                    putString("code", "android-foreground-service-start-not-allowed")
                })
            }
        })
    }

    /**
     * Use [appKilledPlaybackBehavior] instead.
     */
    @Deprecated("This will be removed soon")
    var stoppingAppPausesPlayback = true
        private set

    enum class AppKilledPlaybackBehavior(val string: String) {
        CONTINUE_PLAYBACK("continue-playback"), PAUSE_PLAYBACK("pause-playback"), STOP_PLAYBACK_AND_REMOVE_NOTIFICATION("stop-playback-and-remove-notification")
    }

    private var appKilledPlaybackBehavior = AppKilledPlaybackBehavior.CONTINUE_PLAYBACK

    /**
     * How long the service may stay in the foreground after playback stops.
     *
     * media3 keeps it there for ten minutes by default (`DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS`),
     * which leaves an undismissable notification behind a pause. The ExoPlayer 2 build detached
     * after `stopForegroundGracePeriod` seconds — five by default, and the app does not override it
     * — so that option now drives media3's timeout instead of a hand-written state machine.
     */
    private var stopForegroundGracePeriod: Int = DEFAULT_STOP_FOREGROUND_GRACE_PERIOD

    private var latestOptions: Bundle? = null
    private var latestPlayerOptions: Bundle? = null
    private var capabilities: List<Capability> = emptyList()
    private var notificationCapabilities: List<Capability> = emptyList()
    private var compactCapabilities: List<Capability> = emptyList()

    private val player: QueuedAudioPlayer
        get() = engine ?: throw IllegalStateException("The player is not initialized")

    val tracks: List<Track>
        get() = engine?.items?.map { (it as TrackAudioItem).track } ?: emptyList()

    val currentTrack
        get() = (player.currentItem as TrackAudioItem).track

    val state
        get() = engine?.playerState ?: AudioPlayerState.IDLE

    val playbackError
        get() = engine?.playbackError

    val event
        get() = player.event

    var playWhenReady: Boolean
        get() = engine?.playWhenReady ?: false
        set(value) {
            player.playWhenReady = value
        }

    // region session / player lifecycle

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession =
        ensureSession()

    @MainThread
    private fun ensureSession(): MediaLibraryService.MediaLibrarySession {
        librarySession?.let { return it }

        val engine = ensurePlayer()
        val intercepting = InterceptingPlayer(engine, mediaSessionCallback) { handleRemoteAction(it) }
        configureSessionPlayer(intercepting)
        sessionPlayer = intercepting

        // The session activity: Google Assistant launches the app through this rather than the
        // service ever starting an Activity itself.
        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Intent.ACTION_VIEW
            data = Uri.parse("trackplayer://session-activity")
        }

        val builder = MediaLibraryService.MediaLibrarySession.Builder(this, intercepting, LibraryCallback())
        sessionActivityIntent?.let {
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, it, getPendingIntentFlags()))
            Timber.d("🎵 MusicService: setSessionActivity() called with PendingIntent for MainActivity")
        }
        val session = builder.build()
        librarySession = session
        setMediaNotificationProvider(notificationProvider)
        // `MediaSessionService` only wires a session to the notification manager in `addSession`,
        // and it calls that itself only when a controller connects or a media button arrives. The
        // app's own playback goes through the TurboModule and neither, so without this there would
        // be no notification and no foreground service until something external connected.
        addSession(session)
        return session
    }

    @MainThread
    private fun ensurePlayer(): QueuedAudioPlayer {
        engine?.let { return it }
        val options = latestPlayerOptions
        val created = QueuedAudioPlayer(
            this,
            playerConfigFrom(options),
            bufferConfigFrom(options),
            cacheConfigFrom(options),
            mediaSessionCallback,
        )
        created.automaticallyUpdateNotificationMetadata =
            options?.getBoolean(AUTO_UPDATE_METADATA, true) ?: true
        engine = created
        observeEvents()
        return created
    }

    private fun playerConfigFrom(playerOptions: Bundle?) = PlayerConfig(
        interceptPlayerActionsTriggeredExternally = true,
        handleAudioBecomingNoisy = playerOptions?.getBoolean(AUTO_HANDLE_ROUTE_CHANGES) ?: true,
        handleAudioFocus = playerOptions?.getBoolean(AUTO_HANDLE_INTERRUPTIONS) ?: false,
        audioContentType = when (playerOptions?.getString(ANDROID_AUDIO_CONTENT_TYPE)) {
            "music" -> AudioContentType.MUSIC
            "speech" -> AudioContentType.SPEECH
            "sonification" -> AudioContentType.SONIFICATION
            "movie" -> AudioContentType.MOVIE
            "unknown" -> AudioContentType.UNKNOWN
            else -> AudioContentType.MUSIC
        }
    )

    private fun bufferConfigFrom(playerOptions: Bundle?): BufferConfig? =
        if (playerOptions == null) null else BufferConfig(
            playerOptions.getDouble(MIN_BUFFER_KEY).toMilliseconds().toInt(),
            playerOptions.getDouble(MAX_BUFFER_KEY).toMilliseconds().toInt(),
            playerOptions.getDouble(PLAY_BUFFER_KEY).toMilliseconds().toInt(),
            playerOptions.getDouble(BACK_BUFFER_KEY).toMilliseconds().toInt(),
        )

    private fun cacheConfigFrom(playerOptions: Bundle?): CacheConfig? =
        if (playerOptions == null) null else CacheConfig(playerOptions.getDouble(MAX_CACHE_SIZE_KEY).toLong())

    /**
     * Called by the module once JS has connected. If a browser already forced the engine into
     * existence (Android Auto cold start), the real options are applied to it here instead.
     */
    @MainThread
    fun setupPlayer(playerOptions: Bundle?) {
        latestPlayerOptions = playerOptions
        val existing = engine
        if (existing != null) {
            Timber.d("Player was initialized. Applying the options that arrived with setupPlayer.")
            existing.automaticallyUpdateNotificationMetadata =
                playerOptions?.getBoolean(AUTO_UPDATE_METADATA, true) ?: true
            val rebuilt = existing.applyConfig(
                playerConfigFrom(playerOptions),
                bufferConfigFrom(playerOptions),
                cacheConfigFrom(playerOptions),
            )
            if (rebuilt) {
                val intercepting = InterceptingPlayer(existing, mediaSessionCallback) { handleRemoteAction(it) }
                configureSessionPlayer(intercepting)
                sessionPlayer = intercepting
                librarySession?.player = intercepting
            }
        }
        ensureSession()
    }

    /**
     * Everything about the session player that is not decided by its constructor.
     *
     * **The transport policy.** Play, pause, stop, seek and the ±jumps arriving from the media
     * session — lock screen, notification, Bluetooth, Android Auto, Assistant — are applied by the
     * engine itself and then announced to JS, which records them without re-issuing them. The player
     * is controllable whether or not a React context is alive. Next and previous stay routed to JS
     * forever: JS owns their meaning (chapter navigation, the daily-date rule, the 20-second
     * restart), as does a seek that names a different queue item.
     *
     * **The ±jump increments.** media3 takes them at `ExoPlayer.Builder` time, but the app sets them
     * through `updateOptions`, which arrives later. The session and every controller read them from
     * the session player, so the intercepting player answers with the configured values.
     */
    private fun configureSessionPlayer(target: InterceptingPlayer) {
        target.policy = TransportPolicy.APPLY_NATIVELY_AND_NOTIFY
        val forward = latestOptions?.getDouble(FORWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
        val backward = latestOptions?.getDouble(BACKWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
        target.seekForwardIncrementOverrideMs = (forward * 1000).toLong()
        target.seekBackIncrementOverrideMs = (backward * 1000).toLong()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // START_STICKY as on the ExoPlayer 2 build: the service is the thing that owns playback and
        // is expected back if the system reclaims it.
        return START_STICKY
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
        val engine = this.engine ?: return

        when (appKilledPlaybackBehavior) {
            AppKilledPlaybackBehavior.PAUSE_PLAYBACK -> engine.pause()
            AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION -> {
                engine.clear()
                engine.stop()

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
        // Deliberately *not* calling super: media3's default stops the service as soon as playback
        // is not ongoing, which after `pause-playback` would take the notification and the queue
        // with it. The ExoPlayer 2 build left the service running, and so does this one.
    }

    @MainThread
    override fun onHeadlessJsTaskFinish(taskId: Int) {
        // This is empty so ReactNative doesn't kill this service
    }

    @MainThread
    override fun onDestroy() {
        librarySession?.release()
        librarySession = null
        sessionPlayer = null
        engine?.destroy()
        engine = null

        progressUpdateJob?.cancel()
        eventJobs.forEach { it.cancel() }
        eventJobs.clear()

        // Unblock any browser still waiting on a node the tree never delivered.
        pendingBrowseResults.values.forEach { it.set(LibraryResult.ofItemList(emptyList(), null)) }
        pendingBrowseResults.clear()
        pendingSearchResults.clear()
        pendingSearchRequests.clear()

        setInstance(null)
        super.onDestroy()
    }

    // endregion

    // region Android Auto / Assistant

    private val mediaSessionCallback = object : AAMediaSessionCallBack {
        override fun handlePlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Timber.tag("GVA-RNTP").d("RNTP received req to play from mediaID: $mediaId")
            if (mediaId.isNullOrEmpty()) {
                setPlaybackStateError(
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

            val searchQuery = query ?: ""
            val artistName = extras?.getString("android.intent.extra.artist")
                ?: extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
            val albumName = extras?.getString("android.intent.extra.album")
                ?: extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)
            val title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)

            val hasSearchParams = searchQuery.isNotEmpty() ||
                artistName != null || albumName != null || title != null

            if (!hasSearchParams) {
                Timber.tag("GVA-RNTP").w("Empty search query with no metadata provided")
            }

            val searchBundle = Bundle().apply {
                putString("query", searchQuery)
                if (extras != null) {
                    putBundle("extras", extras)
                    artistName?.let { putString("artist", it) }
                    albumName?.let { putString("album", it) }
                }
            }

            val module = trackPlayerModule
            if (module == null) {
                Timber.tag("GVA-RNTP").w("TrackPlayerModule is null, cannot handle play from search. Initializing React Native...")
                startReactNativeIfNeeded()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (trackPlayerModule != null) {
                        Timber.tag("GVA-RNTP").d("TrackPlayerModule now available, retrying play from search")
                    } else {
                        Timber.tag("GVA-RNTP").e("TrackPlayerModule still null after initialization delay, play from search may fail")
                    }
                    trackPlayerModule?.onRemotePlayFromSearch(searchBundle)
                }, 2000)
                return
            }

            Timber.tag("GVA-RNTP").d("Calling TrackPlayerModule.onRemotePlayFromSearch with query: '$searchQuery'")
            module.onRemotePlayFromSearch(searchBundle)
        }

        override fun handlePrepareFromMediaId(mediaId: String?, extras: Bundle?) {
            Timber.tag("GVA-RNTP").d("RNTP received req to prepare from mediaID: $mediaId")
            if (mediaId.isNullOrEmpty()) {
                setPlaybackStateError(
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
                putBoolean("playWhenReady", false)
                if (extras != null) {
                    putBundle("extras", extras)
                    extras.getString("android.intent.extra.artist")?.let { putString("artist", it) }
                    extras.getString("android.intent.extra.album")?.let { putString("album", it) }
                    extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)?.let { putString("artist", it) }
                    extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)?.let { putString("album", it) }
                }
            }

            val module = trackPlayerModule
            if (module == null) {
                Timber.tag("GVA-RNTP").w("TrackPlayerModule is null, cannot handle prepare from search. Initializing React Native...")
                startReactNativeIfNeeded()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (trackPlayerModule != null) {
                        Timber.tag("GVA-RNTP").d("TrackPlayerModule now available, retrying prepare from search")
                    } else {
                        Timber.tag("GVA-RNTP").e("TrackPlayerModule still null after initialization delay, prepare from search may fail")
                    }
                    trackPlayerModule?.onRemotePrepareFromSearch(searchBundle)
                }, 2000)
                return
            }

            Timber.tag("GVA-RNTP").d("Calling TrackPlayerModule.onRemotePrepareFromSearch with query: '${query ?: ""}'")
            module.onRemotePrepareFromSearch(searchBundle)
        }

        override fun handleSkipToQueueItem(id: Long) {
            Timber.tag("GVA-RNTP").d("RNTP received req to play from queue index: $id")
            trackPlayerModule?.onRemoteSkip(Bundle().apply { putInt("index", id.toInt()) })
        }
    }

    /**
     * The library callback: the media3 shape of `onGetRoot` / `onLoadChildren` / `onSearch`.
     *
     * Legacy `MediaBrowserCompat` clients — Android Auto and Google Assistant — reach it through
     * media3's built-in compat layer, so every content-style hint and every extra keeps the same key
     * it had before; only the container changed from `MediaDescriptionCompat.extras` to
     * `MediaMetadata.extras`.
     */
    private inner class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Android Auto connecting while playback is live: re-apply the audio attributes so the
            // sound is routed to the head unit (KotlinAudio 2c6300b).
            if (browser.packageName == ANDROID_AUTO_PACKAGE) {
                scope.launch {
                    delay(500)
                    engine?.let { if (it.isPlaying) it.ensureAudioSessionInitialized() }
                }
            }

            params?.extras?.getInt("android.media.browse.EXTRA_MEDIA_ART_SIZE_HINT_PIXELS", -1)?.let {
                if (it > 0) Timber.tag("RNTP-AA").d("Android Auto requests images at size: ${it}x${it} pixels")
            }

            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, mediaTreeStyle[0])
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, mediaTreeStyle[1])
                // Declare search support for browsable search results.
                putBoolean(BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
            }

            // A different root for Assistant's "suggested" request, as before.
            val rootId = if (params?.isSuggested == true) "/suggested" else "/"

            // Never brings the app to the foreground: this returns a tree, nothing else.
            if (trackPlayerModule == null && mediaTree.isEmpty()) {
                startReactNativeIfNeeded()
            }

            val root = MediaItem.Builder()
                .setMediaId(rootId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()

            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    root,
                    MediaLibraryService.LibraryParams.Builder().setExtras(extras).build()
                )
            )
        }

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // An empty list is valid content ("no items"); only a missing key means "not built yet".
            val keyExists = mediaTree.containsKey(parentId)

            if (trackPlayerModule == null || !keyExists) {
                // getOrPut, not put: two browsers asking for the same node before the tree exists
                // share one future, so neither is left holding one that is never completed.
                val future = pendingBrowseResults.getOrPut(parentId) { SettableFuture.create() }
                startReactNativeIfNeeded()
                return future
            }

            trackPlayerModule?.onRemoteBrowse(Bundle().apply { putString("mediaId", parentId) })
            return Futures.immediateFuture(
                LibraryResult.ofItemList(mediaTree[parentId] ?: emptyList(), params)
            )
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = mediaTree.values.asSequence().flatten().firstOrNull { it.mediaId == mediaId }
                ?: searchResults.values.asSequence().flatten().firstOrNull { it.mediaId == mediaId }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onSearch(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Timber.tag("RNTP-AA").d("RNTP received search req: query='$query', params=${params?.extras}")

            if (trackPlayerModule == null) {
                Timber.tag("RNTP-AA").w("TrackPlayerModule is null, queueing search request: query='$query'")
                pendingSearchRequests.add(PendingSearchRequest(query, params?.extras, browser, params))
                startReactNativeIfNeeded()
            } else {
                processSearchRequest(query, params?.extras, browser, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = searchResults[query] ?: emptyList()
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(all.subList(from, to), params))
        }

        /**
         * media3's replacement for `onPlayFromMediaId` / `onPlayFromSearch`: the request arrives as
         * a URI-less "request item" here and then as `setMediaItems`/`prepare`/`play` on the session
         * player, where [InterceptingPlayer] captures it. The items are returned unchanged because
         * media3 requires a resolved future and the real queue is loaded by JS.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)

        /**
         * "Play without launching the app" — a head unit or the system resumption notification
         * asking for the last thing that was playing. Answered with the first playable item of the
         * `recent` node, which reaches JS as `onRemotePrepareId` through [InterceptingPlayer]
         * because it carries a media id and no URI.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val recent = mediaTree["recent"] ?: mediaTree["/recent"]
            val playable = recent?.firstOrNull { it.mediaMetadata.isPlayable == true }
            if (playable == null) {
                startReactNativeIfNeeded()
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("No recent item to resume")
                )
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.of(MediaItem.Builder().setMediaId(playable.mediaId).build()),
                    0,
                    androidx.media3.common.C.TIME_UNSET,
                )
            )
        }
    }

    /**
     * Bring up the React runtime for a browse that arrived before the app ever ran.
     *
     * The tree is built in JS, so an Auto cold start has to start the runtime and then wait; the
     * TurboModule binds as soon as it exists and pushes the tree in, which resolves whatever
     * `onGetChildren` futures are outstanding.
     */
    private fun startReactNativeIfNeeded() {
        if (isInitializingReactNative || trackPlayerModule != null) return
        isInitializingReactNative = true
        Timber.tag("RNTP-AA").d("initializeReactNativeForAndroidAuto called")
        try {
            val starting = ensureReactContext {
                Timber.tag("RNTP-AA").d("React Native context ready, scheduling pending requests")
                isInitializingReactNative = false
                markReactNativeInitializedByMusicService()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (trackPlayerModule != null) {
                        Timber.tag("RNTP-AA").d("TrackPlayerModule available after RN init, processing pending requests")
                        sendPendingBrowseResults()
                        processPendingSearchRequests()
                    } else {
                        Timber.tag("RNTP-AA").w("TrackPlayerModule still null after RN init delay")
                    }
                }, 1000)
            }
            if (!starting) {
                Timber.tag("RNTP-AA").d("React Native context already exists")
            }
        } catch (e: Exception) {
            Timber.tag("RNTP-AA").e(e, "Error initializing React Native for Android Auto")
            isInitializingReactNative = false
        }
    }

    /**
     * Mark that React Native was initialized by MusicService so MainApplication can skip duplicate
     * initialization. Uses reflection to find MainApplication dynamically (any app package name).
     */
    private fun markReactNativeInitializedByMusicService() {
        try {
            val packageName = application.javaClass.`package`?.name ?: return
            listOf("$packageName.MainApplication", "$packageName.app.MainApplication").forEach { className ->
                try {
                    Class.forName(className)
                        .getMethod("markReactNativeInitializedByMusicService")
                        .invoke(null)
                    Timber.tag("RNTP-AA").d("Marked React Native as initialized via $className")
                    return
                } catch (e: ClassNotFoundException) {
                    // try the next pattern
                }
            }
        } catch (e: Exception) {
            Timber.tag("RNTP-AA").d("Could not mark React Native as initialized: ${e.message}")
        }
    }

    /**
     * Answer the browse requests that were waiting for the tree.
     *
     * Called both when the TurboModule binds and from `setBrowseTree`, and it only resolves nodes
     * the tree actually has: on an Android Auto cold start the module binds a second or two before
     * JS has built anything, and resolving then would hand the car an empty list for the root and
     * leave it there. A one-shot `getChildren` — which is what a media3 browser does — has no second
     * chance, unlike the legacy `subscribe` that `notifyChildrenChanged` could nudge afterwards.
     */
    fun sendPendingBrowseResults() {
        if (pendingBrowseResults.isEmpty()) {
            Timber.tag("RNTP-AA").d("No pending browse results to send")
            return
        }

        val ready = pendingBrowseResults.filterKeys { mediaTree.containsKey(it) }
        if (ready.isEmpty()) {
            Timber.tag("RNTP-AA").d(
                "%d browse request(s) still waiting for the tree: %s",
                pendingBrowseResults.size,
                pendingBrowseResults.keys,
            )
            return
        }

        Timber.tag("RNTP-AA").d("Sending ${ready.size} pending browse results")
        ready.forEach { (parentMediaId, future) ->
            pendingBrowseResults.remove(parentMediaId)
            val content = mediaTree[parentMediaId] ?: emptyList()
            trackPlayerModule?.onRemoteBrowse(Bundle().apply { putString("mediaId", parentMediaId) })
            future.set(LibraryResult.ofItemList(content, null))
        }
    }

    /** Process search requests queued before the TurboModule was ready. */
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
        requestsToProcess.forEach {
            Timber.tag("RNTP-AA").d("Processing queued search: query='${it.query}'")
            processSearchRequest(it.query, it.extras, it.controller, it.params)
        }
    }

    private fun processSearchRequest(
        query: String,
        extras: Bundle?,
        controller: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ) {
        val searchId = "search_${++searchIdCounter}_${System.currentTimeMillis()}"
        pendingSearchResults[searchId] = PendingSearch(query, controller, params)

        val artistName = extras?.getString("android.intent.extra.artist")
            ?: extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
        val albumName = extras?.getString("android.intent.extra.album")
            ?: extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)

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
            pendingSearchResults.remove(searchId)
            return
        }
        module.onRemoteSearch(searchBundle)
        // Results come back through sendSearchResults().
    }

    /**
     * Called from React Native (via TrackPlayerModule) to send search results back.
     * @param trackResults maps of track metadata: mediaId, title, artist, album, artwork
     */
    fun sendSearchResults(searchId: String, trackResults: List<Map<String, String?>>) {
        Timber.tag("RNTP-AA").d("Received search results: searchId=$searchId, count=${trackResults.size}")

        val pending = pendingSearchResults.remove(searchId)
        if (pending == null) {
            Timber.tag("RNTP-AA").w("No pending search result found for searchId: $searchId")
            return
        }

        scope.launch {
            val mediaItems = withContext(Dispatchers.IO) {
                trackResults.mapNotNull { createMediaItemFromTrackData(it) }
            }
            Timber.tag("RNTP-AA").d("Converted ${mediaItems.size} tracks to MediaItems for searchId: $searchId")
            searchResults[pending.query] = mediaItems
            librarySession?.notifySearchResultChanged(
                pending.controller,
                pending.query,
                mediaItems.size,
                pending.params,
            )
        }
    }

    private fun createMediaItemFromTrackData(trackData: Map<String, String?>): MediaItem? {
        try {
            val mediaId = trackData["mediaId"] ?: return null
            val title = trackData["title"] ?: mediaId
            val artist = trackData["artist"]
            val album = trackData["album"]
            val artwork = trackData["artwork"]

            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            artist?.let { metadata.setSubtitle(it); metadata.setArtist(it) }
            album?.let { metadata.setDescription(it); metadata.setAlbumTitle(it) }
            artwork?.let { metadata.setArtworkUri(browsableArtworkUri(it)) }

            return MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(metadata.build())
                .build()
        } catch (e: Exception) {
            Timber.tag("RNTP-AA").e(e, "Error creating MediaItem from track data: $trackData")
            return null
        }
    }

    /**
     * Android Auto reads artwork across a process boundary, so a `file://` in our private storage is
     * unreadable there and an `https://` may not be fetched at all — both are turned into
     * `content://` URIs served by `ImageContentProvider`, exactly as before.
     */
    private fun browsableArtworkUri(raw: String): Uri? = try {
        val uri = Uri.parse(raw)
        when (uri.scheme) {
            "file" -> UriUtils.convertFileUriToContentUri(this, raw)?.let { Uri.parse(it) } ?: uri
            "http", "https" -> UriUtils.convertHttpUriToContentUri(this, raw)?.let { Uri.parse(it) }
                ?: uri.also {
                    Timber.tag("RNTP-AA").w("HTTPS URL not cached locally - Android Auto may not display this artwork: $raw")
                }
            else -> uri
        }
    } catch (e: Exception) {
        Timber.tag("RNTP-AA").w(e, "Invalid artwork URI: $raw")
        null
    }

    /** `notifyChildrenChanged` for the browse tree, called by the module after `setBrowseTree`. */
    fun notifyChildrenChanged(parentId: String) {
        val count = mediaTree[parentId]?.size ?: 0
        librarySession?.notifyChildrenChanged(parentId, count, null)
    }

    /**
     * PlaybackState error for Google Assistant.
     *
     * JS speaks `PlaybackStateCompat` error codes; media3 speaks [SessionError] codes and converts
     * them back for legacy controllers. [sessionErrorCodeFor] is that conversion run backwards, so
     * the code Assistant sees is the code JS asked for.
     */
    fun setPlaybackStateError(errorCode: Int, errorMessage: String) {
        val session = librarySession
        if (session == null) {
            Timber.w("setPlaybackStateError before the session exists: $errorCode / $errorMessage")
            return
        }
        val sessionErrorCode = sessionErrorCodeFor(errorCode)
        Timber.tag("GVA-RNTP").d("setPlaybackStateError: legacy=%d -> session=%d, %s", errorCode, sessionErrorCode, errorMessage)
        session.sendError(SessionError(sessionErrorCode, errorMessage))
    }

    // endregion

    // region options and playback API used by the module

    @MainThread
    fun updateOptions(options: Bundle) {
        Timber.d("🎵 MusicService.updateOptions: START")
        latestOptions = options
        val androidOptions = options.getBundle(ANDROID_OPTIONS_KEY)

        appKilledPlaybackBehavior =
            AppKilledPlaybackBehavior::string.find(androidOptions?.getString(APP_KILLED_PLAYBACK_BEHAVIOR_KEY))
                ?: AppKilledPlaybackBehavior.CONTINUE_PLAYBACK

        // TODO: This handles a deprecated flag. Should be removed soon.
        options.getBoolean(STOPPING_APP_PAUSES_PLAYBACK_KEY).let {
            stoppingAppPausesPlayback = options.getBoolean(STOPPING_APP_PAUSES_PLAYBACK_KEY)
            if (stoppingAppPausesPlayback) {
                appKilledPlaybackBehavior = AppKilledPlaybackBehavior.PAUSE_PLAYBACK
            }
        }

        BundleUtils.getIntOrNull(androidOptions, STOP_FOREGROUND_GRACE_PERIOD_KEY)?.let {
            stopForegroundGracePeriod = it
        }
        setForegroundServiceTimeoutMs(stopForegroundGracePeriod * 1000L)

        ratingType = BundleUtils.getInt(options, "ratingType", RatingCompat.RATING_NONE)

        engine?.playerOptions?.alwaysPauseOnInterruption =
            androidOptions?.getBoolean(PAUSE_ON_INTERRUPTION_KEY) ?: false

        capabilities = parseCapabilities(options.getStringArrayList("capabilities"))
        notificationCapabilities = parseCapabilities(options.getStringArrayList("notificationCapabilities"))
        compactCapabilities = parseCapabilities(options.getStringArrayList("compactCapabilities"))
        if (notificationCapabilities.isEmpty()) notificationCapabilities = capabilities

        notificationProvider.notificationCapabilities = notificationCapabilities
        notificationProvider.compactCapabilities = compactCapabilities
        notificationProvider.playIcon = BundleUtils.getIconOrNull(this, options, "playIcon")
        notificationProvider.pauseIcon = BundleUtils.getIconOrNull(this, options, "pauseIcon")
        notificationProvider.stopIcon = BundleUtils.getIconOrNull(this, options, "stopIcon")
        notificationProvider.nextIcon = BundleUtils.getIconOrNull(this, options, "nextIcon")
        notificationProvider.previousIcon = BundleUtils.getIconOrNull(this, options, "previousIcon")
        notificationProvider.forwardIcon =
            BundleUtils.getIcon(this, options, "forwardIcon", com.doublesymmetry.trackplayer.R.drawable.forward)
        notificationProvider.rewindIcon =
            BundleUtils.getIcon(this, options, "rewindIcon", com.doublesymmetry.trackplayer.R.drawable.rewind)
        BundleUtils.getIconOrNull(this, options, "icon")?.let { notificationProvider.setSmallIcon(it) }
        sessionPlayer?.let { configureSessionPlayer(it) }
        librarySession?.let { triggerNotificationUpdate() }

        // setup progress update events if configured
        progressUpdateJob?.cancel()
        val updateInterval = BundleUtils.getDoubleOrNull(options, PROGRESS_UPDATE_EVENT_INTERVAL_KEY)
        if (updateInterval != null && updateInterval > 0) {
            progressUpdateJob = scope.launch {
                progressUpdateEventFlow(updateInterval).collect {
                    trackPlayerModule?.onPlaybackProgressUpdated(it)
                }
            }
        }
        Timber.d("🎵 MusicService.updateOptions: COMPLETED")
    }

    @MainThread
    private fun progressUpdateEventFlow(interval: Double) = flow {
        while (true) {
            if (engine?.isPlaying == true) {
                emit(progressUpdateEvent())
            }
            delay((interval * 1000).toLong())
        }
    }

    @MainThread
    private fun progressUpdateEvent(): Bundle = Bundle().apply {
        val player = engine
        putDouble(POSITION_KEY, (player?.position ?: 0).toSeconds())
        putDouble(DURATION_KEY, (player?.duration ?: 0).toSeconds())
        putDouble(BUFFERED_POSITION_KEY, (player?.bufferedPosition ?: 0).toSeconds())
        putInt(TRACK_KEY, player?.currentIndex ?: 0)
    }

    private fun getPendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
    }

    @MainThread
    fun add(track: Track) = add(listOf(track))

    @MainThread
    fun add(tracks: List<Track>) {
        player.add(tracks.map { it.toAudioItem() })
    }

    @MainThread
    fun add(tracks: List<Track>, atIndex: Int) {
        player.add(tracks.map { it.toAudioItem() }, atIndex)
    }

    @MainThread
    fun load(track: Track) {
        player.load(track.toAudioItem())
    }

    /**
     * The atomic load: queue, index and start position in one call, then the intent.
     *
     * `startPositionSeconds <= 0` means "from the beginning" — [androidx.media3.common.C.TIME_UNSET]
     * — so the caller never has to decide between 0 and "unset".
     */
    @MainThread
    fun loadQueue(
        tracks: List<Track>,
        startIndex: Int,
        startPositionSeconds: Double,
        playWhenReady: Boolean,
    ) {
        val positionMs =
            if (startPositionSeconds > 0) (startPositionSeconds * 1000).toLong()
            else androidx.media3.common.C.TIME_UNSET
        player.loadQueue(tracks.map { it.toAudioItem() }, startIndex, positionMs, playWhenReady)
    }

    @MainThread
    fun setStopAt(positionSeconds: Double) {
        player.setStopAt((positionSeconds * 1000).toLong())
    }

    @MainThread
    fun clearStopAt() {
        player.clearStopAt()
    }

    @MainThread
    fun move(fromIndex: Int, toIndex: Int) {
        player.move(fromIndex, toIndex)
    }

    @MainThread
    fun remove(index: Int) = remove(listOf(index))

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
        val positionMs =
            if (initialPositionSeconds != null && initialPositionSeconds > 0f) {
                (initialPositionSeconds * 1000).toLong()
            } else {
                androidx.media3.common.C.TIME_UNSET
            }
        // Single seekTo(index, positionMs) — do not seek again after jumpToItem; ExoPlayer drops
        // a follow-up seek when prepare() is still running (iOS seekTo-after-jump works synchronously).
        player.jumpToItem(index, positionMs)
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
        player.seekBy(offset.toLong(), TimeUnit.SECONDS)
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

    /**
     * The `onPlaybackState` payload and `getPlaybackState()` return value.
     *
     * `state` and `error` are exactly what they were on ExoPlayer 2. Everything else is additive:
     * the [com.doublesymmetry.kotlinaudio.models.PlayerSnapshot] fields the bridge starts carrying
     * in step 3 and the JS button binds to in step 4.
     */
    @MainThread
    fun getPlayerStateBundle(state: AudioPlayerState): Bundle {
        val bundle = Bundle()
        bundle.putString(STATE_KEY, state.asLibState.state)
        if (state == AudioPlayerState.ERROR) {
            bundle.putBundle(ERROR_KEY, getPlaybackErrorBundle())
        }
        engine?.state?.value?.let { snapshot ->
            bundle.putBoolean("playWhenReady", snapshot.playWhenReady)
            bundle.putBoolean("isPlaying", snapshot.isPlaying)
            bundle.putString("transport", snapshot.transport.name.lowercase(Locale.US))
            bundle.putString("readiness", snapshot.readiness.name.lowercase(Locale.US))
            bundle.putString("reason", snapshot.transportReason.name.lowercase(Locale.US))
            bundle.putString("suppression", snapshot.suppression.name.lowercase(Locale.US))
        }
        return bundle
    }

    @MainThread
    fun updateMetadataForTrack(index: Int, track: Track) {
        player.replaceItem(index, track.toAudioItem())
    }

    @MainThread
    fun updateNowPlayingMetadata(track: Track) {
        player.overrideMetadata(track.toAudioItem())
    }

    /**
     * The ExoPlayer 2 build detached the notification here. media3 owns the notification's
     * lifecycle: it goes away on its own once the player is idle with nothing queued, which is the
     * state the app is in when it calls this (it fires when a book is unloaded).
     */
    @MainThread
    fun clearNotificationMetadata() {
        engine?.let { if (it.items.isEmpty()) triggerNotificationUpdate() }
    }

    // endregion

    // region events

    private fun handleRemoteAction(action: MediaSessionCallback) {
        when (action) {
            is MediaSessionCallback.RATING -> {
                // BUTTON_SET_RATING is not in the TurboModule spec - skipping
            }
            is MediaSessionCallback.SEEK -> trackPlayerModule?.onRemoteSeek(Bundle().apply {
                putDouble("position", action.positionMs.toSeconds())
            })
            MediaSessionCallback.PLAY -> trackPlayerModule?.onRemotePlay()
            MediaSessionCallback.PAUSE -> trackPlayerModule?.onRemotePause()
            MediaSessionCallback.NEXT -> trackPlayerModule?.onRemoteNext()
            MediaSessionCallback.PREVIOUS -> trackPlayerModule?.onRemotePrevious()
            MediaSessionCallback.STOP -> trackPlayerModule?.onRemoteStop()
            MediaSessionCallback.FORWARD -> trackPlayerModule?.onRemoteJumpForward(Bundle().apply {
                val interval = latestOptions?.getDouble(FORWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
                putInt("interval", interval.toInt())
            })
            MediaSessionCallback.REWIND -> trackPlayerModule?.onRemoteJumpBackward(Bundle().apply {
                val interval = latestOptions?.getDouble(BACKWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
                putInt("interval", interval.toInt())
            })
        }
    }

    @MainThread
    private fun observeEvents() {
        val event = player.event

        eventJobs += scope.launch {
            event.stateChange.collect {
                Timber.d("🎵 Android TrackPlayer state change: $it -> ${it.asLibState.state}")
                val stateBundle = getPlayerStateBundle(it)
                val state = stateBundle.getString("state") ?: ""
                trackPlayerModule?.onPlaybackState(state, stateBundle)

                if (it == AudioPlayerState.ENDED && engine?.nextItem == null) {
                    emitQueueEndedEvent()
                }
            }
        }

        eventJobs += scope.launch {
            event.audioItemTransition.collect {
                if (it !is AudioItemTransitionReason.REPEAT) {
                    emitPlaybackTrackChangedEvents(
                        engine?.currentIndex,
                        engine?.previousIndex,
                        (it?.oldPosition ?: 0).toSeconds()
                    )
                }
            }
        }

        eventJobs += scope.launch {
            event.onAudioFocusChanged.collect {
                // BUTTON_DUCK is not in the TurboModule spec - skipping
            }
        }

        eventJobs += scope.launch {
            // A seek — including a seek while paused, which no interval tick reports — and an atomic
            // queue load produce one progress event each, so JS's stored progress is never left
            // holding the pre-seek position.
            event.progressDiscontinuity.collect {
                trackPlayerModule?.onPlaybackProgressUpdated(progressUpdateEvent())
            }
        }

        eventJobs += scope.launch {
            event.onTimedMetadata.collect {
                // METADATA_TIMED_RECEIVED and PLAYBACK_METADATA are not in the TurboModule spec
            }
        }

        eventJobs += scope.launch {
            event.onCommonMetadata.collect {
                // METADATA_COMMON_RECEIVED is not in the TurboModule spec
            }
        }

        eventJobs += scope.launch {
            event.playWhenReadyChange.collect {
                trackPlayerModule?.onPlaybackPlayWhenReadyChanged(Bundle().apply {
                    putBoolean("playWhenReady", it.playWhenReady)
                })
            }
        }

        eventJobs += scope.launch {
            event.stopAtReached.collect {
                trackPlayerModule?.onPlaybackStopAtReached(Bundle().apply {
                    putDouble(POSITION_KEY, it.toSeconds())
                })
            }
        }

        eventJobs += scope.launch {
            event.playbackError.collect {
                val errorBundle = getPlaybackErrorBundle()
                val errorMessage = errorBundle.getString("message") ?: ""
                trackPlayerModule?.onPlaybackError(errorMessage, errorBundle)
            }
        }
    }

    private fun emitPlaybackTrackChangedEvents(index: Int?, previousIndex: Int?, oldPosition: Double) {
        // Only emit the modern playback-active-track-changed event; the legacy
        // playback-track-changed event is not in the TurboModule spec.
        val tracks = tracks
        val bundle = Bundle()
        bundle.putDouble("lastPosition", oldPosition)
        if (tracks.isNotEmpty()) {
            val current = engine?.currentIndex ?: 0
            if (current in tracks.indices) {
                bundle.putInt("index", current)
                bundle.putBundle("track", tracks[current].originalItem)
            }
            if (previousIndex != null && previousIndex in tracks.indices) {
                bundle.putInt("lastIndex", previousIndex)
                bundle.putBundle("lastTrack", tracks[previousIndex].originalItem)
            }
        }
        trackPlayerModule?.onPlaybackActiveTrackChanged(bundle)
    }

    private fun emitQueueEndedEvent() {
        trackPlayerModule?.onPlaybackQueueEnded(Bundle().apply {
            putInt(TRACK_KEY, engine?.currentIndex ?: 0)
            putDouble(POSITION_KEY, (engine?.position ?: 0).toSeconds())
        })
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

    // endregion

    @MainThread
    inner class MusicBinder : Binder() {
        val service = this@MusicService
    }

    companion object {
        const val STATE_KEY = "state"
        const val ERROR_KEY = "error"
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

        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

        /** `androidx.media.utils.MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED`. */
        private const val BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED =
            "android.media.browse.SEARCH_SUPPORTED"

        // Static reference to MusicService instance for external access
        @Volatile
        private var instance: MusicService? = null

        fun getInstance(): MusicService? = instance

        fun setInstance(service: MusicService?) {
            instance = service
        }

        /**
         * `LegacyConversions.convertToLegacyErrorCode` run backwards.
         *
         * JS passes `PlaybackStateCompat` error codes (the app passes `1`, `ERROR_CODE_APP_ERROR`).
         * media3 only accepts its own [SessionError] codes and maps them back for legacy
         * controllers, so this picks the [SessionError] code whose legacy image is the one asked
         * for; anything unmapped becomes `ERROR_UNKNOWN`, whose legacy image is
         * `ERROR_CODE_UNKNOWN_ERROR`.
         */
        internal fun sessionErrorCodeFor(playbackStateCompatErrorCode: Int): Int =
            when (playbackStateCompatErrorCode) {
                0 -> SessionError.ERROR_UNKNOWN                            // UNKNOWN_ERROR
                1 -> SessionError.ERROR_INVALID_STATE                      // APP_ERROR
                2 -> SessionError.ERROR_NOT_SUPPORTED                      // NOT_SUPPORTED
                3 -> SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
                4 -> SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED
                5 -> SessionError.ERROR_SESSION_CONCURRENT_STREAM_LIMIT
                6 -> SessionError.ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED
                7 -> SessionError.ERROR_SESSION_NOT_AVAILABLE_IN_REGION
                8 -> SessionError.ERROR_SESSION_CONTENT_ALREADY_PLAYING
                9 -> SessionError.ERROR_SESSION_SKIP_LIMIT_REACHED
                10 -> SessionError.INFO_CANCELLED                          // ACTION_ABORTED
                11 -> SessionError.ERROR_SESSION_END_OF_PLAYLIST
                else -> SessionError.ERROR_UNKNOWN
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
}
