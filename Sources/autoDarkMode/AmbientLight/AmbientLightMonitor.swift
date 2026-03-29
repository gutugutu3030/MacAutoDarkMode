import ALSBridge
import Foundation

enum AmbientLightSourceDescription: String {
    case hid = "IOHID + BezelServices"
    case legacyLMU = "AppleLMUController"
    case unavailable = "Unavailable"

    init(bridgeSource: ALSAmbientLightSource) {
        switch bridgeSource.rawValue {
        case 1:
            self = .hid
        case 2:
            self = .legacyLMU
        default:
            self = .unavailable
        }
    }
}

@MainActor
final class AmbientLightMonitor: ObservableObject {
    @Published private(set) var lastReadingLux: Double = -1
    @Published private(set) var source: AmbientLightSourceDescription = .unavailable
    @Published private(set) var sensorAvailable = false
    @Published private(set) var lastUpdatedAt: Date?
    @Published private(set) var lastError: String?

    private let reader = ALSAmbientLightReader()
    private var timer: Timer?
    private let updateInterval: TimeInterval

    init(updateInterval: TimeInterval = 2.0) {
        self.updateInterval = updateInterval
        sensorAvailable = reader.isSensorAvailable
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
        sensorAvailable = reader.isSensorAvailable

        guard let reading = reader.currentReading() else {
            source = .unavailable
            lastError = sensorAvailable ? "Ambient light sample failed." : "Ambient light sensor is unavailable on this Mac."
            return
        }

        lastReadingLux = reading.lux
        source = AmbientLightSourceDescription(bridgeSource: reading.source)
        sensorAvailable = true
        lastUpdatedAt = Date()
        lastError = nil
    }
}

extension Double {
    var formattedLux: String {
        if self < 0 {
            return "—"
        }

        if self < 1000 {
            return String(format: "%.0f lx", self)
        }

        return String(format: "%.1f klx", self / 1000)
    }
}