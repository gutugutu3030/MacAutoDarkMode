import Foundation

@MainActor
final class LaunchAtLoginManager: ObservableObject {
    private enum Constants {
        static let launchAgentLabel = "com.gutugutu3030.autoDarkMode"
    }

    @Published private(set) var isEnabled = false
    @Published private(set) var statusMessage = "Launch at login is disabled."

    private let fileManager: FileManager

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
        refresh()
    }

    var canManageLaunchAgent: Bool {
        executableURL != nil && Bundle.main.bundleURL.pathExtension == "app"
    }

    var supportMessage: String {
        if canManageLaunchAgent {
            return statusMessage
        }

        return "Launch at login is only available when running from the bundled .app. Build with ./Scripts/build-app.sh and launch the app bundle from dist."
    }

    func setEnabled(_ enabled: Bool) {
        guard canManageLaunchAgent else {
            refresh()
            return
        }

        do {
            if enabled {
                try writeLaunchAgent()
                statusMessage = "Launch at login enabled. The new setting takes effect on the next login."
            } else {
                try removeLaunchAgent()
                statusMessage = "Launch at login disabled."
            }

            refresh()
        } catch {
            statusMessage = error.localizedDescription
            refresh()
        }
    }

    func refresh() {
        guard let executableURL else {
            isEnabled = false
            return
        }

        guard let plist = NSDictionary(contentsOf: launchAgentURL) as? [String: Any] else {
            isEnabled = false
            if canManageLaunchAgent {
                statusMessage = "Launch at login is disabled."
            }
            return
        }

        let arguments = plist["ProgramArguments"] as? [String]
        let configuredExecutable = arguments?.first

        if configuredExecutable == executableURL.path {
            isEnabled = true
            statusMessage = "Launch at login enabled for this app bundle."
        } else {
            isEnabled = false
            statusMessage = "A launch agent exists but points to a different app bundle. Re-enable the checkbox to update it."
        }
    }

    private var executableURL: URL? {
        Bundle.main.executableURL
    }

    private var launchAgentURL: URL {
        fileManager.homeDirectoryForCurrentUser
            .appendingPathComponent("Library", isDirectory: true)
            .appendingPathComponent("LaunchAgents", isDirectory: true)
            .appendingPathComponent("\(Constants.launchAgentLabel).plist", isDirectory: false)
    }

    private func writeLaunchAgent() throws {
        guard let executableURL else {
            throw LaunchAtLoginError.invalidBundle
        }

        let parentDirectory = launchAgentURL.deletingLastPathComponent()
        try fileManager.createDirectory(at: parentDirectory, withIntermediateDirectories: true)

        let plist: [String: Any] = [
            "Label": Constants.launchAgentLabel,
            "ProgramArguments": [executableURL.path],
            "RunAtLoad": true,
            "KeepAlive": false,
            "ProcessType": "Interactive",
            "WorkingDirectory": executableURL.deletingLastPathComponent().path,
        ]

        let data = try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
        try data.write(to: launchAgentURL, options: .atomic)
    }

    private func removeLaunchAgent() throws {
        guard fileManager.fileExists(atPath: launchAgentURL.path) else { return }
        try fileManager.removeItem(at: launchAgentURL)
    }
}

private enum LaunchAtLoginError: LocalizedError {
    case invalidBundle

    var errorDescription: String? {
        switch self {
        case .invalidBundle:
            return "Launch at login requires the app to be running from a bundled .app."
        }
    }
}