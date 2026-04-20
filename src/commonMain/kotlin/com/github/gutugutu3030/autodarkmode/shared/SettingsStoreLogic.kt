package com.github.gutugutu3030.autodarkmode.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 永続化ストアとメモリ上の状態を同期しながら、設定値の更新ルールをまとめて扱います。
 *
 * @param store 値の保存先です。
 */
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

    /**
     * 現在の状態を保持する内部フローです。
     */
    private val mutableState = MutableStateFlow(initialState())

    /**
     * 設定画面や状態監視に公開する現在値です。
     */
    val state: StateFlow<SettingsStoreState> = mutableState.asStateFlow()

    /**
     * 現在の切り替えモードです。
     */
    val switchMode: SwitchMode
        get() = mutableState.value.switchMode

    /**
     * 保存済みの暗い側しきい値です。
     */
    val darkThresholdLux: Double
        get() = mutableState.value.darkThresholdLux

    /**
     * 保存済みの明るい側しきい値です。
     */
    val lightThresholdLux: Double
        get() = mutableState.value.lightThresholdLux

    /**
     * 保存済みの連続サンプル数です。
     */
    val requiredConsecutiveSamples: Int
        get() = mutableState.value.requiredConsecutiveSamples

    /**
     * 保存済みのクールダウン秒数です。
     */
    val cooldownSeconds: Double
        get() = mutableState.value.cooldownSeconds

    /**
     * 永続化ストアから現在値を再読込します。
     */
    fun reloadFromStore() {
        mutableState.value = loadState()
    }

    /**
     * 切り替えモードを保存します。
     *
     * @param mode 保存するモードです。
     */
    fun setSwitchMode(mode: SwitchMode) {
        store.setString(Keys.switchMode, mode.rawValue)
        mutableState.value = mutableState.value.copy(switchMode = mode)
    }

    /**
     * 暗い側しきい値を保存します。
     *
     * @param newValue 保存する値です。
     */
    fun updateDarkThresholdLux(newValue: Double) {
        val clampedDarkThreshold = minOf(clampThreshold(newValue), mutableState.value.effectiveLightThresholdLux)
        store.setDouble(Keys.darkThresholdLux, clampedDarkThreshold)
        mutableState.value = mutableState.value.copy(darkThresholdLux = clampedDarkThreshold)
    }

    /**
     * 明るい側しきい値を保存します。
     *
     * @param newValue 保存する値です。
     */
    fun updateLightThresholdLux(newValue: Double) {
        val clampedLightThreshold = maxOf(clampThreshold(newValue), mutableState.value.effectiveDarkThresholdLux)
        store.setDouble(Keys.lightThresholdLux, clampedLightThreshold)
        mutableState.value = mutableState.value.copy(lightThresholdLux = clampedLightThreshold)
    }

    /**
     * 連続サンプル数を保存します。
     *
     * @param newValue 保存する値です。
     */
    fun updateRequiredConsecutiveSamples(newValue: Int) {
        val clampedRequiredSamples = clampRequiredConsecutiveSamples(newValue)
        store.setInt(Keys.requiredConsecutiveSamples, clampedRequiredSamples)
        mutableState.value = mutableState.value.copy(requiredConsecutiveSamples = clampedRequiredSamples)
    }

    /**
     * クールダウン秒数を保存します。
     *
     * @param newValue 保存する値です。
     */
    fun updateCooldownSeconds(newValue: Double) {
        val clampedCooldownSeconds = clampCooldownSeconds(newValue)
        store.setDouble(Keys.cooldownSeconds, clampedCooldownSeconds)
        mutableState.value = mutableState.value.copy(cooldownSeconds = clampedCooldownSeconds)
    }

    /**
     * 現在の周囲光を暗い側しきい値として保存します。
     *
     * @param lux 保存に使う現在値です。
     */
    fun useCurrentLuxAsDarkThreshold(lux: Double) {
        if (lux < 0) {
            return
        }

        updateDarkThresholdLux(lux)
    }

    /**
     * 現在の周囲光を明るい側しきい値として保存します。
     *
     * @param lux 保存に使う現在値です。
     */
    fun useCurrentLuxAsLightThreshold(lux: Double) {
        if (lux < 0) {
            return
        }

        updateLightThresholdLux(lux)
    }

    /**
     * 起動時の初期状態を組み立てます。
     *
     * @return 初期化済みの状態です。
     */
    private fun initialState(): SettingsStoreState {
        migrateLegacyDefaultsIfNeeded()
        migrateAutomationEnabledIfNeeded()
        return loadState()
    }

    /**
     * 保存済み値を読み出して、制約を適用した状態にします。
     *
     * @return 読み出した状態です。
     */
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

    /**
     * 古いデフォルト値が残っている場合だけ、推奨値へ移行します。
     */
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

    /**
     * 旧来の自動切り替えフラグを、現在のモード設定へ変換します。
     */
    private fun migrateAutomationEnabledIfNeeded() {
        if (store.getString(Keys.switchMode) != null) {
            return
        }

        val legacyAutomationEnabled = store.getBoolean(Keys.legacyAutomationEnabled) ?: return
        val mode = if (legacyAutomationEnabled) SwitchMode.Auto else SwitchMode.Off

        store.setString(Keys.switchMode, mode.rawValue)
        store.remove(Keys.legacyAutomationEnabled)
    }

    /**
     * しきい値を許容範囲へ丸めます。
     *
     * @param value 入力値です。
     * @return 丸め済みの値です。
     */
    private fun clampThreshold(value: Double): Double = value.coerceIn(0.0, 120000.0)

    /**
     * 連続サンプル数を許容範囲へ丸めます。
     *
     * @param value 入力値です。
     * @return 丸め済みの値です。
     */
    private fun clampRequiredConsecutiveSamples(value: Int): Int = value.coerceIn(1, 10)

    /**
     * クールダウン秒数を許容範囲へ丸めます。
     *
     * @param value 入力値です。
     * @return 丸め済みの値です。
     */
    private fun clampCooldownSeconds(value: Double): Double = value.coerceIn(5.0, 300.0)
}