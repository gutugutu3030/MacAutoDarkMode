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
    private let brightnessMonitor: ScreenBrightnessMonitor
    private let appearanceController: AppearanceController

    private var cancellables = Set<AnyCancellable>()
    private var darkCandidateCount = 0
    private var lightCandidateCount = 0
    private var started = false

    init(
        settings: SettingsStore,
        monitor: AmbientLightMonitor,
        brightnessMonitor: ScreenBrightnessMonitor,
        appearanceController: AppearanceController
    ) {
        self.settings = settings
        self.monitor = monitor
        self.brightnessMonitor = brightnessMonitor
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

        brightnessMonitor.$brightness
            .sink { [weak self] brightness in
                self?.evaluateBrightness(brightness)
            }
            .store(in: &cancellables)

        settings.$switchMode
            .sink { [weak self] mode in
                guard let self else { return }
                self.resetCandidateCounts()
                switch mode {
                case .off:
                    self.monitor.stop()
                    self.brightnessMonitor.stop()
                    self.lastActionDescription = mode.menuDescription
                case .auto:
                    self.brightnessMonitor.stop()
                    self.monitor.start()
                    self.lastActionDescription = mode.menuDescription
                    self.evaluate(lux: self.monitor.lastReadingLux)
                case .manual:
                    self.monitor.stop()
                    self.brightnessMonitor.start()
                    self.lastActionDescription = mode.menuDescription
                    self.evaluateBrightness(self.brightnessMonitor.brightness)
                }
            }
            .store(in: &cancellables)

        // モードに応じた初期起動
        switch settings.switchMode {
        case .auto:
            monitor.start()
        case .manual:
            brightnessMonitor.start()
        case .off:
            break
        }

        refreshAppearanceState()
    }

    func forceSetAppearance(_ target: SystemAppearance) {
        applyAppearance(target, reason: "Manual override.")
    }

    // MARK: - 自動モード（環境光センサー）

    private func evaluate(lux: Double) {
        guard settings.switchMode == .auto else { return }
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

    // MARK: - 手動モード（画面輝度）

    private func evaluateBrightness(_ brightness: Float) {
        guard settings.switchMode == .manual else { return }
        guard brightnessMonitor.isAvailable else {
            lastActionDescription = "Display brightness is unavailable."
            return
        }
        guard brightness >= 0 else { return }

        let formattedBrightness = String(format: "%.0f%%", brightness * 100)

        if brightness >= 1.0 {
            lastActionDescription = "Brightness at maximum (\(formattedBrightness))."
            applyAppearance(.light, reason: "Display brightness at maximum.")
        } else {
            lastActionDescription = "Brightness below maximum (\(formattedBrightness))."
            applyAppearance(.dark, reason: "Display brightness below maximum (\(formattedBrightness)).")
        }
    }

    // MARK: - 共通

    private func applyAppearance(_ target: SystemAppearance, reason: String) {
        // 自動モードではクールダウンを適用（手動モードでは即座に反映）
        if settings.switchMode == .auto,
           let lastSwitchAt,
           Date().timeIntervalSince(lastSwitchAt) < settings.effectiveCooldownSeconds {
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