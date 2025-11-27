import { useState, useEffect } from 'react';
import NativeTrackPlayer from '../../js/NativeRTNTrackPlayer';
export const useActiveTrack = () => {
    const [track, setTrack] = useState();
    // Sets the initial index (if still undefined)
    useEffect(() => {
        let unmounted = false;
        NativeTrackPlayer?.getActiveTrack()
            .then((initialTrack) => {
            if (unmounted)
                return;
            setTrack((track) => track ?? initialTrack ?? undefined);
        })
            .catch(() => {
            // throws when you haven't yet setup, which is fine because it also
            // means there's no active track
        });
        return () => {
            unmounted = true;
        };
    }, []);
    // Listen for active track changes using New Architecture event emitter
    useEffect(() => {
        const subscription = NativeTrackPlayer?.onPlaybackActiveTrackChanged?.((event) => {
            setTrack(event.track ?? undefined);
        });
        return () => {
            subscription?.remove();
        };
    }, []);
    return track;
};
