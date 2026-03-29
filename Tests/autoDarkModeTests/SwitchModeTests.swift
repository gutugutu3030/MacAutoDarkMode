import Foundation
import Testing

@testable import autoDarkMode

@Suite("SwitchMode Tests")
struct SwitchModeTests {
    @Test("raw values are stable for UserDefaults persistence")
    func rawValues() {
        #expect(SwitchMode.off.rawValue == "off")
        #expect(SwitchMode.auto.rawValue == "auto")
        #expect(SwitchMode.manual.rawValue == "manual")
    }

    @Test("round-trip through rawValue")
    func rawValueRoundTrip() {
        for mode in SwitchMode.allCases {
            #expect(SwitchMode(rawValue: mode.rawValue) == mode)
        }
    }

    @Test("invalid rawValue returns nil")
    func invalidRawValue() {
        #expect(SwitchMode(rawValue: "unknown") == nil)
        #expect(SwitchMode(rawValue: "") == nil)
    }

    @Test("allCases contains exactly three modes")
    func allCases() {
        #expect(SwitchMode.allCases.count == 3)
        #expect(SwitchMode.allCases.contains(.off))
        #expect(SwitchMode.allCases.contains(.auto))
        #expect(SwitchMode.allCases.contains(.manual))
    }

    @Test("displayName is non-empty for all modes")
    func displayNames() {
        for mode in SwitchMode.allCases {
            #expect(!mode.displayName.isEmpty)
        }
    }

    @Test("manual mode treats near-maximum brightness as light")
    func manualBrightnessNearMaximumUsesLight() {
        #expect(AutoSwitchEngine.appearance(forManualBrightness: 1.0) == .light)
        #expect(AutoSwitchEngine.appearance(forManualBrightness: 0.995) == .light)
    }

    @Test("manual mode keeps lower brightness as dark")
    func manualBrightnessBelowThresholdUsesDark() {
        #expect(AutoSwitchEngine.appearance(forManualBrightness: 0.98) == .dark)
        #expect(AutoSwitchEngine.appearance(forManualBrightness: 0.0) == .dark)
    }
}
