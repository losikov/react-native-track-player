#import <Foundation/Foundation.h>

@protocol RTNTrackPlayerEventEmitter <NSObject>
- (void)emitPlaybackState:(NSDictionary *)state;
- (void)emitPlaybackProgressUpdated:(NSDictionary *)progress;
- (void)emitPlaybackQueueEnded:(NSDictionary *)event;
- (void)emitPlaybackError:(NSDictionary *)error;
- (void)emitPlaybackActiveTrackChanged:(NSDictionary *)event;
- (void)emitPlaybackPlayWhenReadyChanged:(NSDictionary *)event;
- (void)emitRemotePlay;
- (void)emitRemotePause;
- (void)emitRemoteStop;
- (void)emitRemoteNext;
- (void)emitRemotePrevious;
- (void)emitRemoteSeek:(NSDictionary *)event;
- (void)emitRemoteJumpForward:(NSDictionary *)event;
- (void)emitRemoteJumpBackward:(NSDictionary *)event;
- (void)emitRemoteBookmark;
- (void)emitRemoteDuck:(NSDictionary *)event;
@end

// Interface declaration moved to implementation file to avoid C++ import issues
