@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.gutugutu3030.autodarkmode.prototype

import kotlin.math.round
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.posix.fprintf
import platform.posix.stderr

internal object PrototypeCalibrationCli {
    internal data class Options(
        val count: Int = 10,
        val intervalSeconds: Double = 1.0,
    )

    internal data class Recommendation(
        val darkThresholdLux: Double,
        val lightThresholdLux: Double,
        val consecutiveSamples: Int,
    )

    fun canHandle(arguments: List<String>): Boolean {
        val command = arguments.firstOrNull() ?: return false
        return command == "sample" || command == "watch" || command == "appearance"
    }

    fun run(arguments: List<String>): Int {
        val command = arguments.firstOrNull() ?: "sample"
        if (command == "appearance") {
            return runAppearance(arguments.drop(1))
        }

        val options = parseOptions(arguments.drop(1))
        val sampleCount = if (command == "watch") Int.MAX_VALUE else options.count

        val reader = NativeAmbientLightReader()
        if (!reader.isSensorAvailable()) {
            fprintf(stderr, "Ambient light sensor is unavailable on this Mac.\n")
            reader.close()
            return 1
        }

        val countLabel = if (command == "watch") "∞" else sampleCount.toString()
        println("Sampling ambient light sensor using ${command} mode.")
        println("count=${countLabel} interval=${formatInterval(options.intervalSeconds)}s")

        val samples = mutableListOf<Double>()
        var iteration = 0
        while (iteration < sampleCount) {
            iteration += 1

            val timestamp = NSDate().description
            val reading = reader.currentReading()
            if (reading != null) {
                samples += reading.lux
                println("[${timestamp}] ${reading.source.cliName()}: ${formatLux(reading.lux)}")
            } else {
                println("[${timestamp}] unavailable")
            }

            if (iteration < sampleCount) {
                NSThread.sleepForTimeInterval(options.intervalSeconds)
            }
        }

        if (samples.isNotEmpty()) {
            printSummary(samples)
        }

        reader.close()
        return 0
    }

    private fun runAppearance(arguments: List<String>): Int {
        val controller = PrototypeSystemAppearanceController()
        return when (arguments.firstOrNull() ?: "get") {
            "get" -> {
                val appearance = controller.currentAppearance()
                if (appearance == null) {
                    fprintf(stderr, "Failed to read macOS appearance.\n")
                    1
                } else {
                    println(appearance.displayName.lowercase())
                    0
                }
            }
            "light", "dark" -> {
                val target = if (arguments.first() == "dark") PrototypeAppearance.Dark else PrototypeAppearance.Light
                val error = controller.setAppearance(target)
                if (error != null) {
                    fprintf(stderr, "%s\n", error)
                    1
                } else {
                    val actual = controller.currentAppearance() ?: target
                    println(actual.displayName.lowercase())
                    0
                }
            }
            else -> {
                fprintf(stderr, "Usage: appearance [get|light|dark]\n")
                1
            }
        }
    }

    internal fun parseOptions(arguments: List<String>): Options {
        var count = 10
        var intervalSeconds = 1.0
        var index = 0

        while (index < arguments.size) {
            when (arguments[index]) {
                "--count" -> if (index + 1 < arguments.size) {
                    count = maxOf(1, arguments[index + 1].toIntOrNull() ?: count)
                    index += 1
                }
                "--interval" -> if (index + 1 < arguments.size) {
                    intervalSeconds = maxOf(0.1, arguments[index + 1].toDoubleOrNull() ?: intervalSeconds)
                    index += 1
                }
            }
            index += 1
        }

        return Options(count = count, intervalSeconds = intervalSeconds)
    }

    internal fun recommendedThresholdPreset(forMedianLux: Double): Recommendation {
        return when {
            forMedianLux < 500.0 -> Recommendation(120.0, 1500.0, 2)
            forMedianLux < 5000.0 -> Recommendation(800.0, 5000.0, 2)
            else -> Recommendation(3000.0, 12000.0, 3)
        }
    }

    private fun printSummary(samples: List<Double>) {
        val sorted = samples.sorted()
        val minValue = sorted.first()
        val maxValue = sorted.last()
        val median = sorted[sorted.size / 2]
        val average = sorted.sum() / sorted.size
        val recommendation = recommendedThresholdPreset(median)

        println("---")
        println("samples=${samples.size}")
        println("min=${formatLux(minValue)}")
        println("median=${formatLux(median)}")
        println("avg=${formatLux(average)}")
        println("max=${formatLux(maxValue)}")
        println(
            "Recommended starting thresholds: dark<=${formatLux(recommendation.darkThresholdLux)}, " +
                "light>=${formatLux(recommendation.lightThresholdLux)}, " +
                "consecutiveSamples=${recommendation.consecutiveSamples}",
        )
        println("Suggested calibration flow: capture one dark-room baseline and one bright-room or outdoor baseline, then set thresholds in the app between those ranges.")
    }

    private fun formatInterval(value: Double): String {
        val rounded = round(value * 10.0) / 10.0
        return rounded.toString()
    }

    private fun NativeAmbientLightSource.cliName(): String {
        return when (this) {
            NativeAmbientLightSource.HID -> "hid"
            NativeAmbientLightSource.Unavailable -> "unavailable"
        }
    }
}