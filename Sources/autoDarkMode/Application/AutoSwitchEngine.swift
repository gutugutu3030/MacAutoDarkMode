import Combine
import Foundation

@MainActor
final class AutoSwitchEngine: ObservableObject {
    nonisolated static let manualLightBrightnessThreshold: Float = 0.99
    nonisolated static let manualLightLongPressSeconds: TimeInterval = 0.35

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
    private var manualBrightnessKeyMonitoringEnabled = false
    private var manualBrightnessUpIsPressed = false
    private var manualBrightnessRequiresReleaseAfterMax = false
    private var manualBrightnessWasNearMax = false
    private var manualBrightnessUpHoldTask: Task<Void, Never>?
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
                    self.resetManualBrightnessKeyState()
                    self.monitor.stop()
                    self.brightnessMonitor.stop()
                    self.lastActionDescription = mode.menuDescription
                case .auto:
                    self.resetManualBrightnessKeyState()
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

    func setManualBrightnessKeyMonitoringEnabled(_ enabled: Bool) {
        manualBrightnessKeyMonitoringEnabled = enabled

        if !enabled {
            resetManualBrightnessKeyState()
        }
    }

    func handleManualBrightnessKeyEvent(_ event: BrightnessKeyEvent) {
        guard settings.switchMode == .manual else { return }

        if event.direction == .up, event.phase == .up {
            manualBrightnessUpIsPressed = false
            manualBrightnessRequiresReleaseAfterMax = false
            cancelManualBrightnessUpHold()

            if manualBrightnessKeyMonitoringEnabled,
               Self.appearance(forManualBrightness: brightnessMonitor.brightness) == .light {
                lastActionDescription = "Brightness at or near maximum. Hold Brightness Up to switch to Light mode."
            }
            return
        }

        if event.direction == .up, event.phase == .down {
            manualBrightnessUpIsPressed = true
        }

        brightnessMonitor.sample()

        guard manualBrightnessKeyMonitoringEnabled else { return }

        switch event.direction {
        case .down:
            cancelManualBrightnessUpHold()
        case .up:
            if event.phase == .down,
               Self.shouldArmManualBrightnessLongPress(
                direction: event.direction,
                brightnessAfterSampling: brightnessMonitor.brightness,
                phase: event.phase,
                keyMonitoringEnabled: manualBrightnessKeyMonitoringEnabled,
                requiresReleaseAfterMax: manualBrightnessRequiresReleaseAfterMax
               ) {
                armManualBrightnessUpHold()
            }
        }
    }

    func reportManualBrightnessKeyMonitoringPermissionRequired() {
        guard settings.switchMode == .manual else { return }
        lastActionDescription = "Brightness key monitoring requires Accessibility permission. Manual mode still follows display brightness."
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
        let isNearMax = Self.appearance(forManualBrightness: brightness) == .light

        // IOKit の輝度値は最大でも 1.0 ちょうどにならないことがあるため、上限近傍を Light 扱いにする。
        switch Self.appearance(forManualBrightness: brightness) {
        case .light:
            guard !manualBrightnessKeyMonitoringEnabled else {
                if Self.shouldRequireReleaseAfterReachingManualMax(
                    isNearMax: isNearMax,
                    wasNearMax: manualBrightnessWasNearMax,
                    brightnessUpIsPressed: manualBrightnessUpIsPressed,
                    keyMonitoringEnabled: manualBrightnessKeyMonitoringEnabled
                ) {
                    manualBrightnessRequiresReleaseAfterMax = true
                    cancelManualBrightnessUpHold()
                    lastActionDescription = "Brightness at or near maximum (\(formattedBrightness)). Release Brightness Up once, then hold it again to switch to Light mode."
                    manualBrightnessWasNearMax = true
                    return
                }

                if manualBrightnessRequiresReleaseAfterMax {
                    lastActionDescription = "Brightness at or near maximum (\(formattedBrightness)). Release Brightness Up once, then hold it again to switch to Light mode."
                } else {
                    lastActionDescription = "Brightness at or near maximum (\(formattedBrightness)). Hold Brightness Up to switch to Light mode."
                }

                manualBrightnessWasNearMax = true
                return
            }

            manualBrightnessWasNearMax = true
            lastActionDescription = "Brightness at or near maximum (\(formattedBrightness))."
            applyAppearance(.light, reason: "Display brightness at or near maximum.")
        case .dark:
            manualBrightnessWasNearMax = false
            manualBrightnessRequiresReleaseAfterMax = false
            cancelManualBrightnessUpHold()
            lastActionDescription = "Brightness below maximum (\(formattedBrightness))."
            applyAppearance(.dark, reason: "Display brightness below maximum (\(formattedBrightness)).")
        }
    }

    nonisolated static func appearance(forManualBrightness brightness: Float) -> SystemAppearance {
        brightness >= manualLightBrightnessThreshold ? .light : .dark
    }

    nonisolated static func shouldArmManualBrightnessLongPress(
        direction: BrightnessKeyDirection,
        brightnessAfterSampling: Float,
        phase: BrightnessKeyPhase,
        keyMonitoringEnabled: Bool,
        requiresReleaseAfterMax: Bool
    ) -> Bool {
        guard keyMonitoringEnabled else { return false }
        guard direction == .up else { return false }
        guard phase == .down else { return false }
        guard !requiresReleaseAfterMax else { return false }
        return appearance(forManualBrightness: brightnessAfterSampling) == .light
    }

    nonisolated static func shouldRequireReleaseAfterReachingManualMax(
        isNearMax: Bool,
        wasNearMax: Bool,
        brightnessUpIsPressed: Bool,
        keyMonitoringEnabled: Bool
    ) -> Bool {
        guard keyMonitoringEnabled else { return false }
        guard isNearMax else { return false }
        guard brightnessUpIsPressed else { return false }
        return !wasNearMax
    }

    private func armManualBrightnessUpHold() {
        guard manualBrightnessUpHoldTask == nil else { return }

        lastActionDescription = "Brightness at or near maximum. Keep holding Brightness Up to switch to Light mode."
        manualBrightnessUpHoldTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.manualLightLongPressSeconds * 1_000_000_000))
            guard let self else { return }
            guard !Task.isCancelled else { return }

            self.manualBrightnessUpHoldTask = nil
            self.brightnessMonitor.sample()

            guard self.settings.switchMode == .manual else { return }
            guard self.manualBrightnessKeyMonitoringEnabled else { return }
            guard Self.appearance(forManualBrightness: self.brightnessMonitor.brightness) == .light else { return }

            self.applyAppearance(.light, reason: "Held Brightness Up while display brightness was already at or near maximum.")
        }
    }

    private func cancelManualBrightnessUpHold() {
        manualBrightnessUpHoldTask?.cancel()
        manualBrightnessUpHoldTask = nil
    }

    private func resetManualBrightnessKeyState() {
        manualBrightnessUpIsPressed = false
        manualBrightnessRequiresReleaseAfterMax = false
        manualBrightnessWasNearMax = false
        cancelManualBrightnessUpHold()
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