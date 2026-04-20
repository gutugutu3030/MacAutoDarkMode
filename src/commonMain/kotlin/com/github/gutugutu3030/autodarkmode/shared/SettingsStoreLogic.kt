package com.github.gutugutu3030.autodarkmode.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStoreLogic(
    private val store: KeyValueStore,
) {
    private object Keys {
        const val switchMode = "switchMode"
        const val darkThresholdLux = "darkThresholdLux"
        const val lightThresholdLux = "lightThresholdLux"
        const val requiredConsecutiveSamples = "requiredConsecutiveSamples"
        const val cooldownSeconds = "cooldownSeconds"
        const val legacyAutomationEnabled = "automationEnabled"

        const val recommendedDarkThresholdLux = 3000.0
        const val recommendedLightThresholdLux = 12000.0
        const val recommendedRequiredConsecutiveSamples = 3
        const val recommendedCooldownSeconds = 30.0

        const val legacyDarkThresholdLux = 60.0
        const val legacyLightThresholdLux = 600.0
        const val legacyRequiredConsecutiveSamples = 2
    }

    private val mutableState = MutableStateFlow(initialState())

    val state: StateFlow<SettingsStoreState> = mutableState.asStateFlow()

    val switchMode: SwitchMode
        get() = mutableState.value.switchMode

    val darkThresholdLux: Double
        get() = mutableState.value.darkThresholdLux

    val lightThresholdLux: Double
        get() = mutableState.value.lightThresholdLux

    val requiredConsecutiveSamples: Int
        get() = mutableState.value.requiredConsecutiveSamples

    val cooldownSeconds: Double
        get() = mutableState.value.cooldownSeconds

    fun reloadFromStore() {
        mutableState.value = loadState()
    }

    fun setSwitchMode(mode: SwitchMode) {
        store.setString(Keys.switchMode, mode.rawValue)
        mutableState.value = mutableState.value.copy(switchMode = mode)
    }

    fun updateDarkThresholdLux(newValue: Double) {
        val clampedDarkThreshold = minOf(clampThreshold(newValue), mutableState.value.effectiveLightThresholdLux)
        store.setDouble(Keys.darkThresholdLux, clampedDarkThreshold)
        mutableState.value = mutableState.value.copy(darkThresholdLux = clampedDarkThreshold)
    }

    fun updateLightThresholdLux(newValue: Double) {
        val clampedLightThreshold = maxOf(clampThreshold(newValue), mutableState.value.effectiveDarkThresholdLux)
        store.setDouble(Keys.lightThresholdLux, clampedLightThreshold)
        mutableState.value = mutableState.value.copy(lightThresholdLux = clampedLightThreshold)
    }

    fun updateRequiredConsecutiveSamples(newValue: Int) {
        val clampedRequiredSamples = clampRequiredConsecutiveSamples(newValue)
        store.setInt(Keys.requiredConsecutiveSamples, clampedRequiredSamples)
        mutableState.value = mutableState.value.copy(requiredConsecutiveSamples = clampedRequiredSamples)
    }

    fun updateCooldownSeconds(newValue: Double) {
        val clampedCooldownSeconds = clampCooldownSeconds(newValue)
        store.setDouble(Keys.cooldownSeconds, clampedCooldownSeconds)
        mutableState.value = mutableState.value.copy(cooldownSeconds = clampedCooldownSeconds)
    }

    fun useCurrentLuxAsDarkThreshold(lux: Double) {
        if (lux < 0) {
            return
        }

        updateDarkThresholdLux(lux)
    }

    fun useCurrentLuxAsLightThreshold(lux: Double) {
        if (lux < 0) {
            return
        }

        updateLightThresholdLux(lux)
    }

    private fun initialState(): SettingsStoreState {
        migrateLegacyDefaultsIfNeeded()
        migrateAutomationEnabledIfNeeded()
        return loadState()
    }

    private fun loadState(): SettingsStoreState {
        val storedDarkThresholdValue = store.getDouble(Keys.darkThresholdLux) ?: Keys.recommendedDarkThresholdLux
        val storedLightThresholdValue = store.getDouble(Keys.lightThresholdLux) ?: Keys.recommendedLightThresholdLux
        val storedRequiredSamplesValue = store.getInt(Keys.requiredConsecutiveSamples) ?: Keys.recommendedRequiredConsecutiveSamples
        val storedCooldownSecondsValue = store.getDouble(Keys.cooldownSeconds) ?: Keys.recommendedCooldownSeconds

        val storedDarkThreshold = clampThreshold(storedDarkThresholdValue)
        val storedLightThreshold = maxOf(clampThreshold(storedLightThresholdValue), storedDarkThreshold)

        return SettingsStoreState(
            switchMode = SwitchMode.fromRawValue(store.getString(Keys.switchMode)) ?: SwitchMode.Auto,
            darkThresholdLux = storedDarkThreshold,
            lightThresholdLux = storedLightThreshold,
            requiredConsecutiveSamples = clampRequiredConsecutiveSamples(storedRequiredSamplesValue),
            cooldownSeconds = clampCooldownSeconds(storedCooldownSecondsValue),
        )
    }

    private fun migrateLegacyDefaultsIfNeeded() {
        val storedDarkThreshold = store.getDouble(Keys.darkThresholdLux)
        val storedLightThreshold = store.getDouble(Keys.lightThresholdLux)
        val storedRequiredSamples = store.getInt(Keys.requiredConsecutiveSamples)

        if (storedDarkThreshold != Keys.legacyDarkThresholdLux ||
            storedLightThreshold != Keys.legacyLightThresholdLux ||
            storedRequiredSamples != Keys.legacyRequiredConsecutiveSamples
        ) {
            return
        }

        store.setDouble(Keys.darkThresholdLux, Keys.recommendedDarkThresholdLux)
        store.setDouble(Keys.lightThresholdLux, Keys.recommendedLightThresholdLux)
        store.setInt(Keys.requiredConsecutiveSamples, Keys.recommendedRequiredConsecutiveSamples)
    }

    private fun migrateAutomationEnabledIfNeeded() {
        if (store.getString(Keys.switchMode) != null) {
            return
        }

        val legacyAutomationEnabled = store.getBoolean(Keys.legacyAutomationEnabled) ?: return
        val mode = if (legacyAutomationEnabled) SwitchMode.Auto else SwitchMode.Off

        store.setString(Keys.switchMode, mode.rawValue)
        store.remove(Keys.legacyAutomationEnabled)
    }

    private fun clampThreshold(value: Double): Double = value.coerceIn(0.0, 120000.0)

    private fun clampRequiredConsecutiveSamples(value: Int): Int = value.coerceIn(1, 10)

    private fun clampCooldownSeconds(value: Double): Double = value.coerceIn(5.0, 300.0)
}