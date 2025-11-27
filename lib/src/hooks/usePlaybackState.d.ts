import type { PlaybackState } from '../../js/NativeRTNTrackPlayer';
/**
 * Get current playback state and subsequent updates.
 *
 * Note: While it is fetching the initial state from the native module, the
 * returned state will be `undefined`.
 *
 * Uses the New Architecture event emitter pattern to receive state updates.
 * */
export declare const usePlaybackState: () => PlaybackState | undefined;
