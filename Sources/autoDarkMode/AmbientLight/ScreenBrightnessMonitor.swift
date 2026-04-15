import CoreGraphics
import Darwin
import Foundation
import IOKit
import IOKit.graphics

@MainActor
final class ScreenBrightnessMonitor: ObservableObject {
    private typealias DisplayServicesGetBrightness = @convention(c) (CGDirectDisplayID, UnsafeMutablePointer<Float>) -> Int32

    @Published private(set) var brightness: Float = -1
    @Published private(set) var isAvailable = false
    @Published private(set) var lastError: String?

    private var timerTask: Task<Void, Never>?
    private let updateInterval: TimeInterval

    private static let displayServicesGetBrightness: DisplayServicesGetBrightness? = {
        guard let handle = dlopen("/System/Library/PrivateFrameworks/DisplayServices.framework/DisplayServices", RTLD_NOW),
              let symbol = dlsym(handle, "DisplayServicesGetBrightness") else {
            return nil
        }

        return unsafeBitCast(symbol, to: DisplayServicesGetBrightness.self)
    }()

    init(updateInterval: TimeInterval = 0.5) {
        self.updateInterval = updateInterval
    }

    func start() {
        guard timerTask == nil else { return }

        sample()

        let interval = updateInterval
        let sleepNanoseconds = UInt64(max(interval, 0) * 1_000_000_000)
        timerTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: sleepNanoseconds)
                guard let self, !Task.isCancelled else { break }
                self.sample()
            }
        }
    }

    func stop() {
        timerTask?.cancel()
        timerTask = nil
    }

    func sample() {
        guard let value = Self.readDisplayBrightness() else {
            isAvailable = false
            lastError = "Display brightness is unavailable."
            return
        }

        brightness = value
        isAvailable = true
        lastError = nil
    }

    /// 内蔵ディスプレイの輝度（0.0〜1.0）を IOKit 経由で取得する
    private static func readDisplayBrightness() -> Float? {
        if let brightness = readBrightnessViaDisplayServices() {
            return brightness
        }

        return readBrightnessViaIOKit()
    }

    private static func readBrightnessViaDisplayServices() -> Float? {
        guard let getBrightness = displayServicesGetBrightness else { return nil }

        for displayID in candidateDisplayIDs() {
            var brightness: Float = -1
            let result = getBrightness(displayID, &brightness)

            guard result == 0 else { continue }
            guard brightness.isFinite else { continue }

            return min(max(brightness, 0), 1)
        }

        return nil
    }

    private static func candidateDisplayIDs() -> [CGDirectDisplayID] {
        var onlineDisplayCount: UInt32 = 0
        let countResult = CGGetOnlineDisplayList(0, nil, &onlineDisplayCount)
        guard countResult == .success else {
            return [CGMainDisplayID()]
        }

        var displayIDs = Array(repeating: CGDirectDisplayID(), count: Int(onlineDisplayCount))
        let listResult = CGGetOnlineDisplayList(onlineDisplayCount, &displayIDs, &onlineDisplayCount)
        guard listResult == .success else {
            return [CGMainDisplayID()]
        }

        let mainDisplayID = CGMainDisplayID()
        let onlineDisplays = Array(displayIDs.prefix(Int(onlineDisplayCount))).filter { $0 != mainDisplayID }
        return [mainDisplayID] + onlineDisplays
    }

    private static func readBrightnessViaIOKit() -> Float? {
        var iterator = io_iterator_t()
        let result = IOServiceGetMatchingServices(
            kIOMainPortDefault,
            IOServiceMatching("IODisplayConnect"),
            &iterator
        )
        guard result == kIOReturnSuccess else { return nil }
        defer { IOObjectRelease(iterator) }

        var service = IOIteratorNext(iterator)
        while service != IO_OBJECT_NULL {
            var brightness: Float = 0
            let readResult = IODisplayGetFloatParameter(
                service,
                0,
                "brightness" as CFString,
                &brightness
            )
            IOObjectRelease(service)

            if readResult == kIOReturnSuccess {
                return brightness
            }

            service = IOIteratorNext(iterator)
        }

        return nil
    }
}
