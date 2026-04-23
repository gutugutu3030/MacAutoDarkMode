package com.github.gutugutu3030.autodarkmode.shared

/**
 * 設定ストアが保持する現在の状態のスナップショットです。
 *
 * @property switchMode 現在の切り替えモードです。
 * @property darkThresholdLux 暗い側のしきい値です。
 * @property lightThresholdLux 明るい側のしきい値です。
 * @property requiredConsecutiveSamples しきい値判定に必要な連続サンプル数です。
 * @property cooldownSeconds 切り替え後に再切り替えを抑制する秒数です。
 */
data class SettingsStoreState(
    val switchMode: SwitchMode,
    val darkThresholdLux: Double,
    val lightThresholdLux: Double,
    val requiredConsecutiveSamples: Int,
    val cooldownSeconds: Double,
) {
    /**
     * 実際の判定で使う暗い側しきい値を返します。
     */
    val effectiveDarkThresholdLux: Double
        get() = minOf(clampThreshold(darkThresholdLux), effectiveLightThresholdLux)

    /**
     * 実際の判定で使う明るい側しきい値を返します。
     */
    val effectiveLightThresholdLux: Double
        get() = maxOf(clampThreshold(lightThresholdLux), clampThreshold(darkThresholdLux))

    /**
     * 判定に使う連続サンプル数を制約付きで返します。
     */
    val effectiveRequiredConsecutiveSamples: Int
        get() = requiredConsecutiveSamples.coerceIn(1, 10)

    /**
     * クールダウン秒数を制約付きで返します。
     */
    val effectiveCooldownSeconds: Double
        get() = cooldownSeconds.coerceIn(5.0, 300.0)

    /**
     * しきい値の上限下限を共通化して揃えます。
     */
    private fun clampThreshold(value: Double): Double = value.coerceIn(0.0, 120000.0)
}
