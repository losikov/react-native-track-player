# NOTICE

Source in this directory (`com.doublesymmetry.kotlinaudio`) was vendored from
`github.com/losikov/KotlinAudio` (a fork of `doublesymmetry/KotlinAudio`) at commit
`c98a0dd28492489b3ad52bf431312e79e2f096ab` on 2026-09-10.

Licensed under the Apache License, Version 2.0 (see `LICENSE` in this directory).

From this point on, this source is modified in place within this repository
(`react-native-track-player`) and is no longer synced from the upstream KotlinAudio repo.

## AndroidX Media3 migration (2026-09-10)

The vendored engine was moved from `com.google.android.exoplayer:exoplayer:2.19.1` to
`androidx.media3:*:1.11.0`. `notification/NotificationManager.kt` and `event/NotificationEventHolder.kt`
were deleted (media3's `DefaultMediaNotificationProvider` and `MediaSessionService` replace them);
`players/InterceptingPlayer.kt`, `players/components/MediaFactory.kt` and `models/PlayerSnapshot.kt`
are new and were written for this repository.

## Third-party code taken by hand

| File | Taken from | Licence | Changes |
|---|---|---|---|
| `../trackplayer/HeadlessJsMediaService.kt` | `lovegaoshi/react-native-track-player`, branch `APM`, `android/src/main/java/com/doublesymmetry/trackplayer/HeadlessJsMediaService.kt` | Apache-2.0 (that repository is a fork of `doublesymmetry/react-native-track-player`); the file itself carries Meta's MIT header from React Native's `HeadlessJsTaskService`, which is kept | Base class changed from `MediaBrowserServiceCompat` to media3 `MediaLibraryService` (as in APM). Our changes on top of APM: the wake lock is acquired again (APM commented `acquireWakeLockNow` out) and released only when held; `reactContext` returns null instead of `checkNotNull`-ing a `ReactHost` that does not exist yet, and is wrapped in a try/catch; `ensureReactContext(onReady)` was added so `MusicService` can start the runtime for an Android Auto browse that has no headless *task* to run; `activeTasks` is private. |

Nothing else was taken from that branch. In particular its FFT, ffmpeg, Compose, crossfade,
equalizer, loudness, fade and `TeeListener` code is not present here, and its `MusicService`,
`MusicModule`, `AudioPlayer`, `QueuedAudioPlayer`, `FocusManager`, `MediaFactory` and
`PlayerEventHolder` were read for reference only — the equivalents here are this repository's own,
because the app is on a codegen TurboModule rather than the legacy bridge and its Android Auto
library callback is complete where APM's is a documented stub.
