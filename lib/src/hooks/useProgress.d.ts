import type { Progress } from '../../js/NativeRTNTrackPlayer';
/**
 * Get track progress updates from the player.
 * Uses New Architecture event emitter pattern to receive progress updates.
 * Also polls as a fallback with the given interval (in milliseconds).
 * @param updateInterval - ms interval for polling fallback
 */
export declare function useProgress(updateInterval?: number): Progress;
