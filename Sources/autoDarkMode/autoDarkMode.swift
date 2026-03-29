import AppKit

@main
enum AutoDarkModeMain {
    static func main() {
        let arguments = Array(CommandLine.arguments.dropFirst())

        if CalibrationCLI.canHandle(arguments: arguments) {
            exit(CalibrationCLI.run(arguments: arguments))
        }

        let application = NSApplication.shared
        let delegate = AppDelegate()

        application.setActivationPolicy(.accessory)
        application.delegate = delegate
        application.run()
    }
}
