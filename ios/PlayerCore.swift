//
//  PlayerCore.swift
//  react-native-track-player
//
//  The iOS half of the engine state model that step 2 installed on Android
//  (`kotlinaudio/models/PlayerSnapshot.kt`). Same fields, same string values, same rules.
//
//  Why it exists: `AudioPlayerState` mirrors raw AVPlayer readiness, so a play/pause button bound to
//  it flickers loading -> ready -> buffering -> playing on every load, and a load that takes a while
//  leaves the button on "play" after the user tapped it. `PlayerSnapshot.transport` answers the only
//  question that button asks — "is this thing *meant* to be playing?" — and is deliberately not
//  derived from readiness.
//
//  Why a singleton that owns the player: native code in the app (CarPlay, intent handlers, a future
//  sleep timer) needs to command playback and observe it without a React hop. `TrackPlayer.swift` is
//  now one subscriber/commander of this object rather than the owner of the queue; every bridged
//  method and event it had still works exactly as before.
//

import AVFoundation
import Combine
import Foundation
import MediaPlayer
import SwiftAudioEx

// MARK: - The model

/// What the play/pause button shows. Raw values match `PlaybackTransport` in the TurboModule spec.
public enum PlaybackTransport: String {
    case playing
    case paused
    case ended
    case error
}

/// How far the player has got with actually being able to produce sound.
public enum PlaybackReadiness: String {
    case idle
    case loading
    case buffering
    case ready
    case ended
}

/// Why ``PlaybackTransport`` last changed.
public enum PlaybackTransportReason: String {
    case user
    case remote
    case error
    case endOfQueue = "end_of_queue"
    case audioFocusLoss = "audio_focus_loss"
    case stopAt = "stop_at"
    case system
}

/// Playback is intended but the system is holding it back. A phone call is a *suppression*, not a
/// pause: the button keeps showing "pause" and playback resumes when the interruption ends.
public enum PlaybackSuppression: String {
    case none
    case transientAudioFocusLoss = "transient_audio_focus_loss"
    case unsuitableOutput = "unsuitable_output"
}

/// What the `MPRemoteCommandCenter` handlers in `TrackPlayer.swift` do with a transport command that
/// arrives from outside the app — the lock screen, Control Center, the headphone remote, CarPlay,
/// Siri. The Android twin is `TransportPolicy` in `kotlinaudio/models/PlayerSnapshot.kt`, and it is
/// switched the same way.
///
/// `routeToListeners` is what the app shipped before step 4: the handler only emits `onRemotePlay`
/// and friends and JS issues the real command back through the module — so nothing external can
/// control playback while JS is asleep or dead. `applyNativelyAndNotify`, the default, applies the
/// command on ``PlayerCore/shared`` first and *then* emits, so JS records it (analytics, stored
/// progress) without re-issuing it.
///
/// Next and previous are never in this list: JS owns their meaning (chapter navigation, the
/// daily-date rule, the 20-second restart).
public enum TransportPolicy {
    case routeToListeners
    case applyNativelyAndNotify
}

public struct PlaybackErrorInfo: Equatable {
    public let code: String
    public let message: String

    /// The mapping `TrackPlayer.swift` has always used for `onPlaybackState`'s `error` key, moved
    /// here so the snapshot and the bridged payload cannot drift apart.
    static func from(_ error: AudioPlayerError.PlaybackError?) -> PlaybackErrorInfo? {
        guard let error = error else { return nil }
        switch error {
        case .failedToLoadKeyValue:
            return PlaybackErrorInfo(code: "ios_failed_to_load_resource", message: "Failed to load resource")
        case .invalidSourceUrl:
            return PlaybackErrorInfo(code: "ios_invalid_source_url", message: "The source url was invalid")
        case .notConnectedToInternet:
            return PlaybackErrorInfo(
                code: "ios_not_connected_to_internet",
                message: "A network resource was requested, but an internet connection has not been established and can’t be established automatically."
            )
        case .playbackFailed:
            return PlaybackErrorInfo(code: "ios_playback_failed", message: "Playback of the track failed")
        case .itemWasUnplayable:
            return PlaybackErrorInfo(code: "ios_track_unplayable", message: "The track could not be played")
        }
    }
}

public struct PlayerSnapshot: Equatable {
    public var transport: PlaybackTransport = .paused
    public var transportReason: PlaybackTransportReason = .system
    /// The raw intent: what `play()` / `pause()` last asked for.
    public var playWhenReady: Bool = false
    public var readiness: PlaybackReadiness = .idle
    /// Whether sound is actually coming out right now.
    public var isPlaying: Bool = false
    public var suppression: PlaybackSuppression = .none
    public var index: Int?
    public var queueSize: Int = 0
    public var position: Double = 0
    public var duration: Double = 0
    public var buffered: Double = 0
    public var rate: Float = 1
    public var volume: Float = 1
    /// Sticky until the next load or a play-after-error.
    public var error: PlaybackErrorInfo?

    /// The `onPlaybackState` / `getPlaybackState()` keys, and the `userInfo` an ObjC observer reads.
    /// The legacy `state` string is not in here: `TrackPlayer.swift` adds it, because it is derived
    /// from `AudioPlayerState` and must stay bit for bit what it always was.
    public func asDictionary() -> [String: Any] {
        var dict: [String: Any] = [
            "playWhenReady": playWhenReady,
            "isPlaying": isPlaying,
            "transport": transport.rawValue,
            "readiness": readiness.rawValue,
            "reason": transportReason.rawValue,
            "suppression": suppression.rawValue,
        ]
        if let error = error {
            dict["error"] = ["code": error.code, "message": error.message]
        }
        return dict
    }
}

// MARK: - The engine

public final class PlayerCore: NSObject {
    // The `@objc` members are what app code reaches from Objective-C: a Swift `import` of this module
    // from an app target can hide React's own types from the app's other Swift files.
    @objc public static let shared = PlayerCore()

    /// Posted whenever ``snapshot`` changes. `userInfo["snapshot"]` is ``PlayerSnapshot/asDictionary()``
    /// so ObjC and UIKit code can subscribe without Combine; Swift callers can read ``snapshot``
    /// or subscribe to `$snapshot`.
    @objc public static let snapshotDidChange = Notification.Name("PlayerCore.snapshotDidChange")
    /// Posted on a position jump no interval tick will report — a seek (including a seek while
    /// paused) and ``loadQueue(items:startIndex:startPosition:playWhenReady:)``.
    /// `userInfo` carries `position`, `duration`, `buffered`.
    @objc public static let progressDidJump = Notification.Name("PlayerCore.progressDidJump")
    /// Posted when playback reached the position armed by ``setStopAt(_:)``. `userInfo["position"]`.
    public static let stopAtReached = Notification.Name("PlayerCore.stopAtReached")
    /// Posted on main after a *remote* speed control changed the rate — never for ``setRate(_:)``,
    /// which is what JS calls. `userInfo[rateKey]` is the rate applied (a `Double`, already snapped),
    /// `userInfo[trackIdKey]` the ``activeTrackId`` it applies to, absent when nothing is queued.
    /// App code persists the rate from here, which is why it is posted whether or not JS is alive.
    @objc public static let remoteRateDidChange = Notification.Name("PlayerCore.remoteRateDidChange")
    /// Posted once when a queued item played to its end by itself — never for a skip, a seek or a
    /// load. `userInfo[trackIdKey]` is that item's `id`; `userInfo[pausedKey]` is true when
    /// ``pauseAtEndOfItem`` stopped playback there (never at the last item: that end is the queue's).
    /// Posted on the thread AVFoundation reports the end on; the queue may already have moved on.
    @objc public static let itemDidPlayToEnd = Notification.Name("PlayerCore.itemDidPlayToEnd")

    public static let snapshotKey = "snapshot"
    public static let positionKey = "position"
    @objc public static let rateKey = "rate"
    @objc public static let trackIdKey = "trackId"
    @objc public static let pausedKey = "paused"

    /// The queue. `TrackPlayer.swift` still talks to it directly for everything the snapshot does
    /// not cover (metadata, remote commands, repeat mode); transport goes through the commands below
    /// so the reason and the pending start position are recorded.
    public let player = QueuedAudioPlayer()

    @Published public private(set) var snapshot = PlayerSnapshot()

    /// See ``TransportPolicy``. Lives here rather than on `TrackPlayer` so other native code in the
    /// app (CarPlay, a future sleep timer) reads the same value the remote handlers do.
    public var transportPolicy: TransportPolicy = .applyNativelyAndNotify

    private var transportReason: PlaybackTransportReason = .system
    private var suppression: PlaybackSuppression = .none

    /// The position a load or a seek asked for, held until the player confirms it.
    ///
    /// ExoPlayer masks a seek: `currentPosition` is the target the instant `seekTo` returns.
    /// AVPlayer does not — `currentTime` is the *old* position until the asynchronous seek lands,
    /// and 0 for as long as the item is still loading. Reporting those is what wrote position 0
    /// into the app's stored progress on a load, and the pre-seek position on a paused seek. The
    /// seek callback (`event.seek`, which fires only once the real seek has run, including for a
    /// seek deferred while the item loaded) clears it again.
    private var pendingPosition: Double?

    private var stopAtTarget: Double?
    private var stopAtTimer: Timer?

    /// The rates a remote speed control offers, in ascending order; empty while the app has not enabled
    /// one. Set through ``configureRemotePlaybackRates(_:)``, read on main.
    public private(set) var remotePlaybackRates: [Double] = []
    private var changePlaybackRateTarget: Any?

    /// media3's `pauseAtEndOfMediaItems`: while true, an item that plays to its end with a next item
    /// queued pauses playback there, and the queue stands, paused, at the start of that next item.
    /// The last item's end is the queue's end, as without it. Skips, seeks and loads are not ends. The
    /// pause is published with reason `system`. App code arms it (a sleep timer's "end of chapter");
    /// ``setStopAt(_:)`` stays free for its own use.
    @objc public var pauseAtEndOfItem = false
    private var itemEndObserver: NSObjectProtocol?

    /// See ``commandGeneration``. Commands run on the module queue and on main; readers on either.
    private let commandLock = NSLock()
    private var commandGenerationValue = 0
    private var commandsInFlight = 0

    override private init() {
        super.init()
        player.playWhenReady = false
        player.event.stateChange.addListener(self) { [weak self] _ in self?.publishSnapshot() }
        player.event.playWhenReadyChange.addListener(self) { [weak self] _ in self?.publishSnapshot() }
        player.event.currentItem.addListener(self) { [weak self] _ in
            self?.publishSnapshot()
            // `loadQueue` clears the Now Playing info, and a new item starts from the old keys.
            self?.reportDefaultRate()
        }
        player.event.updateDuration.addListener(self) { [weak self] _ in self?.publishSnapshot() }
        player.event.fail.addListener(self) { [weak self] _ in
            self?.pendingPosition = nil
            self?.publishSnapshot(reason: .error)
        }
        player.event.seek.addListener(self) { [weak self] data in
            guard let self = self else { return }
            // Every seek issued here reports exactly the target it asked for, so a completion for any
            // other value is a seek a newer one replaced, finished or not. Unmasking on it would
            // report the old clock until the newer seek lands.
            if let pending = self.pendingPosition, abs(pending - data.seconds) > 0.001 {
                return
            }
            // The seek has landed. Report it once: no interval tick will, because a seek while
            // paused produces none, and the tick after a seek while playing is up to a second late.
            self.pendingPosition = nil
            self.publishSnapshot()
            self.postProgressJump(position: data.seconds)
        }
        // Synchronous, on the thread AVFoundation posts from: SwiftAudioEx's own handler moves the
        // queue on, and a pause has to be in place before the next item is loaded with the intent.
        itemEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: nil
        ) { [weak self] note in
            self?.itemDidPlayToEndTime(note)
        }
        publishSnapshot()
    }

    // MARK: - Reading

    /// The position to report to anyone outside the engine: the position a load or a seek asked
    /// for while that is still in flight, the player's own clock otherwise. This is what makes
    /// `getPosition()` / `getProgress()` answer the same way ExoPlayer's masked position does.
    @objc public var position: Double {
        if let pending = pendingPosition { return pending }
        let current = player.currentTime
        return current.isFinite ? current : 0
    }

    public var duration: Double {
        let value = player.duration
        return value.isFinite ? value : 0
    }

    public var bufferedPosition: Double {
        let value = player.bufferedPosition
        return value.isFinite ? value : 0
    }

    /// The `id` the app gave the current queue item, or nil when nothing is queued. `Track` is internal,
    /// so native app code has no other way to tell which track ``position`` belongs to.
    @objc public var activeTrackId: String? {
        (player.currentItem as? Track)?.toObject()["id"] as? String
    }

    /// Whether the recording is moving: playing, or buffering on the way to playing. A seek while
    /// paused buffers too, and is not. Read live, unlike ``snapshot``, which is published from several
    /// queues and can arrive out of order.
    @objc public var isRunning: Bool {
        switch player.playerState {
        case .playing: return true
        case .buffering: return player.playWhenReady
        default: return false
        }
    }

    /// Whether the queue has played out.
    @objc public var hasEnded: Bool {
        player.playerState == .ended
    }

    /// Whether sound is coming out: playing — not loading, buffering or paused — and not held back by
    /// an interruption (a call). Read live, like ``isRunning``.
    @objc public var isPlaying: Bool {
        player.playerState == .playing && suppression == .none
    }

    /// What `play()` / `pause()` last asked for, read live.
    @objc public var playWhenReady: Bool {
        player.playWhenReady
    }

    /// The player's own volume, 0...1 — not the device's. App code fades with it (a sleep timer).
    @objc public var volume: Float {
        get { player.volume }
        set {
            player.volume = newValue
            publishSnapshot()
        }
    }

    /// Moves whenever a load or a skip starts or finishes, and reads -1 while one is running.
    ///
    /// Those commands pass through states no reader may pair up: a load makes the queue's *first*
    /// item current before it jumps to the requested one. A reader on another thread samples this
    /// around its reads and drops the sample unless it read the same non-negative value both times.
    @objc public var commandGeneration: Int {
        commandLock.lock()
        defer { commandLock.unlock() }
        return commandsInFlight > 0 ? -1 : commandGenerationValue
    }

    private func beginCommand() {
        commandLock.lock()
        commandsInFlight += 1
        commandGenerationValue += 1
        commandLock.unlock()
    }

    private func endCommand() {
        commandLock.lock()
        commandsInFlight -= 1
        commandGenerationValue += 1
        commandLock.unlock()
    }

    /// Whether a ``setStopAt(_:)`` is currently armed.
    public var stopAtPosition: Double? { stopAtTarget }

    // MARK: - Commands

    /// Replace the queue, the index and the position in one call, then apply the intent.
    ///
    /// The order matters: the intent goes on *last*, after the item is in place and the seek has
    /// been issued, so nothing starts playing at 0 and then jumps. The seek is safe to issue while
    /// the item is still loading — `AVPlayerWrapper` holds it as `timeToSeekToAfterLoading` and
    /// applies it the moment the asset is ready — and ``position`` reports the target in the
    /// meantime, so no event can carry a 0 before it.
    public func loadQueue(items: [AudioItem], startIndex: Int, startPosition: Double, playWhenReady: Bool) throws {
        beginCommand()
        defer { endCommand() }
        clearStopAt()
        pendingPosition = nil
        player.playWhenReady = false
        player.stop()
        player.clear()
        guard !items.isEmpty else {
            publishSnapshot(reason: .user)
            return
        }
        player.add(items: items, playWhenReady: false)

        let index = max(0, min(startIndex, items.count - 1))
        // Armed before the seek, not after: `AVPlayerWrapper.seek` defers to
        // `timeToSeekToAfterLoading` while the item is still loading and only calls back once the
        // real seek has run, and that callback is what disarms it again.
        pendingPosition = startPosition > 0 ? startPosition : nil
        try player.jumpToItem(atIndex: index, playWhenReady: false)
        if startPosition > 0 {
            player.seek(to: startPosition)
        }
        player.playWhenReady = playWhenReady

        publishSnapshot(reason: .user)
        postProgressJump(position: startPosition > 0 ? startPosition : 0)
    }

    /// `playWhenReady` goes true and the snapshot updates in the same call, so a button bound to it
    /// never shows the stale value. A play after a failure reloads the current item first —
    /// otherwise AVPlayer sits in `.failed` and the intent goes nowhere.
    public func play(reason: PlaybackTransportReason = .user) {
        if player.playerState == .failed {
            player.reload(startFromCurrentTime: true)
        }
        player.play()
        publishSnapshot(reason: reason)
    }

    public func pause(reason: PlaybackTransportReason = .user) {
        player.pause()
        publishSnapshot(reason: reason)
    }

    /// A pause app code decides on (a sleep timer running out): ``pause(reason:)`` with reason
    /// `system`, the path every other pause takes.
    @objc public func pauseFromApp() {
        pause(reason: .system)
    }

    public func stop(reason: PlaybackTransportReason = .user) {
        clearStopAt()
        pendingPosition = nil
        player.stop()
        publishSnapshot(reason: reason)
    }

    public func setPlayWhenReady(_ playWhenReady: Bool, reason: PlaybackTransportReason = .user) {
        player.playWhenReady = playWhenReady
        publishSnapshot(reason: reason)
    }

    public func seek(to position: Double, reason: PlaybackTransportReason = .user) {
        clearStopAt()
        pendingPosition = position
        player.seek(to: position)
        publishSnapshot(reason: reason)
    }

    public func seek(by offset: Double, reason: PlaybackTransportReason = .user) {
        clearStopAt()
        // An absolute seek from the position already reported, so the mask and the seek always agree
        // and quick jumps add up the way ExoPlayer's masked `seekBy` does. `AVPlayerWrapper.seek(by:)`
        // reads the item's own clock instead, which is 0 while a replaced item is still loading.
        let target = max(0, position + offset)
        pendingPosition = target
        player.seek(to: target)
        publishSnapshot(reason: reason)
    }

    public func skip(to index: Int, position: Double? = nil) throws {
        beginCommand()
        defer { endCommand() }
        clearStopAt()
        // Armed before the jump, and only when a seek will actually follow: `jumpToItem` answers
        // "already on this index" with `seek(to: 0)`, and the position below is the one that must
        // win. Nothing arms it when no seek is issued, because `event.seek` is what disarms it.
        let target = (position ?? -1) >= 0 ? position : nil
        pendingPosition = target
        try player.jumpToItem(atIndex: index, playWhenReady: player.playWhenReady)
        if let target = target {
            player.seek(to: target)
        }
        publishSnapshot()
    }

    public func next() {
        clearStopAt()
        pendingPosition = nil
        player.next()
        publishSnapshot()
    }

    public func previous() {
        clearStopAt()
        pendingPosition = nil
        player.previous()
        publishSnapshot()
    }

    /// The rate JS asked for. Never announced as a remote change: JS saves its own choices, and it
    /// sets each book's rate right before its queue loads.
    public func setRate(_ rate: Float) {
        player.rate = rate
        publishSnapshot()
        reportDefaultRate()
    }

    // MARK: - Remote playback rate

    /// The nearest of `rates` to `rate`; `rate` itself when `rates` is empty.
    public static func snap(_ rate: Double, to rates: [Double]) -> Double {
        rates.min(by: { abs($0 - rate) < abs($1 - rate) }) ?? rate
    }

    /// The first of `rates` faster than `current`, wrapping to the first (slowest) after the last;
    /// `rates` is in ascending order. A rate the list does not contain — a custom speed the app set,
    /// 1.35 — moves up to the next one in the list, 1.4.
    public static func nextRate(after current: Double, in rates: [Double]) -> Double? {
        guard let first = rates.first else { return nil }
        return rates.first(where: { $0 > current + 0.001 }) ?? first
    }

    /// Turn the system's playback-rate command on with `rates`, or off when `rates` is empty.
    ///
    /// The handler lives here rather than in `TrackPlayer.swift` beside the transport handlers so it
    /// is registered once per process and survives a JS reload: it only ever talks to the engine.
    /// SwiftAudioEx's `RemoteCommandController` has no case for this command, so it never touches it.
    public func configureRemotePlaybackRates(_ rates: [Double]) {
        let valid = rates.filter { $0.isFinite && $0 > 0 }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.remotePlaybackRates = valid
            let command = MPRemoteCommandCenter.shared().changePlaybackRateCommand
            if valid.isEmpty {
                if let target = self.changePlaybackRateTarget {
                    command.removeTarget(target)
                    self.changePlaybackRateTarget = nil
                }
                command.isEnabled = false
                return
            }
            command.supportedPlaybackRates = valid.map { NSNumber(value: $0) }
            if self.changePlaybackRateTarget == nil {
                self.changePlaybackRateTarget = command.addTarget { event in
                    guard let event = event as? MPChangePlaybackRateCommandEvent else {
                        return .commandFailed
                    }
                    PlayerCore.shared.applyRemoteRate(Double(event.playbackRate))
                    return .success
                }
            }
            command.isEnabled = true
        }
    }

    /// A remote speed control asked for `requested`: snap it to ``remotePlaybackRates``, apply it,
    /// publish, and post ``remoteRateDidChange``. Returns the rate applied. Call on main.
    @discardableResult
    public func applyRemoteRate(_ requested: Double) -> Double {
        let rate = Self.snap(requested, to: remotePlaybackRates)
        player.rate = Float(rate)
        publishSnapshot()
        reportDefaultRate()
        NSLog("RNTP-Transport: applied remote rate natively: \(requested) -> \(rate)")
        var userInfo: [String: Any] = [Self.rateKey: rate]
        if let trackId = activeTrackId { userInfo[Self.trackIdKey] = trackId }
        NotificationCenter.default.post(name: Self.remoteRateDidChange, object: self, userInfo: userInfo)
        return rate
    }

    /// CarPlay's playback-rate button: the next of ``remotePlaybackRates``, wrapping from the fastest
    /// to the slowest. The button's handler only says "tapped", so the cycle is the app's to define.
    /// A no-op while no rates are configured. Call on main.
    @objc public func cycleRemoteRate() {
        guard let next = Self.nextRate(after: Double(player.rate), in: remotePlaybackRates) else { return }
        applyRemoteRate(next)
    }

    /// `MPNowPlayingInfoPropertyDefaultPlaybackRate`: the rate the item plays at when it plays.
    /// SwiftAudioEx reports `PlaybackRate` as 0 while paused, so this is what a paused CarPlay rate
    /// button and the lock screen read the chosen speed from.
    private func reportDefaultRate() {
        guard player.automaticallyUpdateNowPlayingInfo, player.currentItem != nil else { return }
        player.nowPlayingInfoController.set(
            keyValue: NowPlayingInfoProperty.defaultPlaybackRate(Double(player.rate))
        )
    }

    // MARK: - stopAt

    /// Arm a pending stop: pause at `position` in the current item, with reason `stop_at`, and never
    /// auto-advance past it. Cleared by ``clearStopAt()`` and by any seek, skip, load or
    /// ``loadQueue(items:startIndex:startPosition:playWhenReady:)``.
    ///
    /// Android arms an ExoPlayer `PlayerMessage`, which the playback thread delivers at a timeline
    /// position. SwiftAudioEx keeps its `AVPlayer` `fileprivate` inside `AVPlayerWrapper` and its
    /// `wrapper` internal to the pod, so `addBoundaryTimeObserver` is not reachable from here
    /// without forking the pod; a 100 ms poll while armed is the next best thing and is only ever
    /// scheduled while something is actually armed.
    public func setStopAt(_ position: Double) {
        clearStopAt()
        guard position.isFinite else { return }
        stopAtTarget = position
        let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            self?.checkStopAt()
        }
        RunLoop.main.add(timer, forMode: .common)
        stopAtTimer = timer
    }

    /// Disarm a pending ``setStopAt(_:)``. A no-op when nothing is armed.
    public func clearStopAt() {
        stopAtTimer?.invalidate()
        stopAtTimer = nil
        stopAtTarget = nil
    }

    private func checkStopAt() {
        guard let target = stopAtTarget else {
            clearStopAt()
            return
        }
        let now = player.currentTime
        guard now.isFinite, now >= target - 0.05 else { return }
        clearStopAt()
        transportReason = .stopAt
        player.pause()
        publishSnapshot(reason: .stopAt)
        NotificationCenter.default.post(
            name: PlayerCore.stopAtReached,
            object: self,
            userInfo: [PlayerCore.positionKey: now]
        )
    }

    // MARK: - End of an item

    /// An `AVPlayerItem` played to its end. Ours only if its URL is one the queue holds: other
    /// players in the process post the same notification.
    ///
    /// Runs before or after SwiftAudioEx's own handler — NotificationCenter promises no order — so
    /// the item is found by its URL rather than taken to be the current one. Either way the pause
    /// lands before the next item plays: `QueuedAudioPlayer` loads it with the intent it finds, and
    /// even when it read the old intent the load still has an asynchronous asset hop to make.
    private func itemDidPlayToEndTime(_ note: Notification) {
        guard let url = ((note.object as? AVPlayerItem)?.asset as? AVURLAsset)?.url,
              let ended = queuedItem(of: url)
        else { return }
        let trackId = (ended.item as? Track)?.toObject()["id"] as? String
        // Android arms media3's pause only while a next item exists: the last item's end stays the
        // queue's end on both platforms.
        let paused = pauseAtEndOfItem && ended.hasNext
        if paused {
            player.playWhenReady = false
            publishSnapshot(reason: .system)
        }
        var userInfo: [String: Any] = [Self.pausedKey: paused]
        if let trackId = trackId { userInfo[Self.trackIdKey] = trackId }
        NotificationCenter.default.post(name: Self.itemDidPlayToEnd, object: self, userInfo: userInfo)
    }

    /// The queued item whose source is `url` — the current one, or the one before it when the queue
    /// has already moved on, else the first match — and whether another item follows it. Read from
    /// one copy of the queue: a load or a reset on the module queue may change `player.items` at any
    /// moment, and an index into a later copy could be out of range.
    private func queuedItem(of url: URL) -> (item: AudioItem, hasNext: Bool)? {
        let items = player.items
        let matches = items.indices.filter { Self.sourceURL(of: items[$0]) == url.standardized }
        let current = player.currentIndex
        guard let index = matches.first(where: { $0 == current })
            ?? matches.first(where: { $0 == current - 1 })
            ?? matches.first
        else { return nil }
        return (items[index], index < items.count - 1 || player.repeatMode == .queue)
    }

    /// The URL `AVPlayerWrapper` builds for an item: a file path for a local item, the string as is
    /// for a stream.
    private static func sourceURL(of item: AudioItem) -> URL? {
        let source = item.getSourceUrl()
        let url = item.getSourceType() == .file ? URL(fileURLWithPath: source) : URL(string: source)
        return url?.standardized
    }

    // MARK: - Audio session

    /// An interruption (a call, an alarm, another app taking the session) is a *suppression*: the
    /// intent stays what it was and the button keeps showing "pause".
    ///
    /// Note that this only records the fact. What the app does about it is `onRemoteDuck` in JS,
    /// which is unchanged.
    public func noteInterruption(began: Bool) {
        suppression = began ? .transientAudioFocusLoss : .none
        publishSnapshot()
    }

    // MARK: - Snapshot

    private func readiness() -> PlaybackReadiness {
        switch player.playerState {
        case .idle, .stopped: return .idle
        case .loading: return .loading
        case .buffering: return .buffering
        case .ready, .playing, .paused: return .ready
        case .ended: return .ended
        case .failed: return .ready
        }
    }

    /// Derive the snapshot from the player as it is right now, store it, and tell subscribers if it
    /// changed. Safe to call from anywhere and as often as you like: derivation is pure and the
    /// notification is only posted on a real change.
    @discardableResult
    public func publishSnapshot(reason: PlaybackTransportReason? = nil) -> PlayerSnapshot {
        if let reason = reason { transportReason = reason }

        let readiness = readiness()
        let failed = player.playerState == .failed
        let error = failed ? PlaybackErrorInfo.from(player.playbackError) : nil
        let transport: PlaybackTransport
        if failed {
            transport = .error
        } else if readiness == .ended {
            transport = .ended
        } else if player.playWhenReady {
            transport = .playing
        } else {
            transport = .paused
        }

        let index = player.currentIndex
        var next = PlayerSnapshot()
        next.transport = transport
        next.transportReason = transportReason
        next.playWhenReady = player.playWhenReady
        next.readiness = readiness
        next.isPlaying = player.playerState == .playing
        next.suppression = suppression
        next.index = (index >= 0 && index < player.items.count) ? index : nil
        next.queueSize = player.items.count
        next.position = position
        next.duration = duration
        next.buffered = bufferedPosition
        next.rate = player.rate
        next.volume = player.volume
        next.error = error

        if next != snapshot {
            snapshot = next
            NotificationCenter.default.post(
                name: PlayerCore.snapshotDidChange,
                object: self,
                userInfo: [PlayerCore.snapshotKey: next.asDictionary()]
            )
        }
        return next
    }

    private func postProgressJump(position: Double) {
        NotificationCenter.default.post(
            name: PlayerCore.progressDidJump,
            object: self,
            userInfo: [
                "position": position,
                "duration": duration,
                "buffered": bufferedPosition,
            ]
        )
    }
}
