import AppKit
import Combine

@MainActor
final class StatusBarController: NSObject {
    private let settings: SettingsStore
    private let monitor: AmbientLightMonitor
    private let engine: AutoSwitchEngine
    private let onOpenSettings: () -> Void

    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()

    private let luxItem = NSMenuItem()
    private let sourceItem = NSMenuItem()
    private let appearanceItem = NSMenuItem()
    private let autoToggleItem = NSMenuItem()
    private let thresholdItem = NSMenuItem()
    private let messageItem = NSMenuItem()

    private var cancellables = Set<AnyCancellable>()

    init(settings: SettingsStore, monitor: AmbientLightMonitor, engine: AutoSwitchEngine, onOpenSettings: @escaping () -> Void) {
        self.settings = settings
        self.monitor = monitor
        self.engine = engine
        self.onOpenSettings = onOpenSettings

        super.init()

        configureMenu()
        bindState()
        updatePresentation()
    }

    private func configureMenu() {
        luxItem.isEnabled = false
        sourceItem.isEnabled = false
        appearanceItem.isEnabled = false
        thresholdItem.isEnabled = false
        messageItem.isEnabled = false

        autoToggleItem.target = self
        autoToggleItem.action = #selector(toggleAutomaticSwitching)

        let sampleItem = NSMenuItem(title: "Sample Now", action: #selector(sampleNow), keyEquivalent: "r")
        sampleItem.target = self

        let lightItem = NSMenuItem(title: "Switch Light", action: #selector(switchLight), keyEquivalent: "l")
        lightItem.target = self

        let darkItem = NSMenuItem(title: "Switch Dark", action: #selector(switchDark), keyEquivalent: "d")
        darkItem.target = self

        let settingsItem = NSMenuItem(title: "Open Settings", action: #selector(openSettings), keyEquivalent: ",")
        settingsItem.target = self

        let quitItem = NSMenuItem(title: "Quit", action: #selector(quit), keyEquivalent: "q")
        quitItem.target = self

        menu.items = [
            luxItem,
            sourceItem,
            appearanceItem,
            NSMenuItem.separator(),
            autoToggleItem,
            thresholdItem,
            sampleItem,
            lightItem,
            darkItem,
            NSMenuItem.separator(),
            settingsItem,
            messageItem,
            NSMenuItem.separator(),
            quitItem,
        ]

        statusItem.menu = menu
    }

    private func bindState() {
        monitor.$lastReadingLux
            .sink { [weak self] _ in self?.updatePresentation() }
            .store(in: &cancellables)

        monitor.$source
            .sink { [weak self] _ in self?.updatePresentation() }
            .store(in: &cancellables)

        settings.$automationEnabled
            .sink { [weak self] _ in self?.updatePresentation() }
            .store(in: &cancellables)

        settings.$darkThresholdLux
            .sink { [weak self] _ in self?.updatePresentation() }
            .store(in: &cancellables)

        settings.$lightThresholdLux
            .sink { [weak self] _ in self?.updatePresentation() }
            .store(in: &cancellables)

        engine.$lastKnownAppearance
            .sink { [weak self] _ in self?.updatePresentation() }
            .store(in: &cancellables)

        engine.$lastActionDescription
            .sink { [weak self] _ in self?.updatePresentation() }
            .store(in: &cancellables)

        engine.$lastError
            .sink { [weak self] _ in self?.updatePresentation() }
            .store(in: &cancellables)
    }

    private func updatePresentation() {
        luxItem.title = "Ambient light: \(monitor.lastReadingLux.formattedLux)"
        sourceItem.title = "Sensor path: \(monitor.source.rawValue)"
        appearanceItem.title = "Appearance: \(engine.lastKnownAppearance?.displayName ?? "Unknown")"
        autoToggleItem.title = settings.automationEnabled ? "Disable Automatic Switching" : "Enable Automatic Switching"
        autoToggleItem.state = settings.automationEnabled ? .on : .off
        thresholdItem.title = "Dark <= \(settings.effectiveDarkThresholdLux.formattedLux) / Light >= \(settings.effectiveLightThresholdLux.formattedLux)"
        messageItem.title = engine.lastError ?? engine.lastActionDescription

        guard let button = statusItem.button else { return }

        button.image = NSImage(systemSymbolName: symbolName, accessibilityDescription: "autoDarkMode")
        button.toolTip = "Ambient light: \(monitor.lastReadingLux.formattedLux)"
    }

    private var symbolName: String {
        if !monitor.sensorAvailable {
            return "exclamationmark.triangle"
        }

        switch engine.lastKnownAppearance {
        case .dark:
            return "moon.fill"
        case .light:
            return "sun.max.fill"
        case nil:
            return "lightspectrum.horizontal"
        }
    }

    @objc private func toggleAutomaticSwitching() {
        settings.automationEnabled.toggle()
    }

    @objc private func sampleNow() {
        monitor.sample()
    }

    @objc private func switchLight() {
        engine.forceSetAppearance(.light)
    }

    @objc private func switchDark() {
        engine.forceSetAppearance(.dark)
    }

    @objc private func openSettings() {
        onOpenSettings()
    }

    @objc private func quit() {
        NSApplication.shared.terminate(nil)
    }
}