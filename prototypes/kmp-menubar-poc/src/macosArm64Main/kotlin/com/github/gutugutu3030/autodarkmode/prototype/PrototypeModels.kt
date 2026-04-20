package com.github.gutugutu3030.autodarkmode.prototype

enum class PrototypeMode(val displayName: String) {
    Off("Off"),
    Auto("Auto"),
    Manual("Manual"),
}

internal enum class PrototypeAppearance(val displayName: String) {
    Light("Light"),
    Dark("Dark"),
}

internal data class PrototypeStatusState(
    val lux: Double = 240.0,
    val source: String = "iohid-bezelservices",
    val mode: PrototypeMode = PrototypeMode.Auto,
    val appearance: PrototypeAppearance? = PrototypeAppearance.Light,
    val sensorAvailable: Boolean = true,
    val darkThresholdLux: Double = 180.0,
    val lightThresholdLux: Double = 420.0,
    val requiredConsecutiveSamples: Int = 3,
    val cooldownSeconds: Double = 30.0,
    val message: String = "Kotlin-side menu coordinator is active.",
    val lastError: String? = null,
    val tickCount: Int = 0,
)

internal data class PrototypeAggregationStats(
    val brightnessEventCount: Int = 0,
    val engineEventCount: Int = 0,
    val settingsEventCount: Int = 0,
    val presentationFlushCount: Int = 0,
    val coalescedMutationCount: Int = 0,
    val maxMutationsPerFlush: Int = 0,
    val pendingMutationsSinceLastFlush: Int = 0,
    val lastFlushSummary: String = "No flush yet.",
)

internal enum class PrototypeBrightnessDirection {
    Up,
    Down,
}

internal enum class PrototypeBrightnessPhase {
    Down,
    Up,
}

internal data class PrototypeBrightnessEvent(
    val direction: PrototypeBrightnessDirection,
    val phase: PrototypeBrightnessPhase,
    val brightnessAfterSampling: Double,
)

internal data class PrototypeCoordinatorSnapshot(
    val status: PrototypeStatusState,
    val stats: PrototypeAggregationStats,
)

internal fun formatLux(value: Double): String {
    return if (value >= 1000.0) {
        "${((value / 100.0).toInt() / 10.0)} klx"
    } else {
        "${value.toInt()} lx"
    }
}