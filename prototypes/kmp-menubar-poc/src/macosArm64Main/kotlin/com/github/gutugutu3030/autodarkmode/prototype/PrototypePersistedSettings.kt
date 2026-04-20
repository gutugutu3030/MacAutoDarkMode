@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.gutugutu3030.autodarkmode.prototype

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

private const val modeKey = "com.github.gutugutu3030.autodarkmode.prototype.switchMode"
private const val darkThresholdKey = "com.github.gutugutu3030.autodarkmode.prototype.darkThresholdLux"
private const val lightThresholdKey = "com.github.gutugutu3030.autodarkmode.prototype.lightThresholdLux"

internal data class PrototypePersistedSettingsSnapshot(
    val mode: PrototypeMode,
    val darkThresholdLux: Double,
    val lightThresholdLux: Double,
)

internal enum class PrototypeThresholdPreset(
    val menuTitle: String,
    val darkThresholdLux: Double,
    val lightThresholdLux: Double,
) {
    DimRoom("Persist Dim-Room Thresholds", 140.0, 260.0),
    BrightRoom("Persist Bright-Room Thresholds", 40.0, 80.0),
}

internal class PrototypePersistedSettings(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) {
    fun currentSnapshot(): PrototypePersistedSettingsSnapshot {
        val mode = when (defaults.stringForKey(modeKey)) {
            PrototypeMode.Off.displayName.lowercase() -> PrototypeMode.Off
            PrototypeMode.Manual.displayName.lowercase() -> PrototypeMode.Manual
            else -> PrototypeMode.Auto
        }
        val darkThresholdLux = readDouble(darkThresholdKey, 180.0).coerceIn(0.0, 120000.0)
        val storedLightThreshold = readDouble(lightThresholdKey, 420.0).coerceIn(0.0, 120000.0)
        val lightThresholdLux = maxOf(storedLightThreshold, darkThresholdLux)

        return PrototypePersistedSettingsSnapshot(
            mode = mode,
            darkThresholdLux = darkThresholdLux,
            lightThresholdLux = lightThresholdLux,
        )
    }

    fun persistMode(mode: PrototypeMode) {
        defaults.setObject(mode.displayName.lowercase(), forKey = modeKey)
        println("[kmp-menubar-poc] PrototypePersistedSettings wrote mode=${mode.displayName}.")
    }

    fun persistThresholdPreset(preset: PrototypeThresholdPreset) {
        defaults.setDouble(preset.darkThresholdLux, forKey = darkThresholdKey)
        defaults.setDouble(preset.lightThresholdLux, forKey = lightThresholdKey)
        println(
            "[kmp-menubar-poc] PrototypePersistedSettings wrote preset=${preset.name} " +
                "dark=${formatPersistedLux(preset.darkThresholdLux)} light=${formatPersistedLux(preset.lightThresholdLux)}."
        )
    }

    private fun readDouble(key: String, fallback: Double): Double {
        return (defaults.objectForKey(key) as? NSNumber)?.doubleValue ?: fallback
    }
}

private fun formatPersistedLux(value: Double): String {
    return if (value >= 1000.0) {
        "${((value / 100.0).toInt() / 10.0)} klx"
    } else {
        "${value.toInt()} lx"
    }
}