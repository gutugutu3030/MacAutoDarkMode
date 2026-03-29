import Foundation

@MainActor
final class SettingsStore: ObservableObject {
    private enum Keys {
        static let switchMode = "switchMode"
        static let darkThresholdLux = "darkThresholdLux"
        static let lightThresholdLux = "lightThresholdLux"
        static let requiredConsecutiveSamples = "requiredConsecutiveSamples"
        static let cooldownSeconds = "cooldownSeconds"

        static let recommendedDarkThresholdLux = 3000.0
        static let recommendedLightThresholdLux = 12000.0
        static let recommendedRequiredConsecutiveSamples = 3
        static let recommendedCooldownSeconds = 30.0

        static let legacyDarkThresholdLux = 60.0
        static let legacyLightThresholdLux = 600.0
        static let legacyRequiredConsecutiveSamples = 2

        /// 旧バージョンの automationEnabled キー（移行用）
        static let legacyAutomationEnabled = "automationEnabled"
    }

    private let defaults: UserDefaults

    @Published var switchMode: SwitchMode {
        didSet {
            defaults.set(switchMode.rawValue, forKey: Keys.switchMode)
        }
    }

    @Published var darkThresholdLux: Double {
        didSet {
            defaults.set(darkThresholdLux, forKey: Keys.darkThresholdLux)
        }
    }

    @Published var lightThresholdLux: Double {
        didSet {
            defaults.set(lightThresholdLux, forKey: Keys.lightThresholdLux)
        }
    }

    @Published var requiredConsecutiveSamples: Int {
        didSet {
            defaults.set(requiredConsecutiveSamples, forKey: Keys.requiredConsecutiveSamples)
        }
    }

    @Published var cooldownSeconds: TimeInterval {
        didSet {
            defaults.set(cooldownSeconds, forKey: Keys.cooldownSeconds)
        }
    }

    var effectiveDarkThresholdLux: Double {
        min(Self.clampThreshold(darkThresholdLux), effectiveLightThresholdLux)
    }

    var effectiveLightThresholdLux: Double {
        max(Self.clampThreshold(lightThresholdLux), Self.clampThreshold(darkThresholdLux))
    }

    var effectiveRequiredConsecutiveSamples: Int {
        min(max(requiredConsecutiveSamples, 1), 10)
    }

    var effectiveCooldownSeconds: TimeInterval {
        min(max(cooldownSeconds, 5), 300)
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        defaults.register(defaults: [
            Keys.switchMode: SwitchMode.auto.rawValue,
            Keys.darkThresholdLux: Keys.recommendedDarkThresholdLux,
            Keys.lightThresholdLux: Keys.recommendedLightThresholdLux,
            Keys.requiredConsecutiveSamples: Keys.recommendedRequiredConsecutiveSamples,
            Keys.cooldownSeconds: Keys.recommendedCooldownSeconds,
        ])

        Self.migrateLegacyDefaultsIfNeeded(in: defaults)
        Self.migrateAutomationEnabledIfNeeded(in: defaults)

        let storedDarkThreshold = Self.clampThreshold(defaults.double(forKey: Keys.darkThresholdLux))
        let storedLightThreshold = max(Self.clampThreshold(defaults.double(forKey: Keys.lightThresholdLux)), storedDarkThreshold)
        let storedRequiredSamples = min(max(defaults.integer(forKey: Keys.requiredConsecutiveSamples), 1), 10)
        let storedCooldownSeconds = min(max(defaults.double(forKey: Keys.cooldownSeconds), 5), 300)

        switchMode = SwitchMode(rawValue: defaults.string(forKey: Keys.switchMode) ?? "") ?? .auto
        darkThresholdLux = storedDarkThreshold
        lightThresholdLux = storedLightThreshold
        requiredConsecutiveSamples = storedRequiredSamples
        cooldownSeconds = storedCooldownSeconds
    }

    func updateDarkThresholdLux(_ newValue: Double) {
        darkThresholdLux = min(Self.clampThreshold(newValue), effectiveLightThresholdLux)
    }

    func updateLightThresholdLux(_ newValue: Double) {
        lightThresholdLux = max(Self.clampThreshold(newValue), effectiveDarkThresholdLux)
    }

    func updateRequiredConsecutiveSamples(_ newValue: Int) {
        requiredConsecutiveSamples = min(max(newValue, 1), 10)
    }

    func updateCooldownSeconds(_ newValue: TimeInterval) {
        cooldownSeconds = min(max(newValue, 5), 300)
    }

    func useCurrentLuxAsDarkThreshold(_ lux: Double) {
        guard lux >= 0 else { return }
        updateDarkThresholdLux(lux)
    }

    func useCurrentLuxAsLightThreshold(_ lux: Double) {
        guard lux >= 0 else { return }
        updateLightThresholdLux(lux)
    }

    private static func clampThreshold(_ value: Double) -> Double {
        min(max(value, 0), 120000)
    }

    private static func migrateLegacyDefaultsIfNeeded(in defaults: UserDefaults) {
        let storedDarkThreshold = defaults.object(forKey: Keys.darkThresholdLux) as? Double
        let storedLightThreshold = defaults.object(forKey: Keys.lightThresholdLux) as? Double
        let storedRequiredSamples = defaults.object(forKey: Keys.requiredConsecutiveSamples) as? Int

        guard storedDarkThreshold == Keys.legacyDarkThresholdLux,
              storedLightThreshold == Keys.legacyLightThresholdLux,
              storedRequiredSamples == Keys.legacyRequiredConsecutiveSamples else {
            return
        }

        defaults.set(Keys.recommendedDarkThresholdLux, forKey: Keys.darkThresholdLux)
        defaults.set(Keys.recommendedLightThresholdLux, forKey: Keys.lightThresholdLux)
        defaults.set(Keys.recommendedRequiredConsecutiveSamples, forKey: Keys.requiredConsecutiveSamples)
    }

    /// 旧 automationEnabled (Bool) → switchMode (String) への移行
    private static func migrateAutomationEnabledIfNeeded(in defaults: UserDefaults) {
        guard defaults.object(forKey: Keys.switchMode) == nil,
              defaults.object(forKey: Keys.legacyAutomationEnabled) != nil else {
            return
        }

        let wasEnabled = defaults.bool(forKey: Keys.legacyAutomationEnabled)
        let mode: SwitchMode = wasEnabled ? .auto : .off
        defaults.set(mode.rawValue, forKey: Keys.switchMode)
        defaults.removeObject(forKey: Keys.legacyAutomationEnabled)
    }
}