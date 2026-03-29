import Foundation

enum SystemAppearance: String {
    case light
    case dark

    var displayName: String {
        switch self {
        case .light:
            return "Light"
        case .dark:
            return "Dark"
        }
    }

    var appleScriptBoolean: String {
        switch self {
        case .light:
            return "false"
        case .dark:
            return "true"
        }
    }
}

enum AppearanceControllerError: LocalizedError {
    case scriptExecutionFailed(String)
    case invalidScriptResponse(String)

    var errorDescription: String? {
        switch self {
        case let .scriptExecutionFailed(message):
            return "osascript failed: \(message)"
        case let .invalidScriptResponse(message):
            return "Unexpected appearance response: \(message)"
        }
    }
}

final class AppearanceController {
    func currentAppearance() throws -> SystemAppearance {
        let response = try runAppleScript(lines: [
            "tell application \"System Events\"",
            "tell appearance preferences",
            "get dark mode",
            "end tell",
            "end tell",
        ])

        switch response.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "true":
            return .dark
        case "false":
            return .light
        default:
            throw AppearanceControllerError.invalidScriptResponse(response)
        }
    }

    func setAppearance(_ appearance: SystemAppearance) throws {
        _ = try runAppleScript(lines: [
            "tell application \"System Events\"",
            "tell appearance preferences",
            "set dark mode to \(appearance.appleScriptBoolean)",
            "end tell",
            "end tell",
        ])
    }

    private func runAppleScript(lines: [String]) throws -> String {
        let process = Process()
        let outputPipe = Pipe()
        let errorPipe = Pipe()

        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = lines.flatMap { ["-e", $0] }
        process.standardOutput = outputPipe
        process.standardError = errorPipe

        try process.run()
        process.waitUntilExit()

        let output = String(decoding: outputPipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        let errorOutput = String(decoding: errorPipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)

        guard process.terminationStatus == 0 else {
            throw AppearanceControllerError.scriptExecutionFailed(errorOutput.isEmpty ? output : errorOutput)
        }

        return output
    }
}