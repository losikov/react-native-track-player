#import "RTNTrackPlayer.h"
#import <RTNTrackPlayerSpec/RTNTrackPlayerSpec.h>
#import "react_native_track_player/react_native_track_player-Swift.h"

// We need to actually inherit from the base class to get the emitOnXXX methods
@interface RTNTrackPlayer : NativeRTNTrackPlayerSpecBase <NativeRTNTrackPlayerSpec, RTNTrackPlayerEventEmitter>
@end

@implementation RTNTrackPlayer {
    TrackPlayer *_trackPlayer;
}

RCT_EXPORT_MODULE()

- (id)init {
    if (self = [super init]) {
        _trackPlayer = [TrackPlayer new];
        [_trackPlayer setEventEmitter:self];
    }
    return self;
}

// MARK: - Private helper methods
- (NSDictionary *)convertPlayerOptionsToDict:(JS::NativeRTNTrackPlayer::PlayerOptions &)options {
    NSMutableDictionary *config = [NSMutableDictionary dictionary];
    
    @try {
        if (options.minBuffer().has_value()) { config[@"minBuffer"] = @(options.minBuffer().value()); }
        if (options.maxBuffer().has_value()) { config[@"maxBuffer"] = @(options.maxBuffer().value()); }
        if (options.playBuffer().has_value()) { config[@"playBuffer"] = @(options.playBuffer().value()); }
        if (options.backBuffer().has_value()) { config[@"backBuffer"] = @(options.backBuffer().value()); }
        if (options.iosCategory() != nil) { config[@"iosCategory"] = options.iosCategory(); }
        if (options.iosCategoryMode() != nil) { config[@"iosCategoryMode"] = options.iosCategoryMode(); }
        if (options.iosCategoryOptions().has_value()) {
            NSMutableArray *categoryOptions = [NSMutableArray array];
            try {
                auto categoryOptionsValue = options.iosCategoryOptions().value();
                for (NSString *option : categoryOptionsValue) {
                    if (option != nil) { [categoryOptions addObject:option]; }
                }
                config[@"iosCategoryOptions"] = categoryOptions;
            } catch (...) { NSLog(@"Error processing iosCategoryOptions"); }
        }
        if (options.androidAudioContentType() != nil) { config[@"androidAudioContentType"] = options.androidAudioContentType(); }
        if (options.autoHandleInterruptions().has_value()) { config[@"autoHandleInterruptions"] = @(options.autoHandleInterruptions().value()); }
        if (options.autoHandleRouteChanges().has_value()) { config[@"autoHandleRouteChanges"] = @(options.autoHandleRouteChanges().value()); }
        if (options.waitForBuffer().has_value()) { config[@"waitForBuffer"] = @(options.waitForBuffer().value()); }
        if (options.autoUpdateMetadata().has_value()) { config[@"autoUpdateMetadata"] = @(options.autoUpdateMetadata().value()); }
    } @catch (NSException *exception) {
        NSLog(@"Error converting player options: %@", exception.reason);
        return @{};
    }
    return config;
}

- (NSDictionary *)convertTrackToDict:(JS::NativeRTNTrackPlayer::Track &)track {
    NSMutableDictionary *trackDict = [NSMutableDictionary dictionary];
    @try {
        if (track.id_() != nil) { trackDict[@"id"] = track.id_(); }
        if (track.url() != nil) { trackDict[@"url"] = track.url(); }
        if (track.title() != nil) { trackDict[@"title"] = track.title(); }
        if (track.artist() != nil) { trackDict[@"artist"] = track.artist(); }
        if (track.album() != nil) { trackDict[@"album"] = track.album(); }
        if (track.genre() != nil) { trackDict[@"genre"] = track.genre(); }
        if (track.date() != nil) { trackDict[@"date"] = track.date(); }
        if (track.duration().has_value()) { trackDict[@"duration"] = @(track.duration().value()); }
        if (track.artwork() != nil) { trackDict[@"artwork"] = track.artwork(); }
        if (track.description() != nil) { trackDict[@"description"] = track.description(); }
        if (track.rating() != nil) { trackDict[@"rating"] = track.rating(); }
        if (track.isLiveStream().has_value()) { trackDict[@"isLiveStream"] = @(track.isLiveStream().value()); }
        if (track.type() != nil) { trackDict[@"type"] = track.type(); }
        if (track.userAgent() != nil) { trackDict[@"userAgent"] = track.userAgent(); }
        if (track.contentType() != nil) { trackDict[@"contentType"] = track.contentType(); }
        if (track.pitchAlgorithm() != nil) { trackDict[@"pitchAlgorithm"] = track.pitchAlgorithm(); }
        if (track.headers() != nil) { trackDict[@"headers"] = track.headers(); }
    } @catch (NSException *exception) {
        NSLog(@"Error converting track: %@", exception.reason);
        return @{};
    }
    return trackDict;
}

- (NSDictionary *)convertUpdateOptionsToDict:(JS::NativeRTNTrackPlayer::UpdateOptions &)options {
    NSMutableDictionary *config = [NSMutableDictionary dictionary];
    
    @try {
        if (options.ratingType().has_value()) {
            config[@"ratingType"] = @(options.ratingType().value());
        }
        if (options.forwardJumpInterval().has_value()) {
            config[@"forwardJumpInterval"] = @(options.forwardJumpInterval().value());
        }
        if (options.backwardJumpInterval().has_value()) {
            config[@"backwardJumpInterval"] = @(options.backwardJumpInterval().value());
        }
        if (options.progressUpdateEventInterval().has_value()) {
            config[@"progressUpdateEventInterval"] = @(options.progressUpdateEventInterval().value());
        }
        if (options.color().has_value()) {
            config[@"color"] = @(options.color().value());
        }
        if (options.icon() != nil) {
            config[@"icon"] = options.icon();
        }
        if (options.playIcon() != nil) {
            config[@"playIcon"] = options.playIcon();
        }
        if (options.pauseIcon() != nil) {
            config[@"pauseIcon"] = options.pauseIcon();
        }
        if (options.stopIcon() != nil) {
            config[@"stopIcon"] = options.stopIcon();
        }
        if (options.previousIcon() != nil) {
            config[@"previousIcon"] = options.previousIcon();
        }
        if (options.nextIcon() != nil) {
            config[@"nextIcon"] = options.nextIcon();
        }
        if (options.rewindIcon() != nil) {
            config[@"rewindIcon"] = options.rewindIcon();
        }
        if (options.forwardIcon() != nil) {
            config[@"forwardIcon"] = options.forwardIcon();
        }
        if (options.capabilities().has_value()) {
            NSMutableArray *capabilities = [NSMutableArray array];
            try {
                auto capabilitiesValue = options.capabilities().value();
                for (NSString *capability : capabilitiesValue) {
                    if (capability != nil) {
                        [capabilities addObject:capability];
                    }
                }
                config[@"capabilities"] = capabilities;
            } catch (...) {
                NSLog(@"Error processing capabilities");
            }
        }
        if (options.notificationCapabilities().has_value()) {
            NSMutableArray *notificationCapabilities = [NSMutableArray array];
            try {
                auto notificationCapabilitiesValue = options.notificationCapabilities().value();
                for (NSString *capability : notificationCapabilitiesValue) {
                    if (capability != nil) {
                        [notificationCapabilities addObject:capability];
                    }
                }
                config[@"notificationCapabilities"] = notificationCapabilities;
            } catch (...) {
                NSLog(@"Error processing notificationCapabilities");
            }
        }
        if (options.compactCapabilities().has_value()) {
            NSMutableArray *compactCapabilities = [NSMutableArray array];
            try {
                auto compactCapabilitiesValue = options.compactCapabilities().value();
                for (NSString *capability : compactCapabilitiesValue) {
                    if (capability != nil) {
                        [compactCapabilities addObject:capability];
                    }
                }
                config[@"compactCapabilities"] = compactCapabilities;
            } catch (...) {
                NSLog(@"Error processing compactCapabilities");
            }
        }
    } @catch (NSException *exception) {
        NSLog(@"Error converting update options: %@", exception.reason);
        return @{};
    }
    
    return config;
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeRTNTrackPlayerSpecJSI>(params);
}

// MARK: - RTNTrackPlayerEventEmitter Methods
- (void)emitPlaybackState:(NSDictionary *)state {
    [self emitOnPlaybackState:state];
}

- (void)emitPlaybackProgressUpdated:(NSDictionary *)progress {
    [self emitOnPlaybackProgressUpdated:progress];
}

- (void)emitPlaybackQueueEnded:(NSDictionary *)event {
    [self emitOnPlaybackQueueEnded:event];
}

- (void)emitPlaybackError:(NSDictionary *)error {
    [self emitOnPlaybackError:error];
}

- (void)emitPlaybackActiveTrackChanged:(NSDictionary *)event {
    [self emitOnPlaybackActiveTrackChanged:event];
}

- (void)emitPlaybackPlayWhenReadyChanged:(NSDictionary *)event {
    [self emitOnPlaybackPlayWhenReadyChanged:event];
}

- (void)emitPlaybackStopAtReached:(NSDictionary *)event {
    [self emitOnPlaybackStopAtReached:event];
}

- (void)emitRemotePlay {
    [self emitOnRemotePlay];
}

- (void)emitRemotePause {
    [self emitOnRemotePause];
}

- (void)emitRemoteStop {
    [self emitOnRemoteStop];
}

- (void)emitRemoteNext {
    [self emitOnRemoteNext];
}

- (void)emitRemotePrevious {
    [self emitOnRemotePrevious];
}

- (void)emitRemoteSeek:(NSDictionary *)event {
    [self emitOnRemoteSeek:event];
}

- (void)emitRemoteJumpForward:(NSDictionary *)event {
    [self emitOnRemoteJumpForward:event];
}

- (void)emitRemoteJumpBackward:(NSDictionary *)event {
    [self emitOnRemoteJumpBackward:event];
}

- (void)emitRemoteBookmark {
    [self emitOnRemoteBookmark];
}

// MARK: - Setup and configuration
- (void)add:(nonnull NSArray *)tracks insertBeforeIndex:(NSInteger)insertBeforeIndex resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer add:tracks before:@(insertBeforeIndex) resolver:resolve rejecter:reject];
}

- (void)clearNowPlayingMetadata:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer clearNowPlayingMetadata:resolve rejecter:reject];
}

- (void)destroy:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer reset:resolve rejecter:reject];
}

- (void)getActiveTrack:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getActiveTrack:resolve rejecter:reject];
}

- (void)getActiveTrackIndex:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getActiveTrackIndex:resolve rejecter:reject];
}

- (void)getBufferedPosition:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getBufferedPosition:resolve rejecter:reject];
}

- (void)getCurrentIndex:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getActiveTrackIndex:resolve rejecter:reject];
}

- (void)getCurrentTrack:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getActiveTrack:resolve rejecter:reject];
}

- (void)getDuration:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getDuration:resolve rejecter:reject];
}

- (void)getPlayWhenReady:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getPlayWhenReady:resolve rejecter:reject];
}

- (void)getPlaybackRate:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getRate:resolve rejecter:reject];
}

- (void)getPlaybackState:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getPlaybackState:resolve rejecter:reject];
}

- (void)getPosition:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getPosition:resolve rejecter:reject];
}

- (void)getProgress:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getProgress:resolve rejecter:reject];
}

- (void)getQueue:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getQueue:resolve rejecter:reject];
}

- (void)getRate:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getRate:resolve rejecter:reject];
}

- (void)getRepeatMode:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getRepeatMode:resolve rejecter:reject];
}

- (void)getState:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getPlaybackState:resolve rejecter:reject];
}

- (void)getTrack:(NSInteger)index resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getTrack:@(index) resolver:resolve rejecter:reject];
}

- (void)getVolume:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer getVolume:resolve rejecter:reject];
}

- (void)load:(JS::NativeRTNTrackPlayer::Track &)track resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSDictionary *trackDict = [self convertTrackToDict:track];
    [_trackPlayer load:trackDict resolver:resolve rejecter:reject];
}

- (void)pause:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer pause:resolve rejecter:reject];
}

- (void)play:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer play:resolve rejecter:reject];
}

- (void)remove:(nonnull NSArray *)indexes resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSMutableArray *indexArray = [NSMutableArray array];
    for (NSNumber *index in indexes) {
        [indexArray addObject:@([index integerValue])];
    }
    [_trackPlayer remove:indexArray resolver:resolve rejecter:reject];
}

- (void)removeUpcomingTracks:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer removeUpcomingTracks:resolve rejecter:reject];
}

- (void)reset:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer reset:resolve rejecter:reject];
}

- (void)seekBy:(double)offset resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer seekBy:offset resolver:resolve rejecter:reject];
}

- (void)seekTo:(double)position resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer seekTo:position resolver:resolve rejecter:reject];
}

- (void)loadQueue:(nonnull NSArray *)tracks startIndex:(NSInteger)startIndex startPositionSec:(double)startPositionSec playWhenReady:(BOOL)playWhenReady resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer loadQueue:tracks startIndex:@(startIndex) startPositionSec:startPositionSec playWhenReady:playWhenReady resolver:resolve rejecter:reject];
}

- (void)setStopAt:(double)positionSec resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer setStopAt:positionSec resolver:resolve rejecter:reject];
}

- (void)clearStopAt:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer clearStopAt:resolve rejecter:reject];
}

- (void)setBrowseTree:(nonnull NSDictionary *)tree resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: setBrowseTree called with tree: %@", tree);
}

- (void)setBrowseTreeStyle:(double)contentStyleGrid contentStyleList:(double)contentStyleList resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: setBrowseTreeStyle called with grid: %f, list: %f", contentStyleGrid, contentStyleList);
}

- (void)setPlayWhenReady:(BOOL)playWhenReady resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer setPlayWhenReady:playWhenReady resolver:resolve rejecter:reject];
}

- (void)setPlaybackRate:(double)rate resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer setRate:rate resolver:resolve rejecter:reject];
}

- (void)setRate:(double)rate resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer setRate:rate resolver:resolve rejecter:reject];
}

- (void)setRepeatMode:(double)mode resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer setRepeatMode:@(mode) resolver:resolve rejecter:reject];
}

- (void)setVolume:(double)volume resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    [_trackPlayer setVolume:volume resolver:resolve rejecter:reject];
}

- (void)setupPlayer:(JS::NativeRTNTrackPlayer::PlayerOptions &)options resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSDictionary *config = [self convertPlayerOptionsToDict:options];
    [_trackPlayer setupPlayer:config resolver:resolve rejecter:reject];
}

- (void)skip:(NSInteger)index initialPosition:(nonnull NSNumber *)initialPosition resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: skip called with index: %ld, initialPosition: %@", (long)index, initialPosition);
    [_trackPlayer skip:@(index) initialTime:[initialPosition doubleValue] resolver:resolve rejecter:reject];
}

- (void)skipToNext:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: skipToNext called");
    [_trackPlayer skipToNext:0.0 resolver:resolve rejecter:reject];
}

- (void)skipToPrevious:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: skipToPrevious called");
    [_trackPlayer skipToPrevious:0.0 resolver:resolve rejecter:reject];
}

- (void)skipToTrack:(NSInteger)index resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: skipToTrack called with index: %ld", (long)index);
    [_trackPlayer skip:@(index) initialTime:0.0 resolver:resolve rejecter:reject];
}

- (void)stop:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: stop called");
    [_trackPlayer stop:resolve rejecter:reject];
}

- (void)updateMetadataForTrack:(nonnull NSString *)id metadata:(JS::NativeRTNTrackPlayer::Track &)metadata resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: updateMetadataForTrack called with id: %@", id);
    NSDictionary *metadataDict = [self convertTrackToDict:metadata];
    [_trackPlayer updateMetadataForTrack:@([id integerValue]) metadata:metadataDict resolver:resolve rejecter:reject];
}

- (void)updateNowPlayingMetadata:(JS::NativeRTNTrackPlayer::Track &)metadata resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: updateNowPlayingMetadata called");
    NSDictionary *metadataDict = [self convertTrackToDict:metadata];
    [_trackPlayer updateNowPlayingMetadata:metadataDict resolver:resolve rejecter:reject];
}

- (void)updateOptions:(JS::NativeRTNTrackPlayer::UpdateOptions &)options resolve:(nonnull RCTPromiseResolveBlock)resolve reject:(nonnull RCTPromiseRejectBlock)reject {
    NSLog(@"RTNTrackPlayer: updateOptions called");
    NSDictionary *config = [self convertUpdateOptionsToDict:options];
    [_trackPlayer updateOptions:config resolver:resolve rejecter:reject];
}

// MARK: - Event Emitters
- (void)emitRemoteDuck:(NSDictionary *)event {
    [self emitOnRemoteDuck:event];
}

@end