#import "ALSBridge.h"

#import <CoreFoundation/CoreFoundation.h>
#import <IOKit/IOKitLib.h>
#import <IOKit/hidsystem/IOHIDServiceClient.h>

typedef struct __IOHIDEvent *IOHIDEventRef;

extern IOHIDEventRef IOHIDServiceClientCopyEvent(IOHIDServiceClientRef service, int64_t type, int32_t options, int64_t timestamp);
extern double IOHIDEventGetFloatValue(IOHIDEventRef event, int32_t field);
extern IOHIDServiceClientRef ALCALSCopyALSServiceClient(void);

#define kAmbientLightSensorEvent 12
#define IOHIDEventFieldBase(type) ((type) << 16)

@implementation ALSAmbientLightReading

- (instancetype)initWithLux:(double)lux source:(ALSAmbientLightSource)source {
    self = [super init];
    if (self) {
        _lux = lux;
        _source = source;
    }
    return self;
}

@end

@interface ALSAmbientLightReader () {
    IOHIDServiceClientRef _hidClient;
    io_connect_t _legacySensorPort;
    BOOL _legacySensorReady;
}

@end

@implementation ALSAmbientLightReader

- (instancetype)init {
    self = [super init];
    if (self) {
        _legacySensorPort = IO_OBJECT_NULL;
        _legacySensorReady = NO;
        _hidClient = NULL;
    }
    return self;
}

- (void)dealloc {
    if (_hidClient != NULL) {
        CFRelease(_hidClient);
        _hidClient = NULL;
    }

    if (_legacySensorReady) {
        IOConnectRelease(_legacySensorPort);
        _legacySensorPort = IO_OBJECT_NULL;
        _legacySensorReady = NO;
    }
}

- (BOOL)isSensorAvailable {
    return [self hidClient] != NULL || [self initializeLegacySensorIfNeeded];
}

- (nullable ALSAmbientLightReading *)currentReading {
    IOHIDEventRef event = [self copyAmbientLightEvent];
    if (event != NULL) {
        double lux = IOHIDEventGetFloatValue(event, IOHIDEventFieldBase(kAmbientLightSensorEvent));
        CFRelease(event);

        if (lux >= 0) {
            return [[ALSAmbientLightReading alloc] initWithLux:lux source:ALSAmbientLightSourceHID];
        }
    }

    if (![self initializeLegacySensorIfNeeded]) {
        return nil;
    }

    uint32_t outputCount = 2;
    uint64_t values[2] = {0, 0};
    kern_return_t result = IOConnectCallMethod(
        _legacySensorPort,
        0,
        NULL,
        0,
        NULL,
        0,
        values,
        &outputCount,
        NULL,
        0
    );

    if (result != KERN_SUCCESS || outputCount == 0) {
        return nil;
    }

    double legacyLux = ((double)(3 * values[0]) / 100000.0) - 1.5;
    if (legacyLux < 0) {
        legacyLux = 0;
    }

    return [[ALSAmbientLightReading alloc] initWithLux:legacyLux source:ALSAmbientLightSourceLegacyLMU];
}

- (IOHIDServiceClientRef)hidClient {
    if (_hidClient == NULL) {
        _hidClient = ALCALSCopyALSServiceClient();
    }

    return _hidClient;
}

- (nullable IOHIDEventRef)copyAmbientLightEvent {
    IOHIDServiceClientRef client = [self hidClient];
    if (client == NULL) {
        return NULL;
    }

    return IOHIDServiceClientCopyEvent(client, kAmbientLightSensorEvent, 0, 0);
}

- (BOOL)initializeLegacySensorIfNeeded {
    if (_legacySensorReady) {
        return YES;
    }

    io_service_t legacySensorService = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("AppleLMUController"));
    if (legacySensorService == IO_OBJECT_NULL) {
        return NO;
    }

    kern_return_t result = IOServiceOpen(legacySensorService, mach_task_self(), 0, &_legacySensorPort);
    IOObjectRelease(legacySensorService);

    if (result != KERN_SUCCESS) {
        _legacySensorPort = IO_OBJECT_NULL;
        return NO;
    }

    _legacySensorReady = YES;
    return YES;
}

@end