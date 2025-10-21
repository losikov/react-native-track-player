import { useEffect, useState } from 'react';

import NativeTrackPlayer from '../../js/NativeRTNTrackPlayer';
import type {
  PlaybackState,
  PlaybackErrorEvent,
} from '../../js/NativeRTNTrackPlayer';

/**
 * Get current playback state and subsequent updates.
 *
 * Note: While it is fetching the initial state from the native module, the
 * returned state will be `undefined`.
 *
 * Uses the New Architecture event emitter pattern to receive state updates.
 * */
export const usePlaybackState = (): PlaybackState | undefined => {
  const [playbackState, setPlaybackState] = useState<PlaybackState | undefined>(
    undefined
  );

  useEffect(() => {
    let mounted = true;

    // Get initial state
    NativeTrackPlayer?.getState()
      .then((state: PlaybackState) => {
        if (!mounted) return;
        setPlaybackState(state);
      })
      .catch(() => {
        /** getState only throw while you haven't yet setup, ignore failure. */
      });

    // Subscribe to state changes using New Architecture event emitter
    const subscription = NativeTrackPlayer?.onPlaybackState?.(
      (data: { state: PlaybackState; error?: PlaybackErrorEvent }) => {
        if (mounted) {
          setPlaybackState(data.state);
        }
      }
    );

    return () => {
      mounted = false;
      subscription?.remove();
    };
  }, []);

  return playbackState;
};
