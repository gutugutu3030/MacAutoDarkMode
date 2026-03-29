import Foundation
import IOKit
import IOKit.graphics

@MainActor
final class ScreenBrightnessMonitor: ObservableObject {
    @Published private(set) var brightness: Float = -1
    @Published private(set) var isAvailable = false
    @Published private(set) var lastError: String?

    private var timer: Timer?
    private let updateInterval: TimeInterval

    init(updateInterval: TimeInterval = 0.5) {
        self.updateInterval = updateInterval
    }

    func start() {
        guard timer == nil else { return }

        sample()

        let timer = Timer(timeInterval: updateInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.sample()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stop() {
        timer?.invalidate()
        timer = nil
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
