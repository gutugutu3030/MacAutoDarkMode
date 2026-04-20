@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.gutugutu3030.autodarkmode.prototype

import com.github.gutugutu3030.autodarkmode.shared.NSUserDefaultsKeyValueStore
import com.github.gutugutu3030.autodarkmode.shared.SettingsStoreLogic
import com.github.gutugutu3030.autodarkmode.shared.SwitchMode
import platform.Foundation.NSUserDefaults

internal data class PrototypePersistedSettingsSnapshot(
    val mode: PrototypeMode,
    val darkThresholdLux: Double,
    val lightThresholdLux: Double,
    val requiredConsecutiveSamples: Int,
    val cooldownSeconds: Double,
)

internal interface PrototypePersistedSettingsClient {
    fun currentSnapshot(): PrototypePersistedSettingsSnapshot
    fun persistMode(mode: PrototypeMode)
    fun persistThresholds(darkThresholdLux: Double, lightThresholdLux: Double)
    fun persistThresholdPreset(preset: PrototypeThresholdPreset)
}

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
) : PrototypePersistedSettingsClient {
    private val logic = SettingsStoreLogic(NSUserDefaultsKeyValueStore(defaults))

    override fun currentSnapshot(): PrototypePersistedSettingsSnapshot {
        logic.reloadFromStore()
        val state = logic.state.value

        return PrototypePersistedSettingsSnapshot(
            mode = state.switchMode.toPrototypeMode(),
            darkThresholdLux = state.effectiveDarkThresholdLux,
            lightThresholdLux = state.effectiveLightThresholdLux,
            requiredConsecutiveSamples = state.effectiveRequiredConsecutiveSamples,
            cooldownSeconds = state.effectiveCooldownSeconds,
        )
    }

    override fun persistMode(mode: PrototypeMode) {
        logic.setSwitchMode(mode.toSharedSwitchMode())
        println("[autoDarkMode] PrototypePersistedSettings wrote mode=${mode.displayName}.")
    }

    override fun persistThresholds(darkThresholdLux: Double, lightThresholdLux: Double) {
        logic.updateDarkThresholdLux(darkThresholdLux)
        logic.updateLightThresholdLux(lightThresholdLux)
        println(
            "[autoDarkMode] PrototypePersistedSettings wrote direct thresholds " +
                "dark=${formatPersistedLux(darkThresholdLux)} light=${formatPersistedLux(lightThresholdLux)}."
        )
    }

    override fun persistThresholdPreset(preset: PrototypeThresholdPreset) {
        persistThresholds(preset.darkThresholdLux, preset.lightThresholdLux)
        println(
            "[autoDarkMode] PrototypePersistedSettings wrote preset=${preset.name} " +
                "dark=${formatPersistedLux(preset.darkThresholdLux)} light=${formatPersistedLux(preset.lightThresholdLux)}."
        )
    }
}

private fun SwitchMode.toPrototypeMode(): PrototypeMode = when (this) {
    SwitchMode.Off -> PrototypeMode.Off
    SwitchMode.Auto -> PrototypeMode.Auto
    SwitchMode.Manual -> PrototypeMode.Manual
}

private fun PrototypeMode.toSharedSwitchMode(): SwitchMode = when (this) {
    PrototypeMode.Off -> SwitchMode.Off
    PrototypeMode.Auto -> SwitchMode.Auto
    PrototypeMode.Manual -> SwitchMode.Manual
}

private fun formatPersistedLux(value: Double): String {
    return if (value >= 1000.0) {
        "${((value / 100.0).toInt() / 10.0)} klx"
    } else {
        "${value.toInt()} lx"
    }
}