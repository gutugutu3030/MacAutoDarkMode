import AutoDarkModeKMP
import Foundation

/// 永続設定の公開 API は Swift 側に維持しつつ、保存・移行・clamp は KMP logic へ委譲する。
/// 現在のアプリでは Swift wrapper が唯一の mutation 境界なので、KMP state flow は購読せず
/// 各更新直後に scalar 値を MainActor 上の `@Published` へ同期する。
@MainActor
final class SettingsStore: ObservableObject {
    private let logic: AutoDarkModeKMP.SettingsStoreLogic
    private var isSynchronizingFromLogic = false

    @Published var switchMode: SwitchMode {
        didSet {
            guard !isSynchronizingFromLogic else { return }
            logic.setSwitchMode(mode: switchMode.kmpSwitchMode)
            synchronizeFromLogic()
        }
    }

    @Published var darkThresholdLux: Double {
        didSet {
            guard !isSynchronizingFromLogic else { return }
            logic.updateDarkThresholdLux(newValue: darkThresholdLux)
            synchronizeFromLogic()
        }
    }

    @Published var lightThresholdLux: Double {
        didSet {
            guard !isSynchronizingFromLogic else { return }
            logic.updateLightThresholdLux(newValue: lightThresholdLux)
            synchronizeFromLogic()
        }
    }

    @Published var requiredConsecutiveSamples: Int {
        didSet {
            guard !isSynchronizingFromLogic else { return }
            logic.updateRequiredConsecutiveSamples(newValue: Int32(requiredConsecutiveSamples))
            synchronizeFromLogic()
        }
    }

    @Published var cooldownSeconds: TimeInterval {
        didSet {
            guard !isSynchronizingFromLogic else { return }
            logic.updateCooldownSeconds(newValue: cooldownSeconds)
            synchronizeFromLogic()
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
        let keyValueStore = AutoDarkModeKMP.NSUserDefaultsKeyValueStore(defaults: defaults)
        let logic = AutoDarkModeKMP.SettingsStoreLogic(store: keyValueStore)
        self.logic = logic

        switchMode = SwitchMode(kmpSwitchMode: logic.switchMode)
        darkThresholdLux = logic.darkThresholdLux
        lightThresholdLux = logic.lightThresholdLux
        requiredConsecutiveSamples = Int(logic.requiredConsecutiveSamples)
        cooldownSeconds = logic.cooldownSeconds
    }

    func updateDarkThresholdLux(_ newValue: Double) {
        logic.updateDarkThresholdLux(newValue: newValue)
        synchronizeFromLogic()
    }

    func updateLightThresholdLux(_ newValue: Double) {
        logic.updateLightThresholdLux(newValue: newValue)
        synchronizeFromLogic()
    }

    func updateRequiredConsecutiveSamples(_ newValue: Int) {
        logic.updateRequiredConsecutiveSamples(newValue: Int32(newValue))
        synchronizeFromLogic()
    }

    func updateCooldownSeconds(_ newValue: TimeInterval) {
        logic.updateCooldownSeconds(newValue: newValue)
        synchronizeFromLogic()
    }

    func useCurrentLuxAsDarkThreshold(_ lux: Double) {
        guard lux >= 0 else { return }
        updateDarkThresholdLux(lux)
    }

    func useCurrentLuxAsLightThreshold(_ lux: Double) {
        guard lux >= 0 else { return }
        logic.useCurrentLuxAsLightThreshold(lux: lux)
        synchronizeFromLogic()
    }

    private static func clampThreshold(_ value: Double) -> Double {
        min(max(value, 0), 120000)
    }

    /// 現行ハイブリッド構成では Swift が唯一の書き込み元なので、state flow bridge を挟まず即時同期で十分。
    /// Kotlin 側に非同期 writer が増えた時点で、この同期戦略は再評価する。
    private func synchronizeFromLogic() {
        isSynchronizingFromLogic = true
        defer { isSynchronizingFromLogic = false }

        switchMode = SwitchMode(kmpSwitchMode: logic.switchMode)
        darkThresholdLux = logic.darkThresholdLux
        lightThresholdLux = logic.lightThresholdLux
        requiredConsecutiveSamples = Int(logic.requiredConsecutiveSamples)
        cooldownSeconds = logic.cooldownSeconds
    }
}

private extension SwitchMode {
    init(kmpSwitchMode: AutoDarkModeKMP.SwitchMode) {
        switch kmpSwitchMode {
        case .off:
            self = .off
        case .auto_:
            self = .auto
        default:
            self = .manual
        }
    }

    var kmpSwitchMode: AutoDarkModeKMP.SwitchMode {
        switch self {
        case .off:
            return .off
        case .auto:
            return .auto_
        case .manual:
            return .manual
        }
    }
}
