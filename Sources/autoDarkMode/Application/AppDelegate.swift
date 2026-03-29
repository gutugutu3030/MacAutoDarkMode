import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let settings = SettingsStore()
    private let monitor = AmbientLightMonitor()
    private let appearanceController = AppearanceController()
    private let launchAtLoginManager = LaunchAtLoginManager()
    private lazy var engine = AutoSwitchEngine(
        settings: settings,
        monitor: monitor,
        appearanceController: appearanceController
    )

    private var statusBarController: StatusBarController?
    private var settingsWindowController: SettingsWindowController?

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

        engine.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        monitor.stop()
    }
}