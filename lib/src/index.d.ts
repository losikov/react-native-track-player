import NativeTrackPlayer from '../js/NativeRTNTrackPlayer';
export default NativeTrackPlayer;
export * from './hooks';
export type { PlayerOptions, Track, Progress, PlaybackState, IOSCategory, IOSCategoryMode, IOSCategoryOptions, AndroidAudioContentType, AppKilledPlaybackBehavior, FeedbackOptions, AndroidOptions, UpdateOptions, AndroidAutoBrowseTree, MediaItem, PlaybackQueueEndedEvent, PlaybackErrorEvent, PlaybackActiveTrackChangedEvent, } from '../js/NativeRTNTrackPlayer';
export { AndroidAutoContentStyle, MediaItemPlayable, MediaType, RepeatMode, Capability, TrackType, PitchAlgorithm, State, } from '../js/NativeRTNTrackPlayer';
export { default as TrackPlayer } from '../js/NativeRTNTrackPlayer';
export declare function registerPlaybackService(factory: () => () => void): void;
