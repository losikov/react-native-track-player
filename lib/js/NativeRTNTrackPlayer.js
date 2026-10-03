import { TurboModuleRegistry } from 'react-native';
export var RatingType;
(function (RatingType) {
    RatingType["Heart"] = "heart";
    RatingType["ThumbsUpDown"] = "thumbsUpDown";
    RatingType["ThreeStars"] = "3stars";
    RatingType["FourStars"] = "4stars";
    RatingType["FiveStars"] = "5stars";
    RatingType["Percentage"] = "percentage";
})(RatingType || (RatingType = {}));
export var TrackType;
(function (TrackType) {
    TrackType["Default"] = "default";
    TrackType["Dash"] = "dash";
    TrackType["HLS"] = "hls";
    TrackType["SmoothStreaming"] = "smoothstreaming";
})(TrackType || (TrackType = {}));
export var PitchAlgorithm;
(function (PitchAlgorithm) {
    PitchAlgorithm["Linear"] = "linear";
    PitchAlgorithm["Music"] = "music";
    PitchAlgorithm["Voice"] = "voice";
})(PitchAlgorithm || (PitchAlgorithm = {}));
// State enum for backward compatibility
export const State = {
    None: 'none',
    Ready: 'ready',
    Playing: 'playing',
    Paused: 'paused',
    Stopped: 'stopped',
    Loading: 'loading',
    Buffering: 'buffering',
    Error: 'error',
    Ended: 'ended',
};
// Android Auto Content Style
export var AndroidAutoContentStyle;
(function (AndroidAutoContentStyle) {
    AndroidAutoContentStyle[AndroidAutoContentStyle["List"] = 1] = "List";
    AndroidAutoContentStyle[AndroidAutoContentStyle["Grid"] = 2] = "Grid";
    AndroidAutoContentStyle[AndroidAutoContentStyle["CategoryList"] = 3] = "CategoryList";
    AndroidAutoContentStyle[AndroidAutoContentStyle["CategoryGrid"] = 4] = "CategoryGrid";
})(AndroidAutoContentStyle || (AndroidAutoContentStyle = {}));
// Media Item Playable (for Android Auto browse tree)
export var MediaItemPlayable;
(function (MediaItemPlayable) {
    MediaItemPlayable["MediaPlayable"] = "0";
    MediaItemPlayable["MediaBrowsable"] = "1";
})(MediaItemPlayable || (MediaItemPlayable = {}));
// Media Type enum for Google Assistant recommendations
export var MediaType;
(function (MediaType) {
    MediaType["Audiobook"] = "AUDIO_BOOK";
    MediaType["Podcast"] = "PODCAST_EPISODE";
    MediaType["Music"] = "MUSIC";
})(MediaType || (MediaType = {}));
// Repeat Mode
export var RepeatMode;
(function (RepeatMode) {
    RepeatMode[RepeatMode["Off"] = 0] = "Off";
    RepeatMode[RepeatMode["Track"] = 1] = "Track";
    RepeatMode[RepeatMode["Queue"] = 2] = "Queue";
})(RepeatMode || (RepeatMode = {}));
// Capability constants (string values - match Capability.ts)
export const Capability = {
    Play: 'play',
    PlayFromId: 'playFromId',
    PlayFromSearch: 'playFromSearch',
    Pause: 'pause',
    Stop: 'stop',
    SeekTo: 'seekTo',
    Skip: 'skip',
    SkipToNext: 'next',
    SkipToPrevious: 'previous',
    JumpForward: 'jumpForward',
    JumpBackward: 'jumpBackward',
    SetRating: 'setRating',
    Like: 'like',
    Dislike: 'dislike',
    Bookmark: 'bookmark',
    /**
     * A remote playback-speed control: `changePlaybackRateCommand` on iOS (lock screen, Control
     * Center, Watch; CarPlay's rate button drives the same engine path), a speed button in the media
     * session's media button preferences on Android (notification, Android Auto). Needs
     * {@link UpdateOptions.playbackRates}.
     */
    ChangePlaybackRate: 'changePlaybackRate',
};
export default TurboModuleRegistry.get("RTNTrackPlayer");
