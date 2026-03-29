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

    @Test("system-defined brightness up key decodes correctly")
    func systemDefinedBrightnessUpDecodes() {
        let data1 = Int((UInt32(NX_KEYTYPE_BRIGHTNESS_UP) << 16) | 0x0A00)
        #expect(
            BrightnessKeyEventDecoder.event(
                forSystemDefinedSubtype: Int16(NX_SUBTYPE_AUX_CONTROL_BUTTONS),
                data1: data1
            )?.direction == .up
        )
        #expect(
            BrightnessKeyEventDecoder.event(
                forSystemDefinedSubtype: Int16(NX_SUBTYPE_AUX_CONTROL_BUTTONS),
                data1: data1
            )?.phase == .down
        )
    }

    @Test("system-defined brightness down key decodes correctly")
    func systemDefinedBrightnessDownDecodes() {
        let data1 = Int((UInt32(NX_KEYTYPE_BRIGHTNESS_DOWN) << 16) | 0x0A00)
        #expect(
            BrightnessKeyEventDecoder.event(
                forSystemDefinedSubtype: Int16(NX_SUBTYPE_AUX_CONTROL_BUTTONS),
                data1: data1
            )?.direction == .down
        )
    }

    @Test("brightness key decoder maps key-up payloads to release events")
    func brightnessKeyDecoderMapsKeyUpPayloads() {
        let data1 = Int((UInt32(NX_KEYTYPE_BRIGHTNESS_UP) << 16) | 0x0B00)
        #expect(
            BrightnessKeyEventDecoder.event(
                forSystemDefinedSubtype: Int16(NX_SUBTYPE_AUX_CONTROL_BUTTONS),
                data1: data1
            )?.phase == .up
        )
    }

    @Test("function-key fallback maps F1 and F2")
    func functionKeyFallbackDecodes() {
        #expect(BrightnessKeyEventDecoder.event(forFunctionKeyCharacter: UnicodeScalar(NSF1FunctionKey), phase: .down)?.direction == .down)
        #expect(BrightnessKeyEventDecoder.event(forFunctionKeyCharacter: UnicodeScalar(NSF2FunctionKey), phase: .down)?.direction == .up)
    }

    @Test("manual long press only arms on brightness up key down at max")
    func manualBrightnessLongPressArming() {
        #expect(
            AutoSwitchEngine.shouldArmManualBrightnessLongPress(
                direction: .up,
                brightnessAfterSampling: 1.0,
                phase: .down,
                keyMonitoringEnabled: true,
                requiresReleaseAfterMax: false
            )
        )

        #expect(
            !AutoSwitchEngine.shouldArmManualBrightnessLongPress(
                direction: .up,
                brightnessAfterSampling: 1.0,
                phase: .up,
                keyMonitoringEnabled: true,
                requiresReleaseAfterMax: false
            )
        )

        #expect(
            !AutoSwitchEngine.shouldArmManualBrightnessLongPress(
                direction: .down,
                brightnessAfterSampling: 1.0,
                phase: .down,
                keyMonitoringEnabled: true,
                requiresReleaseAfterMax: false
            )
        )

        #expect(
            !AutoSwitchEngine.shouldArmManualBrightnessLongPress(
                direction: .up,
                brightnessAfterSampling: 0.98,
                phase: .down,
                keyMonitoringEnabled: true,
                requiresReleaseAfterMax: false
            )
        )

        #expect(
            !AutoSwitchEngine.shouldArmManualBrightnessLongPress(
                direction: .up,
                brightnessAfterSampling: 1.0,
                phase: .down,
                keyMonitoringEnabled: false,
                requiresReleaseAfterMax: false
            )
        )

        #expect(
            !AutoSwitchEngine.shouldArmManualBrightnessLongPress(
                direction: .up,
                brightnessAfterSampling: 1.0,
                phase: .down,
                keyMonitoringEnabled: true,
                requiresReleaseAfterMax: true
            )
        )
    }

    @Test("manual mode only requires release when max is first reached while key is already held")
    func manualBrightnessReleaseRequirementOnlyOnTransitionToMax() {
        #expect(
            AutoSwitchEngine.shouldRequireReleaseAfterReachingManualMax(
                isNearMax: true,
                wasNearMax: false,
                brightnessUpIsPressed: true,
                keyMonitoringEnabled: true
            )
        )

        #expect(
            !AutoSwitchEngine.shouldRequireReleaseAfterReachingManualMax(
                isNearMax: true,
                wasNearMax: true,
                brightnessUpIsPressed: true,
                keyMonitoringEnabled: true
            )
        )

        #expect(
            !AutoSwitchEngine.shouldRequireReleaseAfterReachingManualMax(
                isNearMax: true,
                wasNearMax: false,
                brightnessUpIsPressed: false,
                keyMonitoringEnabled: true
            )
        )

        #expect(
            !AutoSwitchEngine.shouldRequireReleaseAfterReachingManualMax(
                isNearMax: false,
                wasNearMax: false,
                brightnessUpIsPressed: true,
                keyMonitoringEnabled: true
            )
        )
    }
}
