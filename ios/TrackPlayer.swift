//
//  RTNTrackPlayerSwift.swift
//  RNTrackPlayer
//
//  Created for TurboModules implementation
//

import Foundation
import MediaPlayer
import SwiftAudioEx


@objc
public class TrackPlayer: NSObject, AudioSessionControllerDelegate {

    // MARK: - Attributes

    private var hasInitialized = false
    /// The engine. `TrackPlayer` is one commander/subscriber of it now, not the owner of the queue:
    /// native code in the app can drive and observe playback through `PlayerCore.shared` without a
    /// React hop. Every bridged method and event below behaves exactly as it did before.
    private let core = PlayerCore.shared
    private var player: QueuedAudioPlayer { core.player }
    private var observerTokens: [NSObjectProtocol] = []
    private let audioSessionController = AudioSessionController.shared
    private var shouldEmitProgressEvent: Bool = false
    private var shouldResumePlaybackAfterInterruptionEnds: Bool = false
    private var forwardJumpInterval: NSNumber? = nil;
    private var backwardJumpInterval: NSNumber? = nil;
    private var interruptionStartTime: Int64? = nil
    private var sessionCategory: AVAudioSession.Category = .playback
    private var sessionCategoryMode: AVAudioSession.Mode = .default
    private var sessionCategoryPolicy: AVAudioSession.RouteSharingPolicy = .default
    private var sessionCategoryOptions: AVAudioSession.CategoryOptions = []
    
    // Event emitter for TurboModule events
    public weak var eventEmitter: RTNTrackPlayerEventEmitter?

    // MARK: - Lifecycle Methods

    public override init() {
        super.init()
        audioSessionController.delegate = self
        player.playWhenReady = false;
        player.event.receiveChapterMetadata.addListener(self, handleAudioPlayerChapterMetadataReceived)
        player.event.receiveTimedMetadata.addListener(self, handleAudioPlayerTimedMetadataReceived)
        player.event.receiveCommonMetadata.addListener(self, handleAudioPlayerCommonMetadataReceived)
        player.event.stateChange.addListener(self, handleAudioPlayerStateChange)
        player.event.fail.addListener(self, handleAudioPlayerFailed)
        player.event.currentItem.addListener(self, handleAudioPlayerCurrentItemChange)
        player.event.secondElapse.addListener(self, handleAudioPlayerSecondElapse)
        player.event.playWhenReadyChange.addListener(self, handlePlayWhenReadyChange)

        // A position jump no interval tick will report — a seek (including a seek while paused) and
        // `loadQueue`. One progress event each, so JS's stored progress is never left holding the
        // pre-seek position. This is the iOS half of the engine-level fix Android got in step 2.
        observerTokens.append(
            NotificationCenter.default.addObserver(
                forName: PlayerCore.progressDidJump,
                object: core,
                queue: .main
            ) { [weak self] note in
                guard let self = self, self.shouldEmitProgressEvent else { return }
                self.eventEmitter?.emitPlaybackProgressUpdated([
                    "position": note.userInfo?["position"] as? Double ?? 0,
                    "duration": note.userInfo?["duration"] as? Double ?? 0,
                    "buffered": note.userInfo?["buffered"] as? Double ?? 0,
                    "track": self.player.currentIndex,
                ])
            }
        )

        observerTokens.append(
            NotificationCenter.default.addObserver(
                forName: PlayerCore.stopAtReached,
                object: core,
                queue: .main
            ) { [weak self] note in
                // The ObjC selector `emitPlaybackStopAtReached:` imports into Swift split at the
                // preposition, exactly like `emitPlaybackPlayWhenReadyChanged:` above it.
                self?.eventEmitter?.emitPlaybackStop(atReached: [
                    "position": note.userInfo?[PlayerCore.positionKey] as? Double ?? 0,
                ])
            }
        )
    }
    
    @objc
    public func setEventEmitter(_ eventEmitter: RTNTrackPlayerEventEmitter) {
        self.eventEmitter = eventEmitter
    }

    deinit {
        observerTokens.forEach { NotificationCenter.default.removeObserver($0) }
        observerTokens.removeAll()
        reset(resolve: { _ in }, reject: { _, _, _  in })
    }

    // MARK: - RCTEventEmitter

    public static func requiresMainQueueSetup() -> Bool {
        return true;
    }

    @objc(constantsToExport)
    public func constantsToExport() -> [AnyHashable: Any] {
        return [
            "STATE_NONE": State.none.rawValue,
            "STATE_READY": State.ready.rawValue,
            "STATE_PLAYING": State.playing.rawValue,
            "STATE_PAUSED": State.paused.rawValue,
            "STATE_STOPPED": State.stopped.rawValue,
            "STATE_BUFFERING": State.buffering.rawValue,
            "STATE_LOADING": State.loading.rawValue,
            "STATE_ERROR": State.error.rawValue,
            
            // MusicControl state constants
            "MEDIA_STATE_PLAYING": "STATE_PLAYING",
            "MEDIA_STATE_PAUSED": "STATE_PAUSED",
            "MEDIA_STATE_STOPPED": "STATE_STOPPED",
            "MEDIA_STATE_BUFFERING": "STATE_BUFFERING",
            "MEDIA_STATE_ERROR": "STATE_ERROR",

            "TRACK_PLAYBACK_ENDED_REASON_END": PlaybackEndedReason.playedUntilEnd.rawValue,
            "TRACK_PLAYBACK_ENDED_REASON_JUMPED": PlaybackEndedReason.jumpedToIndex.rawValue,
            "TRACK_PLAYBACK_ENDED_REASON_NEXT": PlaybackEndedReason.skippedToNext.rawValue,
            "TRACK_PLAYBACK_ENDED_REASON_PREVIOUS": PlaybackEndedReason.skippedToPrevious.rawValue,
            "TRACK_PLAYBACK_ENDED_REASON_STOPPED": PlaybackEndedReason.playerStopped.rawValue,

            "PITCH_ALGORITHM_LINEAR": PitchAlgorithm.linear.rawValue,
            "PITCH_ALGORITHM_MUSIC": PitchAlgorithm.music.rawValue,
            "PITCH_ALGORITHM_VOICE": PitchAlgorithm.voice.rawValue,

            "CAPABILITY_PLAY": Capability.play.rawValue,
            "CAPABILITY_PLAY_FROM_ID": "NOOP",
            "CAPABILITY_PLAY_FROM_SEARCH": "NOOP",
            "CAPABILITY_PAUSE": Capability.pause.rawValue,
            "CAPABILITY_STOP": Capability.stop.rawValue,
            "CAPABILITY_SEEK_TO": Capability.seek.rawValue,
            "CAPABILITY_SKIP": "NOOP",
            "CAPABILITY_SKIP_TO_NEXT": Capability.next.rawValue,
            "CAPABILITY_SKIP_TO_PREVIOUS": Capability.previous.rawValue,
            "CAPABILITY_SET_RATING": "NOOP",
            "CAPABILITY_JUMP_FORWARD": Capability.jumpForward.rawValue,
            "CAPABILITY_JUMP_BACKWARD": Capability.jumpBackward.rawValue,
            "CAPABILITY_LIKE": Capability.like.rawValue,
            "CAPABILITY_DISLIKE": Capability.dislike.rawValue,
            "CAPABILITY_BOOKMARK": Capability.bookmark.rawValue,

            "REPEAT_OFF": RepeatMode.off.rawValue,
            "REPEAT_TRACK": RepeatMode.track.rawValue,
            "REPEAT_QUEUE": RepeatMode.queue.rawValue,
        ]
    }

    // Note: TurboModules don't use supportedEvents() - events are handled differently
    // The old RCTEventEmitter.supportedEvents() is not needed for TurboModules


    // MARK: - AudioSessionControllerDelegate

    public func handleInterruption(type: InterruptionType) {
        switch type {
        case .began:
            // The engine records this as a *suppression*, not a pause: the intent is untouched and
            // `transport` stays `playing`. What the app does about it is `onRemoteDuck` in JS, which
            // is unchanged — it still pauses and resumes.
            core.noteInterruption(began: true)
            eventEmitter?.emitRemoteDuck([
                "reason": "began"
            ])
            
        case .ended:
            core.noteInterruption(began: false)
            eventEmitter?.emitRemoteDuck([
                "reason": "ended"
            ])
        }
    }

    // MARK: - Bridged Methods

    private func rejectWhenNotInitialized(reject: RCTPromiseRejectBlock) -> Bool {
        let rejected = !hasInitialized;
        if (rejected) {
            reject("player_not_initialized", "The player is not initialized. Call setupPlayer first.", nil)
        }
        return rejected;
    }

    private func rejectWhenTrackIndexOutOfBounds(
        index: Int,
        min: Int? = nil,
        max : Int? = nil,
        message : String? = "The track index is out of bounds",
        reject: RCTPromiseRejectBlock
    ) -> Bool {
        let rejected = index < (min ?? 0) || index > (max ?? player.items.count - 1);
        if (rejected) {
            reject("index_out_of_bounds", message, nil)
        }
        return rejected
    }

    @objc(setupPlayer:resolver:rejecter:)
    public func setupPlayer(config: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if hasInitialized {
            reject("player_already_initialized", "The player has already been initialized via setupPlayer.", nil)
            return
        }

        // configure buffer size
        if let bufferDuration = config["minBuffer"] as? TimeInterval {
            player.bufferDuration = bufferDuration
        }

        if let autoHandleInterruptions = config["autoHandleInterruptions"] as? Bool {
            self.shouldResumePlaybackAfterInterruptionEnds = autoHandleInterruptions
        }

        // configure wether player waits to play (deprecated)
        if let waitForBuffer = config["waitForBuffer"] as? Bool {
            player.automaticallyWaitsToMinimizeStalling = waitForBuffer
        }

        // configure wether control center metdata should auto update
        player.automaticallyUpdateNowPlayingInfo = config["autoUpdateMetadata"] as? Bool ?? true

        updateCategory(config: config)

        configureAudioSession()

        // setup event listeners
        //
        // Step 4: pure transport — play, pause, stop, seek, jump ± — is applied on `PlayerCore`
        // *here*, synchronously, and only then announced to JS, which records it (analytics, stored
        // progress) without re-issuing it. See `TransportPolicy`. The player is therefore
        // controllable from the lock screen, Control Center, a headphone remote and CarPlay whether
        // or not the React context is alive. Next and previous are never applied here: JS owns what
        // they mean (chapter navigation, the daily-date rule, the 20-second restart).
        player.remoteCommandController.handleChangePlaybackPositionCommand = { [weak self] event in
            if let event = event as? MPChangePlaybackPositionCommandEvent {
                self?.applyNatively("seek to \(event.positionTime)") { core in
                    core.seek(to: event.positionTime, reason: .remote)
                }
                self?.eventEmitter?.emitRemoteSeek(["position": event.positionTime])
                return MPRemoteCommandHandlerStatus.success
            }

            return MPRemoteCommandHandlerStatus.commandFailed
        }

        player.remoteCommandController.handleNextTrackCommand = { [weak self] _ in
            NSLog("RNTP-Transport: routing next to JS (never applied natively)")
            self?.eventEmitter?.emitRemoteNext()
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handlePauseCommand = { [weak self] _ in
            self?.applyNatively("pause") { core in core.pause(reason: .remote) }
            self?.eventEmitter?.emitRemotePause()
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handlePlayCommand = { [weak self] _ in
            self?.applyNatively("play") { core in core.play(reason: .remote) }
            self?.eventEmitter?.emitRemotePlay()
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handlePreviousTrackCommand = { [weak self] _ in
            NSLog("RNTP-Transport: routing previous to JS (never applied natively)")
            self?.eventEmitter?.emitRemotePrevious()
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleSkipBackwardCommand = { [weak self] event in
            if let command = event.command as? MPSkipIntervalCommand,
               let interval = command.preferredIntervals.first {
                self?.applyNatively("jump back \(interval.doubleValue)s") { core in
                    core.seek(by: -interval.doubleValue, reason: .remote)
                }
                self?.eventEmitter?.emitRemoteJumpBackward(["interval": interval])
                return MPRemoteCommandHandlerStatus.success
            }

            return MPRemoteCommandHandlerStatus.commandFailed
        }

        player.remoteCommandController.handleSkipForwardCommand = { [weak self] event in
            if let command = event.command as? MPSkipIntervalCommand,
               let interval = command.preferredIntervals.first {
                self?.applyNatively("jump forward \(interval.doubleValue)s") { core in
                    core.seek(by: interval.doubleValue, reason: .remote)
                }
                self?.eventEmitter?.emitRemoteJumpForward(["interval": interval])
                return MPRemoteCommandHandlerStatus.success
            }

            return MPRemoteCommandHandlerStatus.commandFailed
        }

        player.remoteCommandController.handleStopCommand = { [weak self] _ in
            self?.applyNatively("stop") { core in core.stop(reason: .remote) }
            self?.eventEmitter?.emitRemoteStop()
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleTogglePlayPauseCommand = { [weak self] _ in
            guard let self = self else { return MPRemoteCommandHandlerStatus.success }
            // Which way to toggle is decided on the *intent*, not on `playerState`, because the
            // engine is what acts on it now: a track still loading has `playerState == .loading`
            // with `playWhenReady == true`, and a toggle then has to pause. (This is also the
            // decision media3's session makes on Android.)
            if self.core.snapshot.playWhenReady {
                self.applyNatively("toggle -> pause") { core in core.pause(reason: .remote) }
                self.eventEmitter?.emitRemotePause()
            } else {
                self.applyNatively("toggle -> play") { core in core.play(reason: .remote) }
                self.eventEmitter?.emitRemotePlay()
            }

            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleLikeCommand = { [weak self] _ in
            // Note: Like/Dislike are not in TurboModule spec, so we skip them
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleDislikeCommand = { [weak self] _ in
            // Note: Like/Dislike are not in TurboModule spec, so we skip them
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleBookmarkCommand = { [weak self] _ in
            self?.eventEmitter?.emitRemoteBookmark()
            return MPRemoteCommandHandlerStatus.success
        }

        hasInitialized = true
        resolve(NSNull())
    }

    /// Run `command` on the engine when the policy says so, and say so in the log.
    ///
    /// The position is logged either side because that is the only way to see, from a device, that a
    /// jump moved exactly one interval and did not compound with a JS-issued one.
    private func applyNatively(_ label: String, _ command: (PlayerCore) -> Void) {
        guard core.transportPolicy == .applyNativelyAndNotify else {
            NSLog("RNTP-Transport: routing \(label) to JS (policy: routeToListeners)")
            return
        }
        let before = core.position
        command(core)
        NSLog("RNTP-Transport: applied \(label) natively: \(before) -> \(core.position)")
    }

    private func updateCategory(config: [String: Any]) {
        // configure audio session - category, options & mode
        if
            let sessionCategoryStr = config["iosCategory"] as? String,
            let mappedCategory = SessionCategory(rawValue: sessionCategoryStr) {
            sessionCategory = mappedCategory.mapConfigToAVAudioSessionCategory()
        }

        if
            let sessionCategoryModeStr = config["iosCategoryMode"] as? String,
            let mappedCategoryMode = SessionCategoryMode(rawValue: sessionCategoryModeStr) {
            sessionCategoryMode = mappedCategoryMode.mapConfigToAVAudioSessionCategoryMode()
        }

        if
            let sessionCategoryPolicyStr = config["iosCategoryPolicy"] as? String,
            let mappedCategoryPolicy = SessionCategoryPolicy(rawValue: sessionCategoryPolicyStr) {
            sessionCategoryPolicy = mappedCategoryPolicy.mapConfigToAVAudioSessionCategoryPolicy()
        }

        let sessionCategoryOptsStr = config["iosCategoryOptions"] as? [String]
        let mappedCategoryOpts = sessionCategoryOptsStr?.compactMap { SessionCategoryOptions(rawValue: $0)?.mapConfigToAVAudioSessionCategoryOptions() } ?? []
        sessionCategoryOptions = AVAudioSession.CategoryOptions(mappedCategoryOpts)
    }

    private func configureAudioSession() {

        // deactivate the session when there is no current item to be played
        if (player.currentItem == nil) {
            try? audioSessionController.deactivateSession()
            return
        }
        
        // activate the audio session when there is an item to be played
        // and the player has been configured to start when it is ready loading:
        if (player.playWhenReady) {
            try? audioSessionController.activateSession()
            if #available(iOS 11.0, *) {
                try? AVAudioSession.sharedInstance().setCategory(sessionCategory, mode: sessionCategoryMode, policy: sessionCategoryPolicy, options: sessionCategoryOptions)
            } else {
                try? AVAudioSession.sharedInstance().setCategory(sessionCategory, mode: sessionCategoryMode, options: sessionCategoryOptions)
            }
        }
    }

    @objc(isServiceRunning:rejecter:)
    public func isServiceRunning(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        // TODO That is probably always true
        resolve(player != nil)
    }

    @objc(updateOptions:resolver:rejecter:)
    public func update(options: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        var capabilitiesStr = options["capabilities"] as? [String] ?? []
        if (capabilitiesStr.contains("play") && capabilitiesStr.contains("pause")) {
            capabilitiesStr.append("togglePlayPause");
        }

        forwardJumpInterval = options["forwardJumpInterval"] as? NSNumber ?? forwardJumpInterval
        backwardJumpInterval = options["backwardJumpInterval"] as? NSNumber ?? backwardJumpInterval

        player.remoteCommands = capabilitiesStr
            .compactMap { Capability(rawValue: $0) }
            .map { capability in
                capability.mapToPlayerCommand(
                    forwardJumpInterval: forwardJumpInterval,
                    backwardJumpInterval: backwardJumpInterval,
                    likeOptions: options["likeOptions"] as? [String: Any],
                    dislikeOptions: options["dislikeOptions"] as? [String: Any],
                    bookmarkOptions: options["bookmarkOptions"] as? [String: Any]
                )
            }

        configureProgressUpdateEvent(
            interval: ((options["progressUpdateEventInterval"] as? NSNumber) ?? 0).doubleValue
        )

        updateCategory(config: options)

        configureAudioSession()

        resolve(NSNull())
    }

    private func configureProgressUpdateEvent(interval: Double) {
        shouldEmitProgressEvent = interval > 0
        self.player.timeEventFrequency = shouldEmitProgressEvent
            ? .custom(time: CMTime(seconds: interval, preferredTimescale: 1000))
            : .everySecond
    }

    @objc(add:before:resolver:rejecter:)
    public func add(
        trackDicts: [[String: Any]],
        before trackIndex: NSNumber,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        // -1 means no index was passed and therefore should be inserted at the end.
        let index = trackIndex.intValue == -1 ? player.items.count : trackIndex.intValue;
        if (rejectWhenNotInitialized(reject: reject)) { return }
        if (rejectWhenTrackIndexOutOfBounds(
            index: index,
            max: player.items.count,
            reject: reject
        )) { return }

        var tracks = [Track]()
        for trackDict in trackDicts {
            guard let track = Track(dictionary: trackDict) else {
                reject("invalid_track_object", "Track is missing a required key", nil)
                return
            }

            tracks.append(track)
        }

        try? player.add(
            items: tracks,
            at: index
        )
        resolve(index)
    }

    @objc(load:resolver:rejecter:)
    public func load(
        trackDict: [String: Any],
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        guard let track = Track(dictionary: trackDict) else {
            reject("invalid_track_object", "Track is missing a required key", nil)
            return
        }

        player.load(item: track)
        resolve(player.currentIndex)
    }

    @objc(remove:resolver:rejecter:)
    public func remove(tracks indexes: [Int], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        for index in indexes {
            if (rejectWhenTrackIndexOutOfBounds(index: index, message: "One or more of the indexes were out of bounds.", reject: reject)) {
                return
            }
        }

        // Sort the indexes in descending order so we can safely remove them one by one
        // without having the next index possibly newly pointing to another item than intended:
        for index in indexes.sorted().reversed() {
            try? player.removeItem(at: index)
        }

        resolve(NSNull())
    }

    @objc(move:toIndex:resolver:rejecter:)
    public func move(
        fromIndex: NSNumber,
        toIndex: NSNumber,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        if (rejectWhenTrackIndexOutOfBounds(
            index: fromIndex.intValue,
            message: "The fromIndex is out of bounds",
            reject: reject)
        ) { return }
        if (rejectWhenTrackIndexOutOfBounds(
            index: toIndex.intValue,
            max: Int.max,
            message: "The toIndex is out of bounds",
            reject: reject)
        ) { return }
        try? player.moveItem(fromIndex: fromIndex.intValue, toIndex: toIndex.intValue)
        resolve(NSNull())
    }


    @objc(removeUpcomingTracks:rejecter:)
    public func removeUpcomingTracks(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.removeUpcomingItems()
        resolve(NSNull())
    }

    @objc(skip:initialTime:resolver:rejecter:)
    public func skip(
        to trackIndex: NSNumber,
        initialTime: Double,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        let index = trackIndex.intValue;
        if (rejectWhenTrackIndexOutOfBounds(index: index, reject: reject)) { return }

        if (rejectWhenNotInitialized(reject: reject)) { return }

        print("Skipping to track:", index)
        // One call, so the snapshot carries the target position from the start: the old
        // jump-then-seek pair let a progress tick report 0 in between.
        try? core.skip(to: index, position: initialTime >= 0 ? initialTime : nil)
        resolve(NSNull())
    }

    @objc(skipToNext:resolver:rejecter:)
    public func skipToNext(
        initialTime: Double,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.next()

        // if an initialTime is passed the seek to it
        if (initialTime >= 0) {
            self.seekTo(time: initialTime, resolve: resolve, reject: reject)
        } else {
            resolve(NSNull())
        }
    }

    @objc(skipToPrevious:resolver:rejecter:)
    public func skipToPrevious(
        initialTime: Double,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.previous()

        // if an initialTime is passed the seek to it
        if (initialTime >= 0) {
            self.seekTo(time: initialTime, resolve: resolve, reject: reject)
        } else {
            resolve(NSNull())
        }
    }

    @objc(reset:rejecter:)
    public func reset(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.stop()
        player.clear()
        core.publishSnapshot()
        resolve(NSNull())
    }

    @objc(play:rejecter:)
    public func play(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        core.play()
        resolve(NSNull())
    }

    @objc(pause:rejecter:)
    public func pause(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.pause()
        resolve(NSNull())
    }

    @objc(setPlayWhenReady:resolver:rejecter:)
    public func setPlayWhenReady(playWhenReady: Bool, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        core.setPlayWhenReady(playWhenReady)
        resolve(NSNull())
    }

    @objc(getPlayWhenReady:rejecter:)
    public func getPlayWhenReady(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        resolve(player.playWhenReady)
    }

    @objc(stop:rejecter:)
    public func stop(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.stop()
        resolve(NSNull())
    }

    @objc(seekTo:resolver:rejecter:)
    public func seekTo(time: Double, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.seek(to: time)
        resolve(NSNull())
    }

    @objc(seekBy:resolver:rejecter:)
    public func seekBy(offset: Double, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.seek(by: offset)
        resolve(NSNull())
    }

    /// Replace the queue, the index and the position in one call. See `PlayerCore.loadQueue`.
    @objc(loadQueue:startIndex:startPositionSec:playWhenReady:resolver:rejecter:)
    public func loadQueue(
        trackDicts: [[String: Any]],
        startIndex: NSNumber,
        startPositionSec: Double,
        playWhenReady: Bool,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        var tracks = [Track]()
        for trackDict in trackDicts {
            guard let track = Track(dictionary: trackDict) else {
                reject("invalid_track_object", "Track is missing a required key", nil)
                return
            }
            tracks.append(track)
        }

        if tracks.isEmpty {
            reject("invalid_parameter", "The track list is empty", nil)
            return
        }

        let index = startIndex.intValue
        if index < 0 || index >= tracks.count {
            reject("index_out_of_bounds", "The track index is out of bounds", nil)
            return
        }

        do {
            try core.loadQueue(
                items: tracks,
                startIndex: index,
                startPosition: startPositionSec,
                playWhenReady: playWhenReady
            )
        } catch {
            reject("runtime_exception", error.localizedDescription, error)
            return
        }
        resolve(NSNull())
    }

    @objc(setStopAt:resolver:rejecter:)
    public func setStopAt(positionSec: Double, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.setStopAt(positionSec)
        resolve(NSNull())
    }

    @objc(clearStopAt:rejecter:)
    public func clearStopAt(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.clearStopAt()
        resolve(NSNull())
    }

    @objc(retry:rejecter:)
    public func retry(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        player.reload(startFromCurrentTime: true)
        resolve(NSNull())
    }

    @objc(setRepeatMode:resolver:rejecter:)
    public func setRepeatMode(repeatMode: NSNumber, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.repeatMode = SwiftAudioEx.RepeatMode(rawValue: repeatMode.intValue) ?? .off
        resolve(NSNull())
    }

    @objc(getRepeatMode:rejecter:)
    public func getRepeatMode(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.repeatMode.rawValue)
    }

    @objc(setVolume:resolver:rejecter:)
    public func setVolume(level: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.volume = level
        resolve(NSNull())
    }

    @objc(getVolume:rejecter:)
    public func getVolume(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.volume)
    }

    @objc(setRate:resolver:rejecter:)
    public func setRate(rate: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        core.setRate(rate)
        resolve(NSNull())
    }

    @objc(getRate:rejecter:)
    public func getRate(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.rate)
    }

    @objc(getTrack:resolver:rejecter:)
    public func getTrack(index: NSNumber, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        if (index.intValue >= 0 && index.intValue < player.items.count) {
            let track = player.items[index.intValue]
            resolve((track as? Track)?.toObject())
        } else {
            resolve(NSNull())
        }
    }

    @objc(getQueue:rejecter:)
    public func getQueue(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        let serializedQueue = player.items.map { ($0 as! Track).toObject() }
        resolve(serializedQueue)
    }

    @objc(setQueue:resolver:rejecter:)
    public func setQueue(
        trackDicts: [[String: Any]],
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        var tracks = [Track]()
        for trackDict in trackDicts {
            guard let track = Track(dictionary: trackDict) else {
                reject("invalid_track_object", "Track is missing a required key", nil)
                return
            }

            tracks.append(track)
        }
        player.clear()
        try? player.add(items: tracks)
        resolve(index)
    }

    @objc(getActiveTrack:rejecter:)
    public func getActiveTrack(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        let index = player.currentIndex
        if (index >= 0 && index < player.items.count) {
            let track = player.items[index]
            resolve((track as? Track)?.toObject())
        } else {
            resolve(NSNull())
        }
    }

    @objc(getActiveTrackIndex:rejecter:)
    public func getActiveTrackIndex(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        let index = player.currentIndex
        if index < 0 || index >= player.items.count {
            resolve(NSNull())
        } else {
            resolve(index)
        }
    }

    @objc(getDuration:rejecter:)
    public func getDuration(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.duration)
    }

    @objc(getBufferedPosition:rejecter:)
    public func getBufferedPosition(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.bufferedPosition)
    }

    @objc(getPosition:rejecter:)
    public func getPosition(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(core.position)
    }

    @objc(getProgress:rejecter:)
    public func getProgress(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        resolve([
            "position": core.position,
            "duration": core.duration,
            "buffered": core.bufferedPosition
        ])
    }

    @objc(getPlaybackState:rejecter:)
    public func getPlaybackState(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        resolve(getPlaybackStateBodyKeyValues(state: player.playerState))
    }

    @objc(updateMetadataForTrack:metadata:resolver:rejecter:)
    public func updateMetadata(for trackIndex: NSNumber, metadata: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        let index = trackIndex.intValue;
        if (rejectWhenNotInitialized(reject: reject)) { return }
        if (rejectWhenTrackIndexOutOfBounds(index: index, reject: reject)) { return }

        let track : Track = player.items[index] as! Track;
        track.updateMetadata(dictionary: metadata)

        if (player.currentIndex == index) {
            Metadata.update(for: player, with: metadata)
        }

        resolve(NSNull())
    }

    @objc(clearNowPlayingMetadata:rejecter:)
    public func clearNowPlayingMetadata(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        // Clear SwiftAudioEx controller - it handles MPNowPlayingInfoCenter internally
        player.nowPlayingInfoController.clear()
        
        resolve(NSNull())
    }

    @objc(updateNowPlayingMetadata:resolver:rejecter:)
    public func updateNowPlayingMetadata(metadata: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        Metadata.update(for: player, with: metadata)
        resolve(NSNull())
    }

    private func getPlaybackStateErrorKeyValues() -> Dictionary<String, Any> {
        // The mapping itself lives in `PlayerCore` so the snapshot's `error` and this payload's
        // cannot drift apart. Same codes and messages as before.
        guard let info = PlaybackErrorInfo.from(player.playbackError) else {
            return ["message": "A playback error occurred", "code": "ios_playback_error"]
        }
        return ["message": info.message, "code": info.code]
    }

    /// The `onPlaybackState` payload and `getPlaybackState()` return value.
    ///
    /// `state` and `error` are exactly what they were. Everything else is the `PlayerSnapshot`
    /// `TrackPlayerModule.kt` already sends on Android: `transport` is what the play/pause button
    /// binds to, and it does not flicker through readiness.
    ///
    /// The snapshot is re-derived here rather than read off the stored value, so it cannot matter
    /// whether `PlayerCore`'s own listener or this one ran first for the event being reported.
    private func getPlaybackStateBodyKeyValues(state: AudioPlayerState) -> Dictionary<String, Any> {
        var body = core.publishSnapshot().asDictionary()
        body["state"] = State.fromPlayerState(state: state).rawValue
        if (state == AudioPlayerState.failed) {
            body["error"] = getPlaybackStateErrorKeyValues()
        }
        return body
    }

    // MARK: - QueuedAudioPlayer Event Handlers

    func handleAudioPlayerStateChange(state: AVPlayerWrapperState) {
        let body = getPlaybackStateBodyKeyValues(state: state)
        // The Android half of this line is `TrackPlayerModule.onPlaybackState`. `transport` is what
        // the JS button binds to as of step 3, and "did the button ever leave pause during this
        // load" is a question you answer from this line.
        NSLog(
            "🎵 TrackPlayer.onPlaybackState: state=%@ transport=%@ readiness=%@ reason=%@ playWhenReady=%@ isPlaying=%@ suppression=%@",
            String(describing: body["state"] ?? ""),
            String(describing: body["transport"] ?? ""),
            String(describing: body["readiness"] ?? ""),
            String(describing: body["reason"] ?? ""),
            String(describing: body["playWhenReady"] ?? ""),
            String(describing: body["isPlaying"] ?? ""),
            String(describing: body["suppression"] ?? "")
        )
        eventEmitter?.emitPlaybackState(body)
        
        // Set playbackState for macOS/CarPlay simulator compatibility
        // SwiftAudioEx automatically handles playbackRate and elapsedPlaybackTime updates
        switch state {
        case .playing:
            MPNowPlayingInfoCenter.default().playbackState = .playing
        case .paused:
            MPNowPlayingInfoCenter.default().playbackState = .paused
        case .stopped, .ended:
            MPNowPlayingInfoCenter.default().playbackState = .stopped
        case .loading, .buffering, .ready, .idle, .failed:
            // For transitional states, set to unknown
            // Buffering/loading during playback might be better as .playing, but .unknown is safer
            MPNowPlayingInfoCenter.default().playbackState = .unknown
        @unknown default:
            MPNowPlayingInfoCenter.default().playbackState = .unknown
        }
        
        if (state == .ended) {
            eventEmitter?.emitPlaybackQueueEnded([
                "track": player.currentIndex,
                "position": player.currentTime,
            ] as [String : Any])
        }
    }
    
    func handleAudioPlayerCommonMetadataReceived(metadata: [AVMetadataItem]) {
        // Note: Metadata events are not in TurboModule spec, so we skip them
    }
    
    func handleAudioPlayerChapterMetadataReceived(metadata: [AVTimedMetadataGroup]) {
        // Note: Metadata events are not in TurboModule spec, so we skip them
    }

    func handleAudioPlayerTimedMetadataReceived(metadata: [AVTimedMetadataGroup]) {
        // Note: Metadata events are not in TurboModule spec, so we skip them
    }

    func handleAudioPlayerFailed(error: Error?) {
        eventEmitter?.emitPlaybackError(["error": error?.localizedDescription])
    }

    func handleAudioPlayerCurrentItemChange(
        item: AudioItem?,
        index: Int?,
        lastItem: AudioItem?,
        lastIndex: Int?,
        lastPosition: Double?
    ) {

        if let item = item {
            DispatchQueue.main.async {
                UIApplication.shared.beginReceivingRemoteControlEvents();
            }
            // Update now playing controller with isLiveStream option from track
            if self.player.automaticallyUpdateNowPlayingInfo {
                let isTrackLiveStream = (item as? Track)?.isLiveStream ?? false
                self.player.nowPlayingInfoController.set(keyValue: NowPlayingInfoProperty.isLiveStream(isTrackLiveStream))
            }
        } else {
            DispatchQueue.main.async {
                UIApplication.shared.endReceivingRemoteControlEvents();
            }
        }

        if ((item != nil && lastItem == nil) || item == nil) {
            configureAudioSession();
        }

        var a: Dictionary<String, Any> = ["lastPosition": lastPosition ?? 0]
        if let lastIndex = lastIndex {
            a["lastIndex"] = lastIndex
        }

        if let lastTrack = (lastItem as? Track)?.toObject() {
            a["lastTrack"] = lastTrack
        }

        if let index = index {
            a["index"] = index
        }

        if let track = (item as? Track)?.toObject() {
            a["track"] = track
        }
        eventEmitter?.emitPlaybackActiveTrackChanged(a)

        // deprecated:
        var b: Dictionary<String, Any> = ["position": lastPosition ?? 0]
        if let lastIndex = lastIndex {
            b["lastIndex"] = lastIndex
        }
        if let index = index {
            b["nextTrack"] = index
        }
        // Note: PlaybackTrackChanged is not in TurboModule spec, so we skip it
    }

    func handleAudioPlayerSecondElapse(seconds: Double) {
        // because you cannot prevent the `event.secondElapse` from firing
        // do not emit an event if `progressUpdateEventInterval` is nil
        // additionally, there are certain instances in which this event is emitted
        // _after_ a manipulation to the queu causing no currentItem to exist (see reset)
        // in which case we shouldn't emit anything or we'll get an exception.
        if !shouldEmitProgressEvent || player.currentItem == nil { return }
        
        // SwiftAudioEx automatically updates elapsedPlaybackTime and playbackRate when state changes
        // No manual updates needed here. If CarPlay progress bar isn't updating correctly,
        // we may need to re-enable manual updates via nowPlayingInfoController.set()
        
        eventEmitter?.emitPlaybackProgressUpdated([
            "position": core.position,
            "duration": core.duration,
            "buffered": core.bufferedPosition,
            "track": player.currentIndex,
        ])
    }

    func handlePlayWhenReadyChange(playWhenReady: Bool) {
        configureAudioSession();
        eventEmitter?.emitPlaybackPlay(whenReadyChanged: [
            "playWhenReady": playWhenReady
        ])
    }
    
    
    // MARK: - Sleep Timer Methods (Stub Implementations)
    
    @objc
    func getSleepTimerProgress(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        NSLog("RNTrackPlayer.getSleepTimerProgress called - stub implementation")
        // Stub implementation - sleep timer not yet implemented
        resolve(0.0) // Return 0 progress
    }
    
    @objc
    func setSleepTimer(_ time: Double, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        NSLog("RNTrackPlayer.setSleepTimer called with time: \(time) - stub implementation")
        // Stub implementation - sleep timer not yet implemented
        resolve(nil)
    }
    
    @objc
    func sleepWhenActiveTrackReachesEnd(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        NSLog("RNTrackPlayer.sleepWhenActiveTrackReachesEnd called - stub implementation")
        // Stub implementation - sleep timer not yet implemented
        resolve(nil)
    }
    
    @objc
    func clearSleepTimer(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        NSLog("RNTrackPlayer.clearSleepTimer called - stub implementation")
        // Stub implementation - sleep timer not yet implemented
        resolve(nil)
    }
}
