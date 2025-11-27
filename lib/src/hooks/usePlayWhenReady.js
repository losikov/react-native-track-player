import { useState, useEffect } from 'react';
import NativeTrackPlayer from '../../js/NativeRTNTrackPlayer';
/**
 * Get current playWhenReady state and subsequent updates.
 *
 * Uses the New Architecture event emitter pattern to receive state updates.
 */
export const usePlayWhenReady = () => {
    const [playWhenReady, setPlayWhenReady] = useState(undefined);
    useEffect(() => {
        let mounted = true;
        // Get initial state
        NativeTrackPlayer?.getPlayWhenReady()
            .then((initialState) => {
            if (!mounted)
                return;
            setPlayWhenReady(initialState);
        })
            .catch(() => {
            /** getPlayWhenReady only throw while you haven't yet setup, ignore failure. */
        });
        // Subscribe to playWhenReady changes using New Architecture event emitter
        const subscription = NativeTrackPlayer?.onPlaybackPlayWhenReadyChanged?.((event) => {
            if (mounted) {
                setPlayWhenReady(event.playWhenReady);
            }
        });
        return () => {
            mounted = false;
            subscription?.remove();
        };
    }, []);
    return playWhenReady;
};
