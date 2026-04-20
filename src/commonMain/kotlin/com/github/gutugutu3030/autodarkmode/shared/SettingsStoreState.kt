package com.github.gutugutu3030.autodarkmode.shared

data class SettingsStoreState(
    val switchMode: SwitchMode,
    val darkThresholdLux: Double,
    val lightThresholdLux: Double,
    val requiredConsecutiveSamples: Int,
    val cooldownSeconds: Double,
) {
    val effectiveDarkThresholdLux: Double
        get() = minOf(clampThreshold(darkThresholdLux), effectiveLightThresholdLux)

    val effectiveLightThresholdLux: Double
        get() = maxOf(clampThreshold(lightThresholdLux), clampThreshold(darkThresholdLux))

    val effectiveRequiredConsecutiveSamples: Int
        get() = requiredConsecutiveSamples.coerceIn(1, 10)

    val effectiveCooldownSeconds: Double
        get() = cooldownSeconds.coerceIn(5.0, 300.0)

    private fun clampThreshold(value: Double): Double = value.coerceIn(0.0, 120000.0)
}