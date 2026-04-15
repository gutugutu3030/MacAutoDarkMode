import AppKit
import Combine

/// メニューバー UI と内部状態の橋渡しを行い、状態変化を 1 つのメニュー表現へ集約する。
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
    private let modeOffItem = NSMenuItem()
    private let modeAutoItem = NSMenuItem()
    private let modeManualItem = NSMenuItem()
    private let thresholdItem = NSMenuItem()
    private let messageItem = NSMenuItem()

    private var cancellables = Set<AnyCancellable>()
    private var updateScheduled = false

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

        modeOffItem.title = "Mode: Off"
        modeOffItem.target = self
        modeOffItem.action = #selector(selectModeOff)

        modeAutoItem.title = "Mode: Auto"
        modeAutoItem.target = self
        modeAutoItem.action = #selector(selectModeAuto)

        modeManualItem.title = "Mode: Manual"
        modeManualItem.target = self
        modeManualItem.action = #selector(selectModeManual)

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
            modeOffItem,
            modeAutoItem,
            modeManualItem,
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

    /// 状態購読を一箇所に集め、UI 更新トリガーを scheduleUpdatePresentation に統一する。
    private func bindState() {
        monitor.$lastReadingLux
            .sink { [weak self] _ in self?.scheduleUpdatePresentation() }
            .store(in: &cancellables)

        monitor.$source
            .sink { [weak self] _ in self?.scheduleUpdatePresentation() }
            .store(in: &cancellables)

        settings.$switchMode
            .sink { [weak self] _ in self?.scheduleUpdatePresentation() }
            .store(in: &cancellables)

        settings.$darkThresholdLux
            .sink { [weak self] _ in self?.scheduleUpdatePresentation() }
            .store(in: &cancellables)

        settings.$lightThresholdLux
            .sink { [weak self] _ in self?.scheduleUpdatePresentation() }
            .store(in: &cancellables)

        engine.$lastKnownAppearance
            .sink { [weak self] _ in self?.scheduleUpdatePresentation() }
            .store(in: &cancellables)

        engine.$lastActionDescription
            .sink { [weak self] _ in self?.scheduleUpdatePresentation() }
            .store(in: &cancellables)

        engine.$lastError
            .sink { [weak self] _ in self?.scheduleUpdatePresentation() }
            .store(in: &cancellables)
    }

    /// 同一RunLoopイテレーション内の複数のプロパティ変更を1回のUI更新にまとめる
    private func scheduleUpdatePresentation() {
        guard !updateScheduled else { return }
        updateScheduled = true
        perform(#selector(flushScheduledPresentationUpdate), with: nil, afterDelay: 0, inModes: [.common])
    }

    /// common run loop modes で延期した UI 更新を MainActor 上で 1 回だけ実行する。
    @objc private func flushScheduledPresentationUpdate() {
        updateScheduled = false
        updatePresentation()
    }

    /// 現在の監視値とモードを、ステータスアイコンとメニュー項目へ反映する。
    private func updatePresentation() {
        luxItem.title = "Ambient light: \(monitor.lastReadingLux.formattedLux)"
        sourceItem.title = "Sensor path: \(monitor.source.rawValue)"
        appearanceItem.title = "Appearance: \(engine.lastKnownAppearance?.displayName ?? "Unknown")"

        modeOffItem.state = settings.switchMode == .off ? .on : .off
        modeAutoItem.state = settings.switchMode == .auto ? .on : .off
        modeManualItem.state = settings.switchMode == .manual ? .on : .off

        thresholdItem.isHidden = settings.switchMode != .auto
        thresholdItem.title = "Dark <= \(settings.effectiveDarkThresholdLux.formattedLux) / Light >= \(settings.effectiveLightThresholdLux.formattedLux)"
        messageItem.title = engine.lastError ?? engine.lastActionDescription

        guard let button = statusItem.button else { return }

        button.image = NSImage(systemSymbolName: symbolName, accessibilityDescription: "autoDarkMode")
        button.toolTip = "autoDarkMode (\(settings.switchMode.displayName))"
    }

    private var symbolName: String {
        if settings.switchMode == .off {
            return "lightspectrum.horizontal"
        }

        if settings.switchMode == .auto, !monitor.sensorAvailable {
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

    @objc private func selectModeOff() {
        settings.switchMode = .off
    }

    @objc private func selectModeAuto() {
        settings.switchMode = .auto
    }

    @objc private func selectModeManual() {
        settings.switchMode = .manual
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