import AppKit
import ApplicationServices

enum BrightnessKeyDirection: Sendable {
    case up
    case down
}

enum BrightnessKeyPhase: Sendable {
    case down
    case up
}

struct BrightnessKeyEvent: Sendable {
    let direction: BrightnessKeyDirection
    let phase: BrightnessKeyPhase
}

enum BrightnessKeyEventDecoder {
    private static let auxiliaryControlButtonsSubtype = Int16(NX_SUBTYPE_AUX_CONTROL_BUTTONS)
    private static let keyDownState = 0xA
    private static let keyUpState = 0xB
    private static let brightnessDownFunctionKey = UnicodeScalar(NSF1FunctionKey)
    private static let brightnessUpFunctionKey = UnicodeScalar(NSF2FunctionKey)

    static func decode(_ event: NSEvent) -> BrightnessKeyEvent? {
        switch event.type {
        case .systemDefined:
            return Self.event(forSystemDefinedSubtype: event.subtype.rawValue, data1: event.data1)
        case .keyDown:
            return Self.event(
                forFunctionKeyCharacter: event.charactersIgnoringModifiers?.unicodeScalars.first,
                phase: .down
            )
        case .keyUp:
            return Self.event(
                forFunctionKeyCharacter: event.charactersIgnoringModifiers?.unicodeScalars.first,
                phase: .up
            )
        default:
            return nil
        }
    }

    static func event(forSystemDefinedSubtype subtype: Int16, data1: Int) -> BrightnessKeyEvent? {
        guard subtype == auxiliaryControlButtonsSubtype else { return nil }

        let payload = UInt32(truncatingIfNeeded: data1)
        let keyState = Int((payload & 0x0000_FF00) >> 8)
        let phase: BrightnessKeyPhase
        switch keyState {
        case keyDownState:
            phase = .down
        case keyUpState:
            phase = .up
        default:
            return nil
        }

        let keyType = Int((payload & 0xFFFF_0000) >> 16)
        switch keyType {
        case Int(NX_KEYTYPE_BRIGHTNESS_UP):
            return BrightnessKeyEvent(direction: .up, phase: phase)
        case Int(NX_KEYTYPE_BRIGHTNESS_DOWN):
            return BrightnessKeyEvent(direction: .down, phase: phase)
        default:
            return nil
        }
    }

    static func event(forFunctionKeyCharacter character: UnicodeScalar?, phase: BrightnessKeyPhase) -> BrightnessKeyEvent? {
        switch character {
        case brightnessUpFunctionKey:
            return BrightnessKeyEvent(direction: .up, phase: phase)
        case brightnessDownFunctionKey:
            return BrightnessKeyEvent(direction: .down, phase: phase)
        default:
            return nil
        }
    }
}

@MainActor
final class BrightnessKeyMonitor {
    enum StartResult {
        case active
        case permissionRequired
    }

    private let onBrightnessKeyEvent: (BrightnessKeyEvent) -> Void

    private var globalMonitor: Any?
    private var localMonitor: Any?
    private var didPromptForTrust = false

    init(onBrightnessKeyEvent: @escaping (BrightnessKeyEvent) -> Void) {
        self.onBrightnessKeyEvent = onBrightnessKeyEvent
    }

    func start(promptForPermission: Bool) -> StartResult {
        guard hasAccessibilityTrust(promptForPermission: promptForPermission) else {
            stop()
            return .permissionRequired
        }

        guard globalMonitor == nil, localMonitor == nil else {
            return .active
        }

        let mask = NSEvent.EventTypeMask.systemDefined.union(.keyDown).union(.keyUp)

        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] event in
            Task { @MainActor in
                self?.handle(event)
            }
        }

        localMonitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            self?.handle(event)
            return event
        }

        return .active
    }

    func stop() {
        if let globalMonitor {
            NSEvent.removeMonitor(globalMonitor)
            self.globalMonitor = nil
        }

        if let localMonitor {
            NSEvent.removeMonitor(localMonitor)
            self.localMonitor = nil
        }
    }

    private func handle(_ event: NSEvent) {
        guard let keyEvent = BrightnessKeyEventDecoder.decode(event) else { return }
        onBrightnessKeyEvent(keyEvent)
    }

    private func hasAccessibilityTrust(promptForPermission: Bool) -> Bool {
        guard promptForPermission else {
            return AXIsProcessTrusted()
        }

        if didPromptForTrust {
            return AXIsProcessTrusted()
        }

        didPromptForTrust = true
        let options = [
            "AXTrustedCheckOptionPrompt": true,
        ] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }
}