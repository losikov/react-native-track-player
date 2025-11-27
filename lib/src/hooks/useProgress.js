import { useEffect, useState } from 'react';
import NativeTrackPlayer from '../../js/NativeRTNTrackPlayer';
const INITIAL_STATE = {
    position: 0,
    duration: 0,
    buffered: 0,
};
/**
 * Get track progress updates from the player.
 * Uses New Architecture event emitter pattern to receive progress updates.
 * Also polls as a fallback with the given interval (in milliseconds).
 * @param updateInterval - ms interval for polling fallback
 */
export function useProgress(updateInterval = 1000) {
    const [state, setState] = useState(INITIAL_STATE);
    useEffect(() => {
        let mounted = true;
        // Get initial progress
        const getInitialProgress = async () => {
            try {
                const progress = await NativeTrackPlayer?.getProgress();
                if (!mounted || !progress)
                    return;
                setState(progress);
            }
            catch {
                // getProgress only throws while you haven't yet setup, ignore failure.
            }
        };
        getInitialProgress();
        // Subscribe to progress updates using New Architecture event emitter
        const progressSubscription = NativeTrackPlayer?.onPlaybackProgressUpdated?.((progress) => {
            if (mounted) {
                setState(progress);
            }
        });
        // Reset progress when track changes
        const trackChangedSubscription = NativeTrackPlayer?.onPlaybackActiveTrackChanged?.(() => {
            if (mounted) {
                setState(INITIAL_STATE);
            }
        });
        // Polling fallback in case events are missed
        const poll = async () => {
            if (!mounted)
                return;
            try {
                const progress = await NativeTrackPlayer?.getProgress();
                if (!mounted || !progress)
                    return;
                setState(progress);
            }
            catch {
                // Ignore errors
            }
            if (!mounted)
                return;
            await new Promise((resolve) => setTimeout(resolve, updateInterval));
            if (mounted)
                poll();
        };
        poll();
        return () => {
            mounted = false;
            progressSubscription?.remove();
            trackChangedSubscription?.remove();
        };
    }, [updateInterval]);
    return state;
}
