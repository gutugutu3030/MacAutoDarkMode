import ALSBridge
import Foundation

enum CalibrationCLI {
    static func canHandle(arguments: [String]) -> Bool {
        guard let command = arguments.first else { return false }
        return command == "sample" || command == "watch"
    }

    static func run(arguments: [String]) -> Int32 {
        let command = arguments.first ?? "sample"
        let options = parseOptions(Array(arguments.dropFirst()))
        let sampleCount = command == "watch" ? Int.max : options.count

        let reader = ALSAmbientLightReader()
        guard reader.isSensorAvailable else {
            FileHandle.standardError.write(Data("Ambient light sensor is unavailable on this Mac.\n".utf8))
            return 1
        }

        let countLabel = command == "watch" ? "∞" : String(sampleCount)
        let intervalLabel = String(format: "%.1fs", options.interval)

        print("Sampling ambient light sensor using \(command) mode.")
        print("count=\(countLabel) interval=\(intervalLabel)")

        var samples: [Double] = []
        var iteration = 0

        while iteration < sampleCount {
            iteration += 1

            if let reading = reader.currentReading() {
                samples.append(reading.lux)
                let timestamp = ISO8601DateFormatter().string(from: Date())
                let source = calibrationSourceName(reading.source)
                print("[\(timestamp)] \(source): \(reading.lux.formattedLux)")
            } else {
                let timestamp = ISO8601DateFormatter().string(from: Date())
                print("[\(timestamp)] unavailable")
            }

            if iteration < sampleCount {
                Thread.sleep(forTimeInterval: options.interval)
            }
        }

        if !samples.isEmpty {
            printSummary(samples)
        }

        return 0
    }

    private static func calibrationSourceName(_ source: ALSAmbientLightSource) -> String {
        switch source.rawValue {
        case 1:
            return "hid"
        case 2:
            return "legacy-lmu"
        default:
            return "unknown"
        }
    }

    private static func printSummary(_ samples: [Double]) {
        let sorted = samples.sorted()
        let minValue = sorted.first ?? 0
        let maxValue = sorted.last ?? 0
        let median = sorted[sorted.count / 2]
        let average = sorted.reduce(0, +) / Double(sorted.count)
        let recommendation = recommendedThresholdPreset(forMedianLux: median)

        print("---")
        print("samples=\(samples.count)")
        print("min=\(minValue.formattedLux)")
        print("median=\(median.formattedLux)")
        print("avg=\(average.formattedLux)")
        print("max=\(maxValue.formattedLux)")
        print("Recommended starting thresholds: dark<=\(recommendation.dark.formattedLux), light>=\(recommendation.light.formattedLux), consecutiveSamples=\(recommendation.consecutiveSamples)")
        print("Suggested calibration flow: capture one dark-room baseline and one bright-room or outdoor baseline, then set thresholds in the app between those ranges.")
    }

    private static func recommendedThresholdPreset(forMedianLux median: Double) -> (dark: Double, light: Double, consecutiveSamples: Int) {
        switch median {
        case 0..<500:
            return (dark: 120, light: 1500, consecutiveSamples: 2)
        case 500..<5000:
            return (dark: 800, light: 5000, consecutiveSamples: 2)
        default:
            return (dark: 3000, light: 12000, consecutiveSamples: 3)
        }
    }

    private static func parseOptions(_ arguments: [String]) -> (count: Int, interval: TimeInterval) {
        var count = 10
        var interval: TimeInterval = 1.0
        var index = 0

        while index < arguments.count {
            let argument = arguments[index]
            switch argument {
            case "--count" where index + 1 < arguments.count:
                count = max(1, Int(arguments[index + 1]) ?? count)
                index += 1
            case "--interval" where index + 1 < arguments.count:
                interval = max(0.1, Double(arguments[index + 1]) ?? interval)
                index += 1
            default:
                break
            }
            index += 1
        }

        return (count, interval)
    }
}