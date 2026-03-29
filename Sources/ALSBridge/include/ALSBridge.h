#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, ALSAmbientLightSource) {
    ALSAmbientLightSourceUnavailable = 0,
    ALSAmbientLightSourceHID = 1,
    ALSAmbientLightSourceLegacyLMU = 2,
};

@interface ALSAmbientLightReading : NSObject

@property (nonatomic, readonly) double lux;
@property (nonatomic, readonly) ALSAmbientLightSource source;

- (instancetype)initWithLux:(double)lux source:(ALSAmbientLightSource)source NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

@interface ALSAmbientLightReader : NSObject

@property (nonatomic, readonly, getter=isSensorAvailable) BOOL sensorAvailable;

- (nullable ALSAmbientLightReading *)currentReading;

@end

NS_ASSUME_NONNULL_END