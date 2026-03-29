import Combine
import Foundation

@MainActor
final class AutoSwitchEngine: ObservableObject {
    @Published private(set) var lastActionDescription = "Waiting for ambient light samples."
    @Published private(set) var lastError: String?
    @Published private(set) var lastKnownAppearance: SystemAppearance?
    @Published private(set) var lastSwitchAt: Date?

    private let settings: SettingsStore
    private let monitor: AmbientLightMonitor
    private let appearanceController: AppearanceController

    private var cancellables = Set<AnyCancellable>()
    private var darkCandidateCount = 0
    private var lightCandidateCount = 0
    private var started = false

    init(settings: SettingsStore, monitor: AmbientLightMonitor, appearanceController: AppearanceController) {
        self.settings = settings
        self.monitor = monitor
        self.appearanceController = appearanceController
    }

    func start() {
        guard !started else { return }
        started = true

        monitor.$lastReadingLux
            .sink { [weak self] lux in
                self?.evaluate(lux: lux)
            }
            .store(in: &cancellables)

        settings.$automationEnabled
            .sink { [weak self] enabled in
                guard let self else { return }
                if enabled {
                    self.lastActionDescription = "Automatic switching enabled."
                    self.evaluate(lux: self.monitor.lastReadingLux)
                } else {
                    self.resetCandidateCounts()
                    self.lastActionDescription = "Automatic switching disabled."
                }
            }
            .store(in: &cancellables)

        monitor.start()
        refreshAppearanceState()
    }

    func forceSetAppearance(_ target: SystemAppearance) {
        applyAppearance(target, reason: "Manual override.")
    }

    private func evaluate(lux: Double) {
        guard settings.automationEnabled else { return }
        guard monitor.sensorAvailable else {
            resetCandidateCounts()
            lastActionDescription = "Ambient light sensor unavailable."
            return
        }
        guard lux >= 0 else { return }

        if lux <= settings.effectiveDarkThresholdLux {
            darkCandidateCount += 1
            lightCandidateCount = 0
            lastActionDescription = "Dark candidate \(darkCandidateCount)/\(settings.effectiveRequiredConsecutiveSamples) at \(lux.formattedLux)."

            if darkCandidateCount >= settings.effectiveRequiredConsecutiveSamples {
                applyAppearance(.dark, reason: "Ambient light dropped to \(lux.formattedLux).")
            }
            return
        }

        if lux >= settings.effectiveLightThresholdLux {
            lightCandidateCount += 1
            darkCandidateCount = 0
            lastActionDescription = "Light candidate \(lightCandidateCount)/\(settings.effectiveRequiredConsecutiveSamples) at \(lux.formattedLux)."

            if lightCandidateCount >= settings.effectiveRequiredConsecutiveSamples {
                applyAppearance(.light, reason: "Ambient light rose to \(lux.formattedLux).")
            }
            return
        }

        resetCandidateCounts()
        lastActionDescription = "Inside hysteresis band at \(lux.formattedLux)."
    }

    private func applyAppearance(_ target: SystemAppearance, reason: String) {
        if let lastSwitchAt, Date().timeIntervalSince(lastSwitchAt) < settings.effectiveCooldownSeconds {
            lastActionDescription = "Cooldown active. Next change allowed in \((settings.effectiveCooldownSeconds - Date().timeIntervalSince(lastSwitchAt)).formattedSeconds)."
            resetCandidateCounts()
            return
        }

        do {
            let current = try appearanceController.currentAppearance()
            lastKnownAppearance = current

            guard current != target else {
                lastActionDescription = "Already in \(target.displayName) mode."
                lastError = nil
                resetCandidateCounts()
                return
            }

            try appearanceController.setAppearance(target)

            lastKnownAppearance = target
            lastSwitchAt = Date()
            lastError = nil
            lastActionDescription = reason
            resetCandidateCounts()
        } catch {
            lastError = error.localizedDescription
            lastActionDescription = "Failed to change appearance."
            resetCandidateCounts()
        }
    }

    private func refreshAppearanceState() {
        do {
            lastKnownAppearance = try appearanceController.currentAppearance()
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    private func resetCandidateCounts() {
        darkCandidateCount = 0
        lightCandidateCount = 0
    }
}

private extension TimeInterval {
    var formattedSeconds: String {
        String(format: "%.0fs", self)
    }
}