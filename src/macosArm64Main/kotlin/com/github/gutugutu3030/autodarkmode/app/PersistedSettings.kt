@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.gutugutu3030.autodarkmode.app

import com.github.gutugutu3030.autodarkmode.shared.NSUserDefaultsKeyValueStore
import com.github.gutugutu3030.autodarkmode.shared.SettingsStoreLogic
import com.github.gutugutu3030.autodarkmode.shared.SwitchMode
import platform.Foundation.NSUserDefaults

/**
 * 永続化された設定の読み取り結果です。
 *
 * @property mode 現在のモードです。
 * @property darkThresholdLux 暗い側しきい値です。
 * @property lightThresholdLux 明るい側しきい値です。
 * @property requiredConsecutiveSamples 連続サンプル数です。
 * @property cooldownSeconds クールダウン秒数です。
 */
internal data class PersistedSettingsSnapshot(
    val mode: Mode,
    val darkThresholdLux: Double,
    val lightThresholdLux: Double,
    val requiredConsecutiveSamples: Int,
    val cooldownSeconds: Double,
)

/**
 * アプリ層が永続化設定を扱うための抽象です。
 */
internal interface PersistedSettingsClient {
    /**
     * 現在の永続化設定を読み出します。
     *
     * @return 読み出したスナップショットです。
     */
    fun currentSnapshot(): PersistedSettingsSnapshot

    /**
     * モードを保存します。
     *
     * @param mode 保存するモードです。
     */
    fun persistMode(mode: Mode)

    /**
     * しきい値を保存します。
     *
     * @param darkThresholdLux 暗い側しきい値です。
     * @param lightThresholdLux 明るい側しきい値です。
     */
    fun persistThresholds(darkThresholdLux: Double, lightThresholdLux: Double)

    /**
     * 既定のしきい値プリセットを保存します。
     *
     * @param preset 保存するプリセットです。
     */
    fun persistThresholdPreset(preset: ThresholdPreset)
}

/**
 * 既定のしきい値プリセットです。
 *
 * @property menuTitle メニューに表示する名称です。
 * @property darkThresholdLux 暗い側しきい値です。
 * @property lightThresholdLux 明るい側しきい値です。
 */
internal enum class ThresholdPreset(
    val menuTitle: String,
    val darkThresholdLux: Double,
    val lightThresholdLux: Double,
) {
    DimRoom("Persist Dim-Room Thresholds", 140.0, 260.0),
    BrightRoom("Persist Bright-Room Thresholds", 40.0, 80.0),
}

/**
 * `SettingsStoreLogic` と `NSUserDefaults` を使って、アプリの永続化設定を扱います。
 *
 * @param defaults 保存先の `NSUserDefaults` です。
 */
internal class PersistedSettings(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : PersistedSettingsClient {
    private val logic = SettingsStoreLogic(NSUserDefaultsKeyValueStore(defaults))

    /**
     * 現在の永続化設定をスナップショットとして返します。
     *
     * @return 現在の設定値です。
     */
    override fun currentSnapshot(): PersistedSettingsSnapshot {
        logic.reloadFromStore()
        val state = logic.state.value

        return PersistedSettingsSnapshot(
            mode = state.switchMode.toMode(),
            darkThresholdLux = state.effectiveDarkThresholdLux,
            lightThresholdLux = state.effectiveLightThresholdLux,
            requiredConsecutiveSamples = state.effectiveRequiredConsecutiveSamples,
            cooldownSeconds = state.effectiveCooldownSeconds,
        )
    }

    /**
     * モードを永続化します。
     *
     * @param mode 保存するモードです。
     */
    override fun persistMode(mode: Mode) {
        logic.setSwitchMode(mode.toSharedSwitchMode())
        println("[autoDarkMode] PersistedSettings wrote mode=${mode.displayName}.")
    }

    /**
     * しきい値を永続化します。
     *
     * @param darkThresholdLux 暗い側しきい値です。
     * @param lightThresholdLux 明るい側しきい値です。
     */
    override fun persistThresholds(darkThresholdLux: Double, lightThresholdLux: Double) {
        logic.updateDarkThresholdLux(darkThresholdLux)
        logic.updateLightThresholdLux(lightThresholdLux)
        println(
            "[autoDarkMode] PersistedSettings wrote direct thresholds " +
                "dark=${formatPersistedLux(darkThresholdLux)} light=${formatPersistedLux(lightThresholdLux)}."
        )
    }

    /**
     * 既定のしきい値プリセットを永続化します。
     *
     * @param preset 保存するプリセットです。
     */
    override fun persistThresholdPreset(preset: ThresholdPreset) {
        persistThresholds(preset.darkThresholdLux, preset.lightThresholdLux)
        println(
            "[autoDarkMode] PersistedSettings wrote preset=${preset.name} " +
                "dark=${formatPersistedLux(preset.darkThresholdLux)} light=${formatPersistedLux(preset.lightThresholdLux)}."
        )
    }
}

/**
 * 共有モードを内部モードへ変換します。
 *
 * @return 変換後のモードです。
 */
private fun SwitchMode.toMode(): Mode = when (this) {
    SwitchMode.Off -> Mode.Off
    SwitchMode.Auto -> Mode.Auto
    SwitchMode.Manual -> Mode.Manual
}

/**
 * 内部モードを共有モードへ変換します。
 *
 * @return 変換後のモードです。
 */
private fun Mode.toSharedSwitchMode(): SwitchMode = when (this) {
    Mode.Off -> SwitchMode.Off
    Mode.Auto -> SwitchMode.Auto
    Mode.Manual -> SwitchMode.Manual
}

/**
 * 永続化ログ向けに lux 値を整形します。
 *
 * @param value 生の lux 値です。
 * @return ログ用の文字列です。
 */
private fun formatPersistedLux(value: Double): String {
    return if (value >= 1000.0) {
        "${((value / 100.0).toInt() / 10.0)} klx"
    } else {
        "${value.toInt()} lx"
    }
}