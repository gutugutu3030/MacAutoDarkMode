import AppKit
import Combine

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let settings = SettingsStore()
    private let monitor = AmbientLightMonitor()
    private let brightnessMonitor = ScreenBrightnessMonitor()
    private let appearanceController = AppearanceController()
    private let launchAtLoginManager = LaunchAtLoginManager()
    private lazy var engine = AutoSwitchEngine(
        settings: settings,
        monitor: monitor,
        brightnessMonitor: brightnessMonitor,
        appearanceController: appearanceController
    )
    private lazy var brightnessKeyMonitor = BrightnessKeyMonitor { [weak self] event in
        self?.engine.handleManualBrightnessKeyEvent(event)
    }

    private var statusBarController: StatusBarController?
    private var settingsWindowController: SettingsWindowController?
    private var cancellables = Set<AnyCancellable>()

    func applicationDidFinishLaunching(_ notification: Notification) {
        let settingsWindowController = SettingsWindowController(
            settings: settings,
            monitor: monitor,
            engine: engine,
            launchAtLoginManager: launchAtLoginManager
        )

        self.settingsWindowController = settingsWindowController
        self.statusBarController = StatusBarController(
            settings: settings,
            monitor: monitor,
            engine: engine,
            onOpenSettings: { [weak settingsWindowController] in
                settingsWindowController?.showWindow(nil)
            }
        )

        bindBrightnessKeyMonitoring()
        engine.start()
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        guard settings.switchMode == .manual else { return }
        updateBrightnessKeyMonitoring(for: .manual, promptForPermission: false)
    }

    func applicationWillTerminate(_ notification: Notification) {
        brightnessKeyMonitor.stop()
        monitor.stop()
        brightnessMonitor.stop()
    }

    private func bindBrightnessKeyMonitoring() {
        settings.$switchMode
            .sink { [weak self] mode in
                self?.updateBrightnessKeyMonitoring(for: mode, promptForPermission: true)
            }
            .store(in: &cancellables)
    }

    private func updateBrightnessKeyMonitoring(for mode: SwitchMode, promptForPermission: Bool) {
        switch mode {
        case .manual:
            switch brightnessKeyMonitor.start(promptForPermission: promptForPermission) {
            case .active:
                engine.setManualBrightnessKeyMonitoringEnabled(true)
                break
            case .permissionRequired:
                engine.setManualBrightnessKeyMonitoringEnabled(false)
                engine.reportManualBrightnessKeyMonitoringPermissionRequired()
            }
        case .off, .auto:
            engine.setManualBrightnessKeyMonitoringEnabled(false)
            brightnessKeyMonitor.stop()
        }
    }
}