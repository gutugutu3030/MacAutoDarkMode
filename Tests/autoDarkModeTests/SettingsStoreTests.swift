import Foundation
import Testing

@testable import autoDarkMode

@Suite("SettingsStore Tests")
@MainActor
struct SettingsStoreTests {
    /// テスト用に独立した UserDefaults を返す
    private func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "test.autoDarkMode.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    @Test("default switchMode is auto")
    func defaultSwitchMode() {
        let defaults = makeIsolatedDefaults()
        let store = SettingsStore(defaults: defaults)
        #expect(store.switchMode == .auto)
    }

    @Test("switchMode persists to UserDefaults")
    func switchModePersistence() {
        let defaults = makeIsolatedDefaults()
        let store = SettingsStore(defaults: defaults)

        store.switchMode = .manual
        #expect(defaults.string(forKey: "switchMode") == "manual")

        store.switchMode = .off
        #expect(defaults.string(forKey: "switchMode") == "off")

        store.switchMode = .auto
        #expect(defaults.string(forKey: "switchMode") == "auto")
    }

    @Test("switchMode restores from UserDefaults on init")
    func switchModeRestore() {
        let defaults = makeIsolatedDefaults()
        defaults.set("manual", forKey: "switchMode")

        let store = SettingsStore(defaults: defaults)
        #expect(store.switchMode == .manual)
    }

    @Test("invalid switchMode string falls back to auto")
    func invalidSwitchModeFallback() {
        let defaults = makeIsolatedDefaults()
        defaults.set("invalid", forKey: "switchMode")

        let store = SettingsStore(defaults: defaults)
        #expect(store.switchMode == .auto)
    }

    @Test("migrates legacy automationEnabled=true to auto")
    func migrateLegacyEnabledTrue() {
        let defaults = makeIsolatedDefaults()
        defaults.set(true, forKey: "automationEnabled")
        // switchMode キーが存在しない状態
        defaults.removeObject(forKey: "switchMode")

        let store = SettingsStore(defaults: defaults)
        #expect(store.switchMode == .auto)
        // 移行後、旧キーは削除される
        #expect(defaults.object(forKey: "automationEnabled") == nil)
    }

    @Test("migrates legacy automationEnabled=false to off")
    func migrateLegacyEnabledFalse() {
        let defaults = makeIsolatedDefaults()
        defaults.set(false, forKey: "automationEnabled")
        defaults.removeObject(forKey: "switchMode")

        let store = SettingsStore(defaults: defaults)
        #expect(store.switchMode == .off)
        #expect(defaults.object(forKey: "automationEnabled") == nil)
    }

    @Test("does not migrate when switchMode already exists")
    func noMigrationWhenSwitchModeExists() {
        let defaults = makeIsolatedDefaults()
        defaults.set("manual", forKey: "switchMode")
        defaults.set(false, forKey: "automationEnabled")

        let store = SettingsStore(defaults: defaults)
        #expect(store.switchMode == .manual)
        // automationEnabled は残ったまま（移行されない）
        #expect(defaults.object(forKey: "automationEnabled") != nil)
    }

    @Test("threshold defaults are reasonable")
    func thresholdDefaults() {
        let defaults = makeIsolatedDefaults()
        let store = SettingsStore(defaults: defaults)
        #expect(store.effectiveDarkThresholdLux > 0)
        #expect(store.effectiveLightThresholdLux > store.effectiveDarkThresholdLux)
        #expect(store.effectiveRequiredConsecutiveSamples >= 1)
        #expect(store.effectiveCooldownSeconds >= 5)
    }

    @Test("threshold updates sync clamped values from KMP logic")
    func thresholdUpdateClampSync() {
        let defaults = makeIsolatedDefaults()
        let store = SettingsStore(defaults: defaults)

        store.updateLightThresholdLux(5000)
        store.updateDarkThresholdLux(9000)

        #expect(store.darkThresholdLux == 5000)
        #expect(defaults.double(forKey: "darkThresholdLux") == 5000)
        #expect(store.effectiveLightThresholdLux == 5000)
    }

    @Test("cooldown and sample updates sync clamped values from KMP logic")
    func cooldownAndSampleClampSync() {
        let defaults = makeIsolatedDefaults()
        let store = SettingsStore(defaults: defaults)

        store.updateCooldownSeconds(1)
        store.updateRequiredConsecutiveSamples(99)

        #expect(store.cooldownSeconds == 5)
        #expect(store.requiredConsecutiveSamples == 10)
        #expect(defaults.double(forKey: "cooldownSeconds") == 5)
        #expect(defaults.integer(forKey: "requiredConsecutiveSamples") == 10)
    }

    @Test("direct property writes stay synchronized without state flow subscription")
    func directPropertyWritesSynchronizeImmediately() {
        let defaults = makeIsolatedDefaults()
        let store = SettingsStore(defaults: defaults)

        store.lightThresholdLux = 4000
        store.darkThresholdLux = 9000
        store.cooldownSeconds = 1
        store.requiredConsecutiveSamples = 99

        #expect(store.darkThresholdLux == 4000)
        #expect(store.lightThresholdLux == 4000)
        #expect(store.cooldownSeconds == 5)
        #expect(store.requiredConsecutiveSamples == 10)
        #expect(defaults.double(forKey: "darkThresholdLux") == 4000)
        #expect(defaults.double(forKey: "lightThresholdLux") == 4000)
        #expect(defaults.double(forKey: "cooldownSeconds") == 5)
        #expect(defaults.integer(forKey: "requiredConsecutiveSamples") == 10)
    }
}
