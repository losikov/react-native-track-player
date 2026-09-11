import type { TurboModule } from 'react-native';
import type { Double, EventEmitter, Int32 } from 'react-native/Libraries/Types/CodegenTypes';
export declare enum RatingType {
    Heart = "heart",
    ThumbsUpDown = "thumbsUpDown",
    ThreeStars = "3stars",
    FourStars = "4stars",
    FiveStars = "5stars",
    Percentage = "percentage"
}
export interface PlaybackQueueEndedEvent {
    /** The index of the active track when the playback queue ended. */
    track: number;
    /**
     * The playback position in seconds of the active track when the playback
     * queue ended.
     **/
    position: number;
}
export interface PlaybackErrorEvent {
    /** The error code */
    code: string;
    /** The error message */
    message: string;
}
export interface PlaybackActiveTrackChangedEvent {
    /** The index of previously active track. */
    lastIndex?: number;
    /**
     * The previously active track or `undefined` when there wasn't a previously
     * active track.
     */
    lastTrack?: Track;
    /**
     * The position of the previously active track in seconds.
     */
    lastPosition: number;
    /**
     * The newly active track index or `undefined` if there is no longer an
     * active track.
     */
    index?: number;
    /**
     * The newly active track or `undefined` if there is no longer an
     * active track.
     */
    track?: Track;
}
export declare enum TrackType {
    Default = "default",
    Dash = "dash",
    HLS = "hls",
    SmoothStreaming = "smoothstreaming"
}
export declare enum PitchAlgorithm {
    Linear = "linear",
    Music = "music",
    Voice = "voice"
}
export interface Track {
    id: string;
    url: string;
    /** The track title */
    title?: string;
    /** The track album */
    album?: string;
    /** The track artist */
    artist?: string;
    /** The track duration in seconds */
    duration?: number;
    /** The track artwork */
    artwork?: string;
    /** track description */
    description?: string;
    /** The track genre */
    genre?: string;
    /** The track release date in [RFC 3339](https://www.ietf.org/rfc/rfc3339.txt) */
    date?: string;
    /** The track rating */
    rating?: RatingType;
    /**
     * (iOS only) Whether the track is presented in the control center as being
     * live
     **/
    isLiveStream?: boolean;
    type?: TrackType;
    /** The user agent HTTP header */
    userAgent?: string;
    /** Mime type of the media file */
    contentType?: string;
    /** (iOS only) The pitch algorithm to apply to the sound. */
    pitchAlgorithm?: PitchAlgorithm;
    /** HTTP headers for the request */
    headers?: Object;
}
export interface Progress {
    /**
     * The playback position of the current track in seconds.
     * See https://rntp.dev/docs/api/functions/player#getposition
     **/
    position: number;
    /** The duration of the current track in seconds.
     * See https://rntp.dev/docs/api/functions/player#getduration
     **/
    duration: number;
    /**
     * The buffered position of the current track in seconds.
     **/
    buffered: number;
}
export type PlaybackState = 'none' | 'ready' | 'playing' | 'paused' | 'stopped' | 'loading' | 'buffering' | 'error' | 'ended';
export declare const State: {
    readonly None: "none";
    readonly Ready: "ready";
    readonly Playing: "playing";
    readonly Paused: "paused";
    readonly Stopped: "stopped";
    readonly Loading: "loading";
    readonly Buffering: "buffering";
    readonly Error: "error";
    readonly Ended: "ended";
};
export type State = typeof State[keyof typeof State];
export type IOSCategory = 'playback' | 'playAndRecord' | 'multiRoute' | 'ambient' | 'soloAmbient' | 'record';
export type IOSCategoryMode = 'default' | 'gameChat' | 'measurement' | 'moviePlayback' | 'spokenAudio' | 'videoChat' | 'videoRecording' | 'voiceChat' | 'voicePrompt';
export type IOSCategoryOptions = 'mixWithOthers' | 'duckOthers' | 'interruptSpokenAudioAndMixWithOthers' | 'allowBluetooth' | 'allowBluetoothA2DP' | 'allowAirPlay' | 'defaultToSpeaker';
export type AndroidAudioContentType = 'music' | 'speech' | 'sonification' | 'movie' | 'unknown';
export type AppKilledPlaybackBehavior = 'continue-playback' | 'pause-playback' | 'stop-playback-and-remove-notification';
export declare enum AndroidAutoContentStyle {
    List = 1,
    Grid = 2,
    CategoryList = 3,
    CategoryGrid = 4
}
export declare enum MediaItemPlayable {
    MediaPlayable = "0",
    MediaBrowsable = "1"
}
export interface AndroidAutoBrowseTree {
    [key: string]: any;
}
export declare enum MediaType {
    Audiobook = "AUDIO_BOOK",
    Podcast = "PODCAST_EPISODE",
    Music = "MUSIC"
}
export interface MediaItem {
    id: string;
    title: string;
    subtitle?: string;
    description?: string;
    iconUri?: string;
    mediaId?: string;
    playable?: MediaItemPlayable;
    contentStyle?: string;
    playbackProgress?: string;
    children?: MediaItem[];
    /** mediaType for Google Assistant recommendations */
    mediaType: MediaType;
}
export declare enum RepeatMode {
    Off = 0,
    Track = 1,
    Queue = 2
}
export declare const Capability: {
    readonly Play: "play";
    readonly PlayFromId: "playFromId";
    readonly PlayFromSearch: "playFromSearch";
    readonly Pause: "pause";
    readonly Stop: "stop";
    readonly SeekTo: "seekTo";
    readonly Skip: "skip";
    readonly SkipToNext: "next";
    readonly SkipToPrevious: "previous";
    readonly JumpForward: "jumpForward";
    readonly JumpBackward: "jumpBackward";
    readonly SetRating: "setRating";
    readonly Like: "like";
    readonly Dislike: "dislike";
    readonly Bookmark: "bookmark";
};
export interface FeedbackOptions {
    isActive: boolean;
    title?: string;
}
export interface AndroidOptions {
    appKilledPlaybackBehavior?: AppKilledPlaybackBehavior;
    alwaysPauseOnInterruption?: boolean;
    stopForegroundGracePeriod?: number;
}
export interface UpdateOptions {
    ratingType?: number;
    forwardJumpInterval?: number;
    backwardJumpInterval?: number;
    progressUpdateEventInterval?: number;
    android?: AndroidOptions;
    likeOptions?: FeedbackOptions;
    dislikeOptions?: FeedbackOptions;
    bookmarkOptions?: FeedbackOptions;
    capabilities?: Array<string>;
    notificationCapabilities?: Array<string>;
    compactCapabilities?: Array<string>;
    icon?: Object;
    playIcon?: Object;
    pauseIcon?: Object;
    stopIcon?: Object;
    previousIcon?: Object;
    nextIcon?: Object;
    rewindIcon?: Object;
    forwardIcon?: Object;
    color?: number;
}
export interface PlayerOptions {
    minBuffer?: number;
    maxBuffer?: number;
    backBuffer?: number;
    playBuffer?: number;
    maxCacheSize?: number;
    iosCategory?: IOSCategory;
    iosCategoryMode?: IOSCategoryMode;
    iosCategoryOptions?: Array<IOSCategoryOptions>;
    androidAudioContentType?: AndroidAudioContentType;
    waitForBuffer?: boolean;
    autoUpdateMetadata?: boolean;
    autoHandleInterruptions?: boolean;
    autoHandleRouteChanges?: boolean;
    androidAudioFocusGainType?: 'gain' | 'gainTransient' | 'gainTransientMayDuck';
}
/**
 * The engine state model ("PlayerCore").
 *
 * `state` above stays exactly what it always was — a mirror of raw player readiness, which is why a
 * button bound to it flickers LOADING -> READY -> BUFFERING -> PLAYING on every load. These fields
 * ride alongside it in the same `onPlaybackState` payload and in `getPlaybackState()`. Both
 * platforms fill them in as of step 3 (Android from `PlayerSnapshot`, iOS from `PlayerCore.swift`);
 * they stay optional so a JS build running against an older native binary still type-checks, and
 * `AudioPlayer.handlePlaybackState` keeps a fallback for that case.
 */
export type PlaybackTransport = 'playing' | 'paused' | 'ended' | 'error';
export type PlaybackReadiness = 'idle' | 'loading' | 'buffering' | 'ready' | 'ended';
export type PlaybackTransportReason = 'user' | 'remote' | 'error' | 'end_of_queue' | 'audio_focus_loss' | 'stop_at' | 'system';
export type PlaybackSuppression = 'none' | 'transient_audio_focus_loss' | 'unsuitable_output';
export interface PlaybackStateSnapshot {
    state: PlaybackState;
    error?: PlaybackErrorEvent;
    /** The raw intent: what `play()`/`pause()` last asked for. */
    playWhenReady?: boolean;
    /** Whether sound is actually coming out right now. */
    isPlaying?: boolean;
    /** What the play/pause button should show. */
    transport?: PlaybackTransport;
    readiness?: PlaybackReadiness;
    /** Why `transport` last changed. */
    reason?: PlaybackTransportReason;
    /** Playback is intended but held back by the system (a phone call, an unsuitable output). */
    suppression?: PlaybackSuppression;
}
export interface Spec extends TurboModule {
    setupPlayer(options: PlayerOptions): Promise<void>;
    add(tracks: Array<Track>, insertBeforeIndex: Int32): Promise<void>;
    remove(indexes: Array<Int32>): Promise<void>;
    updateMetadataForTrack(id: string, metadata: Track): Promise<void>;
    play(): Promise<void>;
    pause(): Promise<void>;
    stop(): Promise<void>;
    reset(): Promise<void>;
    seekTo(position: number): Promise<void>;
    /**
     * Replace the queue, the index and the position in one call, then apply the intent.
     *
     * The replacement for `reset()` + `add()` + `skip(index, position)` (+ `play()`). Loading used to
     * be two or three round trips, so the engine emitted a media-item transition and a progress tick
     * at position 0 before the seek landed, and JS wrote that 0 to stored progress. Here the start
     * position is part of the load: no event can report 0 before the target.
     *
     * `startPositionSec <= 0` means "from the beginning".
     */
    loadQueue(tracks: Array<Track>, startIndex: Int32, startPositionSec: Double, playWhenReady: boolean): Promise<void>;
    /**
     * Arm a pending stop: when playback reaches `positionSec` in the current track, the engine pauses
     * with reason `stop_at`, never auto-advances, and emits {@link Spec.onPlaybackStopAtReached}.
     *
     * Cleared by {@link Spec.clearStopAt} and by ANY seek, skip, load or `loadQueue`.
     */
    setStopAt(positionSec: Double): Promise<void>;
    /** Disarm a pending {@link Spec.setStopAt}. A no-op when nothing is armed. */
    clearStopAt(): Promise<void>;
    setVolume(volume: number): Promise<void>;
    getVolume(): Promise<number>;
    setRate(rate: number): Promise<void>;
    getRate(): Promise<number>;
    getPosition(): Promise<Double>;
    getBufferedPosition(): Promise<number>;
    getDuration(): Promise<Double>;
    getState(): Promise<PlaybackState>;
    getCurrentTrack(): Promise<Int32 | null>;
    getActiveTrack(): Promise<Track | null>;
    getTrack(index: Int32): Promise<Track | null>;
    getQueue(): Promise<Array<Track>>;
    getCurrentIndex(): Promise<number>;
    skipToNext(): Promise<void>;
    skipToPrevious(): Promise<void>;
    skip(index: Int32, initialPosition?: Int32): Promise<void>;
    skipToTrack(index: Int32): Promise<void>;
    removeUpcomingTracks(): Promise<void>;
    load(track: Track): Promise<void>;
    seekBy(offset: number): Promise<void>;
    getProgress(): Promise<Progress>;
    getActiveTrackIndex(): Promise<Int32>;
    setBrowseTreeStyle(contentStyleGrid: number, contentStyleList: number): Promise<void>;
    updateNowPlayingMetadata(metadata: Track): Promise<void>;
    clearNowPlayingMetadata(): Promise<void>;
    setRepeatMode(mode: number): Promise<void>;
    getRepeatMode(): Promise<number>;
    getPlaybackState(): Promise<PlaybackStateSnapshot>;
    getPlaybackRate(): Promise<number>;
    setPlaybackRate(rate: number): Promise<void>;
    getPlayWhenReady(): Promise<boolean>;
    setPlayWhenReady(playWhenReady: boolean): Promise<void>;
    setBrowseTree(tree: Object): Promise<void>;
    updateOptions(options: UpdateOptions): Promise<void>;
    destroy(): Promise<void>;
    setPlaybackStateError(errorCode: number, errorMessage: string): Promise<void>;
    sendSearchResults(searchId: string, results: Array<{
        mediaId: string;
        title: string;
        artist?: string;
        album?: string;
        artwork?: string;
        url?: string;
        duration?: number;
    }>): Promise<void>;
    readonly onPlaybackState: EventEmitter<PlaybackStateSnapshot>;
    readonly onPlaybackProgressUpdated: EventEmitter<Progress>;
    readonly onPlaybackQueueEnded: EventEmitter<PlaybackQueueEndedEvent>;
    readonly onPlaybackError: EventEmitter<PlaybackErrorEvent>;
    readonly onPlaybackActiveTrackChanged: EventEmitter<PlaybackActiveTrackChangedEvent>;
    readonly onPlaybackPlayWhenReadyChanged: EventEmitter<{
        playWhenReady: boolean;
    }>;
    /** Playback reached the position armed by {@link Spec.setStopAt} and the engine paused there. */
    readonly onPlaybackStopAtReached: EventEmitter<{
        position: number;
    }>;
    readonly onRemotePlay: EventEmitter<void>;
    readonly onRemotePause: EventEmitter<void>;
    readonly onRemoteStop: EventEmitter<void>;
    readonly onRemoteNext: EventEmitter<void>;
    readonly onRemotePrevious: EventEmitter<void>;
    readonly onRemoteSkip: EventEmitter<{
        index: Int32;
    }>;
    readonly onRemoteSeek: EventEmitter<{
        position: number;
    }>;
    readonly onRemoteJumpForward: EventEmitter<{
        interval: number;
    }>;
    readonly onRemoteJumpBackward: EventEmitter<{
        interval: number;
    }>;
    readonly onRemoteBookmark: EventEmitter<void>;
    readonly onRemotePlayId: EventEmitter<{
        id: string;
    }>;
    readonly onRemoteBrowse: EventEmitter<{
        mediaId: string;
    }>;
    /**
     * Triggered when user issues voice commands like "Play [song name]" or "Play [artist name]"
     * through Android Auto, Android Automotive OS, or Google Assistant on phone.
     *
     * To enable this functionality, add the following intent filter to your AndroidManifest.xml:
     *
     * ```xml
     * <activity android:name=".MainActivity" android:exported="true">
     *   <intent-filter>
     *     <action android:name="android.media.action.MEDIA_PLAY_FROM_SEARCH"/>
     *     <category android:name="android.intent.category.DEFAULT"/>
     *   </intent-filter>
     * </activity>
     * ```
     *
     * The query parameter contains the user's voice command text.
     * Examples: "Play Big Book", "Play Daily Reflections", "Play some music"
     */
    readonly onRemotePlayFromSearch: EventEmitter<{
        query: string;
        extras: Object;
    }>;
    /**
     * Triggered when Google Assistant prepares media for playback (for reduced latency).
     * This is called before actual playback starts, allowing the app to prepare media in advance.
     * PREPARE always means prepare without playing.
     */
    readonly onRemotePrepareId: EventEmitter<{
        id: string;
    }>;
    /**
     * Triggered when Google Assistant prepares media from search query (for reduced latency).
     * This is called before actual playback starts, allowing the app to prepare media in advance.
     * PREPARE always means prepare without playing.
     */
    readonly onRemotePrepareFromSearch: EventEmitter<{
        query: string;
        extras: Object;
    }>;
    /**
     * Triggered when Android Auto / Android Automotive OS requests browsable search results.
     * This is called when the user searches for content in Android Auto's search interface.
     * The app should return search results (media IDs) via sendSearchResults().
     */
    readonly onRemoteSearch: EventEmitter<{
        searchId: string;
        query: string;
        artistName?: string;
        albumName?: string;
    }>;
    readonly onRemoteDuck: EventEmitter<{
        reason: 'began' | 'ended';
    }>;
}
declare const _default: Spec;
export default _default;
