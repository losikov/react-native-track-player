import type { Spec } from '../js/NativeRTNTrackPlayer';
declare const _default: Spec;
export default _default;
export * from './hooks';
export type { PlayerOptions, Track, Progress, PlaybackState, IOSCategory, IOSCategoryMode, IOSCategoryOptions, AndroidAudioContentType, AppKilledPlaybackBehavior, FeedbackOptions, AndroidOptions, UpdateOptions, AndroidAutoBrowseTree, MediaItem, PlaybackQueueEndedEvent, PlaybackErrorEvent, PlaybackActiveTrackChangedEvent, PlaybackStateSnapshot, PlaybackTransport, PlaybackReadiness, PlaybackTransportReason, PlaybackSuppression, Spec, } from '../js/NativeRTNTrackPlayer';
export { AndroidAutoContentStyle, MediaItemPlayable, MediaType, RepeatMode, Capability, TrackType, PitchAlgorithm, State, } from '../js/NativeRTNTrackPlayer';
export { default as TrackPlayer } from '../js/NativeRTNTrackPlayer';
export declare function registerPlaybackService(factory: () => () => void): void;
