import { Platform } from 'react-native';

// Import the TurboModule directly
import NativeTrackPlayer from '../js/NativeRTNTrackPlayer';

// Export the TurboModule as the default export
// This includes all methods AND all event emitters
export default NativeTrackPlayer;

// Re-export all hooks
export * from './hooks';
export type {
  PlayerOptions,
  Track,
  Progress,
  PlaybackState,
  IOSCategory,
  IOSCategoryMode,
  IOSCategoryOptions,
  AndroidAudioContentType,
  AppKilledPlaybackBehavior,
  FeedbackOptions,
  AndroidOptions,
  UpdateOptions,
  AndroidAutoBrowseTree,
  MediaItem,
  // Event types
  PlaybackQueueEndedEvent,
  PlaybackErrorEvent,
  PlaybackActiveTrackChangedEvent,
} from '../js/NativeRTNTrackPlayer';

export {
  AndroidAutoContentStyle,
  MediaItemPlayable,
  MediaType,
  RepeatMode,
  Capability,
  TrackType,
  PitchAlgorithm,
  State,
} from '../js/NativeRTNTrackPlayer';

// Re-export setPlaybackStateError for error reporting
export { default as TrackPlayer } from '../js/NativeRTNTrackPlayer';

// Service registration for background playback
// In New Architecture, we don't use headless tasks - just call the factory directly
export function registerPlaybackService(factory: () => () => void) {
  if (Platform.OS === 'android') {
    // For Android with New Architecture, call factory in main context
    // The factory should return a function that sets up event handlers
    const serviceFunction = factory();
    if (typeof serviceFunction === 'function') {
      // Call it immediately to set up handlers
      serviceFunction();
    }
  } else {
    // iOS doesn't use service registration
    // The factory is called immediately to set up event handlers
    const serviceFunction = factory();
    if (typeof serviceFunction === 'function') {
      serviceFunction();
    }
  }
}
